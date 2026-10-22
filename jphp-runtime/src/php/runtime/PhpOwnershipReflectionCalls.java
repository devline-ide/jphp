package php.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.core.reflection.ReflectionClass;
import php.runtime.ext.core.reflection.ReflectionObject;
import php.runtime.ext.core.reflection.ReflectionProperty;
import php.runtime.lang.IObject;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.helper.PhpReflectionShellVerifier;

/** Typed instance-native packets. No CompileFunction capability, generated FramePlan or raw heap adoption. */
public final class PhpOwnershipReflectionCalls {
    private PhpOwnershipReflectionCalls() {}
    private static final Set<String> CLASS_METHODS=Set.of("__construct","getName","newLazyGhost","newLazyProxy","resetAsLazyGhost","resetAsLazyProxy",
            "initializeLazyObject","isUninitializedLazyObject","markLazyObjectAsInitialized","getLazyInitializer");
    private static final Set<String> PROPERTY_METHODS=Set.of("__construct","getName","getRawValue","setRawValue","setRawValueWithoutLazyInitialization","skipLazyInitialization","isLazy");
    private static final ThreadLocal<Packet> CURRENT=new ThreadLocal<>();
    public static PhpOwnershipObjects.Provider registerShell(PhpOwnershipObjects objects,Environment env,php.runtime.reflection.ClassEntity entity) {
        var proof=PhpReflectionShellVerifier.require(entity);
        return objects.registerPropertiesProvider(entity,proof.constructor(),(actual,environment)->{
            if(actual!=entity||environment!=env||objects.domain()!=PhpOwnershipExecution.current(env).domain()
                    ||PhpReflectionShellVerifier.require(actual)!=proof)
                throw new IllegalArgumentException("Reflection shell registration lost its actual class/Environment protocol");
            var names=new java.util.HashSet<String>();
            for(var property:actual.getProperties()) {
                if(property.isStatic()||!property.isPublic()||property.getGetter()!=null||!property.getSpecificName().equals(property.getName()))
                    throw new IllegalArgumentException("Reflection metadata table has an unsupported physical declaration");
                names.add(property.getName());
            }
            var expected=entity.getNativeClass()==ReflectionProperty.class?Set.of("name","class"):Set.of("name");
            if(!names.equals(expected))throw new IllegalArgumentException("Reflection metadata table is incomplete");
        });
    }
    /** Constructor metadata is stored through its actual declared property publisher. */
    public static void publishMetadata(BaseObject receiver,String key,Memory value) {
        try {
            Environment env=receiver.getEnvironment();Packet packet=CURRENT.get();
            if(packet==null||packet.receiver!=receiver||!packet.member.getName().equals("__construct"))
                throw new IllegalArgumentException("Reflection metadata write lacks its actual constructor packet");
            packet.open();var certificate=receiver.getOwnershipObject();
            if(certificate==null||certificate.domain()!=packet.session.domain())throw new IllegalArgumentException("Reflection metadata receiver has no same-domain allocation");
            if(!key.equals("name")&&!(receiver.getClass()==ReflectionProperty.class&&key.equals("class")))
                throw new IllegalArgumentException("Unknown Reflection metadata field");
            var table=receiver.getOwnershipProperties();var carrier=table.getByScalar(key);
            if(carrier==null||table.declaration(key)==null)throw new IllegalArgumentException("Reflection metadata lost its declared physical slot");
            certificate.properties().entry(carrier).payloadPublisher().assign(packet.session.value(value));
        } catch(Throwable failure) {PhpOwnershipReflectionCalls.<RuntimeException>raise(failure);}
    }
    public static boolean supports(MethodEntity entity) {
        Method member=entity==null?null:entity.getNativeMethod();
        if(member==null)return false;Class<?> owner=member.getDeclaringClass();
        return owner==ReflectionClass.class&&CLASS_METHODS.contains(member.getName())
                ||owner==ReflectionObject.class&&member.getName().equals("__construct")
                ||owner==ReflectionProperty.class&&PROPERTY_METHODS.contains(member.getName());
    }
    public static Method require(MethodEntity entity) {
        if(!supports(entity)||entity.isStatic()||!entity.isPublic()||entity.getClazz()==null||!entity.getClazz().isInternal())
            throw new IllegalArgumentException("Native Reflection member has no complete typed instance protocol");
        Method member=entity.getNativeMethod();
        if(!Modifier.isPublic(member.getModifiers())||Modifier.isStatic(member.getModifiers())||member.getReturnType()!=Memory.class
                ||!java.util.Arrays.equals(member.getParameterTypes(),new Class<?>[]{Environment.class,Memory[].class})
                ||entity.getClazz().getNativeClass()!=member.getDeclaringClass())
            throw new IllegalArgumentException("Reflection MethodEntity differs from its actual native member");
        if(entity.getClazz().findMethod(entity.getLowerName())!=entity)
            throw new IllegalArgumentException("Reflection native member is not its actual registered MethodEntity");
        PhpReflectionShellVerifier.require(entity.getClazz());return member;
    }
    static Packet prepare(PhpOwnershipExecution.Session session,MethodEntity entity,IObject receiver,TraceInfo trace,PhpOwnershipExecution.CallRoots roots) {
        Method member=require(entity);
        if(session!=PhpOwnershipExecution.current(session.environment())||roots.session()!=session)
            throw new IllegalArgumentException("Foreign Reflection invocation packet");
        var receiverBinding=roots.receiver();
        if(receiverBinding==null||receiverBinding.role()!=PhpOwnershipValues.Role.VALUE||!(receiverBinding.rawValue()instanceof ObjectMemory raw)||raw.value!=receiver)
            throw new IllegalArgumentException("Reflection invocation needs its actual certified receiver root");
        receiverBinding.requireCertifiedObject();PhpReflectionShellVerifier.require(receiver.getClass());
        if(receiver.getReflection().findMethod(entity.getLowerName())!=entity)
            throw new IllegalArgumentException("Reflection receiver lookup differs from its actual registered member");
        if(!member.getDeclaringClass().isAssignableFrom(receiver.getClass())||receiverBinding.domain()!=session.domain())
            throw new IllegalArgumentException("Reflection receiver is not the actual typed allocation");
        return new Packet(session,entity,member,receiver,trace,roots);
    }
    public static Packet current(Environment env,IObject receiver,String method,Memory[] preparedArguments) {
        Packet packet=CURRENT.get();
        if(packet==null||packet.closed||packet.session.environment()!=env||packet.receiver!=receiver||!packet.member.getName().equals(method)||packet.prepared!=preparedArguments
                ||PhpOwnershipExecution.current(env)!=packet.session)
            throw new IllegalArgumentException("Lazy Reflection body lacks its exact active native packet");
        return packet;
    }
    public static final class Packet implements AutoCloseable {
        private final PhpOwnershipExecution.Session session;
        private final MethodEntity entity;
        private final Method member;
        private final IObject receiver;
        private final TraceInfo trace;
        private final PhpOwnershipExecution.CallRoots roots;
        private Packet previous;
        private Memory[] prepared;
        private List<PhpOwnershipValues.Binding> arguments;
        private boolean entered,closed;
        private Packet(PhpOwnershipExecution.Session session,MethodEntity entity,Method member,IObject receiver,TraceInfo trace,PhpOwnershipExecution.CallRoots roots) {
            this.session=session;this.entity=entity;this.member=member;this.receiver=receiver;this.trace=trace;this.roots=roots;
        }
        /** Called inside the real Ticket, after checked argument preparation; original SEND roots remain live. */
        void enter(Memory[] original,Memory[] prepared)throws Throwable {
            if(entered||closed)throw new IllegalStateException("Reflection packet reused");
            if(session.requireNativeCallRoots(member,original)!=roots)throw new IllegalArgumentException("Reflection packet lost its exact Ticket");
            var bindings=new ArrayList<PhpOwnershipValues.Binding>();
            for(int i=0;i<prepared.length;i++) {
                Memory raw=prepared[i];PhpOwnershipValues.Binding binding;
                if(raw instanceof ObjectMemory||raw instanceof ArrayMemory||raw instanceof ReferenceMemory) {
                    if(i>=roots.size())throw new IllegalArgumentException("Omitted heap defaults require their actual producer");
                    binding=roots.argument(i);
                    if(binding.role()!=PhpOwnershipValues.Role.VALUE||raw instanceof ReferenceMemory)
                        throw new IllegalArgumentException("Reflection by-value parameter lacks its actual semantic VALUE SEND");
                    binding=binding.view(raw).requireCertifiedObject();
                } else binding=session.value(raw);
                bindings.add(binding);
            }
            this.prepared=prepared;arguments=List.copyOf(bindings);previous=CURRENT.get();CURRENT.set(this);entered=true;
        }
        public PhpOwnershipExecution.Session session(){open();return session;}
        public TraceInfo trace(){open();return trace;}
        public PhpOwnershipValues.Binding argument(int index,Memory actualPrepared) {
            open();if(index<0||index>=prepared.length||prepared[index]!=actualPrepared)throw new IllegalArgumentException("Wrong prepared Reflection argument");
            return arguments.get(index);
        }
        public PhpOwnershipValues.Binding receiver(){open();return roots.receiver();}
        public void result(PhpOwnershipValues.Binding actualPublished)throws Throwable {open();roots.stageResult(actualPublished);}
        private void open() {
            if(closed||!entered||CURRENT.get()!=this||session!=PhpOwnershipExecution.current(session.environment()))
                throw new IllegalStateException("Reflection packet is not active in its actual Session");
        }
        Memory complete(Memory raw)throws Throwable {
            open();if(raw==null)raw=Memory.NULL;
            var pending=roots.result();
            if(raw instanceof ObjectMemory||raw instanceof ArrayMemory||raw instanceof ReferenceMemory) {
                if(pending==null)throw new IllegalArgumentException("Reflection heap result lacks its actual producer/holder publication");
                var actual=pending.view(raw).requireCertifiedObject();
                if(actual.rawValue()!=raw)throw new IllegalArgumentException("Reflection result changed after publication");
            } else roots.stageResult(session.value(raw));
            return raw;
        }
        @Override public void close() {
            if(closed)return;
            if(entered) {
                if(CURRENT.get()!=this)throw new IllegalStateException("Unbalanced Reflection invocation context");
                if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            }
            arguments=null;prepared=null;previous=null;closed=true;
        }
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable>void raise(Throwable failure)throws T {throw(T)failure;}
}
