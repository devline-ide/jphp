package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.Invoker;
import php.runtime.lang.BaseObject;
import php.runtime.lang.IObject;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import java.util.*;

/** Lazy-only state. Managed creation/query has actual holders; managed initialization/reset remains incomplete. */
public final class PhpLazyObjects {
    private PhpLazyObjects() {}
    public static final int SKIP_INITIALIZATION_ON_SERIALIZE = 8;
    public static final int SKIP_DESTRUCTOR = 16;
    public enum Strategy { GHOST, PROXY }
    public enum Phase { PREPARING, LAZY, INITIALIZING, INITIALIZED, RETIRED }
    public enum Observation { PROPERTIES, SERIALIZE, RAW }

    /** Filter only these new native declarations while wrapping the existing legacy class. */
    public static boolean modernReflectionMember(Class<?> type,String name) {
        if(type==php.runtime.ext.core.reflection.ReflectionClass.class)return switch(name) {
            case "newLazyGhost","newLazyProxy","resetAsLazyGhost","resetAsLazyProxy","initializeLazyObject",
                    "isUninitializedLazyObject","markLazyObjectAsInitialized","getLazyInitializer" -> true;
            default -> false;
        };
        return type==php.runtime.ext.core.reflection.ReflectionProperty.class
                &&(name.equals("skipLazyInitialization")||name.equals("setRawValueWithoutLazyInitialization")||name.equals("isLazy"));
    }
    public static boolean modernReflectionSignature(Class<?> type,String name) {
        return modernReflectionMember(type,name)||type==php.runtime.ext.core.reflection.ReflectionProperty.class
                &&(name.equals("getRawValue")||name.equals("setRawValue"));
    }
    public static State state(IObject object) {
        if (!(object instanceof BaseObject base) || !(base.peekProperties() instanceof PhpObjectProperties table)) return null;
        return table.lazyState();
    }
    private static void ordinary(Environment env,IObject object) {
        if (PhpOwnershipExecution.current(env) != null || object instanceof BaseObject base && base.getOwnershipObject()!=null)
            throw new IllegalArgumentException("Lazy native Reflection requires its actual managed method packet and result publisher");
    }
    private static BaseObject actual(Environment env,IObject object) {
        ordinary(env,object);
        if (!(object instanceof BaseObject base)) throw new IllegalArgumentException("Lazy receiver requires actual BaseObject storage");
        return base;
    }
    private static void error(Environment env,String text) {env.exception(env.trace(),php.runtime.lang.exception.BaseError.class,text);}
    private static void typeError(Environment env,String text) {env.exception(env.trace(),php.runtime.lang.exception.BaseTypeError.class,text);}
    private static void reflectionError(Environment env,String text) {env.exception(env.trace(),php.runtime.ext.core.reflection.ReflectionException.class,text);}

    public static void options(Environment env,int flags,boolean reset,String method) {
        if ((flags & ~(SKIP_INITIALIZATION_ON_SERIALIZE | SKIP_DESTRUCTOR))!=0)
            reflectionError(env,"ReflectionClass::"+method+"(): Argument #"+(reset?3:2)+" ($options) contains invalid flags");
        if (!reset && (flags & SKIP_DESTRUCTOR)!=0)
            reflectionError(env,"ReflectionClass::"+method+"(): Argument #2 ($options) does not accept ReflectionClass::SKIP_DESTRUCTOR");
    }
    public static IObject requireReceiver(Environment env,ClassEntity reflected,Memory value,String method) {
        if (!value.isObject() || !value.toValue(ObjectMemory.class).getReflection().isInstanceOf(reflected)) {
            typeError(env,"ReflectionClass::"+method+"(): Argument #1 ($object) must be of type "+reflected.getName()+", "+
                    (value.isObject()?value.toValue(ObjectMemory.class).getReflection().getName():value.getRealType().toString())+" given");
        }
        return value.toValue(ObjectMemory.class).value;
    }
    public static List<PropertyEntity> stored(ClassEntity entity) {
        Deque<ClassEntity> lineage=new ArrayDeque<>();
        for(ClassEntity owner=entity;owner!=null;owner=owner.getParent())lineage.push(owner);
        List<PropertyEntity> result=new ArrayList<>();Set<String> keys=new HashSet<>();
        while(!lineage.isEmpty()) {
            ClassEntity owner=lineage.pop();
            for(PropertyEntity declared:owner.getProperties()) {
                if(declared.getClazz()!=owner || declared.isStatic() || declared.getGetter()!=null
                        || declared.getPhpHooks()!=null&&declared.getPhpHooks().isVirtual())continue;
                PropertyEntity property=declared.isPrivate()?declared:entity.findProperty(declared.getName());
                if(property!=null&&keys.add(property.getSpecificName()))result.add(property);
            }
        }
        return result;
    }
    public static IObject create(Environment env,ClassEntity entity,Memory initializer,Strategy strategy,int flags) throws Throwable {
        ordinary(env,null);
        String method=strategy==Strategy.GHOST?"newLazyGhost":"newLazyProxy";options(env,flags,false,method);
        checkClass(env,entity);
        IObject object=entity.newLazyObjectShell(env);
        attach(env,actual(env,object),initializer,strategy,flags);
        return object;
    }
    private static void checkClass(Environment env,ClassEntity entity) {
        entity.checkCanInstance(env,env.trace());
        for(ClassEntity type=entity;type!=null;type=type.getParent())
            if(type.isInternal()&&!type.getName().equals("stdClass"))error(env,"Cannot make instance of internal class lazy: "+type.getName()+" is internal");
    }
    private static void attach(Environment env,BaseObject object,Memory initializer,Strategy strategy,int flags) throws Throwable {
        if(Invoker.valueOf(env,env.trace(),initializer)==null)typeError(env,"Lazy initializer must be a valid callable");
        var properties=stored(object.getReflection());
        if(properties.isEmpty())return;
        State state=new State(object,env,strategy,flags,properties,initializer);
        state.table.installLazyState(state);
        state.rawClear=true;
        try {
            for(PropertyEntity property:properties) {
                state.table.declare(property);
                state.table.putAsKeyString(property.getSpecificName(),Memory.UNDEFINED)
                        .attachInstancePropertySource(env,property,object,null);
            }
            state.phase=Phase.LAZY;
        } finally {state.rawClear=false;}
    }
    public static void reset(Environment env,IObject object,Memory initializer,Strategy strategy,int flags) throws Throwable {
        String method=strategy==Strategy.GHOST?"resetAsLazyGhost":"resetAsLazyProxy";options(env,flags,true,method);
        if(PhpOwnershipExecution.current(env)!=null||object instanceof BaseObject owned&&owned.getOwnershipObject()!=null)
            throw new IllegalArgumentException("Managed lazy reset requires its certified transactional property replacement");
        BaseObject base=actual(env,object);checkClass(env,object.getReflection());
        State old=state(object);
        if(old!=null&&old.phase==Phase.INITIALIZING)error(env,"Cannot reset an object while it is being initialized");
        if(Invoker.valueOf(env,env.trace(),initializer)==null)typeError(env,"Lazy initializer must be a valid callable");
        if((flags&SKIP_DESTRUCTOR)==0 && (old==null||old.phase==Phase.INITIALIZED&&old.strategy==Strategy.GHOST)
                &&object.getReflection().methodDestruct!=null)
            php.runtime.invoke.ObjectInvokeHelper.invokeMethod(object,object.getReflection().methodDestruct,env,env.trace(),new Memory[0],false);
        if(old!=null)old.retire();
        clear(base.getOwnershipProperties(),null);
        attach(env,base,initializer,strategy,flags);
    }
    public static void resetManaged(Environment env,IObject object,Memory actualObject,Memory initializer,Strategy strategy,int flags,
                                    PhpOwnershipReflectionCalls.Packet packet)throws Throwable {
        var session=packet.session();var shell=packet.argument(0,actualObject);var callback=packet.argument(1,initializer);
        String method=strategy==Strategy.GHOST?"resetAsLazyGhost":"resetAsLazyProxy";options(env,flags,true,method);checkClass(env,object.getReflection());
        if(!(object instanceof BaseObject base)||shell.domain()!=session.domain()||!(shell.rawValue()instanceof ObjectMemory raw)||raw.value!=object)
            throw new IllegalArgumentException("Lazy reset needs its actual native OBJECT SEND");
        State prior=state(object);
        if(prior!=null&&prior.phase==Phase.INITIALIZING)error(env,"Cannot reset an object while it is being initialized");
        if(Invoker.valueOf(env,packet.trace(),initializer)==null)typeError(env,"Lazy initializer must be a valid callable");
        if((flags&SKIP_DESTRUCTOR)==0&&(prior==null||prior.phase==Phase.INITIALIZED&&prior.strategy==Strategy.GHOST)
                &&object.getReflection().methodDestruct!=null) {
            try(var destructor=session.newCallRoots()) {
                destructor.stageReceiver(shell);
                session.invokeCheckedMethod(object.getReflection().methodDestruct,object,packet.trace(),destructor,false);
            }
        }
        State old=state(object);
        if(old!=null)old.actual();
        var properties=stored(object.getReflection());State fresh=new State(base,env,strategy,flags,properties,Memory.NULL);
        fresh.managed=new Managed(fresh,shell);
        try(var transaction=new PhpLazyPropertyTransaction(shell)) {
            var rows=new ArrayList<PhpLazyPropertyTransaction.Row>();
            for(var property:properties)rows.add(new PhpLazyPropertyTransaction.Row(property.getSpecificName(),session.value(Memory.UNDEFINED),property,false));
            session.domain().graph().deferDestruction(()->{
                transaction.replace(rows);
                if(old!=null){old.phase=Phase.RETIRED;old.table.detachLazyState(old);}
                if(!properties.isEmpty()) {
                    fresh.table.installLazyState(fresh);fresh.managed.publishInitializer(callback);fresh.phase=Phase.LAZY;
                } else fresh.phase=Phase.RETIRED;
                if(old!=null)old.managed.retireUnlinked();
                if(properties.isEmpty())fresh.managed.retireUnlinked();
            });
        } catch(Throwable failure) {
            if(fresh.table.lazyState()!=fresh&&!fresh.managed.destroyed) {
                fresh.phase=Phase.RETIRED;
                try{fresh.managed.retireUnlinked();}catch(Throwable cleanup){failure=session.domain().graph().composeFailure(failure,cleanup);}
            }
            throw failure;
        }
    }
    /** Actual native SEND -> real allocation -> hidden holder; initialization remains a separate managed phase. */
    public static IObject createManaged(Environment env,ClassEntity entity,Memory initializer,Strategy strategy,int flags,
                                        PhpOwnershipReflectionCalls.Packet packet) throws Throwable {
        var session=packet.session();var entry=PhpOwnershipExecution.currentEntry(env);
        if(session.environment()!=env||entry==null||entry.session()!=session)
            throw new IllegalArgumentException("Lazy producer has no current native invocation Entry");
        String method=strategy==Strategy.GHOST?"newLazyGhost":"newLazyProxy";options(env,flags,false,method);checkClass(env,entity);
        var callback=packet.argument(0,initializer);
        if(Invoker.valueOf(env,packet.trace(),initializer)==null)typeError(env,"Lazy initializer must be a valid callable");
        var allocation=entity.allocateLazyOwnershipShell(entry,packet.trace());
        try {
            var binding=allocation.binding();var object=(BaseObject)allocation.object();
            entry.stageAllocation(allocation,allocation.restoration());allocation=null;
            var properties=stored(entity);
            if(!properties.isEmpty()) {
                State state=new State(object,env,strategy,flags,properties,Memory.NULL);
                state.managed=new Managed(state,binding);
                state.table.installLazyState(state);
                state.managed.publishInitializer(callback);
                state.phase=Phase.LAZY;
            }
            packet.result(binding);return object;
        } finally {if(allocation!=null)allocation.close();}
    }
    /** Heap return comes from its existing hidden edge, never from a raw Session.value lookup. */
    public static Memory managedObjectResult(PhpOwnershipReflectionCalls.Packet packet,IObject object,Memory actualArgument,Memory returned)throws Throwable {
        State state=state(object);var original=packet.argument(0,actualArgument);
        if(!(returned instanceof ObjectMemory raw))throw new IllegalArgumentException("Lazy object API did not return its actual OBJECT");
        var binding=raw.value==object?original:state!=null&&state.managed!=null&&state.phase==Phase.INITIALIZED&&state.strategy==Strategy.PROXY&&state.target==raw.value
                ?state.managed.targetBinding:null;
        if(binding==null)throw new IllegalArgumentException("Lazy object result differs from its actual argument/target holder");
        packet.result(binding.view(returned));return returned;
    }
    public static Memory managedInitializerResult(PhpOwnershipReflectionCalls.Packet packet,IObject object)throws Throwable {
        State state=state(object);
        if(state==null||state.phase!=Phase.LAZY)return Memory.NULL;
        state.actual();if(state.managed==null)throw new IllegalArgumentException("Lazy object lacks its actual managed hidden holders");
        var binding=state.managed.initializerBinding;
        if(binding==null)throw new IllegalStateException("Lazy initializer holder is empty");
        packet.result(binding);return binding.rawValue();
    }
    /** Called by the actual object certificate; child checks are identity-only to permit real cycles. */
    public static void validateOwnership(BaseObject object,PhpOwnershipObjects.ObjectCertificate certificate,PhpOwnership.Node node) {
        State state=state(object);if(state==null)return;
        if(state.managed==null)throw new IllegalStateException("Certified lazy object lacks its hidden-edge descriptor");
        state.managed.validate(certificate,node);
    }
    /** The graph already marked DISPOSED. No validation, publication, detachment or PHP callback is allowed here. */
    public static void destroyOwnership(BaseObject object,PhpOwnershipObjects.ObjectCertificate certificate,PhpOwnership.Node node) {
        State state=state(object);if(state==null)return;
        if(state.managed==null||state.owner!=object||state.owner.peekProperties()!=state.table||state.table.lazyState()!=state
                ||state.managed.certificate!=certificate||state.managed.ownerNode!=node||node.state()!=PhpOwnership.State.DISPOSED)
            throw new IllegalStateException("Final lazy cleanup lost its exact object/State identity");
        state.phase=Phase.RETIRED;state.initializer=Memory.NULL;state.target=null;
        state.managed.initializerBinding=null;state.managed.targetBinding=null;
        state.managed.initializerNode=null;state.managed.targetNode=null;
        state.managed.initializerRaw=Memory.NULL;state.managed.targetRaw=null;state.managed.destroyed=true;
        // Graph.cleanup now releases the actual outgoing edges, including self/cross cycles.
    }
    /** Lazy-only storage: two physical strong child edges, never independent root owners. */
    private static final class Managed {
        private final State state;
        private final PhpOwnershipObjects.ObjectCertificate certificate;
        private final PhpOwnership.Node ownerNode;
        private final PhpOwnershipValues.Binding ownerBinding;
        private final PhpOwnership.Slot initializerEdge,targetEdge;
        private PhpOwnershipValues.Binding initializerBinding,targetBinding;
        private PhpOwnership.Node initializerNode,targetNode;
        private Memory initializerRaw=Memory.NULL;
        private IObject targetRaw;
        private boolean destroyed;
        private Managed(State state,PhpOwnershipValues.Binding shell) {
            shell.requireCertifiedObject();
            if(shell.role()!=PhpOwnershipValues.Role.VALUE||!(shell.rawValue()instanceof ObjectMemory raw)||raw.value!=state.owner)
                throw new IllegalArgumentException("Hidden lazy holders require the actual shell allocation binding");
            this.state=state;certificate=state.owner.getOwnershipObject();ownerNode=shell.node();ownerBinding=shell;
            if(certificate==null||certificate.domain()!=shell.domain()||certificate.node()!=ownerNode)
                throw new IllegalArgumentException("Hidden holders differ from their shell certificate");
            initializerEdge=shell.domain().graph().edge(ownerNode);targetEdge=shell.domain().graph().edge(ownerNode);
        }
        private void validate(PhpOwnershipObjects.ObjectCertificate actual,PhpOwnership.Node node) {
            if(destroyed||certificate!=actual||ownerNode!=node||state.owner.getOwnershipObject()!=certificate
                    ||state.owner.peekProperties()!=state.table||state.table.lazyState()!=state||state.phase==Phase.RETIRED
                    ||initializerEdge.owner()!=node||targetEdge.owner()!=node||initializerEdge.isClosed()||targetEdge.isClosed()
                    ||initializerEdge.target()!=initializerNode||targetEdge.target()!=targetNode
                    ||state.initializer!=initializerRaw||state.target!=targetRaw)
                // Never call a child Binding or certificate here: initializer/target may lead back to owner.
                throw new IllegalStateException("Lazy hidden fields no longer match their actual strong edges");
        }
        private void publishTarget(PhpOwnershipValues.Binding source)throws Throwable {
            if(source.domain()!=certificate.domain()||source.role()!=PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Foreign lazy factory RESULT");
            source.requireCertifiedObject();Memory raw=source.rawValue();
            if(!raw.isNull()&&!(raw instanceof ObjectMemory))throw new IllegalArgumentException("Proxy target needs the actual OBJECT RESULT");
            var node=source.node();var object=raw instanceof ObjectMemory value?value.value:null;validate(certificate,ownerNode);
            try(var publication=targetEdge.preparePublication(node)) {
                state.target=object;targetRaw=object;targetBinding=raw.isNull()?null:source;targetNode=node;publication.commit();
            }
        }
        private void retireUnlinked()throws Throwable {
            // The new metadata/physical holders must already be published under deferDestruction.
            var graph=certificate.domain().graph();graph.checkMutationAccess();
            if(state.table.lazyState()==state||state.phase!=Phase.RETIRED)throw new IllegalStateException("Lazy holder is still attached");
            state.initializer=Memory.NULL;state.target=null;initializerRaw=Memory.NULL;targetRaw=null;
            initializerBinding=null;targetBinding=null;initializerNode=null;targetNode=null;destroyed=true;
            try(var publication=initializerEdge.prepareRetirement()){publication.commit();}
            try(var publication=targetEdge.prepareRetirement()){publication.commit();}
        }
        private void stageCallableReceiver(php.runtime.invoke.Invoker invoker,PhpOwnershipValues.Binding callback,PhpOwnershipExecution.CallRoots roots)throws Throwable {
            if(invoker.getClass()!=php.runtime.invoke.DynamicMethodInvoker.class)return;
            var actual=((php.runtime.invoke.DynamicMethodInvoker)invoker).getObject();PhpOwnershipValues.Binding receiver;
            if(callback.rawValue() instanceof ObjectMemory value&&value.value==actual)receiver=callback;
            else if(callback.rawValue()instanceof ArrayMemory)receiver=roots.session().arrays().lazyCallableReceiver(callback,LongMemory.valueOf(0));
            else throw new IllegalArgumentException("Callable receiver lacks its actual initializer holder provenance");
            if(!(receiver.rawValue()instanceof ObjectMemory raw)||raw.value!=actual)
                throw new IllegalArgumentException("Resolved callable receiver differs from its physical VALUE");
            roots.stageReceiver(receiver);
        }
        private void publishInitializer(PhpOwnershipValues.Binding source)throws Throwable {
            // Source is the current typed packet's already certified initializer SEND.
            if(source.domain()!=certificate.domain()||source.role()!=PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Foreign initializer SEND");
            source.requireCertifiedObject();Memory raw=source.rawValue();
            if(raw instanceof ArrayMemory array) {
                if(array.getClass()!=ArrayMemory.class)throw new IllegalArgumentException("Initializer ARRAY lacks an ordinary VALUE snapshot protocol");
                raw=array.toImmutable();source=source.view(raw);
            }
            var node=source.node();validate(certificate,ownerNode);
            try(var publication=initializerEdge.preparePublication(node)) {
                state.initializer=raw;initializerRaw=raw;initializerBinding=source;initializerNode=node;publication.commit();
            }
        }
    }
    /** Methods/hooks keep the shell receiver. Only the eventual backing operation is redirected. */
    public static IObject backing(Environment env,IObject object,String storageKey) throws Throwable {
        State state=state(object);
        if(state==null)return object;
        state.actual();
        if(state.phase==Phase.LAZY && !state.skipped.contains(storageKey))state.initialize();
        return state.phase==Phase.INITIALIZED&&state.strategy==Strategy.PROXY?state.target:object;
    }
    public static IObject observe(Environment env,IObject object,Observation observation) throws Throwable {
        State state=state(object);if(state==null)return object;state.actual();
        if(observation!=Observation.RAW && !(observation==Observation.SERIALIZE&&(state.options&SKIP_INITIALIZATION_ON_SERIALIZE)!=0))
            state.initialize();
        return state.phase==Phase.INITIALIZED&&state.strategy==Strategy.PROXY?state.target:object;
    }
    /** Non-throwing signature adapters preserve the exact throwable with no conversion. */
    public static IObject backingUnchecked(Environment env,IObject object,String key) {
        try{return backing(env,object,key);}catch(Throwable failure){return PhpLazyObjects.<RuntimeException,IObject>raise(failure);}
    }
    public static IObject observeUnchecked(Environment env,IObject object,Observation observation) {
        try{return observe(env,object,observation);}catch(Throwable failure){return PhpLazyObjects.<RuntimeException,IObject>raise(failure);}
    }
    public static Memory initialize(Environment env,IObject object) throws Throwable {
        State state=state(object);return ObjectMemory.valueOf(state==null?object:state.initialize());
    }
    public static Memory mark(Environment env,IObject object) throws Throwable {
        State state=state(object);if(state!=null&&state.phase==Phase.LAZY)state.mark();return ObjectMemory.valueOf(object);
    }
    public static boolean uninitialized(IObject object) {State state=state(object);return state!=null&&state.phase==Phase.LAZY;}
    public static Memory initializer(IObject object) {State state=state(object);return state!=null&&state.phase==Phase.LAZY?state.initializer:Memory.NULL;}
    public static boolean isLazy(IObject object,PropertyEntity property) {
        State state=state(object);return state!=null&&state.phase==Phase.LAZY&&state.lazyKeys.contains(property.getSpecificName());
    }
    private static PhpOwnershipArrays.Entry managedProperty(Environment env,IObject object,PropertyEntity property) {
        if(!(object instanceof BaseObject base)||base.getOwnershipObject()==null)
            throw new IllegalArgumentException("Managed raw property needs its actual certified backing object");
        var session=PhpOwnershipExecution.current(env);var certificate=base.getOwnershipObject();
        if(session==null||certificate.domain()!=session.domain())throw new IllegalArgumentException("Foreign raw property backing");
        var table=base.getOwnershipProperties();var carrier=table.getByScalar(property.getSpecificName());
        if(carrier==null)throw new IllegalArgumentException("Actual managed property anchor is absent");
        return certificate.properties().entry(carrier).payloadPublisher();
    }
    public static Memory managedRawValue(Environment env,IObject object,PropertyEntity property,PhpOwnershipReflectionCalls.Packet packet)throws Throwable {
        if(property.isStatic())throw new IllegalArgumentException("Managed static raw property requires its actual metadata publisher");
        if(property.getPhpHooks()!=null&&property.getPhpHooks().isVirtual())
            PhpPropertyHookAccess.error(env,packet.trace(),"Must not read from virtual property %s::$%s",property);
        var backing=backing(env,object,property.getSpecificName());var publisher=managedProperty(env,backing,property);
        var source=publisher.current().binding();var table=((BaseObject)backing).getOwnershipProperties();
        Memory result=property.readInitialized(env,packet.trace(),table.getByScalar(property.getSpecificName())).toImmutable();
        packet.result(source.view(result));return result;
    }
    public static void managedRawAssign(Environment env,IObject object,PropertyEntity property,Memory actualValue,PhpOwnershipReflectionCalls.Packet packet)throws Throwable {
        if(property.isStatic())throw new IllegalArgumentException("Managed static raw property requires its actual metadata publisher");
        if(property.getPhpHooks()!=null&&property.getPhpHooks().isVirtual())
            PhpPropertyHookAccess.error(env,packet.trace(),property.getPhpHooks().set()==null?"Property %s::$%s is read-only":"Must not write to virtual property %s::$%s",property);
        var source=packet.argument(1,actualValue);var backing=backing(env,object,property.getSpecificName());
        managedProperty(env,backing,property).assign(source);
    }
    public static void skipManaged(Environment env,IObject object,PropertyEntity property,Memory raw,boolean assign,PhpOwnershipReflectionCalls.Packet packet)throws Throwable {
        if(property.isStatic()) {
            if(assign)throw new IllegalArgumentException("Managed static skip assignment requires its actual metadata publisher");
            return;
        }
        if(property.getPhpHooks()!=null&&property.getPhpHooks().isVirtual()) {
            if(assign)PhpPropertyHookAccess.error(env,packet.trace(),"Must not write to virtual property %s::$%s",property);
            return;
        }
        State state=state(object);
        if(state==null||state.phase!=Phase.LAZY) {
            if(assign)managedRawAssign(env,object,property,raw,packet);return;
        }
        state.actual();String key=property.getSpecificName();
        if(!state.lazyKeys.contains(key)){if(assign)managedProperty(env,object,property).assign(packet.argument(1,raw));return;}
        var session=packet.session();PhpOwnershipValues.Binding source;
        if(assign)source=packet.argument(1,raw);
        else {
            Memory value=property.getDefaultValue(env);
            if(value instanceof ArrayMemory||value instanceof ObjectMemory||value instanceof ReferenceMemory)
                throw new IllegalArgumentException("Managed heap property default lacks its actual metadata RESULT publisher");
            source=session.value(value);
        }
        state.skipped.add(key);state.lazyKeys.remove(key);
        try{managedProperty(env,object,property).assign(source);}
        catch(Throwable failure){state.skipped.remove(key);state.lazyKeys.add(key);throw failure;}
        if(state.lazyKeys.isEmpty()) {
            state.phase=Phase.INITIALIZED;state.strategy=Strategy.GHOST;state.managed.publishInitializer(session.value(Memory.NULL));
        }
    }
    public static void skip(Environment env,IObject object,PropertyEntity property,Memory raw,boolean assign) throws Throwable {
        State state=state(object);
        if(property.isStatic()||property.getPhpHooks()!=null&&property.getPhpHooks().isVirtual()) {
            if(assign)property.assignValue(env,env.trace(),object,property.getSpecificName(),raw);return;
        }
        if(state==null||state.phase!=Phase.LAZY) {
            if(assign)property.assignValue(env,env.trace(),backing(env,object,property.getSpecificName()),property.getSpecificName(),raw);return;
        }
        state.actual();if(state.managed!=null)throw new IllegalArgumentException("Managed lazy skip requires its actual typed property publisher");
        String key=property.getSpecificName();
        if(!state.lazyKeys.contains(key)) {if(assign)property.assignValue(env,env.trace(),object,key,raw);return;}
        // The skip is visible to conversion/diagnostic callbacks before an effectful type check.
        state.skipped.add(key);state.lazyKeys.remove(key);
        try {
            if(assign)property.assignValue(env,env.trace(),object,key,raw);
            else state.publishDefault(property);
        } catch(Throwable failure) {state.skipped.remove(key);state.lazyKeys.add(key);throw failure;}
        if(state.lazyKeys.isEmpty()) {state.phase=Phase.INITIALIZED;state.strategy=Strategy.GHOST;state.initializer=Memory.NULL;}
    }
    /** Raw casts/debug do not initialize; an initialized proxy exposes the real backing table. */
    public static ArrayMemory currentProperties(BaseObject object,ArrayMemory raw) {
        if(raw instanceof PhpObjectProperties table) {
            State state=table.lazyState();
            if(state!=null&&state.phase==Phase.INITIALIZED&&state.strategy==Strategy.PROXY&&state.target!=null)
                return state.target.getProperties();
        }
        return raw;
    }
    public static boolean skipDestructor(IObject object) {
        State state=state(object);return state!=null&&(state.strategy==Strategy.PROXY||state.phase==Phase.LAZY);
    }
    public static IObject cloneProxy(Environment env,IObject object,TraceInfo trace) throws Throwable {
        State state=state(object);if(state==null)return null;
        state.initialize();if(state.strategy!=Strategy.PROXY)return null;
        IObject target=state.target.getReflection().cloneObject(state.target,env,trace);
        BaseObject copy=actual(env,object.getReflection().newLazyObjectShell(env));
        State cloned=new State(copy,env,Strategy.PROXY,state.options,state.properties,Memory.NULL);
        copy.getOwnershipProperties().installLazyState(cloned);cloned.target=target;cloned.phase=Phase.INITIALIZED;
        return copy;
    }
    private static void clear(PhpObjectProperties table,State state) {
        table.enableBucketLayout();
        for(int ordinal=0;ordinal<table.bucketLimit();ordinal++) {ReferenceMemory entry=table.bucketAt(ordinal);if(entry!=null)entry.detachPropertySource();}
        if(state!=null)state.rawClear=true;
        try{table.clear();}finally{if(state!=null)state.rawClear=false;}
    }
    private record Row(Object key,Memory payload,PropertyEntity property,boolean unset) {}
    private static List<Row> snapshot(PhpObjectProperties table) {
        table.enableBucketLayout();List<Row> rows=new ArrayList<>();
        for(int ordinal=0;ordinal<table.bucketLimit();ordinal++) {
            ReferenceMemory entry=table.bucketAt(ordinal);if(entry==null){rows.add(null);continue;}
            Object key=table.bucketKey(ordinal);Memory raw=entry.getValue();
            rows.add(new Row(key,raw.isReference()?raw:raw.toImmutable(),key instanceof String?table.declaration((String)key):null,
                    key instanceof String&&table.isExplicitlyUnset((String)key)));
        }
        return rows;
    }
    private static void restore(State state,List<Row> rows) {
        clear(state.table,state);
        for(Row row:rows) {
            if(row==null){state.table.appendBucketHole();continue;}
            if(row.property!=null)state.table.declare(row.property);
            ReferenceMemory entry=state.table.insertBucket(row.key,row.payload);
            if(row.property!=null) {
                if(row.unset)state.table.unsetDeclared((String)row.key);
                else entry.attachInstancePropertySource(state.environment,row.property,state.owner,null);
            }
        }
    }
    public static final class State {
        private final BaseObject owner;
        private final Environment environment;
        private final PhpObjectProperties table;
        private Strategy strategy;
        private Phase phase=Phase.PREPARING;
        private final int options;
        private final List<PropertyEntity> properties;
        private final Set<String> lazyKeys=new HashSet<>(),skipped=new HashSet<>();
        private Memory initializer;
        private IObject target;
        private boolean rawClear;
        private Managed managed;
        private State(BaseObject owner,Environment environment,Strategy strategy,int options,List<PropertyEntity> properties,Memory initializer) {
            this.owner=owner;this.environment=environment;this.table=owner.getOwnershipProperties();this.strategy=strategy;
            this.options=options;this.properties=List.copyOf(properties);this.initializer=initializer;
            for(PropertyEntity property:properties)lazyKeys.add(property.getSpecificName());
        }
        private void actual() {
            if(managed==null)ordinary(environment,owner);
            else {var current=PhpOwnershipExecution.current(environment);
                if(current==null||current.domain()!=managed.certificate.domain())throw new IllegalArgumentException("Lazy operation needs its same-domain active Session");
                managed.certificate.validateObject(owner);
            }
            if(phase==Phase.RETIRED||owner.peekProperties()!=table||table.lazyState()!=this)
                throw new IllegalStateException("Lazy state is detached from its actual property table");
        }
        public void validateTableInstallation(PhpObjectProperties actual) {
            if(phase!=Phase.PREPARING||actual!=table||owner.peekProperties()!=table||table.lazyState()!=null)
                throw new IllegalArgumentException("Lazy metadata needs its exact property table");
        }
        public void validateTableDetachment(PhpObjectProperties actual) {
            if(actual!=table||table.lazyState()!=this||phase!=Phase.RETIRED)throw new IllegalArgumentException("Lazy state is still live");
        }
        public void validateRawClear(PhpObjectProperties actual) {
            if(actual!=table||!rawClear)throw new IllegalStateException("Lazy clear is outside reset/rollback");
        }
        public Phase phase(){actual();return phase;}
        public Strategy strategy(){actual();return strategy;}
        BaseObject managedOwner(){actual();if(managed==null)throw new IllegalArgumentException("No managed lazy owner");return owner;}
        PhpOwnershipObjects.ObjectCertificate managedCertificate(){actual();if(managed==null)throw new IllegalArgumentException("No managed lazy certificate");return managed.certificate;}
        PhpOwnershipArrays managedArrays(){actual();return PhpOwnershipExecution.current(environment).arrays();}
        void validateManagedTable(PhpOwnershipObjects.PropertiesCertificate actual){
            actual();if(managed==null||managed.certificate.properties()!=actual||actual.table()!=table)
                throw new IllegalArgumentException("Lazy transaction has another physical TABLE");
        }
        private void publishDefault(PropertyEntity property) {
            Memory value=property.getDefaultValue(environment).toImmutable();String key=property.getSpecificName();
            table.republishDeclared(key,value).attachInstancePropertySource(environment,property,owner,null);
        }
        private void defaults() {for(PropertyEntity property:properties)if(lazyKeys.contains(property.getSpecificName()))publishDefault(property);}
        private void mark() {
            actual();if(managed!=null) {
                try(var transaction=new PhpLazyPropertyTransaction(managed.ownerBinding)) {
                    var rows=managedDefaults(transaction);var session=PhpOwnershipExecution.current(environment);
                    session.domain().graph().deferDestruction(()->{
                        transaction.replace(rows);phase=Phase.INITIALIZED;strategy=Strategy.GHOST;lazyKeys.clear();managed.publishInitializer(session.value(Memory.NULL));
                    });return;
                }catch(Throwable failure){PhpLazyObjects.<RuntimeException,Void>raise(failure);return;}
            }
            List<Row> before=snapshot(table);phase=Phase.INITIALIZING;
            try{defaults();phase=Phase.INITIALIZED;strategy=Strategy.GHOST;initializer=Memory.NULL;lazyKeys.clear();}
            catch(Throwable failure){try{restore(this,before);}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}phase=Phase.LAZY;throw failure;}
        }
        private java.util.List<PhpLazyPropertyTransaction.Row> managedDefaults(PhpLazyPropertyTransaction transaction)throws Throwable {
            var session=PhpOwnershipExecution.current(environment);var rows=transaction.saved();
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i);if(row==null||row.declaration()==null||!lazyKeys.contains(row.key()))continue;
                Memory value=row.declaration().getDefaultValue(environment);
                // Metadata heap defaults still require the separate existing initializer producer protocol.
                if(value instanceof ArrayMemory||value instanceof ObjectMemory||value instanceof ReferenceMemory)
                    throw new IllegalArgumentException("Managed heap property default lacks its actual metadata RESULT publisher");
                var source=transaction.local("lazy-default:"+i,session.value(value));
                rows.set(i,new PhpLazyPropertyTransaction.Row(row.key(),source,row.declaration(),false));
            }
            return rows;
        }
        private IObject initializeManaged()throws Throwable {
            var session=PhpOwnershipExecution.current(environment);
            try(var transaction=new PhpLazyPropertyTransaction(managed.ownerBinding)) {
                var callback=transaction.local("lazy-callable",managed.initializerBinding);
                phase=Phase.INITIALIZING;managed.publishInitializer(session.value(Memory.NULL));
                try {
                    if(strategy==Strategy.GHOST)transaction.replace(managedDefaults(transaction));
                    var invoker=Invoker.valueOf(environment,environment.trace(),callback.rawValue());
                    session.requireInvokerCapability(invoker);
                    try(var call=session.newCallRoots()) {
                        managed.stageCallableReceiver(invoker,callback,call);call.send(managed.ownerBinding);
                        Memory result=call.invokeInvoker(invoker,call.rawArguments(),environment.trace(),false);
                        if(strategy==Strategy.GHOST) {
                            if(!result.isNull())typeError(environment,"Lazy object initializer must return null or void");
                        } else {
                            if(!(result instanceof ObjectMemory value))typeError(environment,"Lazy proxy factory must return an instance of a class compatible with "+owner.getReflection().getName()+", "+
                                    php.runtime.reflection.support.PhpTypeDeclaration.valueType(result)+" returned");
                            IObject real=((ObjectMemory)result).value;validateProxyTarget(real);
                            var actualResult=call.result();
                            if(actualResult==null)throw new IllegalArgumentException("Lazy factory has no actual returned RESULT publisher");
                            managed.publishTarget(actualResult.view(result));
                        }
                    }
                    phase=Phase.INITIALIZED;lazyKeys.clear();return strategy==Strategy.PROXY?target:owner;
                } catch(Throwable failure) {
                    try {session.domain().graph().deferDestruction(()->{
                        transaction.restore();managed.publishTarget(session.value(Memory.NULL));managed.publishInitializer(callback);phase=Phase.LAZY;
                    });}catch(Throwable cleanup){if(cleanup!=failure)failure=session.domain().graph().composeFailure(failure,cleanup);}
                    throw failure;
                }
            }
        }
        private void validateProxyTarget(IObject real) {
            if(real==owner||uninitialized(real))error(environment,"Lazy proxy factory must return a non-lazy object");
            ClassEntity proxy=owner.getReflection(),type=real.getReflection();
            if(!proxy.isInstanceOf(type)||stored(proxy).size()!=stored(type).size()
                    ||proxy.methodDestruct!=type.methodDestruct||proxy.methodMagicClone!=type.methodMagicClone)
                typeError(environment,"The real instance class "+type.getName()+" is not compatible with the proxy class "+proxy.getName()+
                        ". The proxy must be a instance of the same class as the real instance, or a sub-class with no additional properties, and no overrides of the __destructor or __clone methods.");
        }
        private IObject initialize() throws Throwable {
            actual();if(phase!=Phase.LAZY)return phase==Phase.INITIALIZED&&strategy==Strategy.PROXY?target:owner;
            if(managed!=null)return initializeManaged();
            List<Row> before=snapshot(table);Memory callback=initializer;
            phase=Phase.INITIALIZING;initializer=Memory.NULL;
            try {
                if(strategy==Strategy.GHOST)defaults();
                Memory result=Invoker.valueOf(environment,environment.trace(),callback).call(ObjectMemory.valueOf(owner));
                if(strategy==Strategy.GHOST) {
                    if(!result.isNull())typeError(environment,"Lazy object initializer must return null or void");
                } else {
                    if(!result.isObject())typeError(environment,"Lazy proxy factory must return an instance of a class compatible with "+owner.getReflection().getName()+", "+
                            php.runtime.reflection.support.PhpTypeDeclaration.valueType(result)+" returned");
                    IObject real=result.toValue(ObjectMemory.class).value;
                    if(real==owner||uninitialized(real))error(environment,"Lazy proxy factory must return a non-lazy object");
                    ClassEntity proxy=owner.getReflection(),type=real.getReflection();
                    if(!proxy.isInstanceOf(type)||stored(proxy).size()!=stored(type).size()
                            ||proxy.methodDestruct!=type.methodDestruct||proxy.methodMagicClone!=type.methodMagicClone)
                        typeError(environment,"The real instance class "+type.getName()+" is not compatible with the proxy class "+proxy.getName()+
                                ". The proxy must be a instance of the same class as the real instance, or a sub-class with no additional properties, and no overrides of the __destructor or __clone methods.");
                    ordinary(environment,real);target=real;
                }
                phase=Phase.INITIALIZED;lazyKeys.clear();return strategy==Strategy.PROXY?target:owner;
            } catch(Throwable failure) {
                try{restore(this,before);}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
                target=null;initializer=callback;phase=Phase.LAZY;throw failure;
            }
        }
        private void retire() {actual();phase=Phase.RETIRED;initializer=Memory.NULL;target=null;table.detachLazyState(this);}
    }
    public static Memory raiseInitializerFailure(Throwable failure){return PhpLazyObjects.<RuntimeException,Memory>raise(failure);}
    @SuppressWarnings("unchecked") private static <T extends Throwable,R> R raise(Throwable failure)throws T{throw (T)failure;}
}
