package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.MethodEntity;

/** Reflection flags are caller-profile values; shared native declarations stay immutable. */
public final class PhpReflectionModifiers {
    private PhpReflectionModifiers() {}

    public static boolean modern(Environment env, TraceInfo trace) {
        return env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
    }

    public static Memory constantValue(ConstantEntity constant, boolean modern) {
        if (!modern || constant.getClazz() == null) return null;
        if(constant.getClazz().getNativeClass()==ReflectionClass.class) return switch(constant.getName()) {
            case "IS_FINAL" -> LongMemory.valueOf(32);
            case "IS_EXPLICIT_ABSTRACT" -> LongMemory.valueOf(64);
            default -> null;
        };
        if(constant.getClazz().getNativeClass()==ReflectionMethod.class) return switch(constant.getName()) {
            case "IS_PUBLIC" -> LongMemory.valueOf(1);
            case "IS_PROTECTED" -> LongMemory.valueOf(2);
            case "IS_PRIVATE" -> LongMemory.valueOf(4);
            case "IS_STATIC" -> LongMemory.valueOf(16);
            case "IS_FINAL" -> LongMemory.valueOf(32);
            case "IS_ABSTRACT" -> LongMemory.valueOf(64);
            default -> null;
        };
        if(constant.getClazz().getNativeClass()!=ReflectionProperty.class) return null;
        return switch (constant.getName()) {
            case "IS_PUBLIC" -> LongMemory.valueOf(1);
            case "IS_PROTECTED" -> LongMemory.valueOf(2);
            case "IS_PRIVATE" -> LongMemory.valueOf(4);
            case "IS_STATIC" -> LongMemory.valueOf(16);
            case "IS_FINAL" -> LongMemory.valueOf(32);
            case "IS_READONLY" -> LongMemory.valueOf(128);
            case "IS_PROTECTED_SET" -> LongMemory.valueOf(2048);
            case "IS_PRIVATE_SET" -> LongMemory.valueOf(4096);
            default -> null;
        };
    }

    /** Flags use caller-profile values; physical declarations and invocation dispatch stay unchanged. */
    public static int methodFlags(MethodEntity method) {
        int flags=method.isPrivate()?4:method.isProtected()?2:1;
        if(method.isStatic())flags|=16;
        if(method.isFinal())flags|=32;
        if(method.isAbstract())flags|=64;
        return flags;
    }

    public static int propertyFlags(PropertyEntity property, boolean modern) {
        int flags = property.isPrivate() ? (modern ? 4 : ReflectionProperty.IS_PRIVATE)
                : property.isProtected() ? (modern ? 2 : ReflectionProperty.IS_PROTECTED)
                : (modern ? 1 : ReflectionProperty.IS_PUBLIC);
        if (property.isStatic()) flags |= modern ? 16 : ReflectionProperty.IS_STATIC;
        if (property.isFinal()) flags |= ReflectionProperty.IS_FINAL;
        if(property.isPhpReadonly())flags|=128;
        if(modern && property.isPrivateSet())flags|=4096;
        if(modern && property.isProtectedSet())flags|=2048;
        return flags;
    }
}
