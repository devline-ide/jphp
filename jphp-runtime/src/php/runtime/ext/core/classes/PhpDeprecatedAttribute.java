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

/** Profile-local native declaration; argument effects use the ordinary PHP constructor binder. */
@Name("Deprecated") @Final
@Signature({@Arg(value="message",type=HintType.STRING,nullable=true,readOnly=true),
        @Arg(value="since",type=HintType.STRING,nullable=true,readOnly=true)})
public final class PhpDeprecatedAttribute extends BaseObject implements php.runtime.lang.IPhpNativePropertyGuard {
    public PhpDeprecatedAttribute(Environment env,ClassEntity clazz){super(env,clazz);}
    @Ignore public void validateNativePropertyWrite(Environment env,TraceInfo trace,String property){
        if(!property.equals("message") && !property.equals("since"))
            env.exception(trace,php.runtime.lang.exception.BaseError.class,"Cannot create dynamic property Deprecated::$%s",property);
    }
    @Ignore public void validateNativePropertyReference(Environment env,TraceInfo trace,String property,Memory current){
        validateNativePropertyWrite(env,trace,property);
    }
    public static void configure(ClassEntity entity){
        if(entity.getNativeClass()!=PhpDeprecatedAttribute.class)throw new IllegalArgumentException("Foreign Deprecated native declaration");
        entity.setPhpAttributes(List.of(new PhpAttribute("Attribute",TraceInfo.UNKNOWN,PhpAttribute.CLASS,false,
                List.of(PhpAttribute.Argument.nativeInteger(PhpAttribute.CLASS|PhpAttribute.FUNCTION|PhpAttribute.METHOD
                        |PhpAttribute.CLASS_CONSTANT|PhpAttribute.CONSTANT)))));
        var constructor=entity.findMethod("__construct");
        if(constructor==null || constructor.getParameters().length!=2)
            throw new IllegalArgumentException("Deprecated constructor signature is absent");
        for(var parameter:constructor.getParameters())
            parameter.setTypeChecker(new php.runtime.reflection.support.PhpTypeDeclaration(List.of(List.of("string"),List.of("null"))));
        for(String name:List.of("message","since")){
            var property=entity.findProperty(name);
            property.setDeclaredType(new php.runtime.reflection.support.PhpTypeDeclaration(List.of(List.of("string"),List.of("null"))));
            property.setPhpReadonly(true);property.setDefault(false);property.setDefaultValue(Memory.UNDEFINED);
        }
    }
    @Signature({@Arg(value="message",type=HintType.STRING,nullable=true,optional=@Optional("null")),
            @Arg(value="since",type=HintType.STRING,nullable=true,optional=@Optional("null"))})
    public Memory __construct(Environment env,Memory...args)throws Throwable{
        if(getOwnershipObject()!=null)return php.runtime.invoke.PhpDeprecatedOwnedInvocation.publishProperties(this,env,args);
        initialize(env,"message",args[0]);
        initialize(env,"since",args[1]);
        return Memory.NULL;
    }
    private void initialize(Environment env,String name,Memory value){
        String key=getReflection().findProperty(name).getSpecificName();
        var current=getProperties().getByScalar(key);
        if(current!=null&&!current.isUndefined())env.exception(php.runtime.lang.exception.BaseError.class,
                "Cannot modify readonly property Deprecated::$%s",name);
        getProperties().putAsKeyString(key,value.toValue().toImmutable());
    }
}
