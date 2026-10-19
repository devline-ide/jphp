package php.runtime.ext.core.reflection;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.env.Environment;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.util.List;

@Name("ReflectionEnum")
public class ReflectionEnum extends ReflectionClass {
    public ReflectionEnum(Environment env,ClassEntity clazz){super(env,clazz);}
    public ReflectionEnum(Environment env){super(env);}
    public ReflectionEnum(Environment env,ClassEntity declared,boolean metadata){super(env);setEntity(declared);}
    private void requireEnum(Environment env) {
        if(entity==null || !entity.isEnum())env.exception(ReflectionException.class,"Class \"%s\" is not an enum",entity==null?"":entity.getName());
    }
    @Override @Signature(@Arg("objectOrClass")) public Memory __construct(Environment env,Memory... args){
        super.__construct(env,args);requireEnum(env);return Memory.NULL;
    }
    @Signature(@Arg("name")) public Memory hasCase(Environment env,Memory... args){
        requireEnum(env);ConstantEntity constant=entity.findConstant(args[0].toString());
        return constant!=null && constant.isEnumCase()?Memory.TRUE:Memory.FALSE;
    }
    @Signature(@Arg("name")) public Memory getCase(Environment env,Memory... args){
        requireEnum(env);ConstantEntity constant=entity.findConstant(args[0].toString());
        if(constant==null || !constant.isEnumCase())env.exception(ReflectionException.class,"Case %s::%s does not exist",entity.getName(),args[0].toString());
        return new ObjectMemory(caseReflection(env,constant));
    }
    static ReflectionEnumUnitCase caseReflection(Environment env,ConstantEntity constant) {
        return constant.getClazz().getEnumBackingType()==null?new ReflectionEnumUnitCase(env,constant):new ReflectionEnumBackedCase(env,constant);
    }
    @Signature public Memory getCases(Environment env,Memory... args){
        requireEnum(env);ArrayMemory result=new ArrayMemory();
        for(ConstantEntity constant:entity.getConstants())if(constant.isEnumCase())result.add(new ObjectMemory(caseReflection(env,constant)));
        return result.toConstant();
    }
    @Signature public Memory isBacked(Environment env,Memory... args){requireEnum(env);return entity.getEnumBackingType()==null?Memory.FALSE:Memory.TRUE;}
    @Signature public Memory getBackingType(Environment env,Memory... args){
        requireEnum(env);return entity.getEnumBackingType()==null?Memory.NULL:new ObjectMemory(ReflectionType.fromDeclaration(env,new PhpTypeDeclaration(List.of(List.of(entity.getEnumBackingType())))));
    }
}
