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
@Name("DelayedTargetValidation") @Final
public final class PhpDelayedTargetValidationAttribute extends BaseObject {
    public PhpDelayedTargetValidationAttribute(Environment env,ClassEntity clazz){super(env,clazz);}
    public static void configure(ClassEntity entity){
        if(entity.getNativeClass()!=PhpDelayedTargetValidationAttribute.class)throw new IllegalArgumentException("Foreign DelayedTargetValidation native declaration");
        entity.setPhpAttributes(List.of(new PhpAttribute("Attribute",TraceInfo.UNKNOWN,PhpAttribute.CLASS,false,
                List.of(PhpAttribute.Argument.nativeInteger(PhpAttribute.ALL)))));
    }
}
