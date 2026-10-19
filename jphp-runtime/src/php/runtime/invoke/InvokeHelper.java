package php.runtime.invoke;

import php.runtime.Information;
import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.annotation.Reflection.Nullable;
import php.runtime.common.Callback;
import php.runtime.common.Function;
import php.runtime.common.HintType;
import php.runtime.common.Messages;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.FatalException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.cache.FunctionCallCache;
import php.runtime.invoke.cache.MethodCallCache;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.support.TypeChecker;

final public class InvokeHelper {

    private InvokeHelper() {
    }

    public static Memory normalizeExplicitReturn(Memory value) {
        if (!value.isUndefined()) return value;
        if (value instanceof php.runtime.memory.ReferenceMemory) {
            value.assign(Memory.NULL);
            return value;
        }
        return Memory.NULL;
    }

    /** A finally replacement keeps the pending PHP throwable at the end of its previous chain. */
    public static php.runtime.lang.exception.BaseBaseException chainFinallyException(
            php.runtime.lang.exception.BaseBaseException replacement,
            php.runtime.lang.exception.BaseBaseException pending) {
        if (replacement == pending || replacement.getReflection() == null || pending.getReflection() == null)
            return replacement; // Engine-only FinallyException is not a PHP previous value.
        java.util.Set<php.runtime.lang.exception.BaseBaseException> pendingChain =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var current = pending; current != null; current = previousThrowable(current)) {
            if (current.getReflection() == null || !pendingChain.add(current)) return replacement;
        }
        java.util.Set<php.runtime.lang.exception.BaseBaseException> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        var current = replacement;
        while (visited.add(current)) {
            if (pendingChain.contains(current)) return replacement;
            Memory previous = current.getPrevious(current.getEnvironment());
            if (previous.isNull()) {
                current.getReflection().refOfProperty(current.getProperties(), "previous").assign(new ObjectMemory(pending));
                return replacement;
            }
            var next = previousThrowable(current);
            if (next == null || next.getReflection() == null) return replacement;
            current = next;
        }
        return replacement;
    }

    private static php.runtime.lang.exception.BaseBaseException previousThrowable(
            php.runtime.lang.exception.BaseBaseException throwable) {
        Memory previous = throwable.getPrevious(throwable.getEnvironment());
        if (!previous.isObject()) return null;
        IObject object = previous.toValue(ObjectMemory.class).value;
        return object instanceof php.runtime.lang.exception.BaseBaseException
                ? (php.runtime.lang.exception.BaseBaseException) object : null;
    }

    public static void checkAccess(Environment env, TraceInfo trace, MethodEntity method) {
        checkAccess(env, trace, method, method.getName());
    }

    public static void checkAccess(Environment env, TraceInfo trace, MethodEntity method, String requestedName) {
        int access = env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5
                ? method.canAccessPhp8(env) : method.canAccess(env);
        if (access == 0)
            return;

        ClassEntity contextCls = env.getLastClassOnStack();
        String context = contextCls == null ? "" : contextCls.getName();

        if (env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5) {
            env.exception(trace, php.runtime.lang.exception.BaseError.class,
                    "Call to %s method %s::%s() from %s", access == 1 ? "protected" : "private",
                    method.getClazzName(), requestedName, context.isEmpty() ? "global scope" : "scope " + context);
            return;
        }
        switch (access) {
            case 1:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_CALL_TO_PROTECTED_METHOD,
                        method.getClazz().getName() + "::" + method.getName(), context
                );
            case 2:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_CALL_TO_PRIVATE_METHOD,
                        method.getClazz().getName() + "::" + method.getName(), context
                );
        }
    }

    public static void checkAccess(Environment env, TraceInfo trace, PropertyEntity property) {
        switch (property.canAccess(env)) {
            case 1:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_ACCESS_TO_PROTECTED_PROPERTY,
                        property.getClazz().getName(), property.getName()
                );
            case 2:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_ACCESS_TO_PRIVATE_PROPERTY,
                        property.getClazz().getName(), property.getName()
                );
        }
    }

    public static void checkAccess(Environment env, TraceInfo trace, ConstantEntity constant, boolean lateStaticCall) {
        checkAccess(env, trace, constant, lateStaticCall, constant.getClazz());
    }

    public static void checkAccess(Environment env, TraceInfo trace, ConstantEntity constant, boolean lateStaticCall, ClassEntity receiver) {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5) {
            ClassEntity scope = env.getLastClassOnStack();
            ClassEntity owner = constant.getClazz();
            boolean allowed = constant.isPublic() || scope != null && (constant.isPrivate()
                    ? scope.getId() == owner.getId() : scope.isInstanceOf(owner) || owner.isInstanceOf(scope));
            if (!allowed) env.exception(trace, php.runtime.lang.exception.BaseError.class,
                    "Cannot access %s constant %s::%s", constant.isPrivate() ? "private" : "protected",
                    receiver.getName(), constant.getName());
            return;
        }
        switch (constant.canAccess(env, null, lateStaticCall)) {
            case 1:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_ACCESS_TO_PROTECTED_CONSTANT,
                        constant.getClazz().getName(), constant.getName()
                );
            case 2:
                env.error(trace, ErrorType.E_ERROR,
                        Messages.ERR_ACCESS_TO_PRIVATE_CONSTANT,
                        constant.getClazz().getName(), constant.getName()
                );
        }
    }

    /*public static Memory[] makeArguments(Environment env, Memory[] args,
                                         ParameterEntity[] parameters,
                                         String originClassName, String originMethodName,
                                         TraceInfo trace) {
        return InvokeArgumentHelper.makeArguments(env, args, parameters, originClassName, originMethodName, trace);
    }*/

    /**
     * Method is invoked via bytecode
     *
     * @throws Throwable
     */
    public static Memory callAny(Memory method, Memory[] args, Environment env, TraceInfo trace)
            throws Throwable {
        method = method.toValue();
        if (method.isObject()) {
            return ObjectInvokeHelper.invokeMethod(method, null, null, env, trace, args);
        } else if (method.isArray()) {
            Memory one = null, two = null;
            for (Memory el : (ArrayMemory) method) {
                if (one == null)
                    one = el;
                else if (two == null)
                    two = el;
                else
                    break;
            }

            if (one == null || two == null) {
                env.error(trace, Messages.ERR_CALL_TO_UNDEFINED_FUNCTION.fetch(method.toString()));
                return Memory.NULL;
            }

            String methodName = two.toString();
            if (one.isObject())
                return ObjectInvokeHelper.invokeMethod(one, methodName, methodName.toLowerCase(), env, trace, args);
            else {
                String className = one.toString();
                ClassEntity magic = env.fetchMagicClass(className);
                if (magic != null)
                    className = magic.getName();

                return InvokeHelper.callStaticDynamic(
                        env,
                        trace,
                        className, className.toLowerCase(),
                        methodName, methodName.toLowerCase(),
                        args,
                        null, 0
                );
            }
        } else {
            String methodName = method.toString();
            int p;
            if ((p = methodName.indexOf("::")) > -1) {
                String className = methodName.substring(0, p);
                methodName = methodName.substring(p + 2, methodName.length());
                return InvokeHelper.callStaticDynamic(
                        env, trace,
                        className, className.toLowerCase(),
                        methodName, methodName.toLowerCase(),
                        args,
                        null, 0
                );
            } else {
                return InvokeHelper.call(env, trace, methodName.toLowerCase(), methodName, args, null, 0);
            }
        }
    }

    public static Memory checkReturnType(Environment env, TraceInfo trace, Memory result, final MethodEntity method) {
        return checkReturnType(
                env, trace, result,
                new Function<String>() {
                    @Override
                    public String call() {
                        ClassEntity clazz = method.getClazz();

                        switch (clazz.getType()) {
                            case CLOSURE:
                                return "{closure}";
                            default:
                                return clazz.getName() + "::" + method.getName();
                        }
                    }
                },
                method.getReturnTypeChecker(), method.isReturnTypeNullable(),
                method.getClazz().getModule() != null && method.getClazz().getModule().isStrictTypes(),
                method.getClazz().getModule() != null && method.getClazz().getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5
        );
    }

    public static Memory checkReturnType(Environment env, TraceInfo trace, Memory result, final FunctionEntity function) {
        return checkReturnType(
                env, trace, result,
                new Function<String>() {
                    @Override
                    public String call() {
                        return function.getName();
                    }
                },
                function.getReturnTypeChecker(), function.isReturnTypeNullable(),
                function.getModule() != null && function.getModule().isStrictTypes(),
                function.getModule() != null && function.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5
        );
    }

    public static Memory checkReturnType(Environment env, TraceInfo trace, Memory result, Function<String> callName,
                                         TypeChecker typeChecker, boolean nullable) {
        ModuleEntity module = env.getModuleManager().findModule(trace);
        return checkReturnType(env, trace, result, callName, typeChecker, nullable,
                module != null && module.isStrictTypes(),
                module != null && module.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5);
    }

    private static Memory checkReturnType(Environment env, TraceInfo trace, Memory result, Function<String> callName,
                                         TypeChecker typeChecker, boolean nullable, boolean strict, boolean modern) {
        if (typeChecker == null) {
            return result;
        }

        if (typeChecker instanceof TypeChecker.Simple && ((TypeChecker.Simple) typeChecker).getType() == HintType.NEVER) {
            env.exception(trace, BaseTypeError.class, "%s(): never-returning function must not implicitly return", callName.call());
            return null;
        }

        if (modern && result.isUndefined() && !(typeChecker instanceof TypeChecker.Simple
                && ((TypeChecker.Simple) typeChecker).getType() == HintType.VOID)) {
            env.exception(trace, BaseTypeError.class, "%s(): Return value must be of type %s, none returned",
                    callName.call(), php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(typeChecker, nullable).getRuntimeErrorSignature(env, null));
            return null;
        }

        if (!typeChecker.check(env, result, nullable, null)) {
            Memory newReturn = typeChecker.apply(
                    env, result, nullable, strict
            );

            if (newReturn == null) {
                String given = result.isObject()
                        ? result.toValue(ObjectMemory.class).getReflection().getName()
                        : result.getRealType().toString();

                if (modern) {
                    given = result.isNull() ? "null" : result.getRealType() == Memory.Type.INT ? "int"
                            : result.getRealType() == Memory.Type.BOOL ? (result.toBoolean() ? "true" : "false") : given;
                    env.exception(trace, BaseTypeError.class, "%s(): Return value must be of type %s, %s returned",
                            callName.call(), php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(typeChecker, nullable).getRuntimeErrorSignature(env, null), given);
                } else {
                    env.exception(trace, BaseTypeError.class,
                            Messages.ERR_RETURN_TYPE_INVALID.fetch(callName.call(), typeChecker.getHumanString(), given));
                }

                return null;
            } else {
                if (modern) newReturn = php.runtime.PhpNumericConversions.checkedScalarCoercion(result, newReturn, env, trace);
                if (modern && newReturn.getRealType() == Memory.Type.INT) {
                    Memory number = result.isString() ? php.runtime.PhpNumericString.parseStrict(result.toString()) : result;
                    if (number != null) php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(result, number, env, trace);
                }
                if (modern && result.isReference()) {
                    result.assign(newReturn);
                    return result;
                }
                return newReturn;
            }
        }


        return result;
    }

    public static Memory call(Environment env, TraceInfo trace, FunctionEntity function, Memory[] args)
            throws Throwable {
        return call(env, trace, function, args, false);
    }

    public static Memory call(Environment env, TraceInfo trace, FunctionEntity function, Memory[] args, boolean internalCall)
            throws Throwable {return call(env,trace,function,args,internalCall,false);}

    public static Memory call(Environment env, TraceInfo trace, FunctionEntity function, Memory[] args, boolean internalCall,boolean discarded)
            throws Throwable {
        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
        Memory result = modern ? null : function.getImmutableResultTyped(env, trace);

        Memory[] passed = null;

        if (result != null && !function.hasParameters() && args == null) {
            return result;
        }

        if(discarded)PhpNoDiscardInvocation.beforeBody(env,trace,function);
        // Internal functions validate/coerce in their native implementation; declaration metadata
        // is also used by Reflection and must not apply user-function coercion ahead of that call.
        var bound = BoundArguments.current(env, args, function);
        passed = function instanceof php.runtime.reflection.CompileFunctionEntity
                ? bound == null || !bound.signature().completeNames() ? args
                        : InvokeArgumentHelper.makeBoundNativeArguments(env, trace, bound)
                : InvokeArgumentHelper.makeArgumentsForTarget(
                        env, args, function, function.getParameters(), function.getName(), null, null, trace, internalCall
                );

        if (modern) result = function.getImmutableResultTyped(env, trace);
        if (result != null) {
            return result;
        }

        if (trace != null && function.isUsesStackTrace())
            env.pushCall(trace, null, BoundArguments.callArguments(env, args, function), function.getName(), null, null);

        var nativeContext = internalCall && function instanceof php.runtime.reflection.CompileFunctionEntity
                ? NativeFunctionContext.enterWeak(env, trace, function.getName()) : null;
        try {
            result = function.invoke(env, trace, passed);
        } finally {
            if (nativeContext != null) nativeContext.close();
            if (trace != null && function.isUsesStackTrace())
                env.popCall();
        }
        return result;
    }

    public static Memory call(Environment env, TraceInfo trace, String sign, String originName,
                              Memory[] args, FunctionCallCache callCache, int cacheIndex) throws Throwable {
        FunctionEntity function = resolveFunction(env, trace, sign, originName, callCache, cacheIndex);
        return function == null ? Memory.NULL : call(env, trace, function, args);
    }

    public static FunctionEntity resolveFunction(Environment env, TraceInfo trace, String sign, String originName,
                                                 FunctionCallCache callCache, int cacheIndex) {
        FunctionEntity function = null;

        if (callCache != null)
            function = callCache.get(env, cacheIndex);

        if (function == null) {
            function = env.fetchFunction(originName, sign);
            if (function != null && callCache != null) {
                callCache.put(env, cacheIndex, function);
            }
        }

        if (function == null) {
            if (!sign.isEmpty() && sign.charAt(0) != Information.NAMESPACE_SEP_CHAR) { // for global style invoke
                int p = sign.lastIndexOf(Information.NAMESPACE_SEP_CHAR);
                if (p > -1)
                    function = env.fetchFunction(originName.substring(p + 1), sign.substring(p + 1));
            }

            if (function == null) {
                if (env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5)
                    env.exception(trace, php.runtime.lang.exception.BaseError.class,
                            "Call to undefined function %s()", originName);
                env.error(trace, Messages.ERR_CALL_TO_UNDEFINED_FUNCTION.fetch(originName));
                return null;
            }

            if (callCache != null) {
                callCache.put(env, cacheIndex, function);
            }
        }

        return function;
    }

    public static Memory callStaticDynamic(Environment env, TraceInfo trace,
                                           String originClassName, String className,
                                           String originMethodName, String methodName,
                                           Memory[] args, MethodCallCache callCache, int cacheIndex) throws Throwable {
        return callStatic(
                env, trace,
                className, methodName,
                originClassName, originMethodName,
                args,
                callCache, cacheIndex
        );
    }

    public static Memory callStatic(Environment env, TraceInfo trace,
                                    String className, String methodName, String originClassName, String originMethodName,
                                    Memory[] args, MethodCallCache callCache, int cacheIndex)
            throws Throwable {
        return callStatic(env, trace, className, methodName, originClassName, originMethodName,
                args, callCache, cacheIndex, false);
    }

    public static Memory callStaticDynamic(Environment env, TraceInfo trace,
                                           String originClassName, String className,
                                           String originMethodName, String methodName,
                                           Memory[] args, MethodCallCache callCache, int cacheIndex, boolean forwarding) throws Throwable {
        return callStatic(env, trace, className, methodName, originClassName, originMethodName,
                args, callCache, cacheIndex, forwarding);
    }

    public static Memory callStatic(Environment env, TraceInfo trace,
                                    String className, String methodName, String originClassName, String originMethodName,
                                    Memory[] args, MethodCallCache callCache, int cacheIndex, boolean forwarding)
            throws Throwable {
        String calledClass = forwarding ? env.getLateStatic() : originClassName;
        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
        if (modern) {
            ClassEntity receiver = PhpMethodResolver.resolveClass(new StringMemory(originClassName), env, trace);
            return PhpMethodResolver.stat(receiver, new StringMemory(originMethodName), env, trace,
                    callCache, cacheIndex, forwarding).call(args);
        }
        ClassEntity classEntity = env.fetchClass(originClassName, className, true);
        ClassEntity accessScope = env.getLastClassOnStack();
        if (callCache != null) {
            MethodEntity entity = callCache.get(env, cacheIndex, classEntity, methodName, accessScope);
            if (entity != null) {
                return callStatic(env, trace, entity, calledClass, args, false);
            }
        }

        MethodEntity method = classEntity == null ? null : classEntity.findMethod(methodName);
        Memory[] passed = null;
        boolean isMagic = false;

        if (modern && method != null && method.canAccess(env) != 0 && classEntity.methodMagicCallStatic != null) {
            method = classEntity.methodMagicCallStatic;
            isMagic = true;
            passed = new Memory[]{new StringMemory(originMethodName), ArrayMemory.of(args)};
        }
        if (method == null) {
            IObject maybeObject = env.getLateObject();
            if (maybeObject != null && maybeObject.getReflection().isInstanceOf(classEntity))
                return ObjectInvokeHelper.invokeMethod(
                        new ObjectMemory(maybeObject), originMethodName, methodName, env, trace, args
                );

            if (classEntity != null && classEntity.methodMagicCallStatic != null) {
                method = classEntity.methodMagicCallStatic;
                isMagic = true;
                passed = new Memory[]{
                        new StringMemory(originMethodName),
                        ArrayMemory.of(args)
                };
            } else {
                if (classEntity == null) {
                    if (modern) env.exception(trace, php.runtime.lang.exception.BaseError.class, "Class \"%s\" not found", originClassName);
                    env.error(trace, Messages.ERR_CLASS_NOT_FOUND.fetch(originClassName));
                    return Memory.NULL;
                }
            }
        }

        if (method == null) {
            if (modern) env.exception(trace, php.runtime.lang.exception.BaseError.class,
                    "Call to undefined method %s::%s()", originClassName, originMethodName);
            env.error(trace, Messages.ERR_CALL_TO_UNDEFINED_METHOD.fetch(originClassName + "::" + originMethodName));
            return Memory.NULL;
        }

        if (!method.isStatic()) {
            IObject maybeObject = env.getLateObject();
            if (maybeObject != null
                    && maybeObject.getReflection().isInstanceOf(classEntity))
                return ObjectInvokeHelper.invokeMethod(maybeObject, method, env, trace, args, true);

            if (modern)
                env.exception(trace, php.runtime.lang.exception.BaseError.class,
                        "Non-static method %s::%s() cannot be called statically", method.getClazzName(), method.getName());
            env.error(trace,
                    ErrorType.E_STRICT,
                    Messages.ERR_NON_STATIC_METHOD_CALLED_DYNAMICALLY,
                    originClassName, originMethodName
            );
        }

        checkAccess(env, trace, method);

        if (callCache != null && !isMagic) {
            callCache.put(env, cacheIndex, method, classEntity, methodName, accessScope);
        }

        if (passed == null || modern) {
            passed = InvokeArgumentHelper.makeArguments(
                    env, passed == null ? args : passed, method, method.getParameters(),
                    modern ? method.getClazzName() : originClassName,
                    isMagic ? method.getName() : originMethodName, originClassName, trace, false
            );
        }

        Memory result = method.getImmutableResultTyped(env, trace);

        if (result != null) {
            return result;
        }

        try {
            if (trace != null)
                env.pushCall(trace, null, BoundArguments.callArguments(env, args, method), originMethodName, method.getClazz().getName(), calledClass);

            return method.invokeStatic(env, trace, passed);
        } finally {
            if (trace != null)
                env.popCall();
        }
    }


    public static Memory callStatic(Environment env, TraceInfo trace,
                                    MethodEntity method,
                                    Memory[] args)
            throws Throwable {
        return callStatic(env, trace, method, null, args, true);
    }

    public static Memory callStatic(Environment env, TraceInfo trace,
                                    MethodEntity method, @Nullable String staticClass,
                                    Memory[] args, boolean checkAccess)
            throws Throwable {
        return callStatic(env, trace, method, staticClass, args, checkAccess, false);
    }

    public static Memory callStatic(Environment env, TraceInfo trace, MethodEntity method, @Nullable String staticClass,
                                    Memory[] args, boolean checkAccess, boolean internalCall) throws Throwable {
        return callStatic(env,trace,method,staticClass,args,checkAccess,internalCall,false);
    }
    public static Memory callStatic(Environment env, TraceInfo trace, MethodEntity method, @Nullable String staticClass,
                                    Memory[] args, boolean checkAccess, boolean internalCall,boolean discarded) throws Throwable {
        if (checkAccess)
            checkAccess(env, trace, method);

        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
        Memory result = modern ? null : method.getImmutableResultTyped(env, trace);

        if (result != null) {
            return result;
        }

        String originClassName = method.getClazz().getName();
        String originMethodName = method.getName();

        String staticClazz = staticClass == null ? originClassName : staticClass;

        if(discarded)PhpNoDiscardInvocation.beforeBody(env,trace,method);
        Memory[] passed = InvokeArgumentHelper.makeArguments(
                env, args, method, method.getParameters(), originClassName, originMethodName, staticClass, trace, internalCall
        );
        if (modern) {
            result = method.getImmutableResultTyped(env, trace);
            if (result != null) return result;
        }

        TraceInfo callTrace = trace == null && method.requiresReturnTypeCallScope() ? TraceInfo.UNKNOWN : trace;
        boolean useCallScope = callTrace != null && (method.isUsesStackTrace() || method.requiresReturnTypeCallScope());
        try {
            if (useCallScope) {
                env.pushCall(callTrace, null, BoundArguments.callArguments(env, passed, method), originMethodName, originClassName, staticClazz);
            }

            return method.invokeStatic(env, passed);
        } finally {
            if (useCallScope)
                env.popCall();
        }
    }

    public static Memory prepareReturnReference(Memory memory, Environment env, TraceInfo trace) {
        php.runtime.memory.ReferenceMemory.rejectStringOffsetReference(memory,env,trace);
        if (memory.isReference()) return php.runtime.memory.ReferenceMemory.exportReference(memory);
        env.error(trace, ErrorType.E_NOTICE, "Only variable references should be returned by reference");
        return new php.runtime.memory.ReferenceMemory(memory);
    }

    public static void checkReturnReference(Memory memory, Environment env, TraceInfo trace) {
        if (memory.isImmutable()) {
            env.warning(trace, Messages.ERR_RETURN_NOT_REFERENCE.fetch());
        }
    }

    public static void checkYieldReference(Memory memory, Environment env, TraceInfo trace) {
        if (memory.isImmutable()) {
            env.error(trace, ErrorType.E_NOTICE, Messages.ERR_YIELD_NOT_REFERENCE.fetch());
        }
    }
}
