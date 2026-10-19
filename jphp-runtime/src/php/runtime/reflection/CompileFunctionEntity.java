package php.runtime.reflection;

import php.runtime.Memory;
import php.runtime.common.Messages;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.CompileFunction;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.memory.support.MemoryUtils;

import java.lang.reflect.InvocationTargetException;

public class CompileFunctionEntity extends FunctionEntity {
    private final CompileFunctionSpec compileFunctionSpec;
    private CompileFunction compileFunction;

    public CompileFunctionEntity(Extension extension, CompileFunctionSpec compileFunction) {
        super(null);
        this.compileFunctionSpec = compileFunction;
        this.setName(compileFunction.getName());
        this.setExtension(extension);
        var signature = compileFunction.getSignature();
        if (signature != null) {
            var declared = signature.value();
            var parameters = new ParameterEntity[declared.length];
            for (int i = 0; i < declared.length; i++) {
                var arg = declared[i];
                var parameter = new ParameterEntity(null);
                parameter.setName(arg.value());
                parameter.setType(arg.type());
                parameter.setNullable(arg.nullable());
                parameter.setReference(arg.reference());
                if (arg.variadic() && (i != declared.length - 1 || arg.optional().exists()))
                    throw new IllegalArgumentException("PHP variadic parameter must be last without a default");
                parameter.setVariadic(arg.variadic());
                parameter.setMutable(!arg.readOnly());
                if (!arg.typeClass().isEmpty()) parameter.setTypeClass(arg.typeClass());
                if (arg.typeEnum() != Enum.class) parameter.setTypeEnum(arg.typeEnum());
                if (!arg.typeDeclaration().isEmpty()) {
                    if (!arg.typeDeclaration().equals("array|string") || arg.type() != php.runtime.common.HintType.ANY
                            || !arg.typeClass().isEmpty() || arg.typeEnum() != Enum.class
                            || arg.nativeType() != php.runtime.lang.IObject.class || arg.nullable() || arg.optional().exists())
                        throw new IllegalArgumentException("Unsupported or conflicting explicit native parameter type");
                    parameter.setTypeChecker(new php.runtime.reflection.support.PhpTypeDeclaration(
                            java.util.List.of(java.util.List.of("array"), java.util.List.of("string"))));
                }
                if (!arg.optional().constantName().isEmpty()) {
                    if (!arg.optional().exists() || arg.optional().undefined() || arg.variadic()
                            || (!arg.optional().constantName().equals("SORT_REGULAR")
                                && !arg.optional().constantName().equals("PHP_INT_MAX"))
                            || arg.optional().type() != php.runtime.common.HintType.INT || !arg.optional().value().equals(arg.optional().constantName().equals("PHP_INT_MAX")
                                    ? "9223372036854775807" : "0"))
                        throw new IllegalArgumentException("Unsupported native PHP default constant declaration");
                    parameter.setDefaultValue(new php.runtime.memory.helper.ConstantMemory(arg.optional().constantName(),
                            php.runtime.memory.LongMemory.valueOf(arg.optional().constantName().equals("PHP_INT_MAX") ? Long.MAX_VALUE : 0)));
                    parameter.setDefaultValueConstName(arg.optional().constantName());
                } else if (arg.optional().exists()) parameter.setDefaultValue(arg.optional().undefined()
                        ? Memory.UNDEFINED : MemoryUtils.valueOf(arg.optional().value(), arg.optional().type()));
                parameters[i] = parameter;
            }
            setParameters(parameters);
            var result = signature.result();
            if (result.type() != php.runtime.common.HintType.ANY) setReturnType(result.type());
            if (!result.typeClass().isEmpty()) setReturnTypeClass(result.typeClass());
            setReturnTypeNullable(result.nullable() || result.type() == php.runtime.common.HintType.MIXED);
            if (!signature.returnType().isEmpty()) {
                if ((!signature.returnType().equals("true") && !signature.returnType().equals("string|false")
                        && !signature.returnType().equals("int|false"))
                        || result.type() != php.runtime.common.HintType.ANY
                        || !result.typeClass().isEmpty() || result.nullable())
                    throw new IllegalArgumentException("Unsupported or conflicting explicit native return type");
                setReturnTypeChecker(new php.runtime.reflection.support.PhpTypeDeclaration(
                        signature.returnType().equals("true") ? java.util.List.of(java.util.List.of("true"))
                                : java.util.List.of(java.util.List.of(signature.returnType().equals("int|false") ? "int" : "string"),
                                        java.util.List.of("false"))));
                setReturnTypeNullable(false);
            }
        }
    }

    /** The actual explicit PHP declaration; Java parameter names are never its substitute. */
    public php.runtime.annotation.Reflection.Signature getPhpSignature() {
        return compileFunctionSpec.getSignature();
    }

    /** An empty declared argument list is complete; missing names in a nonempty declaration are not. */
    public boolean hasCompletePhpSignature() {
        var signature=getPhpSignature();
        if(signature==null)return false;
        for(var argument:signature.value())if(argument.value().isEmpty())return false;
        return true;
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    public CompileFunction getCompileFunction() {
        if (compileFunction != null) {
            return compileFunction;
        }

        synchronized (compileFunctionSpec) {
            if (compileFunction != null) {
                return compileFunction;
            }

            compileFunction = compileFunctionSpec.toFunction();
        }

        return compileFunction;
    }

    /** Re-registering the same provider preserves the published entity and compile cache. */
    public boolean hasSameNativeDeclaration(Extension extension, CompileFunctionSpec declaration) {
        Extension previous = getExtension();
        return previous != null && extension != null && previous.getClass() == extension.getClass()
                && previous.getName().equals(extension.getName())
                && compileFunctionSpec.isSameNativeDeclaration(declaration);
    }

    @Override
    public Memory invoke(Environment env, TraceInfo trace, Memory[] arguments) throws Throwable {
        if (arguments == null) arguments = new Memory[0];
        CompileFunction.Method method = getCompileFunction().find(arguments.length);
        php.runtime.PhpOwnershipExecution.Session execution=php.runtime.PhpOwnershipExecution.current(env);
        if (execution != null) {
            if (method == null || arguments.length != method.argsCount || method.isVarArg())
                throw new IllegalArgumentException("Native arity has no complete ownership call contract");
            php.runtime.PhpOwnershipNativeCalls.requireCapability(method.method);
            return php.runtime.PhpOwnershipNativeCalls.invoke(env,trace,method,arguments,
                    execution.requireNativeCallRoots(method.method,arguments));
        }
        if (hasCompletePhpSignature() && env.getModuleManager().getPhpLanguageProfile(trace)
                == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            int minimum=getCompileFunction().getMinArgs(),maximum=getCompileFunction().getMaxArgs();
            if(arguments.length<minimum || arguments.length>maximum) {
                int expected=arguments.length<minimum?minimum:maximum;
                String bound=minimum==maximum?"exactly":arguments.length<minimum?"at least":"at most";
                env.exception(trace,php.runtime.lang.exception.BaseArgumentCountError.class,
                        "%s() expects %s %d %s, %d given",name,bound,expected,expected==1?"argument":"arguments",arguments.length);
                return Memory.NULL;
            }
        }
        if (method == null){
            env.warning(trace, Messages.ERR_EXPECT_LEAST_PARAMS.fetch(
                    name, compileFunction.getMinArgs(), arguments.length
            ));
            return Memory.NULL;
        } else {
            if (arguments.length > method.argsCount && !method.isVarArg()) {
                env.warning(trace, Messages.ERR_EXPECT_EXACTLY_PARAMS,
                        name, method.argsCount, arguments.length
                );
                return Memory.NULL;
            }
        }

        Class<?>[] types = method.parameterTypes;
        Object[] passed = new Object[ types.length ];

        int i = 0;
        int j = 0;

        for(Class<?> clazz : types) {
            boolean isRef = method.references[i];
            boolean mutableValue = method.mutableValues[i];

            MemoryUtils.Converter<?> converter = method.converters[i];

            if (clazz == Memory.class) {
                Memory argument = arguments[j];
                passed[i] = isRef ? argument : (mutableValue ? argument.toImmutable() : argument.toValue());
                j++;
            } else if (converter != null) {
                passed[i] = converter.run(arguments[j]);
                j++;
            } else if (clazz == Environment.class) {
                passed[i] = env;
            } else if (clazz == TraceInfo.class) {
                passed[i] = trace;
            } else if (i == types.length - 1 && types[i] == Memory[].class){
                Memory[] arg = new Memory[arguments.length - j];

                if (!isRef){
                    for(int k = 0; k < arg.length; k++)
                        arg[k] = arguments[j + k].toImmutable();
                } else {
                    System.arraycopy(arguments, j, arg, 0, arg.length);
                }
                passed[i] = arg;
                break;
            } else {
                env.error(trace, ErrorType.E_CORE_ERROR, name + "(): Cannot call this function dynamically");
                passed[i] = Memory.NULL;
            }
            i++;
        }

        php.runtime.invoke.BoundArguments.NativeScope nativeTail = null;
        if (parameters != null && parameters.length != 0 && parameters[parameters.length - 1].isVariadic()
                && getPhpSignature().namedVariadic()) {
            nativeTail = php.runtime.invoke.BoundArguments.enterNativeVariadic(env, arguments, this,
                    method.method, (Memory[]) passed[passed.length - 1]);
        }
        Object result;
        php.runtime.invoke.NativeFunctionContext.Frame callbackDispatch = null;
        Throwable primary = null;
        try {
            try {
                callbackDispatch = env.getModuleManager().getPhpLanguageProfile(trace)
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5
                        ? php.runtime.invoke.NativeFunctionContext.enterCallbackDispatcher(env, trace, method.method) : null;
                result = method.method.invoke(null, passed);
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                Throwable cleanup = null;
                try {if (callbackDispatch != null) callbackDispatch.close();}
                catch (Throwable failure) {cleanup = failure;}
                try {if (nativeTail != null) nativeTail.close();}
                catch (Throwable failure) {
                    if (cleanup == null) cleanup = failure;
                    else cleanup.addSuppressed(failure);
                }
                if (cleanup != null) {
                    if (primary == null) throw cleanup;
                    primary.addSuppressed(cleanup);
                }
            }
        } catch (InvocationTargetException e){
            return env.__throwException(e);
        }
        return method.resultType == void.class ? Memory.NULL : MemoryUtils.valueOf(result);
    }
}
