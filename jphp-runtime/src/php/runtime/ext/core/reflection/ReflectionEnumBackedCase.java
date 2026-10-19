package php.runtime.ext.core.reflection;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.env.Environment;
import php.runtime.memory.*;
import php.runtime.reflection.*;

@Name("ReflectionEnumBackedCase")
public class ReflectionEnumBackedCase extends ReflectionEnumUnitCase {
    public ReflectionEnumBackedCase(Environment env,ClassEntity clazz){super(env,clazz);}
    public ReflectionEnumBackedCase(Environment env,ConstantEntity constant){super(env,constant);requireBacking(env);}
    private void requireBacking(Environment env){
        requireCase(env);if(entity.getClazz().getEnumBackingType()==null)env.exception(ReflectionException.class,"Enum case %s::%s is not a backed case",entity.getClazz().getName(),entity.getName());
    }
    @Override @Signature({@Arg("class"),@Arg("constant")}) public Memory __construct(Environment env,Memory... args){super.__construct(env,args);requireBacking(env);return Memory.NULL;}
    @Signature public Memory getBackingValue(Environment env,Memory... args){requireBacking(env);return ((php.runtime.lang.BaseEnum)entity.getValue(env).toValue(ObjectMemory.class).value).enumBackingValue();}
}
