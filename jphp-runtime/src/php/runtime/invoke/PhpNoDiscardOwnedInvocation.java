package php.runtime.invoke;

import java.util.*;
import php.runtime.*;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;
import php.runtime.reflection.support.PhpAttribute;
import php.runtime.ext.core.classes.PhpNoDiscardAttribute;
import php.runtime.reflection.helper.PhpNativePropertiesShellVerifier;

/** Owned NoDiscard initialization uses the actual generated argument return and actual native FIELD publisher. */
public final class PhpNoDiscardOwnedInvocation {
    private PhpNoDiscardOwnedInvocation(){}
    public static Memory message(Environment env,TraceInfo trace,AbstractFunctionEntity declaration,PhpAttribute attribute)throws Throwable {
        var session=PhpOwnershipExecution.current(env);var authority=PhpRuntimeOwnership.current(env);
        if(session==null&&authority==null)throw new IllegalArgumentException("NoDiscard owned initialization needs its current execution authority");
        for(var argument:attribute.arguments()) {
            if(argument.initializer()==null)throw new IllegalArgumentException("Source NoDiscard argument lacks its physical initializer");
            if(session!=null)session.requireAttributeArgument(argument);else authority.requireAttributeArgument(argument);
        }
        ClassEntity clazz=env.fetchClass("NoDiscard",false,php.runtime.common.PhpLanguageProfile.PHP_8_5);
        if(clazz==null||clazz.getNativeClass()!=PhpNoDiscardAttribute.class||clazz.methodConstruct==null)
            throw new IllegalArgumentException("NoDiscard requires its exact native declaration provider");
        var constructor=clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class);
        byte[] proof=PhpNativePropertiesShellVerifier.require(constructor);
        var property=clazz.findProperty("message");
        if(property==null||!property.isPhpReadonly()||property.isStatic()||property.getClazz()!=clazz
                ||!property.getDefaultValue(env).isUndefined()||clazz.methodDestruct!=null)
            throw new IllegalArgumentException("Native NoDiscard properties/lifecycle differ from their complete contract");
        var objects=session!=null?session.objects():authority.objects();
        PhpOwnershipObjects.Provider provider;
        try{provider=objects.provider(clazz);}catch(IllegalArgumentException absent){
            provider=objects.registerPropertiesProvider(clazz,constructor,(entity,environment)->{
                try {
                    if(entity!=clazz||environment!=env||entity.getNativeClass()!=constructor.getDeclaringClass()
                            ||entity.findProperty("message")!=property||entity.methodDestruct!=null
                            ||!Arrays.equals(proof,PhpNativePropertiesShellVerifier.require(constructor)))
                        throw new IllegalArgumentException("Native NoDiscard allocation proof changed");
                }catch(java.io.IOException failure){throw new IllegalArgumentException("Native NoDiscard shell proof was lost",failure);}
            });
        }
        // Retain each fresh initializer result until constructor binding and body are finished.
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
            var signature=PhpNamedArgumentTarget.resolve(null,new DynamicMethodInvoker(env,trace,null,clazz.methodConstruct),clazz.methodConstruct.getParameters(raw.size()),raw.size());
            var binder=new PhpArgumentBinder(new Object(),env,trace,signature,"NoDiscard::__construct");binder.beginKeyed();
            for(int i=0;i<raw.size();i++){var name=attribute.arguments().get(i).name();if(name==null)binder.preparePositional();else binder.prepareNamed(name);binder.send(raw.get(i));}
            var arguments=binder.finish().arguments();
            var propertyContext=PhpPropertyWriteContext.enter(env,trace,env.getModuleManager().findModule(trace)!=null&&env.getModuleManager().findModule(trace).isStrictTypes());
            try {
                Memory[] passed=InvokeArgumentHelper.makeArguments(env,arguments,clazz.methodConstruct.getParameters(arguments.length),"NoDiscard::__construct",null,"NoDiscard",trace,false);
                var defaults=new IdentityHashMap<php.runtime.reflection.PropertyEntity,PhpOwnershipValues.Binding>();
                defaults.put(property,PhpOwnershipValues.bindValue(objects.domain(),Memory.UNDEFINED,null));
                try(var allocation=objects.allocate(provider,env,defaults)) {
                    BaseObject object=(BaseObject)allocation.object();
                    if(session!=null) {
                        try(var roots=session.newCallRoots()) {
                            roots.stageReceiver(allocation.binding());for(Memory value:passed)roots.send(session.value(value));
                            session.invokeNativePropertyConstructor(clazz.methodConstruct,object,trace,roots);
                        }
                    }else {
                        env.pushCall(trace,object,passed,"__construct",clazz.getName(),clazz.getName());
                        try {PhpNoDiscardAttribute.class.getMethod("__construct",Environment.class,Memory[].class).invoke(object,env,passed);}
                        catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        finally{env.popCall();}
                    }
                    return object.getProperties().getByScalar(property.getSpecificName()).toValue().toImmutable();
                }
            }finally{PhpPropertyWriteContext.leave(propertyContext);}
        }catch(Throwable failure){primary=failure;throw failure;}
        finally {
            Throwable cleanupFailure=primary;
            for(int i=retained.size()-1;i>=0;i--)try{retained.get(i).close();}catch(Throwable cleanup){cleanupFailure=objects.domain().graph().composeFailure(cleanupFailure,cleanup);}
            if(cleanupFailure!=primary&&cleanupFailure!=null)PhpNoDiscardOwnedInvocation.<RuntimeException>raise(cleanupFailure);
        }
    }
    /** Reached from the genuine native constructor. The guard and complete FIELD protocol stay intact. */
    public static Memory publishMessage(PhpNoDiscardAttribute object,Environment env,Memory[] arguments)throws Throwable {
        var certificate=Objects.requireNonNull(object.getOwnershipObject());var session=PhpOwnershipExecution.current(env);
        if(session!=null) {
            var member=PhpNoDiscardAttribute.class.getMethod("__construct",Environment.class,Memory[].class);
            var roots=session.requireNativeCallRoots(member,arguments);
            var receiver=roots.receiver();
            if(receiver==null||!(receiver.rawValue() instanceof ObjectMemory)||((ObjectMemory)receiver.rawValue()).value!=object)
                throw new IllegalArgumentException("Native NoDiscard constructor receiver lost its actual invocation root");
        }else if(PhpRuntimeOwnership.current(env)==null)throw new IllegalArgumentException("Owned NoDiscard constructor has no actual execution authority");
        Memory message=arguments[0].toValue().toImmutable();
        var property=object.getReflection().findProperty("message");var carrier=object.getProperties().getByScalar(property.getSpecificName());
        certificate.properties().entry(carrier).payloadPublisher().assign(PhpOwnershipValues.bindValue(certificate.domain(),message,null));
        return Memory.NULL;
    }
    @SuppressWarnings("unchecked")private static <E extends Throwable>void raise(Throwable failure)throws E{throw(E)failure;}
}
