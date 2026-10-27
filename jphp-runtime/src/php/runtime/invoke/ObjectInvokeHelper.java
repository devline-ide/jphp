package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.common.Messages;
import php.runtime.common.Modifier;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.cache.ConstantCallCache;
import php.runtime.invoke.cache.PropertyCallCache;
import php.runtime.lang.Closure;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;

final public class ObjectInvokeHelper {
    private static String argumentScope(IObject object, MethodEntity method, String fallback) {
        if (object instanceof Closure && ((Closure) object).hasPhp8Binding()) {
            String scope = ((Closure) object).getScope();
            return scope == null ? "" : scope;
        }
        ClassEntity owner = method.getClazz();
        if (owner.getModule() != null && owner.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return owner.getName();
        return fallback;
    }

    private ObjectInvokeHelper() {
    }

    public static Memory invokeParentMethod(Memory object, String methodName, String methodLowerName,
                                            Environment env, TraceInfo trace, Memory[] args)
            throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            ClassEntity parent = PhpMethodResolver.parentClass(env, trace);
            return PhpMethodResolver.stat(parent, new StringMemory(methodName), env, trace, null, 0, true).call(args);
        }
        Memory[] passed = null;
        boolean doublePop = false;

        if (object.isNull()) {
            ClassEntity parent = env.__getParentClass(trace);
            return InvokeHelper.callStatic(
                    env, trace, parent.getLowerName(), methodLowerName, parent.getName(), methodName, args, null, 0,
                    env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
            );
        }

        IObject iObject = ((ObjectMemory) object).value;
        ClassEntity childClazz = iObject.getReflection();
        ClassEntity clazz = env.getLastClassOnStack().getParent();
        MethodEntity method;

        if (clazz == null) {
            env.error(trace, "Cannot access parent:: when current class scope has no parent");
            return Memory.NULL;
        }

        if (methodName == null) {
            method = childClazz.methodMagicInvoke != null ? childClazz.methodMagicInvoke : clazz.methodMagicInvoke;
        } else {
            method = clazz.findMethod(methodLowerName);
            if (method == null
                    && ((
                    method = childClazz.methodMagicCall != null
                            ? childClazz.methodMagicCall
                            : clazz.methodMagicCall)
                    != null)) {
                passed = new Memory[]{new StringMemory(methodName), ArrayMemory.of(args)};
                doublePop = true;
            }
        }

        String className = clazz.getName();

        if (method == null) {
            if (methodName == null)
                methodName = "__invoke";

            env.error(trace, ErrorType.E_ERROR,
                    Messages.ERR_CALL_TO_UNDEFINED_METHOD.fetch(
                            className + "::" + methodName
                    )
            );

            return Memory.NULL;
        }

        InvokeHelper.checkAccess(env, trace, method);
        if (!(iObject instanceof PhpCallableFactory.CapturedClosure))
            PhpDeprecatedInvocation.beforeFunction(env, trace, method);

        if (passed == null) {
            passed = InvokeArgumentHelper.makeArguments(
                    env, args, method, method.getParameters(), className, methodName, argumentScope(iObject, method, className), trace, false
            );
        }

        Memory result = method.getImmutableResultTyped(env, trace);

        if (result != null) {
            return result;
        }

        try {
            if (trace != null) {
                String calledClass = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                        ? iObject.getReflection().getName() : className;
                env.pushCall(trace, iObject, BoundArguments.callArguments(env, args, method), methodName, method.getClazz().getName(), calledClass);
                if (doublePop)
                    env.pushCall(trace, iObject, BoundArguments.callArguments(env, passed, method), method.getName(), method.getClazz().getName(), calledClass);
            }

            result = method.invokeDynamic(iObject, env, trace, passed);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new CriticalException("Unable to call parent:: method " + className + "::" + methodName + "(), error = " + e.getMessage());
        } finally {
            if (trace != null) {
                env.popCall();
                if (doublePop)
                    env.popCall();
            }
        }
        return result;
    }

    public static Memory invokeMethod(Memory object, String methodName,
                                      Environment env, TraceInfo trace, Memory... args)
            throws Throwable {
        return invokeMethod(object, methodName, methodName.toLowerCase(), env, trace, args);
    }

    public static Memory invokeMethod(Memory object, String methodName, Environment env, Memory... args)
            throws Throwable {
        return invokeMethod(object, methodName, methodName.toLowerCase(), env, env.trace(), args);
    }

    public static Memory invokeMethod(Memory object, String methodName, String methodLowerName,
                                      Environment env, TraceInfo trace, Memory[] args)
            throws Throwable {
        if (methodName != null && env.getModuleManager().getPhpLanguageProfile(trace)
                == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            return PhpMethodResolver.instance(object, new StringMemory(methodName), env, trace).call(args);
        }
        object = object.toValue();
        Memory[] passed = null;
        boolean doublePop = false;

        if (object.type != Memory.Type.OBJECT) {
            env.error(trace, ErrorType.E_RECOVERABLE_ERROR, Messages.ERR_CANNOT_CALL_OF_NON_OBJECT.fetch(methodName));
            return Memory.NULL;
        }

        IObject iObject = ((ObjectMemory) object).value;
        ClassEntity clazz = iObject.getReflection();
        MethodEntity method;

        if (methodName == null) {
            method = clazz.methodMagicInvoke;
        } else {
            method = clazz.findMethod(methodLowerName);

            if (method != null && method.isContextDepends()) {
                ClassEntity context = env.getLastClassOnStack();

                if (context != null) {
                    MethodEntity contextMethod = context.findMethod(methodLowerName);

                    if (contextMethod != null) {
                        method = contextMethod;
                    }
                }
            }

            if (method == null && ((method = clazz.methodMagicCall) != null)) {
                clazz.methodMagicCall.setModifier(Modifier.PUBLIC);
                passed = new Memory[]{new StringMemory(methodName), ArrayMemory.of(args)};
                doublePop = true;
            }
        }

        String className = clazz.getName();

        if (method == null) {
            if (methodName == null)
                methodName = "__invoke";

            env.error(trace, ErrorType.E_ERROR,
                    Messages.ERR_CALL_TO_UNDEFINED_METHOD.fetch(className + "::" + methodName)
            );
            return Memory.NULL;
        }

        InvokeHelper.checkAccess(env, trace, method);
        if (!(iObject instanceof PhpCallableFactory.CapturedClosure))
            PhpDeprecatedInvocation.beforeFunction(env, trace, method);

        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        Memory result = modern ? null : method.getImmutableResultTyped(env, trace);

        if (passed == null) {
            ParameterEntity[] parameters = method.getParameters(args == null ? 0 : args.length);

            if (result != null && args == null && (parameters == null || parameters.length == 0)) {
                return result;
            }

            passed = InvokeArgumentHelper.makeArguments(
                    env, args, method, parameters, modern ? method.getClazzName() : className, methodName,
                    argumentScope(iObject, method, className), trace, false
            );
        }

        if (modern) result = method.getImmutableResultTyped(env, trace);
        if (result != null) {
            return result;
        }

        try {
            if (trace != null) {
                String staticClass = className;

                if (iObject instanceof Closure) {
                    Closure closure = (Closure) iObject;
                    staticClass = closure.hasPhp8Binding() ? closure.getCalledClass() : closure.getScope();
                }

                String stackClass = clazz.isHiddenInCallStack() ? staticClass : method.getClazz().getName();

                env.pushCall(trace, iObject, BoundArguments.callArguments(env, args, method), methodName, stackClass, staticClass);

                if (doublePop) {
                    env.pushCall(trace, iObject, BoundArguments.callArguments(env, passed, method), method.getName(), stackClass, staticClass);
                }
            }

            return method.invokeDynamic(iObject, env, trace, passed);
        } catch (NoClassDefFoundError e) {
            throw new CriticalException("Unable to call method " + className + "::" + methodName + "(), " + e.getMessage());
        } finally {
            if (trace != null) {
                env.popCall();

                if (doublePop) {
                    env.popCall();
                }
            }
        }
    }

    public static Memory invokeMethod(IObject iObject, MethodEntity method,
                                      Environment env, TraceInfo trace, Memory[] args)
            throws Throwable {
        return invokeMethod(iObject, method, env, trace, args, true);
    }

    public static Memory invokeMethod(IObject iObject, MethodEntity method,
                                      Environment env, TraceInfo trace, Memory[] args, boolean checkAccess)
            throws Throwable {
        return invokeMethod(iObject, method, env, trace, args, checkAccess, false);
    }

    public static Memory invokeMethod(IObject iObject, MethodEntity method,
                                      Environment env, TraceInfo trace, Memory[] args, boolean checkAccess,
                                      boolean internalCall) throws Throwable {
        return invokeMethod(iObject,method,env,trace,args,checkAccess,internalCall,false);
    }
    public static Memory invokeMethod(IObject iObject, MethodEntity method,
                                      Environment env, TraceInfo trace, Memory[] args, boolean checkAccess,
                                      boolean internalCall,boolean discarded) throws Throwable {
        return invokeMethod(iObject, method, env, trace, args, checkAccess, internalCall, discarded, false);
    }

    static Memory invokeMethod(IObject iObject, MethodEntity method,
                                Environment env, TraceInfo trace, Memory[] args, boolean checkAccess,
                                boolean internalCall, boolean discarded, boolean ownedVector) throws Throwable {
        ClassEntity clazz = iObject.getReflection();
        if (method == null)
            method = clazz.methodMagicInvoke;

        String className = clazz.getName();

        if (method == null) {
            env.error(trace, Messages.ERR_CALL_TO_UNDEFINED_METHOD.fetch(className + "::__invoke"));
            return Memory.NULL;
        }

        if (checkAccess)
            InvokeHelper.checkAccess(env, trace, method);
        if (!(iObject instanceof PhpCallableFactory.CapturedClosure))
            PhpDeprecatedInvocation.beforeFunction(env, trace, method);

        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        String originClassName = modern ? method.getClazzName() : className;
        String originMethodName = method.getName();
        if (modern && iObject instanceof Closure) {
            originClassName = InvokeArgumentHelper.closureCallName((Closure) iObject);
            originMethodName = null;
        }
        if(discarded)PhpNoDiscardInvocation.beforeBody(env,trace,method);
        Memory[] passed = InvokeArgumentHelper.makeArguments(
                env, args, method, method.getParameters(args == null ? 0 : args.length), originClassName, originMethodName,
                argumentScope(iObject, method, className), trace, internalCall, ownedVector
        );

        Memory result = method.getImmutableResultTyped(env, trace);

        if (result != null) {
            return result;
        }

        if (trace != null) {
            String staticClass = className;

            if (iObject instanceof Closure) {
                Closure closure = (Closure) iObject;
                staticClass = closure.hasPhp8Binding() ? closure.getCalledClass() : closure.getScope();
            }

            String stackClass = clazz.isHiddenInCallStack() ? staticClass : method.getClazz().getName();

            var frame = env.pushCall(trace, iObject, BoundArguments.callArguments(env, args, method), method.getName(), stackClass, staticClass);
            if (!(iObject instanceof Closure)) {
                frame.classEntity = clazz.isHiddenInCallStack() ? clazz : method.getClazz();
                frame.staticClassEntity = clazz;
            }
        }

        try {
            result = method.invokeDynamic(iObject, env, trace, passed);
        } finally {
            if (trace != null)
                env.popCall();
        }
        return result;
    }

    public static Memory emptyProperty(Memory object, String property, Environment env, TraceInfo trace,
                                       PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        object = object.toValue();
        if (!object.isObject()) {
            return Memory.NULL;
            //env.error(trace, Messages.ERR_CANNOT_GET_PROPERTY_OF_NON_OBJECT.fetch(property));
        }

        IObject iObject = ((ObjectMemory) object).value;
        return iObject.getReflection().emptyProperty(env, trace, iObject, property);
    }

    public static Memory issetProperty(Memory object, String property, Environment env, TraceInfo trace,
                                       PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        object = object.toValue();

        if (!object.isObject()) {
            return Memory.NULL;
            //env.error(trace, Messages.ERR_CANNOT_GET_PROPERTY_OF_NON_OBJECT.fetch(property));
        }

        IObject iObject = ((ObjectMemory) object).value;
        return iObject.getReflection().issetProperty(env, trace, iObject, property, callCache, cacheIndex);
    }

    public static void unsetProperty(Memory object, String property, Environment env, TraceInfo trace,
                                     PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        object = object.toValue();
        if (!object.isObject()) {
            env.error(trace, Messages.ERR_CANNOT_GET_PROPERTY_OF_NON_OBJECT.fetch(property));
        }

        IObject iObject = ((ObjectMemory) object).value;
        iObject.getReflection().unsetProperty(env, trace, iObject, property, callCache, cacheIndex);
    }

    public static Memory getConstant(String className, String lowerClassName, String constant,
                                     Environment env, TraceInfo trace, ConstantCallCache callCache, int cacheIndex,
                                     boolean lateStaticCall) {
        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        ClassEntity entity = env.fetchClass(className, lowerClassName, true);
        if (entity == null) {
            if (modern) env.exception(trace, php.runtime.lang.exception.BaseError.class, "Class \"%s\" not found", className);
            else env.error(trace, Messages.ERR_CLASS_NOT_FOUND.fetch(className));
            return Memory.NULL;
        }
        return getResolvedConstant(entity, constant, env, trace, callCache, cacheIndex, lateStaticCall, modern, className);
    }

    public static Memory getRelativeConstant(Memory name, Environment env, TraceInfo trace, String keyword,
                                             ConstantCallCache callCache, int cacheIndex) {
        ClassEntity entity;
        if ("parent".equals(keyword)) entity = PhpMethodResolver.parentClass(env, trace);
        else if ("static".equals(keyword)) entity = PhpMethodResolver.staticClass(env, trace);
        else {
            entity = env.getLastClassOnStack();
            if (entity == null) env.__getSelf(trace); // Preserve the exact missing-self-scope error.
        }
        return getConstant(entity, name, env, trace, callCache, cacheIndex, "static".equals(keyword), keyword);
    }

    public static Memory getConstant(ClassEntity entity, Memory name, Environment env, TraceInfo trace,
                                     ConstantCallCache callCache, int cacheIndex, boolean lateStaticCall) {
        return getConstant(entity, name, env, trace, callCache, cacheIndex, lateStaticCall, entity.getName());
    }

    public static Memory getConstant(ClassEntity entity, Memory name, Environment env, TraceInfo trace,
                                     ConstantCallCache callCache, int cacheIndex, boolean lateStaticCall, String referenceClassName) {
        name = name.toValue();
        if (!name.isString()) {
            env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                    "Cannot use value of type %s as class constant name", name.isNull() ? "null"
                            : name.getRealType() == Memory.Type.INT ? "int" : name.getRealType() == Memory.Type.BOOL ? "bool"
                            : name.getRealType().toString());
            return Memory.NULL;
        }
        String constant = name.toString();
        if ("class".equalsIgnoreCase(constant)) return new StringMemory(entity.getName());
        return getResolvedConstant(entity, constant, env, trace, callCache, cacheIndex, lateStaticCall, true,
                referenceClassName == null ? entity.getName() : referenceClassName);
    }

    private static Memory getResolvedConstant(ClassEntity entity, String constant, Environment env, TraceInfo trace,
                                              ConstantCallCache callCache, int cacheIndex, boolean lateStaticCall, boolean modern, String referenceClassName) {
        ConstantEntity constantEntity = callCache == null ? null : callCache.get(env, cacheIndex, entity, constant);
        boolean alreadyCached = constantEntity != null;
        if (!alreadyCached) {
            constantEntity = entity.findConstant(constant);
            if (constantEntity == null) {
                if (modern) env.exception(trace, php.runtime.lang.exception.BaseError.class,
                        "Undefined constant %s::%s", entity.getName(), constant);
                else env.error(trace, Messages.ERR_UNDEFINED_CLASS_CONSTANT.fetch(constant));
                return Memory.NULL;
            }
        }
        // A cached declaration grants no access: the same site can run in different closure scopes.
        InvokeHelper.checkAccess(env, trace, constantEntity, lateStaticCall, entity);
        if (modern && entity.isTrait()) {
            env.exception(trace, php.runtime.lang.exception.BaseError.class,
                    "Cannot access trait constant %s::%s directly", entity.getName(), constant);
            return Memory.NULL;
        }
        if (!alreadyCached && callCache != null) callCache.put(env, cacheIndex, constantEntity, entity, constant);
        Memory value = modern ? php.runtime.PhpConstantInitializer.resolveReference(constantEntity, env, trace, referenceClassName)
                : constantEntity.getValue(env, trace);

        if (value == null) {
            return Memory.NULL;
        }

        return modern ? value.toImmutable() : value;
    }

    public static Memory getQuietProperty(Memory object, String property, Environment env, TraceInfo trace,
                                          PropertyCallCache callCache, int cacheIndex) throws Throwable {
        object = object.toValue();
        if (!object.isObject()) return Memory.NULL;
        var receiver = object.toValue(ObjectMemory.class).value;
        var clazz = receiver.getReflection();
        return clazz.getQuietProperty(env, trace, receiver, property, callCache, cacheIndex);
    }

    public static Memory getQuietArrayUnsetProperty(Memory object,String property,Environment env,TraceInfo trace,
            PropertyCallCache callCache,int cacheIndex)throws Throwable {
        object=object.toValue();if(!object.isObject())return Memory.NULL;
        IObject receiver=object.toValue(ObjectMemory.class).value;
        return receiver.getReflection().getQuietArrayUnsetProperty(env,trace,receiver,property,callCache,cacheIndex,null,null);
    }

    public static Memory getProperty(Memory object, String property, Environment env, TraceInfo trace,
                                     PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        object = object.toValue();

        if (!object.isObject()) {
            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                String type=object.isNull() ? "null" : object.getRealType()==Memory.Type.BOOL ? object.toBoolean() ? "true" : "false"
                        : object.getRealType()==Memory.Type.INT ? "int" : object.getRealType().toString();
                env.warning(trace,"Attempt to read property \"%s\" on %s",property,type);
            } else env.error(trace,Messages.ERR_CANNOT_GET_PROPERTY_OF_NON_OBJECT.fetch(property));
            return Memory.NULL;
        }

        IObject iObject = ((ObjectMemory) object).value;

        return iObject.getReflection().getProperty(env, trace, iObject, property, callCache, cacheIndex);
    }

    /** VALUE receiver of an indirect object write; never acquires the containing property's address. */
    public static Memory getObjectWriteReceiverProperty(Memory object, String property, Environment env, TraceInfo trace,
                                                        PropertyCallCache callCache, int cacheIndex) throws Throwable {
        Memory receiver = object.toValue();
        if (!receiver.isObject()) return getProperty(object, property, env, trace, callCache, cacheIndex);
        IObject actual = ((ObjectMemory) receiver).value;
        return actual.getReflection().getObjectWriteReceiverProperty(env, trace, actual, property, callCache, cacheIndex, null, null);
    }

    public static Memory getRefProperty(Memory object, String property, Environment env, TraceInfo trace,
                                        PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        object = object.toValue();
        if (!object.isObject()) {
            env.error(trace,
                    Messages.ERR_CANNOT_GET_PROPERTY_OF_NON_OBJECT.fetch(property)
            );
            return Memory.NULL;
        }

        IObject iObject = ((ObjectMemory) object).value;
        return iObject.getReflection().getRefProperty(env, trace, iObject, property, callCache, cacheIndex);
    }

    public static Memory getStaticProperty(String className,String lowerClassName,String property,Environment env,
            TraceInfo trace,PropertyCallCache callCache,int cacheIndex,boolean lateStaticCall)throws Throwable {
        return staticPropertyIntent(className,lowerClassName,property,env,trace,callCache,cacheIndex,lateStaticCall,false);
    }
    public static Memory directStaticProperty(String className,String lowerClassName,String property,Environment env,
            TraceInfo trace,PropertyCallCache callCache,int cacheIndex,boolean lateStaticCall)throws Throwable {
        return staticPropertyIntent(className,lowerClassName,property,env,trace,callCache,cacheIndex,lateStaticCall,true);
    }
    private static Memory staticPropertyIntent(String className,String lowerClassName,String property,Environment env,
            TraceInfo trace,PropertyCallCache callCache,int cacheIndex,boolean lateStaticCall,boolean assign)throws Throwable {
        ClassEntity entity=env.fetchClass(className,lowerClassName,true);
        if(entity==null){env.error(trace,Messages.ERR_CLASS_NOT_FOUND.fetch(className));return Memory.NULL;}
        Memory value=entity.getStaticProperty(env,trace,property,true,true,entity,callCache,cacheIndex,lateStaticCall);
        var declaration=entity.findStaticProperty(property);
        if(declaration!=null && declaration.getPhpSetModifier()!=null && declaration.effectiveSetModifier()!=declaration.getModifier()) {
            if(assign)return new php.runtime.memory.helper.PhpStaticPropertyAssignment((ReferenceMemory)value,declaration,env,trace);
            php.runtime.PhpPropertyAccess.check(declaration,env,trace,php.runtime.PhpPropertyAccess.Intent.MUTABLE_BACKING_ADDRESS);
        }
        return value;
    }

    public static Memory readStaticProperty(String className, String lowerClassName, String property, Environment env,
                                          TraceInfo trace, PropertyCallCache callCache, int cacheIndex, boolean lateStaticCall) throws Throwable {
        ClassEntity entity=env.fetchClass(className,lowerClassName,true);
        if(entity==null){env.error(trace,Messages.ERR_CLASS_NOT_FOUND.fetch(className));return Memory.NULL;}
        Memory value=entity.getStaticProperty(env,trace,property,true,true,entity,callCache,cacheIndex,lateStaticCall);
        return value instanceof ReferenceMemory ? ((ReferenceMemory)value).readProperty(env,trace) : value;
    }

    public static Memory issetStaticProperty(String className, String lowerClassName, String property, Environment env,
                                             TraceInfo trace, PropertyCallCache callCache, int cacheIndex, boolean lateStaticCall) throws Throwable {
        ClassEntity entity = env.fetchClass(className, lowerClassName, true);
        if (entity == null) {
            env.error(trace, Messages.ERR_CLASS_NOT_FOUND.fetch(className));
            return Memory.NULL;
        }

        return entity.getStaticProperty(env, trace, property, false, true, entity, callCache, cacheIndex, lateStaticCall);
    }

    public static Memory unsetStaticProperty(String className, String lowerClassName, String property, Environment env,
                                             TraceInfo trace, PropertyCallCache callCache, int cacheIndex, boolean lateStaticCall) throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            ClassEntity entity=env.fetchClass(className,lowerClassName,true);
            if (entity != null) env.exception(trace,php.runtime.lang.exception.BaseError.class,
                    "Attempt to unset static property %s::$%s",entity.getName(),property);
        }
        Memory get = getStaticProperty(className, lowerClassName, property, env, trace, callCache, cacheIndex, lateStaticCall);
        get.manualUnset(env);
        return Memory.NULL;
    }

    private static IObject fetchObject(Memory object, String property, Environment env, TraceInfo trace) {
        object = object.toValue();
        if (!object.isObject()) {
            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                env.exception(trace,php.runtime.lang.exception.BaseError.class,"Attempt to assign property \"%s\" on %s",property,
                        php.runtime.reflection.support.PhpTypeDeclaration.valueType(object));
            env.error(trace, Messages.ERR_CANNOT_SET_PROPERTY_OF_NON_OBJECT.fetch(property));
            return null;
        }

        return ((ObjectMemory) object).value;
    }

    public static Memory incrementProperty(Memory object, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache cache, int index, boolean decrement, boolean post) throws Throwable {
        IObject receiver = fetchObject(object, property, env, trace);
        if (receiver == null) return Memory.NULL;
        return receiver.getReflection().incrementProperty(env, trace, receiver, property, decrement, post, cache, index);
    }

    public static Memory incAndGetProperty(Memory object, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return incrementProperty(object, property, env, trace, callCache, cacheIndex, false, false);
        return assignPlusProperty(object, Memory.CONST_INT_1, property, env, trace, callCache, cacheIndex);
    }

    public static Memory GetAndIncProperty(Memory object, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return incrementProperty(object, property, env, trace, callCache, cacheIndex, false, true);
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;

        ReferenceMemory ref = new ReferenceMemory();
        iObject.getReflection().plusProperty(env, trace, iObject, property, Memory.CONST_INT_1, ref);
        return ref.getValue();
    }

    public static Memory decAndGetProperty(Memory object, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return incrementProperty(object, property, env, trace, callCache, cacheIndex, true, false);
        return assignMinusProperty(object, Memory.CONST_INT_1, property, env, trace, callCache, cacheIndex);
    }

    public static Memory GetAndDecProperty(Memory object, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return incrementProperty(object, property, env, trace, callCache, cacheIndex, true, true);
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;

        ReferenceMemory ref = new ReferenceMemory();
        iObject.getReflection().minusProperty(env, trace, iObject, property, Memory.CONST_INT_1, ref);
        return ref.getValue();
    }

    public static Memory assignRefProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex) throws Throwable {
        return getRefProperty(object, property, env, trace, callCache, cacheIndex).assignRef(value);
    }

    public static Memory assignProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                        PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().setProperty(env, trace, iObject, property, value, null, callCache, cacheIndex);
    }

    public static Memory assignPropertyRight(Memory value, String property, Environment env, TraceInfo trace, Memory object,
                                             PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        return assignProperty(object, value, property, env, trace, callCache, cacheIndex);
    }

    public static Memory assignPlusProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                            PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().plusProperty(env, trace, iObject, property, value, null);
    }

    public static Memory assignMinusProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                             PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().minusProperty(env, trace, iObject, property, value, null);
    }

    public static Memory assignMulProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().mulProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignDivProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().divProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignModProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                           PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().modProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignConcatProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                              PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().concatProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignBitAndProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                              PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().bitAndProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignBitOrProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                             PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().bitOrProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignBitXorProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                              PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().bitXorProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignBitShrProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                              PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().bitShrProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }

    public static Memory assignBitShlProperty(Memory object, Memory value, String property, Environment env, TraceInfo trace,
                                              PropertyCallCache callCache, int cacheIndex)
            throws Throwable {
        IObject iObject = fetchObject(object, property, env, trace);
        if (iObject == null) return Memory.NULL;
        return iObject.getReflection().bitShlProperty(env, trace, iObject, property, value, callCache, cacheIndex);
    }


    /**
     * 0 - success
     * 1 - invalid protected
     * 2 - invalid private
     * @param env
     * @return
     */
    public static int canAccess(Environment env, Modifier modifier, ClassEntity classEntity, ClassEntity context, boolean lateStaticCall) {
        switch (modifier){
            case PUBLIC: return 0;
            case PRIVATE:
                ClassEntity cl = context == null
                        ? (lateStaticCall ? env.getLateStaticClass() : env.getLastClassOnStack())
                        : context;

                return cl != null && cl.getId() == classEntity.getId() ? 0 : 2;

            case PROTECTED:
                ClassEntity clazz = context == null
                        ? (lateStaticCall ? env.getLateStaticClass() : env.getLastClassOnStack())
                        : context;

                if (clazz == null) {
                    return 1;
                }

                long id = classEntity.getId();
                do {
                    if (clazz.getId() == id) {
                        return 0;
                    }

                    clazz = clazz.getParent();
                } while (clazz != null);

                return 1;
        }

        return 2;
    }
}
