package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.Closure;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;
import php.runtime.lang.support.IComparableObject;
import java.util.ArrayList;
import java.util.Objects;

/** Acquires a checked target without invoking it or retaining an acquisition Environment. */
public final class PhpCallableFactory {
    private PhpCallableFactory() {}

    static Memory capture(Environment env, TraceInfo trace, FunctionEntity function, Invoker invoker) {
        if (invoker instanceof DynamicMethodInvoker
                && ((DynamicMethodInvoker) invoker).getObject() instanceof Closure)
            return new ObjectMemory(((DynamicMethodInvoker) invoker).getObject());
        MethodEntity method = invoker instanceof DynamicMethodInvoker ? ((DynamicMethodInvoker) invoker).getMethod()
                : invoker instanceof StaticMethodInvoker ? ((StaticMethodInvoker) invoker).getMethod() : null;
        if (function == null && method == null) throw new IllegalArgumentException("Unresolved callable target");
        Memory receiver = invoker instanceof DynamicMethodInvoker
                ? new ObjectMemory(((DynamicMethodInvoker) invoker).getObject()) : Memory.NULL;
        String calledClass = invoker instanceof StaticMethodInvoker ? ((StaticMethodInvoker) invoker).getCalledClass() : null;
        Memory magic = invoker instanceof MagicDynamicMethodInvoker ? ((MagicDynamicMethodInvoker) invoker).methodName
                : invoker instanceof MagicStaticMethodInvoker ? ((MagicStaticMethodInvoker) invoker).methodName : null;
        ClosureEntity entity = new ClosureEntity(function != null ? function.getContext() : method.getContext());
        entity.setParent(env.scope.fetchUserClass(Closure.class));
        entity.setModule(function != null ? function.getModule() : method.getClazz().getModule());
        entity.parameters = magic != null ? magicParameters()
                : function instanceof php.runtime.reflection.CompileFunctionEntity
                ? nativeParameters((php.runtime.reflection.CompileFunctionEntity) function)
                : function != null ? function.getParameters() : method.getParameters();
        entity.setReturnReference(magic == null && (function != null ? function.isReturnReference() : method.isReturnReference()));
        MethodEntity invocation = function instanceof php.runtime.reflection.CompileFunctionEntity
                ? new NativeInvocation(function) : function != null ? new MethodEntity(function) : new MethodEntity(method);
        invocation.setClazz(entity);
        invocation.setName("__invoke");
        if (magic != null) invocation.setParameters(null);
        entity.addMethod(invocation, null);
        entity.doneDeclare();
        CapturedClosure closure = new CapturedClosure(env, entity, receiver, function, method, magic);
        closure.initializeAcquiredBinding(method, calledClass);
        try {
            invocation.setNativeMethod(CapturedClosure.class.getDeclaredMethod("__invoke", Environment.class, Memory[].class));
        } catch (NoSuchMethodException impossible) { throw new IllegalStateException(impossible); }
        return new ObjectMemory(closure);
    }

    private static ParameterEntity[] magicParameters() {
        ParameterEntity args = new ParameterEntity(null);
        args.setName("args"); args.setVariadic(true);
        return new ParameterEntity[]{args};
    }

    // CompileFunction has no PHP declaration metadata; preserve its actual arity/ref/variadic contract.
    private static ParameterEntity[] nativeParameters(php.runtime.reflection.CompileFunctionEntity function) {
        if (function.getParameters() != null) return function.getParameters();
        var compiled = function.getCompileFunction();
        var method = compiled.find(compiled.getMaxArgs());
        if (method == null) method = compiled.find(compiled.getMinArgs());
        ArrayList<ParameterEntity> parameters = new ArrayList<>();
        if (method == null) return new ParameterEntity[0];
        for (int i = 0; i < method.parameterTypes.length; i++) {
            Class<?> type = method.parameterTypes[i];
            if (type == Environment.class || type == TraceInfo.class
                    || method.isPresentAnnotationOfParam(i, php.runtime.annotation.Runtime.GetLocals.class)) continue;
            ParameterEntity parameter = new ParameterEntity(function.getContext());
            parameter.setName(method.method.getParameters()[i].getName());
            parameter.setReference(PhpCallFrame.nativeArgumentByReference(method, i));
            parameter.setVariadic(method.isVarArg() && i == method.parameterTypes.length - 1);
            if (!parameter.isVariadic() && parameters.size() >= compiled.getMinArgs()) parameter.setDefaultValue(Memory.NULL);
            parameters.add(parameter);
        }
        return parameters.toArray(new ParameterEntity[0]);
    }

    /** The native target borrows PHP reference arguments; wrapper cleanup must not unset caller cells. */
    private static final class NativeInvocation extends MethodEntity {
        private final php.runtime.reflection.CompileFunctionEntity target;
        NativeInvocation(FunctionEntity function) {
            super(function); target = (php.runtime.reflection.CompileFunctionEntity) function;
            setParameters(null); // The target owns internal coercion, including null deprecations.
        }
        @Override public void unsetArguments(Memory[] arguments) {
            if (arguments == null) return;
            var nativeMethod = target.getCompileFunction().find(arguments.length);
            if (nativeMethod == null) return;
            int offset = 0;
            for (int i = 0; i < nativeMethod.parameterTypes.length; i++) {
                Class<?> type = nativeMethod.parameterTypes[i];
                if (type == Environment.class || type == TraceInfo.class
                        || nativeMethod.isPresentAnnotationOfParam(i, php.runtime.annotation.Runtime.GetLocals.class)) continue;
                boolean retained = PhpCallFrame.nativeArgumentByReference(nativeMethod, i);
                int limit = type == Memory[].class ? arguments.length : offset + 1;
                while (offset < limit && offset < arguments.length) {
                    Memory argument = arguments[offset++];
                    if (!retained && argument != null && argument.isArray()) argument.unset();
                }
            }
        }
    }

    public static Memory fromCallable(Memory value, Environment env, TraceInfo trace) throws Throwable {
        try (var frame=PhpCallFrame.acquireInternalClosureCallable(value,env,trace)) {
            return frame.capture();
        } catch (PhpMethodResolver.InvalidCallback invalid) {
            throw invalid.typeError(env,trace,"Failed to create closure from callable"
                    +(invalid.getMessage()==null ? "" : ": "+invalid.getMessage()));
        }
    }

    public static final class CapturedClosure extends Closure implements IComparableObject<IObject> {
        private final FunctionEntity function;
        private final MethodEntity method;
        private final Memory magicName;

        private CapturedClosure(Environment env, ClosureEntity entity, Memory receiver,
                                FunctionEntity function, MethodEntity method, Memory magicName) {
            super(env, entity, receiver, null, new Memory[0]);
            this.function = function; this.method = method; this.magicName = magicName;
        }

        public FunctionEntity getFunction() { return function; }
        public AbstractFunctionEntity getTarget() { return function != null ? function : method; }
        public boolean isMagicCapture() { return magicName != null; }
        public boolean isInternalCapture() { return isMagicCapture()
                || function instanceof php.runtime.reflection.CompileFunctionEntity
                || method != null && method.getClazz().isInternal(); }
        public String getMetadataName() { return magicName == null ? getTarget().getName() : magicName.toString(); }

        @Override public boolean __equal(IObject other) {
            if (!(other instanceof CapturedClosure)) return false;
            CapturedClosure captured = (CapturedClosure) other;
            return function == captured.function && method == captured.method
                    && (!getSelf().isObject() ? !captured.getSelf().isObject()
                        : captured.getSelf().isObject() && getSelf().toObject(IObject.class) == captured.getSelf().toObject(IObject.class))
                    && Objects.equals(getScope(), captured.getScope())
                    && Objects.equals(getCalledClass(), captured.getCalledClass())
                    && Objects.equals(magicName == null ? null : magicName.toString(),
                            captured.magicName == null ? null : captured.magicName.toString());
        }
        @Override public boolean __identical(IObject other) { return this == other; }
        @Override public boolean __greater(IObject other) { return false; }
        @Override public boolean __smaller(IObject other) { return false; }
        @Override public boolean __greaterEq(IObject other) { return __equal(other); }
        @Override public boolean __smallerEq(IObject other) { return __equal(other); }
        public String getCallName() { return function != null ? function.getName()
                : method.getClazzName() + "::" + (magicName == null ? method.getName() : magicName.toString()); }

        /** Called by the exact bound frame; bypass the wrapper's second argument-preparation pass. */
        public Memory invokeBound(Environment env, TraceInfo trace, BoundArguments bound, boolean internalCall) throws Throwable {
            return invokeBound(env,trace,bound,internalCall,false);
        }
        public Memory invokeBound(Environment env, TraceInfo trace, BoundArguments bound, boolean internalCall,boolean discarded) throws Throwable {
            Memory[] args = bound.arguments();
            Object target = isMagicCapture() ? this : getTarget();
            if (BoundArguments.current(env, args, target) != bound)
                throw new IllegalArgumentException("Captured invocation has no exact bound target scope");
            if (function instanceof php.runtime.reflection.CompileFunctionEntity) {
                var compiled = ((php.runtime.reflection.CompileFunctionEntity) function).getCompileFunction();
                int minimum = compiled.getMinArgs(), maximum = compiled.getMaxArgs();
                if (args.length < minimum || args.length > maximum) {
                    String range = minimum == maximum ? "exactly " + minimum
                            : args.length < minimum ? "at least " + minimum : "at most " + maximum;
                    env.exception(trace, php.runtime.lang.exception.BaseArgumentCountError.class,
                            "%s() expects %s argument%s, %d given", function.getName(), range,
                            (args.length < minimum ? minimum : maximum) == 1 ? "" : "s", args.length);
                }
                var nativeMethod = compiled.find(args.length);
                if (nativeMethod != null) for (int i = 0; i < nativeMethod.parameterTypes.length; i++) {
                    if (nativeMethod.isPresentAnnotationOfParam(i, php.runtime.annotation.Runtime.GetLocals.class))
                        env.exception(trace, php.runtime.lang.exception.BaseError.class,
                                "Cannot call %s() dynamically", function.getName());
                }
            }
            if (function != null) return InvokeHelper.call(env, trace, function, args, internalCall,discarded);
            Memory[] actual = magicName == null ? args : new Memory[]{magicName,
                    PhpNamedArgumentTarget.magicArguments(env, args, this)};
            return method.isStatic() ? InvokeHelper.callStatic(env, trace, method, getCalledClass(), actual, false, internalCall,discarded)
                    : ObjectInvokeHelper.invokeMethod(getSelf().toObject(IObject.class), method, env, trace, actual, false, internalCall,discarded);
        }

        @Override
        public Memory __invoke(Environment env, Memory... args) throws Throwable {
            return invokePrepared(env,env.trace(),args,false,false);
        }
        public Memory invokePrepared(Environment env,TraceInfo trace,Memory[] args,boolean internalCall,boolean discarded) throws Throwable {
            if (function instanceof php.runtime.reflection.CompileFunctionEntity) {
                var compiled = ((php.runtime.reflection.CompileFunctionEntity) function).getCompileFunction();
                int minimum = compiled.getMinArgs(), maximum = compiled.getMaxArgs();
                if (args.length < minimum || args.length > maximum) {
                    String range = minimum == maximum ? "exactly " + minimum
                            : args.length < minimum ? "at least " + minimum : "at most " + maximum;
                    env.exception(trace, php.runtime.lang.exception.BaseArgumentCountError.class,
                            "%s() expects %s argument%s, %d given", function.getName(), range,
                            (args.length < minimum ? minimum : maximum) == 1 ? "" : "s", args.length);
                }
                var nativeMethod = compiled.find(args.length);
                if (nativeMethod != null) for (int i = 0; i < nativeMethod.parameterTypes.length; i++) {
                    if (nativeMethod.isPresentAnnotationOfParam(i, php.runtime.annotation.Runtime.GetLocals.class))
                        env.exception(trace, php.runtime.lang.exception.BaseError.class,
                                "Cannot call %s() dynamically", function.getName());
                }
            }
            if (function != null) return InvokeHelper.call(env, trace, function, args,internalCall,discarded);
            Memory[] actual = magicName == null ? args : new Memory[]{magicName, new ArrayMemory(false, args)};
            return method.isStatic() ? InvokeHelper.callStatic(env, trace, method, getCalledClass(), actual, false,internalCall,discarded)
                    : ObjectInvokeHelper.invokeMethod(getSelf().toObject(IObject.class), method, env, trace, actual, false,internalCall,discarded);
        }

        @Override
        public Memory getOrCreateStatic(String name) { return Memory.NULL; }
    }
}
