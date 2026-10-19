package php.runtime.ext.core.classes;
import java.util.List;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpAttribute;
import static php.runtime.annotation.Reflection.*;
@Name("SensitiveParameter") @Final
public final class PhpSensitiveParameterAttribute extends BaseObject {
    public PhpSensitiveParameterAttribute(Environment env,ClassEntity clazz) {super(env,clazz);}
    public static void configure(ClassEntity entity) {
        if(entity.getNativeClass()!=PhpSensitiveParameterAttribute.class)throw new IllegalArgumentException("Foreign SensitiveParameter native declaration");
        entity.setPhpAttributes(List.of(new PhpAttribute("Attribute",TraceInfo.UNKNOWN,PhpAttribute.CLASS,false,
                List.of(PhpAttribute.Argument.nativeInteger(PhpAttribute.PARAMETER)))));
    }
    @Signature public Memory __construct(Environment env,Memory...args) {return Memory.NULL;}
}
