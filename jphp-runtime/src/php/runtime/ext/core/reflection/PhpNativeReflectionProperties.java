package php.runtime.ext.core.reflection;
import php.runtime.Memory;
import php.runtime.env.*;
import php.runtime.lang.exception.BaseError;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
public final class PhpNativeReflectionProperties {
    private PhpNativeReflectionProperties() {}
    public static void configure(ClassEntity entity) {
        var name=entity.findProperty("name");
        if(name==null) throw new IllegalArgumentException("Native Reflection name property is absent");
        name.setDeclaredType(new PhpTypeDeclaration(java.util.List.of(java.util.List.of("string"))));
    }
    public static void write(Environment env,TraceInfo trace,String className,String property) {
        if(property.equals("name")) env.exception(trace,ReflectionException.class,"Cannot set read-only property %s::$name",className);
    }
    public static void reference(Environment env,TraceInfo trace,String className,String property,Memory current) {
        if(!property.equals("name")) return;
        if(current==null || current.isUndefined()) env.exception(trace,BaseError.class,
                "Cannot access uninitialized non-nullable property %s::$name by reference",className);
        else env.exception(trace,ReflectionException.class,"Cannot acquire reference to read-only property %s::$name",className);
    }
}
