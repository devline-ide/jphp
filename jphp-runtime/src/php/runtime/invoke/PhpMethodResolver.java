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

    public static ClassEntity resolveClass(Memory value, Environment env, TraceInfo trace) {
        value = value.toValue();
        if (value.isObject()) return value.toValue(ObjectMemory.class).getReflection();
        if (!value.isString()) {
            env.exception(trace, BaseError.class, "Class name must be a valid object or a string");
            return null;
        }
        String name = value.toString();
        String lookup = name.startsWith("\\") ? name.substring(1) : name;
        ClassEntity clazz = env.fetchClass(lookup, lookup.toLowerCase(Locale.ROOT), true);
        if (clazz == null) env.exception(trace, BaseError.class, "Class \"%s\" not found", name);
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
        if (method.isAbstract()) env.exception(trace, BaseError.class,
                "Cannot call abstract method %s::%s()", method.getClazzName(), method.getName());
    }

    static Invoker instance(Memory receiver, Memory methodName, Environment env, TraceInfo trace) {
        return instance(receiver, methodName, env, trace, env.getLastClassOnStack());
    }

    static Invoker instance(Memory receiver, Memory methodName, Environment env, TraceInfo trace, ClassEntity scope) {
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
            env.exception(trace, BaseError.class, "Call to undefined method %s::%s()", clazz.getName(), name);
            return null;
        }
        checkAccess(method, name, env, trace, scope);
        concrete(method, env, trace);
        return method.isStatic() ? new StaticMethodInvoker(env, trace, clazz.getName(), method)
                : new DynamicMethodInvoker(env, trace, object, method);
    }

    private static IObject lateObject(Environment env) {
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

    private static Invoker stat(ClassEntity clazz, Memory methodName, Environment env, TraceInfo trace,
                                MethodCallCache cache, int cacheIndex, boolean forwarding, boolean callable, ClassEntity scope, IObject object) {
        String name = methodName(methodName, env, trace);
        String lowerName = name.toLowerCase(Locale.ROOT);
        String calledClass = forwarding ? env.getLateStatic() : clazz.getName();
        MethodEntity method = cache == null ? null : cache.get(env, cacheIndex, clazz, lowerName, scope);
        if (method != null) return new StaticMethodInvoker(env, trace, calledClass, method);
        method = clazz.findMethod(lowerName);
        boolean compatibleObject = object != null && object.getReflection().isInstanceOf(clazz);
        if (method == null || access(method, env, scope) != 0) {
            if (compatibleObject && object.getReflection().methodMagicCall != null) {
                if (callable) env.exception(trace, BaseError.class,
                        "Non-static method %s::%s() cannot be called statically",
                        object.getReflection().methodMagicCall.getClazzName(), name);
                return new MagicDynamicMethodInvoker(env, trace, object, object.getReflection().methodMagicCall, name);
            }
            if (clazz.methodMagicCallStatic != null) {
                return new MagicStaticMethodInvoker(env, trace, calledClass, clazz.methodMagicCallStatic, name);
            }
        }
        if (method == null) {
            env.exception(trace, BaseError.class, "Call to undefined method %s::%s()", clazz.getName(), name);
            return null;
        }
        checkAccess(method, name, env, trace, scope);
        concrete(method, env, trace);
        if (!method.isStatic()) {
            if (compatibleObject && !callable) return new DynamicMethodInvoker(env, trace, object, method);
            env.exception(trace, BaseError.class, "Non-static method %s::%s() cannot be called statically", method.getClazzName(), method.getName());
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

    private static void checkAccess(MethodEntity method, String name, Environment env, TraceInfo trace, ClassEntity scope) {
        int denied = access(method, env, scope);
        if (denied != 0) env.exception(trace, BaseError.class, "Call to %s method %s::%s() from %s",
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
