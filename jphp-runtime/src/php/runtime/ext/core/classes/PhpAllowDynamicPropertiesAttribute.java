package php.runtime.ext.core.classes;
import java.util.List;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.lang.exception.BaseArgumentCountError;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpAttribute;
import static php.runtime.annotation.Reflection.*;
/** Profile-local native declaration; its class-policy handler never evaluates its arguments. */
@Name("AllowDynamicProperties") @Final
public final class PhpAllowDynamicPropertiesAttribute extends BaseObject {
    public PhpAllowDynamicPropertiesAttribute(Environment env,ClassEntity clazz){super(env,clazz);}
    public static void configure(ClassEntity entity){
        if(entity.getNativeClass()!=PhpAllowDynamicPropertiesAttribute.class)throw new IllegalArgumentException("Foreign AllowDynamicProperties native declaration");
        entity.setPhpAttributes(List.of(new PhpAttribute("Attribute",TraceInfo.UNKNOWN,PhpAttribute.CLASS,false,
                List.of(PhpAttribute.Argument.nativeInteger(PhpAttribute.CLASS)))));
    }
    @Signature public Memory __construct(Environment env,Memory...args){
        if(args.length!=0)env.exception(env.trace(),BaseArgumentCountError.class,
                "AllowDynamicProperties::__construct() expects exactly 0 arguments, %d given",args.length);
        return Memory.NULL;
    }
}
