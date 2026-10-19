package php.runtime.ext.core.reflection;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.env.Environment;
import php.runtime.memory.*;
import php.runtime.reflection.*;

@Name("ReflectionEnumUnitCase")
public class ReflectionEnumUnitCase extends ReflectionClassConstant {
    public ReflectionEnumUnitCase(Environment env,ClassEntity clazz){super(env,clazz);}
    public ReflectionEnumUnitCase(Environment env,ConstantEntity constant){super(env,constant);requireCase(env);}
    protected void requireCase(Environment env){
        if(entity==null || !entity.isEnumCase())env.exception(ReflectionException.class,"Constant %s::%s is not a case",entity.getClazz().getName(),entity.getName());
    }
    @Override @Signature({@Arg("class"),@Arg("constant")}) public Memory __construct(Environment env,Memory... args){
        super.__construct(env,args);requireCase(env);return Memory.NULL;
    }
    @Signature public Memory getEnum(Environment env,Memory... args){requireCase(env);return new ObjectMemory(new ReflectionEnum(env,entity.getClazz(),true));}
}
