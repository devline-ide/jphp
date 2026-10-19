package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.common.Modifier;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.cache.MethodCallCache;
import php.runtime.lang.Closure;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;

import java.util.Locale;

/** Modern method lookup, before SEND; shared by generated and runtime calls. */
public final class PhpMethodResolver {
    private PhpMethodResolver() {}

    /** Checked resolution rejection, kept distinct from user autoloader exceptions. */
    public static final class InvalidCallback extends RuntimeException {
        private InvalidCallback(String reason) { super(reason); }
        private InvalidCallback(String reason, php.runtime.lang.exception.BaseBaseException pending) { super(reason,pending); }
        /** Preserve the PHP pending-exception identity through API-specific validation replacement. */
        public php.runtime.lang.exception.BaseBaseException typeError(Environment env,TraceInfo trace,String message) throws Throwable {
            try {
                env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,"%s",message);
            } catch (php.runtime.lang.exception.BaseTypeError replacement) {
                if (!(getCause() instanceof php.runtime.lang.exception.BaseBaseException pending)) return replacement;
                var entry=php.runtime.PhpOwnershipExecution.currentEntry(env);
                return entry==null ? InvokeHelper.chainFinallyException(replacement,pending) : entry.chainThrown(replacement,pending);
            }
            throw new IllegalStateException("TypeError allocation did not throw");
        }
    }
    static void rejectCallback(String reason) { throw new InvalidCallback(reason); }
    static void rejectCallback(String reason,php.runtime.lang.exception.BaseBaseException pending) { throw new InvalidCallback(reason,pending); }
    private static void reject(Environment env,TraceInfo trace,boolean callback,String reason,String message,Object... args) {
        if (callback) rejectCallback(reason);
        env.exception(trace,BaseError.class,message,args);
    }

    public static ClassEntity resolveClass(Memory value, Environment env, TraceInfo trace) {
        return resolveClass(value,env,trace,false);
    }
    static ClassEntity resolveCallbackClass(Memory value, Environment env, TraceInfo trace) {
        return resolveClass(value,env,trace,true);
    }
    private static ClassEntity resolveClass(Memory value, Environment env, TraceInfo trace,boolean callback) {
        value = value.toValue();
        if (value.isObject()) return value.toValue(ObjectMemory.class).getReflection();
        if (!value.isString()) {
            env.exception(trace, BaseError.class, "Class name must be a valid object or a string");
            return null;
        }
        String name = value.toString();
        String lookup = name.startsWith("\\") ? name.substring(1) : name;
        ClassEntity clazz = env.fetchClass(lookup, lookup.toLowerCase(Locale.ROOT), true);
        if (clazz == null) reject(env,trace,callback,"class \""+name+"\" not found","Class \"%s\" not found",name);
        return clazz;
    }

    public static ClassEntity parentClass(Environment env, TraceInfo trace) {
        ClassEntity scope = env.getLastClassOnStack();
        if (scope == null) env.exception(trace, BaseError.class, "Cannot access \"parent\" when no class scope is active");
        ClassEntity parent = scope.getParent();
        if (parent == null) env.exception(trace, BaseError.class, "Cannot access \"parent\" when current class scope has no parent");
        return parent;
    }

    public static ClassEntity staticClass(Environment env, TraceInfo trace) {
        ClassEntity clazz = env.getLateStaticClass();
        if (clazz == null) env.exception(trace, BaseError.class, "Cannot access \"static\" when no class scope is active");
        return clazz;
    }

    public static String objectClassName(Memory value, Environment env, TraceInfo trace) {
        value = value.toValue();
        if (!value.isObject()) env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                "Cannot use \"::class\" on %s", value.isNull() ? "null" : value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false")
                        : value.getRealType() == Memory.Type.INT ? "int" : value.getRealType().toString());
        return value.toValue(ObjectMemory.class).getReflection().getName();
    }

    public static String relativeClassName(Environment env, TraceInfo trace, String keyword) {
        ClassEntity scope = env.getLastClassOnStack();
        if (scope == null) env.exception(trace, BaseError.class, "Cannot use \"%s\" in the global scope", keyword);
        ClassEntity clazz = "static".equals(keyword) ? env.getLateStaticClass()
                : "parent".equals(keyword) ? scope.getParent() : scope;
        if (clazz == null) env.exception(trace, BaseError.class,
                "Cannot use \"parent\" when current class scope has no parent");
        return clazz.getName();
    }

    private static String methodName(Memory name, Environment env, TraceInfo trace) {
        if (!name.isString()) env.exception(trace, BaseError.class, "Method name must be a string");
        return name.toString();
    }

    private static void concrete(MethodEntity method, Environment env, TraceInfo trace) {
        concrete(method,env,trace,false);
    }
    private static void concrete(MethodEntity method, Environment env, TraceInfo trace,boolean callback) {
        if (method.isAbstract()) reject(env,trace,callback,
                "cannot call abstract method "+method.getClazzName()+"::"+method.getName()+"()",
                "Cannot call abstract method %s::%s()",method.getClazzName(),method.getName());
    }

    static Invoker instance(Memory receiver, Memory methodName, Environment env, TraceInfo trace) {
        return instance(receiver, methodName, env, trace, env.getLastClassOnStack());
    }

    static Invoker instance(Memory receiver, Memory methodName, Environment env, TraceInfo trace, ClassEntity scope) {
        return instance(receiver,methodName,env,trace,scope,false);
    }
    static Invoker internalInstance(Memory receiver, Memory methodName, Environment env, TraceInfo trace, ClassEntity scope) {
        return instance(receiver,methodName,env,trace,scope,true);
    }
    private static Invoker instance(Memory receiver, Memory methodName, Environment env, TraceInfo trace, ClassEntity scope,boolean callback) {
        String name = methodName(methodName, env, trace);
        receiver = receiver.toValue();
        if (!receiver.isObject()) {
            env.exception(trace, BaseError.class, "Call to a member function %s() on %s", name,
                    receiver.isNull() ? "null" : receiver.getRealType() == Memory.Type.BOOL ? (receiver.toBoolean() ? "true" : "false")
                            : receiver.getRealType() == Memory.Type.INT ? "int" : receiver.getRealType().toString());
            return null;
        }
        IObject object = receiver.toValue(ObjectMemory.class).value;
        ClassEntity clazz = object.getReflection();
        String lowerName = name.toLowerCase(Locale.ROOT);
        MethodEntity method = clazz.findMethod(lowerName);
        if (scope != null && clazz.isInstanceOf(scope)) {
            MethodEntity scoped = scope.findMethod(lowerName);
            if (scoped != null && scoped.getClazz() == scope && scoped.getModifier() == Modifier.PRIVATE) method = scoped;
        }
        if ((method == null || access(method, env, scope) != 0) && clazz.methodMagicCall != null) {
            return new MagicDynamicMethodInvoker(env, trace, object, clazz.methodMagicCall, name);
        }
        if (method == null) {
            reject(env,trace,callback,"class "+clazz.getName()+" does not have a method \""+name+"\"",
                    "Call to undefined method %s::%s()",clazz.getName(),name);
            return null;
        }
        checkAccess(method,name,env,trace,scope,callback,clazz.getName());
        concrete(method,env,trace,callback);
        return method.isStatic() ? new StaticMethodInvoker(env, trace, clazz.getName(), method)
                : new DynamicMethodInvoker(env, trace, object, method);
    }

    static IObject lateObject(Environment env) {
        IObject object = env.getLateObject();
        if (object instanceof Closure && ((Closure) object).hasPhp8Binding()) {
            Memory self = ((Closure) object).getSelf();
            return self.isObject() ? self.toValue(ObjectMemory.class).value : null;
        }
        return object;
    }

    static Invoker stat(ClassEntity clazz, Memory methodName, Environment env, TraceInfo trace,
                         MethodCallCache cache, int cacheIndex, boolean forwarding) {
        return stat(clazz, methodName, env, trace, cache, cacheIndex, forwarding, false);
    }

    static Invoker staticCallable(ClassEntity clazz, Memory name, Environment env, TraceInfo trace) {
        return stat(clazz, name, env, trace, null, 0, false, true);
    }

    private static Invoker stat(ClassEntity clazz, Memory methodName, Environment env, TraceInfo trace,
                                MethodCallCache cache, int cacheIndex, boolean forwarding, boolean callable) {
        return stat(clazz, methodName, env, trace, cache, cacheIndex, forwarding, callable, env.getLastClassOnStack(), lateObject(env));
    }

    static Invoker acquireStatic(ClassEntity clazz, Memory name, Environment env, TraceInfo trace, ClassEntity scope, IObject object) {
        return stat(clazz, name, env, trace, null, 0, false, false, scope, object);
    }

    static Invoker internalStatic(ClassEntity clazz,Memory name,Environment env,TraceInfo trace,ClassEntity scope,IObject object) {
        return internalStatic(clazz,name,env,trace,scope,object,false);
    }
    static Invoker internalStatic(ClassEntity clazz,Memory name,Environment env,TraceInfo trace,ClassEntity scope,IObject object,boolean relative) {
        return stat(clazz,name,env,trace,null,0,false,false,scope,object,true,false,relative,false);
    }
    private static Invoker stat(ClassEntity clazz, Memory methodName, Environment env, TraceInfo trace,
                                MethodCallCache cache, int cacheIndex, boolean forwarding, boolean callable, ClassEntity scope, IObject object) {
        return stat(clazz,methodName,env,trace,cache,cacheIndex,forwarding,callable,scope,object,false);
    }
    static Invoker internalArrayStatic(ClassEntity clazz,Memory name,Environment env,TraceInfo trace,ClassEntity scope,IObject object,boolean relative,boolean closureCapture) {
        return stat(clazz,name,env,trace,null,0,false,false,scope,object,true,true,relative,closureCapture);
    }
    static Invoker internalCaptureStatic(ClassEntity clazz,Memory name,Environment env,TraceInfo trace,ClassEntity scope,IObject object,boolean relative) {
        return stat(clazz,name,env,trace,null,0,false,false,scope,object,true,false,relative,true);
    }
    private static Invoker stat(ClassEntity clazz, Memory methodName, Environment env, TraceInfo trace,
                                MethodCallCache cache, int cacheIndex, boolean forwarding, boolean callable, ClassEntity scope, IObject object,boolean callback) {
        return stat(clazz,methodName,env,trace,cache,cacheIndex,forwarding,callable,scope,object,callback,false,false,false);
    }
    private static Invoker stat(ClassEntity clazz, Memory methodName, Environment env, TraceInfo trace,
                                MethodCallCache cache, int cacheIndex, boolean forwarding, boolean callable, ClassEntity scope, IObject object,boolean callback,boolean arrayCallback,boolean relative,boolean closureCapture) {
        String name = methodName(methodName, env, trace);
        String lowerName = name.toLowerCase(Locale.ROOT);
        String calledClass = forwarding ? env.getLateStatic() : clazz.getName();
        MethodEntity method = cache == null ? null : cache.get(env, cacheIndex, clazz, lowerName, scope);
        if (method != null) return new StaticMethodInvoker(env, trace, calledClass, method);
        method = clazz.findMethod(lowerName);
        boolean compatibleObject = object != null && object.getReflection().isInstanceOf(clazz);
        // Native array lookup binds the caller object only when lexical scope derives from the named class.
        boolean callbackObject = compatibleObject && (relative || scope != null && scope.isInstanceOf(clazz));
        boolean objectArray = arrayCallback && callbackObject;
        // Relative array forms may find a real method on the compatible caller object.
        if (method == null && objectArray && relative) method=object.getReflection().findMethod(lowerName);
        if (method == null || access(method, env, scope) != 0) {
            if (compatibleObject && (!closureCapture || objectArray) && object.getReflection().methodMagicCall != null) {
                if (callable) reject(env,trace,callback,
                        "non-static method "+object.getReflection().methodMagicCall.getClazzName()+"::"+name+"() cannot be called statically",
                        "Non-static method %s::%s() cannot be called statically",object.getReflection().methodMagicCall.getClazzName(),name);
                return new MagicDynamicMethodInvoker(env, trace, object, object.getReflection().methodMagicCall, name);
            }
            if (!objectArray && clazz.methodMagicCallStatic != null) {
                return new MagicStaticMethodInvoker(env, trace, calledClass, clazz.methodMagicCallStatic, name);
            }
        }
        if (method == null) {
            reject(env,trace,callback,"class "+clazz.getName()+" does not have a method \""+name+"\"",
                    "Call to undefined method %s::%s()",clazz.getName(),name);
            return null;
        }
        // A class callback without a bound object rejects a concrete non-static target before visibility.
        if (callback && !method.isStatic() && !callbackObject && !method.isAbstract()) {
            reject(env,trace,true,"non-static method "+clazz.getName()+"::"+method.getName()+"() cannot be called statically",
                    "Non-static method %s::%s() cannot be called statically",clazz.getName(),method.getName());
            return null;
        }
        checkAccess(method,name,env,trace,scope,callback,clazz.getName());
        concrete(method,env,trace,callback);
        if (!method.isStatic()) {
            if ((callback ? callbackObject : compatibleObject) && !callable) return new DynamicMethodInvoker(env, trace, object, method);
            reject(env,trace,callback,"non-static method "+(callback?clazz.getName():method.getClazzName())+"::"+method.getName()+"() cannot be called statically",
                    "Non-static method %s::%s() cannot be called statically",method.getClazzName(),method.getName());
            return null;
        }
        if (cache != null) cache.put(env, cacheIndex, method, clazz, lowerName, scope);
        return new StaticMethodInvoker(env, trace, calledClass, method);
    }

    private static int access(MethodEntity method, Environment env, ClassEntity scope) {
        if (method.getModifier() == Modifier.PUBLIC) return 0;
        if (scope == null) return method.getModifier() == Modifier.PRIVATE ? 2 : 1;
        if (method.getModifier() == Modifier.PRIVATE) return scope.getId() == method.getClazz().getId() ? 0 : 2;
        return method.canAccess(env, scope);
    }

    private static void checkAccess(MethodEntity method, String name, Environment env, TraceInfo trace, ClassEntity scope,boolean callback,String callbackClass) {
        int denied = access(method, env, scope);
        if (denied != 0) reject(env,trace,callback,
                "cannot access "+(denied==1?"protected":"private")+" method "+callbackClass+"::"+method.getName()+"()",
                "Call to %s method %s::%s() from %s",
                denied == 1 ? "protected" : "private", method.getClazzName(), name,
                scope == null ? "global scope" : "scope " + scope.getName());
    }

    static void checkConstructor(ClassEntity clazz, Environment env, TraceInfo trace) {
        MethodEntity method = clazz.methodConstruct;
        if (method == null) return;
        int access = method.canAccessPhp8(env);
        if (access != 0) {
            ClassEntity scope = env.getLastClassOnStack();
            env.exception(trace, BaseError.class, "Call to %s %s::__construct() from %s",
                    access == 1 ? "protected" : "private", method.getClazzName(),
                    scope == null ? "global scope" : "scope " + scope.getName());
        }
        concrete(method, env, trace);
    }
}
