package php.runtime.ext.core.classes;
import java.util.List;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpAttribute;
import static php.runtime.annotation.Reflection.*;

/** Native declaration; the functional Override validator consumes declaration metadata. */
@Name("Override") @Final
public final class PhpOverrideAttribute extends BaseObject {
    public PhpOverrideAttribute(Environment env,ClassEntity clazz) {super(env,clazz);}
    public static void configure(ClassEntity entity) {
        if(entity.getNativeClass()!=PhpOverrideAttribute.class) throw new IllegalArgumentException("Foreign Override native declaration");
        entity.setPhpAttributes(List.of(new PhpAttribute("Attribute",TraceInfo.UNKNOWN,PhpAttribute.CLASS,false,
                List.of(PhpAttribute.Argument.nativeInteger(PhpAttribute.METHOD|PhpAttribute.PROPERTY)))));
    }
    @Signature public Memory __construct(Environment env,Memory...args) {return Memory.NULL;}
}
