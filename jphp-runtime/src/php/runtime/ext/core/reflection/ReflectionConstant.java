package php.runtime.ext.core.reflection;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import static php.runtime.annotation.Reflection.*;
@Name("ReflectionConstant")
@Signature(@Arg(value="name",type=HintType.STRING))
public class ReflectionConstant extends Reflection implements Reflector, php.runtime.lang.IPhpNativePropertyGuard {
    public void validateNativePropertyWrite(Environment env,php.runtime.env.TraceInfo trace,String property) {
        PhpNativeReflectionProperties.write(env,trace,"ReflectionConstant",property);
    }
    public void validateNativePropertyReference(Environment env,php.runtime.env.TraceInfo trace,String property,Memory current) {
        PhpNativeReflectionProperties.reference(env,trace,"ReflectionConstant",property,current);
    }
    private ConstantEntity entity;
    public ReflectionConstant(Environment env,ClassEntity clazz) {super(env,clazz);}
    @Signature(@Arg(value="name",type=HintType.STRING))
    public Memory __construct(Environment env,Memory...args) {
        String name=args[0].toString();if(name.startsWith("\\")) name=name.substring(1);
        entity=env.getConstants().get(name.toLowerCase());
        if(entity==null) entity=env.getScope().findUserConstant(name);
        if(entity!=null && entity.isCaseSensitise() && !entity.getName().equals(name)) entity=null;
        if(entity==null) {exception(env,"Constant %s does not exist",name);return Memory.NULL;}
        setProp("name",entity.getName());return Memory.NULL;
    }
    @Signature public String getName() {return entity.getName();}
    @Signature public String getNamespaceName() {return entity.getNamespaceName()==null ? "" : entity.getNamespaceName();}
    @Signature public String getShortName() {return entity.getShortName();}
    @Signature public Memory getValue(Environment env) {return entity.getValue(env).toImmutable();}
    private php.runtime.ext.support.Extension extension(Environment env) {
        if(entity.getModule()!=null || env.getConstants().containsValue(entity)) return null;
        for(String name:env.getScope().getExtensions()) {
            var extension=env.getScope().getExtension(name);
            for(var constant:extension.getConstants().values()) if(constant.name.equals(entity.getName())) return extension;
        }
        return null;
    }
    @Signature public Memory getExtension(Environment env) {
        var extension=extension(env);if(extension==null) return Memory.NULL;
        var result=new ReflectionExtension(env,env.fetchClass("ReflectionExtension"));result.setExtension(extension);
        return php.runtime.memory.ObjectMemory.valueOf(result);
    }
    @Signature public Memory getExtensionName(Environment env) {
        var extension=extension(env);return extension==null ? Memory.FALSE : StringMemory.valueOf(extension.getName());
    }
    @Signature public Memory getFileName() {
        return entity.getTrace()==null || entity.getTrace().getFileName()==null || entity.getTrace()==php.runtime.env.TraceInfo.UNKNOWN
                ? Memory.FALSE : StringMemory.valueOf(entity.getTrace().getFileName());
    }
    @Signature public boolean isDeprecated() {return entity.getPhpAttributes().stream().anyMatch(a->a.name().equalsIgnoreCase("Deprecated"));}
    @Signature({@Arg(value="name",type=HintType.STRING,nullable=true,optional=@Optional("null")),
            @Arg(value="flags",type=HintType.INT,optional=@Optional("0"))})
    public Memory getAttributes(Environment env,Memory...args) {return ReflectionAttribute.collect(env,entity,args[0],args[1],"ReflectionConstant");}
}
