package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.reflection.ReflectionAttribute;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;
import php.runtime.reflection.support.Entity;
import php.runtime.reflection.support.PhpAttribute;

/** Declaration metadata is pure; argument construction and warnings occur only at semantic uses. */
public final class PhpDeprecatedInvocation {
    private PhpDeprecatedInvocation() {}

    public static PhpAttribute marker(Entity declaration) {
        if (declaration == null || declaration.getPhpAttributes().isEmpty()) return null;
        ModuleEntity module = declaration instanceof FunctionEntity function ? function.getModule()
                : declaration instanceof MethodEntity method ? method.getClazz() == null ? null : method.getClazz().getModule()
                : declaration instanceof ConstantEntity constant ? constant.getModule()
                : declaration instanceof ClassEntity clazz ? clazz.getModule() : null;
        if (module == null || module.getPhpLanguageProfile() != PhpLanguageProfile.PHP_8_5) return null;
        for (PhpAttribute attribute : declaration.getPhpAttributes())
            if (attribute.name().equalsIgnoreCase("Deprecated")) return attribute;
        return null;
    }

    public static void beforeFunction(Environment env, TraceInfo trace, AbstractFunctionEntity declaration) throws Throwable {
        PhpAttribute attribute = marker(declaration);
        if (attribute == null) return;
        php.runtime.PhpPrimitiveCellRuntime.opaque(env);
        String display;
        if (declaration instanceof MethodEntity method && method.getClazz() instanceof ClosureEntity) {
            display = closureDisplay(env, method);
        } else if (declaration instanceof MethodEntity method) {
            display = "Method " + method.getClazzName() + "::" + method.getName();
        } else {
            display = "Function " + declaration.getName();
        }
        warn(env, trace, declaration, attribute, display + "() is deprecated");
    }

    private static String closureDisplay(Environment env, MethodEntity declaration) throws Throwable {
        Class<?> physical = env.getScope().getClassLoader().loadClass(declaration.getClazz().getCompiledInternalName());
        try {
            return (String) physical.getDeclaredField("__$deprecatedClosureName").get(null);
        } catch (NoSuchFieldException olderArtifact) {
            return "Function {closure:" + declaration.getTrace().getFileName() + ":"
                    + (declaration.getTrace().getStartLine() + 1) + "}";
        }
    }

    public static void beforeConstant(Environment env, TraceInfo trace, ConstantEntity declaration) {
        PhpAttribute attribute = marker(declaration);
        if (attribute == null) return;
        // A handler may read this same constant. The guard belongs to this Environment and thread,
        // exists only during the diagnostic, and never suppresses a later ordinary read.
        String key = "\0deprecated-constant:" + Thread.currentThread().threadId() + ":" + declaration.getInternalName();
        if (env.getUserValue(key, Boolean.class) != null) return;
        env.setUserValue(key, Boolean.TRUE);
        try {
            php.runtime.PhpPrimitiveCellRuntime.opaque(env);
            String name = declaration.getClazz() == null ? declaration.getName()
                    : declaration.getClazz().getName() + "::" + declaration.getName();
            warn(env, trace, declaration, attribute, "Constant " + name + " is deprecated");
        } catch (Throwable failure) {
            PhpDeprecatedInvocation.<RuntimeException>raise(failure);
        } finally {
            env.removeUserValue(key);
        }
    }

    public static void beforeTrait(Environment env, TraceInfo trace, ClassEntity trait, String usingClass) {
        PhpAttribute attribute = marker(trait);
        if (attribute == null) return;
        try {
            php.runtime.PhpPrimitiveCellRuntime.opaque(env);
            warn(env, trace, trait, attribute, "Trait " + trait.getName() + " used by " + usingClass + " is deprecated");
        } catch (Throwable failure) {
            PhpDeprecatedInvocation.<RuntimeException>raise(failure);
        }
    }

    private static void warn(Environment env, TraceInfo trace, Entity declaration,
                             PhpAttribute attribute, String message) throws Throwable {
        if (!attribute.arguments().isEmpty()) {
            Memory since, detail;
            if (php.runtime.PhpOwnershipExecution.current(env) != null || php.runtime.PhpRuntimeOwnership.current(env) != null) {
                var values = PhpDeprecatedOwnedInvocation.details(env, trace, declaration, attribute);
                since = values.since(); detail = values.message();
            } else {
                var instance = new ReflectionAttribute(env, declaration, attribute).instantiateForInvocation(env, trace);
                var properties = instance.toValue(ObjectMemory.class).value.getProperties();
                since = properties.valueOfIndex("since").toValue();
                detail = properties.valueOfIndex("message").toValue();
            }
            if (!since.isNull() && !since.toString().isEmpty()) message += " since " + since;
            if (!detail.isNull() && !detail.toString().isEmpty()) message += ", " + detail;
        }
        env.error(trace, ErrorType.E_USER_DEPRECATED, message);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void raise(Throwable failure) throws E { throw (E) failure; }
}
