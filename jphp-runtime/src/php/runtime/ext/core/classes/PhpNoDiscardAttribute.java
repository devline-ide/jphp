package php.runtime.ext.core.classes;
import java.util.List;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpAttribute;
import static php.runtime.annotation.Reflection.*;
@Name("NoDiscard") @Final
@Signature(@Arg(value="message",type=HintType.STRING,nullable=true,readOnly=true))
public final class PhpNoDiscardAttribute extends BaseObject {
    public PhpNoDiscardAttribute(Environment env,ClassEntity clazz){super(env,clazz);}
    public static void configure(ClassEntity entity){
        if(entity.getNativeClass()!=PhpNoDiscardAttribute.class)throw new IllegalArgumentException("Foreign NoDiscard native declaration");
        entity.setPhpAttributes(List.of(new PhpAttribute("Attribute",TraceInfo.UNKNOWN,PhpAttribute.CLASS,false,
                List.of(PhpAttribute.Argument.nativeInteger(PhpAttribute.FUNCTION|PhpAttribute.METHOD)))));
        var message=entity.findProperty("message");
        message.setDeclaredType(new php.runtime.reflection.support.PhpTypeDeclaration(List.of(List.of("string"),List.of("null"))));
        message.setPhpReadonly(true);message.setDefault(false);message.setDefaultValue(Memory.UNDEFINED);
    }
    @Signature(@Arg(value="message",type=HintType.STRING,nullable=true,optional=@Optional("null")))
    public Memory __construct(Environment env,Memory...args)throws Throwable{
        String key=getReflection().findProperty("message").getSpecificName();
        var current=getProperties().getByScalar(key);
        if(current!=null&&!current.isUndefined())env.exception(php.runtime.lang.exception.BaseError.class,
                "Cannot modify readonly property NoDiscard::$message");
        if(getOwnershipObject()!=null)return php.runtime.invoke.PhpNoDiscardOwnedInvocation.publishMessage(this,env,args);
        getProperties().putAsKeyString(key,args[0].toValue().toImmutable());return Memory.NULL;
    }
}
