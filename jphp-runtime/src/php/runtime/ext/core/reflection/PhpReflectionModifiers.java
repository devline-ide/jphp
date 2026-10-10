package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.PropertyEntity;

/** Reflection flags are caller-profile values; shared native declarations stay immutable. */
public final class PhpReflectionModifiers {
    private PhpReflectionModifiers() {}

    public static boolean modern(Environment env, TraceInfo trace) {
        return env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
    }

    public static Memory constantValue(ConstantEntity constant, boolean modern) {
        if (!modern || constant.getClazz() == null
                || constant.getClazz().getNativeClass() != ReflectionProperty.class) return null;
        return switch (constant.getName()) {
            case "IS_PUBLIC" -> LongMemory.valueOf(1);
            case "IS_PROTECTED" -> LongMemory.valueOf(2);
            case "IS_PRIVATE" -> LongMemory.valueOf(4);
            case "IS_STATIC" -> LongMemory.valueOf(16);
            case "IS_FINAL" -> LongMemory.valueOf(32);
            default -> null;
        };
    }

    public static int propertyFlags(PropertyEntity property, boolean modern) {
        int flags = property.isPrivate() ? (modern ? 4 : ReflectionProperty.IS_PRIVATE)
                : property.isProtected() ? (modern ? 2 : ReflectionProperty.IS_PROTECTED)
                : (modern ? 1 : ReflectionProperty.IS_PUBLIC);
        if (property.isStatic()) flags |= modern ? 16 : ReflectionProperty.IS_STATIC;
        if (property.isFinal()) flags |= ReflectionProperty.IS_FINAL;
        return flags;
    }
}
