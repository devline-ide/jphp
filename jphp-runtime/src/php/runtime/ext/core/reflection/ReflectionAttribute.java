package php.runtime.ext.core.reflection;
import java.util.List;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseValueError;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.Entity;
import php.runtime.reflection.support.PhpAttribute;
import static php.runtime.annotation.Reflection.*;

@Name("ReflectionAttribute") @Final @Signature(@Arg(value="name",type=HintType.STRING))
public final class ReflectionAttribute extends Reflection implements php.runtime.lang.IPhpNativePropertyGuard {
    public void validateNativePropertyWrite(Environment env,php.runtime.env.TraceInfo trace,String property) {
        PhpNativeReflectionProperties.write(env,trace,"ReflectionAttribute",property);
    }
    public void validateNativePropertyReference(Environment env,php.runtime.env.TraceInfo trace,String property,Memory current) {
        PhpNativeReflectionProperties.reference(env,trace,"ReflectionAttribute",property,current);
    }
    public static final int IS_INSTANCEOF=2;
    private PhpAttribute attribute;
    private Entity declaration;
    public ReflectionAttribute(Environment env,ClassEntity clazz) {super(env,clazz);}
    public ReflectionAttribute(Environment env,Entity declaration,PhpAttribute attribute) {
        super(env,env.getScope().fetchPhp85Class("ReflectionAttribute"));
        this.declaration=declaration;this.attribute=attribute;setProp("name",attribute.name());
    }
    @Signature private Memory __construct(Environment env,Memory...args) {return Memory.NULL;}
    @Signature public String getName() {return attribute.name();}
    @Signature public Memory getArguments(Environment env) {return attribute.evaluateArguments(env,declaration).toImmutable();}
    @Signature public int getTarget() {return attribute.target();}
    @Signature public boolean isRepeated() {
        return declaration.getPhpAttributes().stream().filter(a->a.name().equalsIgnoreCase(attribute.name())).count()>1;
    }
    @Signature public Memory newInstance(Environment env) throws Throwable {
        return instantiate(env,attribute.trace(),attribute.strictTypes());
    }
    @Ignore public Memory instantiateForDiscardedCall(Environment env,php.runtime.env.TraceInfo callTrace) throws Throwable {
        return instantiateForInvocation(env,callTrace);
    }
    @Ignore public Memory instantiateForInvocation(Environment env,php.runtime.env.TraceInfo callTrace) throws Throwable {
        var caller=env.getModuleManager().findModule(callTrace);
        return instantiate(env,callTrace,caller!=null&&caller.isStrictTypes());
    }
    private Memory instantiate(Environment env,php.runtime.env.TraceInfo bindingTrace,boolean strict) throws Throwable {
        ClassEntity clazz=env.fetchClass(attribute.name(),true,PhpLanguageProfile.PHP_8_5);
        if(clazz==null) {env.exception(attribute.trace(),BaseError.class,"Attribute class \"%s\" not found",attribute.name());return Memory.NULL;}
        int flags;
        if(clazz.getNativeClass()==php.runtime.ext.core.classes.PhpAttributeMarker.class) flags=PhpAttribute.CLASS;
        else {
            PhpAttribute marker=null;
            for(PhpAttribute candidate:clazz.getPhpAttributes()) if(candidate.name().equalsIgnoreCase("Attribute")) {marker=candidate;break;}
            if(marker==null) {env.exception(attribute.trace(),BaseError.class,"Attempting to use non-attribute class \"%s\" as attribute",clazz.getName());return Memory.NULL;}
            Memory value=new ReflectionAttribute(env,clazz,marker).newInstance(env);
            flags=value.toValue(ObjectMemory.class).value.getProperties().valueOfIndex("flags").toInteger();
        }
        if((flags & ~(PhpAttribute.ALL|PhpAttribute.REPEATABLE))!=0) {
            env.exception(attribute.trace(),BaseError.class,"Invalid attribute flags specified");return Memory.NULL;
        }
        if((flags & attribute.target())==0) {
            env.exception(attribute.trace(),BaseError.class,"Attribute \"%s\" cannot target %s (allowed targets: %s)",clazz.getName(),targetName(attribute.target()),allowedTargets(flags));return Memory.NULL;
        }
        if(isRepeated() && (flags & PhpAttribute.REPEATABLE)==0) {
            env.exception(attribute.trace(),BaseError.class,"Attribute \"%s\" must not be repeated",clazz.getName());return Memory.NULL;
        }
        if(clazz.getNativeClass()==php.runtime.ext.core.classes.PhpAllowDynamicPropertiesAttribute.class) {
            String rejection=php.runtime.reflection.support.PhpAllowDynamicProperties.rejection(declaration instanceof ClassEntity owner?owner:null,attribute.target());
            if(rejection!=null){env.exception(attribute.trace(),BaseError.class,rejection);return Memory.NULL;}
        }
        if(clazz.getNativeClass()==php.runtime.ext.core.classes.PhpDeprecatedAttribute.class) {
            String rejection=php.runtime.reflection.support.PhpDeprecatedValidation.classRejection(declaration instanceof ClassEntity owner?owner:null,attribute.target());
            if(rejection!=null){env.exception(attribute.trace(),BaseError.class,rejection);return Memory.NULL;}
        }
        var context=php.runtime.PhpPropertyWriteContext.enter(env,bindingTrace,strict);
        try {
            ArrayMemory values=attribute.evaluateArguments(env,declaration);
            try(var call=php.runtime.invoke.PhpCallFrame.constructResolved(clazz,env,bindingTrace,values.size())) {
                return call.invokeKeyed(values,false);
            }
        } finally {php.runtime.PhpPropertyWriteContext.leave(context);}
    }
    private static String allowedTargets(int flags) {
        var names=new java.util.ArrayList<String>();
        for(int bit=1;bit<=64;bit<<=1) if((flags & bit)!=0) names.add(targetName(bit));
        return String.join(", ",names);
    }
    private static String targetName(int target) {
        return switch(target) {case 1->"class";case 2->"function";case 4->"method";case 8->"property";
            case 16->"class constant";case 32->"parameter";case 64->"constant";default->throw new IllegalArgumentException();};
    }
    public static Memory collect(Environment env,Entity declaration,Memory name,Memory flags,String reflector) {
        int mode=flags.toInteger();
        if(mode!=0 && mode!=IS_INSTANCEOF) {
            env.exception(BaseValueError.class,"%s::getAttributes(): Argument #2 ($flags) must be a valid attribute filter flag",reflector);return Memory.NULL;
        }
        ClassEntity filter=null;
        if(mode==IS_INSTANCEOF && !name.isNull()) {
            filter=env.fetchClass(name.toString(),true,PhpLanguageProfile.PHP_8_5);
            if(filter==null) {env.exception(BaseError.class,"Class \"%s\" not found",name);return Memory.NULL;}
        }
        ArrayMemory result=new ArrayMemory();
        for(PhpAttribute attribute:declaration.getPhpAttributes()) {
            boolean include=name.isNull() || mode==0 && attribute.name().equalsIgnoreCase(name.toString());
            if(filter!=null) {
                ClassEntity candidate=env.fetchClass(attribute.name(),true,PhpLanguageProfile.PHP_8_5);
                include=candidate!=null && candidate.isInstanceOf(filter);
            }
            if(include) result.add(ObjectMemory.valueOf(new ReflectionAttribute(env,declaration,attribute)));
        }
        return result.toImmutable();
    }
    @Signature public Memory __serialize(Environment env,Memory...args) {
        env.exception(php.runtime.lang.BaseException.class,"Serialization of 'ReflectionAttribute' is not allowed");return Memory.NULL;
    }
    @Signature public Memory __toString(Environment env,Memory...args) {
        return StringMemory.valueOf("Attribute [ "+attribute.name()+" ]\n");
    }
}
