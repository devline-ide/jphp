package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.CompileFunctionEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;
import java.util.HashSet;

/** PHP declaration names belong to the resolved target, never to Java transport parameters. */
public final class PhpNamedArgumentTarget {
    private PhpNamedArgumentTarget() {}

    public static PhpArgumentBinder.Signature resolve(FunctionEntity function, Invoker invoker,
                                                       ParameterEntity[] fallback, int capacity) {
        if (invoker instanceof MagicDynamicMethodInvoker || invoker instanceof MagicStaticMethodInvoker)
            return new PhpArgumentBinder.Signature(invoker, new ParameterEntity[0], true, true);
        if (function == null && invoker instanceof FunctionInvoker)
            function = ((FunctionInvoker) invoker).getFunction();
        if (function != null) return function(function, fallback);
        if (invoker instanceof DynamicMethodInvoker) {
            var dynamic = (DynamicMethodInvoker) invoker;
            if (dynamic.getObject() instanceof PhpCallableFactory.CapturedClosure) {
                var captured = (PhpCallableFactory.CapturedClosure) dynamic.getObject();
                if (captured.isMagicCapture())
                    return new PhpArgumentBinder.Signature(captured, new ParameterEntity[0], true, true);
                if (captured.getFunction() != null) return function(captured.getFunction(), fallback);
                return method((MethodEntity) captured.getTarget(), fallback);
            }
            return method(dynamic.getMethod(), fallback);
        }
        if (invoker instanceof StaticMethodInvoker)
            return method(((StaticMethodInvoker) invoker).getMethod(), fallback);
        if(invoker instanceof php.runtime.PhpPropertyHookParentCall.BackingInvoker)
            return ((php.runtime.PhpPropertyHookParentCall.BackingInvoker)invoker).signature();
        throw new IllegalArgumentException("Named call has no resolved PHP declaration target");
    }

    private static PhpArgumentBinder.Signature function(FunctionEntity function, ParameterEntity[] fallback) {
        var parameters = function.getParameters();
        if (function instanceof CompileFunctionEntity) {
            var nativeFunction = (CompileFunctionEntity) function;
            boolean complete = completeNativeFunction(nativeFunction);
            return new PhpArgumentBinder.Signature(function,
                    complete ? parameters : fallback, complete, false);
        }
        return new PhpArgumentBinder.Signature(function, parameters, validNames(parameters), false);
    }

    private static PhpArgumentBinder.Signature method(MethodEntity method, ParameterEntity[] fallback) {
        var parameters = method.getParameters();
        boolean complete = validNames(parameters);
        if (method.getExtension() != null || method.getClazz() != null && method.getClazz().isInternal()) {
            var nativeMethod = method.getNativeMethod();
            var declared = nativeMethod == null ? null : nativeMethod.getAnnotation(Reflection.Signature.class);
            complete = declared != null && matches(declared, parameters);
        }
        return new PhpArgumentBinder.Signature(method, complete ? parameters : fallback, complete, false);
    }

    private static boolean validNames(ParameterEntity[] parameters) {
        if (parameters == null) return true;
        var names = new HashSet<String>();
        for (var parameter : parameters)
            if (parameter == null || parameter.getName() == null || parameter.getName().isEmpty()
                    || !names.add(parameter.getName())) return false;
        return true;
    }

    private static boolean matches(Reflection.Signature declared, ParameterEntity[] parameters) {
        if (parameters == null || !validNames(parameters) || declared.value().length != parameters.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            var arg = declared.value()[i];var actual = parameters[i];
            if (arg.value().isEmpty() || !arg.value().equals(actual.getName())
                    || arg.reference() != actual.isReference() || arg.variadic() != actual.isVariadic()
                    || arg.variadic() && (i != parameters.length - 1 || arg.optional().exists())
                    || arg.optional().exists() != (actual.getDefaultValue() != null)) return false;
        }
        return true;
    }

    private static boolean completeNativeFunction(CompileFunctionEntity function) {
        var signature = function.getPhpSignature();
        if (signature == null || !matches(signature, function.getParameters())) return false;
        var compiled = function.getCompileFunction();
        var parameters = function.getParameters();
        if (parameters.length > 0 && parameters[parameters.length - 1].isVariadic())
            return completeNativeVariadic(signature, parameters, compiled);
        if (signature.namedVariadic()) return false;
        int required = parameters.length;
        while (required > 0 && parameters[required - 1].getDefaultValue() != null) required--;
        // A Java Memory... wrapper may implement a fixed PHP declaration. It is not a PHP variadic.
        boolean wrapper = false;
        Class<?>[] transportTypes = new Class<?>[parameters.length];
        boolean[] transportReferences = new boolean[parameters.length];
        boolean[] transportMutable = new boolean[parameters.length];
        Class<?> resultType = null;
        for (int arity = required; arity <= parameters.length; arity++) {
            var method = compiled.find(arity);
            if (method == null) return false;
            if (resultType != null && resultType != method.resultType) return false;
            resultType = method.resultType;
            var declared = method.method.getAnnotation(Reflection.Signature.class);
            if (declared != null && !declared.equals(signature)) return false;
            int phpIndex = 0;
            for (int javaIndex = 0; javaIndex < method.parameterTypes.length; javaIndex++) {
                var type = method.parameterTypes[javaIndex];
                if (method.isPresentAnnotationOfParam(javaIndex, php.runtime.annotation.Runtime.GetLocals.class)) return false;
                if (type == Environment.class || type == php.runtime.env.TraceInfo.class) continue;
                if (type == Memory[].class) {
                    if (!method.isVarArg() || javaIndex != method.parameterTypes.length - 1) return false;
                    wrapper = true;
                    for (; phpIndex < arity; phpIndex++) {
                        if (parameters[phpIndex].isReference() && !method.references[javaIndex]) return false;
                        if (!consistentTransport(phpIndex, Memory[].class, method.references[javaIndex],
                                method.mutableValues[javaIndex], transportTypes, transportReferences, transportMutable)) return false;
                    }
                    break;
                }
                if (phpIndex >= arity || PhpCallFrame.nativeArgumentByReference(method, javaIndex)
                        != parameters[phpIndex].isReference()) return false;
                if (!consistentTransport(phpIndex, type, method.references[javaIndex],
                        method.mutableValues[javaIndex], transportTypes, transportReferences, transportMutable)) return false;
                phpIndex++;
            }
            if (phpIndex != arity || !method.isVarArg() && method.argsCount != arity) return false;
        }
        if (!wrapper && (compiled.getMaxArgs() != parameters.length || compiled.getMinArgs() != required)) return false;
        return true;
    }

    /** One declared PHP variadic backed by one explicit key-aware Java native member. */
    private static boolean completeNativeVariadic(Reflection.Signature signature, ParameterEntity[] parameters,
                                                   php.runtime.ext.support.compile.CompileFunction compiled) {
        if (!signature.namedVariadic()) return false;
        int fixed = parameters.length - 1;
        // Optional native heads and reference variadic bodies need their own real transport contract.
        if (parameters[fixed].isReference()) return false;
        for (int i = 0; i < fixed; i++) if (parameters[i].isOptional()) return false;
        var selected = compiled.find(fixed);
        if (selected == null || !selected.isVarArg() || compiled.getMinArgs() != fixed
                || compiled.getMaxArgs() != Integer.MAX_VALUE || compiled.find(Integer.MAX_VALUE) != selected
                || !signature.equals(selected.method.getAnnotation(Reflection.Signature.class))) return false;
        for (var candidate : compiled.methods) if (candidate != null && candidate != selected) return false;
        int phpIndex = 0;
        for (int javaIndex = 0; javaIndex < selected.parameterTypes.length; javaIndex++) {
            var type = selected.parameterTypes[javaIndex];
            if (selected.isPresentAnnotationOfParam(javaIndex, php.runtime.annotation.Runtime.GetLocals.class)) return false;
            if (type == Environment.class || type == php.runtime.env.TraceInfo.class) continue;
            if (type == Memory[].class) return javaIndex == selected.parameterTypes.length - 1
                    && phpIndex == fixed && !selected.references[javaIndex] && !selected.mutableValues[javaIndex];
            if (phpIndex >= fixed || PhpCallFrame.nativeArgumentByReference(selected, javaIndex)
                    != parameters[phpIndex].isReference()) return false;
            phpIndex++;
        }
        return false;
    }

    private static boolean consistentTransport(int index, Class<?> type, boolean reference, boolean mutable,
                                               Class<?>[] types, boolean[] references, boolean[] mutableValues) {
        if (types[index] != null) return types[index] == type && references[index] == reference
                && mutableValues[index] == mutable;
        types[index] = type;references[index] = reference;mutableValues[index] = mutable;
        return true;
    }

    /** Magic's second argument is the real ordered PHP argument array, including string keys. */
    static ArrayMemory magicArguments(Environment env, Memory[] vector, Object target) {
        var arguments = new ArrayMemory(false, vector);
        var bound = BoundArguments.current(env, vector, target);
        if (bound != null) for (var entry : bound.namedExtras().entrySet())
            arguments.put(entry.getKey(), entry.getValue());
        return arguments;
    }
}
