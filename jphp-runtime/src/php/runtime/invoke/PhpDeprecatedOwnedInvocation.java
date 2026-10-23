package php.runtime.invoke;

import java.util.*;
import php.runtime.*;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.support.Entity;
import php.runtime.reflection.support.PhpAttribute;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.ext.core.classes.PhpDeprecatedAttribute;
import php.runtime.reflection.helper.PhpNativePropertiesShellVerifier;

/** The existing owned initializer/constructor/FIELD protocol, with two scalar diagnostic properties. */
public final class PhpDeprecatedOwnedInvocation {
    private PhpDeprecatedOwnedInvocation() {}
    public record Details(Memory message,Memory since) {}

    public static Details details(Environment env,TraceInfo trace,Entity declaration,PhpAttribute attribute)throws Throwable {
        var session=PhpOwnershipExecution.current(env);var authority=PhpRuntimeOwnership.current(env);
        if(session==null && authority==null)
            throw new IllegalArgumentException("Deprecated owned initialization needs its current execution authority");
        boolean occurrence=false;
        for(var candidate:declaration.getPhpAttributes())if(candidate==attribute)occurrence=true;
        if(!occurrence || PhpDeprecatedInvocation.marker(declaration)!=attribute)
            throw new IllegalArgumentException("Deprecated needs its exact declaration occurrence");
        for(var argument:attribute.arguments()) {
            if(argument.initializer()==null)throw new IllegalArgumentException("Source Deprecated argument lacks its physical initializer");
            if(session!=null)session.requireAttributeArgument(argument);else authority.requireAttributeArgument(argument);
        }
        ClassEntity clazz=env.fetchClass("Deprecated",false,php.runtime.common.PhpLanguageProfile.PHP_8_5);
        if(clazz==null || clazz.getNativeClass()!=PhpDeprecatedAttribute.class || clazz.methodConstruct==null || !clazz.isFinal())
            throw new IllegalArgumentException("Deprecated requires its exact final native declaration provider");
        var constructor=clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class);
        var nativeBody=PhpDeprecatedAttribute.class.getMethod("__construct",Environment.class,Memory[].class);
        if(!nativeBody.equals(clazz.methodConstruct.getNativeMethod()))
            throw new IllegalArgumentException("Deprecated constructor differs from its actual native member");
        byte[] proof=PhpNativePropertiesShellVerifier.require(constructor);
        var message=property(clazz,"message",env);var since=property(clazz,"since",env);
        if(clazz.getProperties().size()!=2 || clazz.methodDestruct!=null)
            throw new IllegalArgumentException("Native Deprecated properties/lifecycle differ from their complete contract");
        var objects=session!=null?session.objects():authority.objects();
        PhpOwnershipObjects.Provider provider;
        try {provider=objects.provider(clazz);}catch(IllegalArgumentException absent) {
            provider=objects.registerPropertiesProvider(clazz,constructor,(entity,environment)->{
                try {
                    if(entity!=clazz || environment!=env || entity.getNativeClass()!=constructor.getDeclaringClass()
                            || property(entity,"message",environment)!=message || property(entity,"since",environment)!=since
                            || entity.getProperties().size()!=2 || entity.methodDestruct!=null
                            || !nativeBody.equals(entity.methodConstruct.getNativeMethod())
                            || !Arrays.equals(proof,PhpNativePropertiesShellVerifier.require(constructor)))
                        throw new IllegalArgumentException("Native Deprecated allocation proof changed");
                }catch(java.io.IOException failure){throw new IllegalArgumentException("Native Deprecated shell proof was lost",failure);}
            });
        }
        var retained=new ArrayList<AutoCloseable>();var raw=new ArrayList<Memory>();Throwable primary=null;
        try {
            String lexical=attribute.arguments().isEmpty()?null:attribute.arguments().get(0).initializer().declarationScope();
            for(var argument:attribute.arguments()) {
                env.pushCall(attribute.trace(),null,null,"{attribute-argument}",lexical,lexical);
                try {
                    if(session!=null)raw.add(session.invokeAttributeArgument(argument,attribute.trace()));
                    else {var result=authority.invokeAttributeArgument(argument);retained.add(result);raw.add(result.raw());}
                }finally{env.popCall();}
            }
            var signature=PhpNamedArgumentTarget.resolve(null,new DynamicMethodInvoker(env,trace,null,clazz.methodConstruct),
                    clazz.methodConstruct.getParameters(raw.size()),raw.size());
            var binder=new PhpArgumentBinder(new Object(),env,trace,signature,"Deprecated::__construct");binder.beginKeyed();
            for(int i=0;i<raw.size();i++) {
                var name=attribute.arguments().get(i).name();
                if(name==null)binder.preparePositional();else binder.prepareNamed(name);binder.send(raw.get(i));
            }
            var arguments=binder.finish().arguments();
            var caller=env.getModuleManager().findModule(trace);
            var context=PhpPropertyWriteContext.enter(env,trace,caller!=null && caller.isStrictTypes());
            try {
                Memory[] passed=InvokeArgumentHelper.makeArguments(env,arguments,clazz.methodConstruct.getParameters(arguments.length),
                        "Deprecated::__construct",null,"Deprecated",trace,false);
                var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                defaults.put(message,PhpOwnershipValues.bindValue(objects.domain(),Memory.UNDEFINED,null));
                defaults.put(since,PhpOwnershipValues.bindValue(objects.domain(),Memory.UNDEFINED,null));
                try(var allocation=objects.allocate(provider,env,defaults)) {
                    BaseObject object=(BaseObject)allocation.object();
                    if(session!=null) {
                        try(var roots=session.newCallRoots()) {
                            roots.stageReceiver(allocation.binding());for(Memory value:passed)roots.send(session.value(value));
                            session.invokeNativePropertyConstructor(clazz.methodConstruct,object,trace,roots);
                        }
                    } else {
                        var call=env.pushCall(trace,object,passed,"__construct",clazz.getName(),clazz.getName());
                        call.classEntity=clazz;call.staticClassEntity=clazz;
                        try {nativeBody.invoke(object,env,passed);}
                        catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        finally {env.popCall();}
                    }
                    return new Details(scalar(object,message),scalar(object,since));
                }
            }finally {PhpPropertyWriteContext.leave(context);}
        }catch(Throwable failure){primary=failure;throw failure;}
        finally {
            Throwable cleanupFailure=primary;
            for(int i=retained.size()-1;i>=0;i--)try {retained.get(i).close();}
            catch(Throwable cleanup){cleanupFailure=objects.domain().graph().composeFailure(cleanupFailure,cleanup);}
            if(cleanupFailure!=primary && cleanupFailure!=null)PhpDeprecatedOwnedInvocation.<RuntimeException>raise(cleanupFailure);
        }
    }

    private static PropertyEntity property(ClassEntity clazz,String name,Environment env) {
        var property=clazz.findProperty(name);
        var type=new PhpTypeDeclaration(List.of(List.of("string"),List.of("null")));
        if(property==null || !property.isPhpReadonly() || property.isStatic() || property.getClazz()!=clazz
                || !property.getDefaultValue(env).isUndefined() || property.getDeclaredType()==null
                || !property.getDeclaredType().equivalentTo(type))
            throw new IllegalArgumentException("Native Deprecated property differs from its nullable-string contract");
        return property;
    }
    private static Memory scalar(BaseObject object,PropertyEntity property) {
        Memory value=object.getProperties().getByScalar(property.getSpecificName()).toValue();
        if(!value.isNull() && !value.isString())
            throw new IllegalArgumentException("Deprecated diagnostic property is not a scalar string/null VALUE");
        return value.toImmutable();
    }

    /** Invoked by the genuine native constructor; every store keeps the actual typed FIELD publisher. */
    public static Memory publishProperties(PhpDeprecatedAttribute object,Environment env,Memory[] arguments)throws Throwable {
        var certificate=Objects.requireNonNull(object.getOwnershipObject());var session=PhpOwnershipExecution.current(env);
        if(session!=null) {
            var member=PhpDeprecatedAttribute.class.getMethod("__construct",Environment.class,Memory[].class);
            var roots=session.requireNativeCallRoots(member,arguments);var receiver=roots.receiver();
            if(receiver==null || !(receiver.rawValue() instanceof ObjectMemory)
                    || ((ObjectMemory)receiver.rawValue()).value!=object)
                throw new IllegalArgumentException("Native Deprecated constructor receiver lost its actual invocation root");
        }else if(PhpRuntimeOwnership.current(env)==null)
            throw new IllegalArgumentException("Owned Deprecated constructor has no actual execution authority");
        if(arguments.length!=2)throw new IllegalArgumentException("Native Deprecated constructor argument vector is incomplete");
        for(int index=0;index<2;index++) {
            String name=index==0?"message":"since";var property=object.getReflection().findProperty(name);
            var carrier=object.getProperties().getByScalar(property.getSpecificName());
            if(carrier!=null && !carrier.isUndefined())env.exception(php.runtime.lang.exception.BaseError.class,
                    "Cannot modify readonly property Deprecated::$%s",name);
            Memory value=arguments[index].toValue().toImmutable();
            if(!value.isNull() && !value.isString())throw new IllegalArgumentException("Bound Deprecated property is not string/null");
            certificate.properties().entry(carrier).payloadPublisher().assign(PhpOwnershipValues.bindValue(certificate.domain(),value,null));
        }
        return Memory.NULL;
    }
    @SuppressWarnings("unchecked")private static <E extends Throwable>void raise(Throwable failure)throws E{throw(E)failure;}
}
