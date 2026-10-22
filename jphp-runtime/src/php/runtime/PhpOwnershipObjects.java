package php.runtime;

import php.runtime.env.Environment;
import php.runtime.invoke.PhpObjectLifecycle;
import php.runtime.lang.BaseObject;
import php.runtime.lang.Closure;
import php.runtime.lang.Generator;
import php.runtime.lang.IObject;
import php.runtime.lang.support.IPhpOwnershipObject;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.PropertyEntity;
import java.lang.reflect.Constructor;
import java.util.*;

/** Explicit actual-object allocation and physical property edges. Ordinary PHP factories remain unowned. */
public final class PhpOwnershipObjects {
    /**
     * A trusted host/provider explicitly attests that this exact shell constructor owns only
     * its BaseObject property table, and that class initialization/default production is
     * already complete. Validation must not construct, initialize or publish PHP values.
     * BaseObject ancestry, compiler version and raw reachability are not this capability.
     */
    public interface CompletePropertiesProtocol {
        void validateResolvedState(ClassEntity entity, Environment environment);
    }
    /** Explicit exact destructor body protocol; validation must have no PHP effects. */
    public interface CompleteDestructorProtocol {
        void validateMember(ClassEntity entity,php.runtime.reflection.MethodEntity method,Environment environment);
        void invoke(ExecutionEntry entry,Environment environment,php.runtime.env.TraceInfo trace) throws Throwable;
    }
    /** An owned clone needs a new allocation witness and complete child publication protocol. */
    public static void rejectUnmanagedClone(IObject object) {
        if (object instanceof BaseObject && ((BaseObject)object).getOwnershipObject() != null)
            throw new IllegalStateException("Certified object cloning requires an explicit owned allocation protocol");
    }
    private final PhpOwnershipHeap heap;
    private final PhpOwnershipArrays arrays;
    private final IdentityHashMap<ClassEntity,Provider> providers=new IdentityHashMap<>();
    public PhpOwnershipObjects(PhpOwnershipHeap heap, PhpOwnershipArrays arrays) {
        this.heap=Objects.requireNonNull(heap);this.arrays=Objects.requireNonNull(arrays);
        if (arrays.domain() != heap.domain()) throw new IllegalArgumentException("Array adapter belongs to another domain");
    }
    public PhpOwnershipDomain domain() {return heap.domain();}

    /** Explicit capability registration; unknown constructors never receive an inferred descriptor. */
    public Provider registerPropertiesProvider(ClassEntity entity, Constructor<?> exactConstructor,
                                               CompletePropertiesProtocol protocol) {
        domain().currentContext();Objects.requireNonNull(entity);Objects.requireNonNull(exactConstructor);Objects.requireNonNull(protocol);
        Class<?> type=entity.getNativeClass();
        if (type == null || exactConstructor.getDeclaringClass() != type || !BaseObject.class.isAssignableFrom(type)
                || !Arrays.equals(exactConstructor.getParameterTypes(),new Class<?>[]{Environment.class,ClassEntity.class})
                || Closure.class.isAssignableFrom(type) || Generator.class.isAssignableFrom(type))
            throw new IllegalArgumentException("Exact native constructor needs its complete supported allocation protocol");
        if (providers.containsKey(entity)) throw new IllegalStateException("Class already has an allocation provider");
        Provider provider=new Provider(entity,exactConstructor,protocol,storedProperties(entity));providers.put(entity,provider);return provider;
    }
    /** Optional managed lifecycle entry; ordinary allocation registration retains its existing dispatch. */
    public Provider registerPropertiesProvider(ClassEntity entity,Constructor<?> exactConstructor,
            CompletePropertiesProtocol protocol,php.runtime.reflection.MethodEntity destructor,CompleteDestructorProtocol lifecycle) {
        Objects.requireNonNull(destructor);Objects.requireNonNull(lifecycle);
        if (destructor!=entity.methodDestruct || destructor.isStatic() || destructor.isAbstract()
                || destructor.getParameters()!=null && destructor.getParameters().length!=0
                || destructor.getNativeMethod()==null
                || !destructor.getNativeMethod().getDeclaringClass().isAssignableFrom(exactConstructor.getDeclaringClass())
                || !Arrays.equals(destructor.getNativeMethod().getParameterTypes(),new Class<?>[]{Environment.class,Memory[].class})
                || destructor.getNativeMethod().getReturnType()!=Memory.class
                || php.runtime.lang.support.IManualDestructable.class.isAssignableFrom(exactConstructor.getDeclaringClass()))
            throw new IllegalArgumentException("Lifecycle protocol needs its exact generated no-argument destructor");
        Provider provider=registerPropertiesProvider(entity,exactConstructor,protocol);
        provider.destructor=destructor;provider.destructorMember=destructor.getNativeMethod();provider.destructorProtocol=lifecycle;
        return provider;
    }
    /** Explicit lexical-shell protocol; capture metadata alone never certifies a constructor. */
    Provider registerLexicalClosureProvider(php.runtime.reflection.helper.ClosureEntity entity, Constructor<?> constructor,
                                           CompletePropertiesProtocol protocol) {
        PhpOwnershipClosures.validateLexicalShell(entity,constructor);
        domain().currentContext();Objects.requireNonNull(protocol);
        if (providers.containsKey(entity)) throw new IllegalStateException("Class already has an allocation provider");
        Provider provider=new Provider(entity,constructor,protocol,storedProperties(entity));
        if (!provider.properties.isEmpty()) throw new IllegalArgumentException("Lexical Closure shell cannot have declared PHP properties");
        provider.lexicalClosure=true;providers.put(entity,provider);return provider;
    }
    public Provider provider(ClassEntity entity) {
        domain().currentContext();Provider provider=providers.get(entity);
        if (provider == null) throw new IllegalArgumentException("Class has no complete ownership allocation provider");
        return provider;
    }
    public final class Provider {
        private final ClassEntity entity;
        private final Constructor<?> constructor;
        private final CompletePropertiesProtocol protocol;
        private final List<PropertyEntity> properties;
        private boolean lexicalClosure;
        private php.runtime.reflection.MethodEntity destructor;
        private java.lang.reflect.Method destructorMember;
        private CompleteDestructorProtocol destructorProtocol;
        private void validateDestructor(Environment environment) {
            if (destructorProtocol==null) return;
            if (providers.get(entity)!=this || entity.methodDestruct!=destructor
                    || !destructorMember.equals(destructor.getNativeMethod()) || destructor.isStatic() || destructor.isAbstract()
                    || destructor.getParameters()!=null && destructor.getParameters().length!=0)
                throw new IllegalArgumentException("Registered destructor identity changed");
            destructorProtocol.validateMember(entity,destructor,environment);
        }
        private Provider(ClassEntity entity,Constructor<?> constructor,CompletePropertiesProtocol protocol,List<PropertyEntity> properties) {
            this.entity=entity;this.constructor=constructor;this.protocol=protocol;this.properties=Collections.unmodifiableList(properties);
        }
        public ClassEntity entity() {return entity;}
        public List<PropertyEntity> properties() {return properties;}
    }
    /** Complete resolved defaults, not a callback that discovers foreign values after shell construction. */
    public Allocation allocate(Provider provider, Environment environment,
                               Map<PropertyEntity,PhpOwnershipValues.Binding> certifiedDefaults) throws Throwable {
        domain().currentContext();Objects.requireNonNull(environment);Objects.requireNonNull(certifiedDefaults);
        if (provider == null || providers.get(provider.entity) != provider)
            throw new IllegalArgumentException("Allocation provider belongs to another object adapter");
        if (!provider.properties.equals(storedProperties(provider.entity)))
            throw new IllegalStateException("Class property descriptor changed after provider registration");
        if (certifiedDefaults.size() != provider.properties.size())
            throw new IllegalArgumentException("Default plan must cover every physical declared property");
        IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding> defaults=new IdentityHashMap<>();
        // All admissions occur before provider/native constructor/class initialization can have effects.
        for (PropertyEntity property : provider.properties) {
            PhpOwnershipValues.Binding value=certifiedDefaults.get(property);
            if (value == null || value.role() != PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Declared defaults require complete certified VALUE bindings");
            candidate(value);defaults.put(property,value);
        }
        for (PropertyEntity property : provider.properties) {
            var value=defaults.get(property);Memory rawValue=value.rawValue();
            if (property.getDeclaredType() != null && !rawValue.isUndefined()) {
                Memory checked=property.getDeclaredType().strictValue(environment,rawValue,property.getClazz());
                if (checked == null) throw new IllegalArgumentException("Certified default violates its declared property type");
                if (checked != rawValue) defaults.put(property,PhpOwnershipValues.bindValue(domain(),checked,null));
            }
        }
        provider.protocol.validateResolvedState(provider.entity,environment);
        provider.validateDestructor(environment);
        AllocationWitness witness=new AllocationWitness(provider,environment);
        IObject raw;
        if (provider.lexicalClosure) {
            PhpOwnershipClosures.validateLexicalShell((php.runtime.reflection.helper.ClosureEntity)provider.entity,provider.constructor);
            witness.consumeFactory(provider.entity,provider.constructor.getDeclaringClass(),provider.constructor,environment);
            try {raw=(IObject)provider.constructor.newInstance(environment,provider.entity,Memory.NULL,null,new Memory[0]);}
            catch (java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
        } else raw=provider.entity.createOwnershipShell(witness,environment);
        if (raw == null || raw.getClass() != provider.constructor.getDeclaringClass() || !(raw instanceof BaseObject))
            throw new IllegalStateException("Allocation provider violated its exact shell protocol");
        BaseObject object=(BaseObject)raw;
        if (object.getOwnershipObject() != null || object.isFinalized())
            throw new IllegalStateException("Shell is not a fresh object allocation");
        PhpObjectProperties table=object.getOwnershipProperties();
        if (table.size() != 0 || table.getOwnershipTable() != null)
            throw new IllegalStateException("Complete shell protocol requires empty unowned property storage");
        ObjectCertificate certificate=new ObjectCertificate(object,provider);
        object.attachOwnershipObject(certificate,witness);
        PhpOwnershipDomain.OwnedValue root=domain().hostValue(certificate.node,new ObjectMemory(object));
        try {
            certificate.properties=new PropertiesCertificate(certificate,table);
            table.attachOwnershipTable(certificate.properties);
            certificate.tableEdge=domain().graph().edge(certificate.node);
            certificate.tableEdge.replace(certificate.properties.node);
            domain().graph().deferDestruction(() -> {
                certificate.properties.constructing=true;
                try {
                    php.runtime.memory.helper.PhpPropertyGuard.OwnerToken lifetime=null;
                    for (PropertyEntity property : provider.properties) {
                        table.declare(property);
                        ReferenceMemory carrier=table.putAsKeyString(property.getSpecificName(),defaults.get(property).rawValue());
                        lifetime=carrier.attachInstancePropertySource(environment,property,object,lifetime);
                        certificate.properties.attach(carrier,defaults.get(property));
                    }
                } finally {certificate.properties.constructing=false;}
            });
            certificate.initialized=true;
            return new Allocation(certificate,root);
        } catch (Throwable failure) {
            try {root.close();} catch (Throwable cleanup) {failure=domain().graph().composeFailure(failure,cleanup);}
            throw failure;
        }
    }
    private static List<PropertyEntity> storedProperties(ClassEntity entity) {
        Deque<ClassEntity> lineage=new ArrayDeque<>();
        for (ClassEntity owner=entity;owner != null;owner=owner.getParent()) lineage.push(owner);
        List<PropertyEntity> result=new ArrayList<>();Set<String> names=new HashSet<>();
        while (!lineage.isEmpty()) {
            ClassEntity owner=lineage.pop();
            for (PropertyEntity declared : owner.getProperties()) {
                if (declared.getClazz() != owner || declared.isStatic() || declared.getGetter() != null) continue;
                PropertyEntity property=declared.isPrivate() ? declared : entity.findProperty(declared.getName());
                if (property != null && property.getGetter() == null && names.add(property.getSpecificName())) result.add(property);
            }
        }
        return result;
    }
    private void candidate(PhpOwnershipValues.Binding binding) {
        if (binding.domain() != domain()) throw new IllegalArgumentException("Child belongs to another domain");
        binding.requireCertifiedObject();Memory raw=binding.rawValue();
        if (raw instanceof ArrayMemory) {
            ArrayMemory array=(ArrayMemory)raw;var origin=array.getRuntimeOrigin();
            if (origin != null) {
                if (origin.authority().domain() != domain() || origin.node() != binding.node())
                    throw new IllegalArgumentException("Runtime ARRAY child belongs to another origin");
                origin.validate(array);
            } else if (arrays.certificate(array).node() != binding.node())
                throw new IllegalArgumentException("Array child does not have its originating storage certificate");
        } else if (binding.role() == PhpOwnershipValues.Role.CELL) {
            ReferenceMemory cell=(ReferenceMemory)raw;var origin=cell.getRuntimeCell();
            if (origin != null) {
                if (origin.authority().domain() != domain() || origin.node() != binding.node())
                    throw new IllegalArgumentException("Runtime CELL child belongs to another origin");
                origin.authority().value(cell,PhpRuntimeOwnership.Role.CELL);
            } else if (arrays.certificate(cell).node() != binding.node())
                throw new IllegalArgumentException("CELL child does not have its originating allocation certificate");
        }
    }
    /** Single-use authority minted only after complete default admission and explicit protocol validation. */
    public final class AllocationWitness {
        private final Provider provider;
        private final Environment environment;
        private boolean factoryUsed,attached;
        private AllocationWitness(Provider provider,Environment environment) {this.provider=provider;this.environment=environment;}
        public void consumeFactory(ClassEntity entity, Class<?> exactType, Constructor<?> actualConstructor, Environment actualEnvironment) {
            domain().currentContext();
            if (factoryUsed || attached || actualEnvironment != environment || entity != provider.entity || exactType != provider.constructor.getDeclaringClass()
                    || !provider.constructor.equals(actualConstructor))
                throw new IllegalArgumentException("Shell factory does not identify the registered allocation witness");
            factoryUsed=true;
        }
        private void attach(ObjectCertificate certificate,IObject object) {
            domain().currentContext();
            if (!factoryUsed || attached || certificate.adapter() != PhpOwnershipObjects.this
                    || certificate.provider != provider || certificate.object != object)
                throw new IllegalArgumentException("Object origin requires its fresh single-use allocation witness");
            attached=true;
        }
    }
    public final class Allocation implements AutoCloseable {
        private final ObjectCertificate certificate;
        private final PhpOwnershipDomain.OwnedValue root;
        private boolean closed;
        private Allocation(ObjectCertificate certificate,PhpOwnershipDomain.OwnedValue root) {this.certificate=certificate;this.root=root;}
        private void open() {domain().currentContext();if (closed) throw new IllegalStateException("Object preparation is closed");certificate.validateObject(certificate.object);}
        private boolean restorationClaimed;
        public PhpOwnershipArrays.RestorationAssembly restoration() {
            open();if (restorationClaimed || certificate.node.incoming() != 1)
                throw new IllegalStateException("Restoration requires its fresh unpublished allocation");
            restorationClaimed=true;return arrays.restorationTable(certificate.properties);
        }
        public IObject object() {open();return certificate.object;}
        public ObjectCertificate certificate() {open();return certificate;}
        public PhpOwnershipValues.Binding binding() {open();return PhpOwnershipValues.bindValue(domain(),new ObjectMemory(certificate.object),certificate.node);}
        public PhpOwnershipHeap.Publication preparePublication(PhpOwnershipHeap.Publisher destination,PhpOwnershipHeap.Snapshot expected) {
            open();if (destination.domain() != domain()) throw new IllegalArgumentException("Destination belongs to another domain");
            certificate.properties.validateAll();return destination.prepare(expected,binding());
        }
        @Override public void close() throws Exception {if (!closed) {domain().currentContext();closed=true;root.close();}}
    }
    public final class ObjectCertificate {
        private final BaseObject object;
        private final Provider provider;
        private final PhpOwnership.Node node;
        private PropertiesCertificate properties;
        private PhpOwnership.Slot tableEdge;
        private boolean initialized;
        private ExecutionEntry activeEntry;
        private PhpOwnershipClosures.Certificate closure;
        public PhpOwnershipClosures.Certificate closure() {validateObject(object);return closure;}
        /** Identity-only leaf for prepared no-callback raw stores. */
        public PhpOwnershipClosures.Certificate rawClosureDescriptor() {return closure;}
        void attachClosure(PhpOwnershipClosures.Certificate descriptor) {
            validateObject(object);
            if (!provider.lexicalClosure || closure != null || descriptor.objectCertificate() != this)
                throw new IllegalArgumentException("Closure holder protocol does not identify its actual allocation");
            closure=descriptor;
        }
        private ObjectCertificate(BaseObject object,Provider provider) {
            this.object=object;this.provider=provider;
            node=domain().object(object,new PhpOwnershipDomain.Lifecycle() {
                @Override public void destruct(PhpOwnership.Node target,Object context) throws Throwable {
                    if (!initialized) return;
                    if (provider.destructorProtocol==null) {
                        PhpObjectLifecycle.destruct(object,(Environment)context,PhpObjectLifecycle.Reason.OWNERSHIP_RELEASE);return;
                    }
                    if (object.isFinalized()) return;
                    provider.validateDestructor((Environment)context);
                    if (target!=node || activeEntry!=null || node.state()!=PhpOwnership.State.FINALIZING)
                        throw new IllegalStateException("Destructor entry needs its actual lifecycle callback");
                    ExecutionEntry entry=new ExecutionEntry(ObjectCertificate.this,(Environment)context);activeEntry=entry;
                    try {PhpObjectLifecycle.destruct(entry,(Environment)context);}
                    finally {entry.expired=true;activeEntry=null;}
                }
                @Override public void destroy(PhpOwnership.Node target,Object context) {
                    // Graph calls destroy only after its resurrection decision. No early/manual/GC guard detach.
                    PhpLazyObjects.destroyOwnership(object,ObjectCertificate.this,target);
                    if (properties != null) properties.detachSources();
                }
            });
        }
        private PhpOwnershipObjects adapter() {return PhpOwnershipObjects.this;}
        public PhpOwnershipDomain domain() {return PhpOwnershipObjects.this.domain();}
        public IObject object() {validateObject(object);return object;}
        public PhpOwnership.Node node() {validateObject(object);return node;}
        public PropertiesCertificate properties() {validateObject(object);return properties;}
        public void validateObject(IObject actual) {
            domain().currentContext();
            if (actual != object || object.getReflection() != provider.entity || object.getOwnershipObject() != this || node.identity() != object || node.state() == PhpOwnership.State.DISPOSED)
                throw new IllegalArgumentException("Certificate does not identify this live actual object");
            if (properties != null && object.getOwnershipProperties() != properties.table)
                throw new IllegalStateException("Actual object property field changed outside its descriptor");
            PhpLazyObjects.validateOwnership(object,this,node);
        }
        public void validateBinding(PhpOwnershipDomain requested,PhpOwnership.Node candidate,IObject actual) {
            if (requested != domain() || candidate != node || actual != object)
                throw new IllegalArgumentException("Actual object origin belongs to another domain/allocation");
            validateObject(actual);
        }
        public void validateAttachment(IObject actual,AllocationWitness witness) {Objects.requireNonNull(witness).attach(this,actual);}
    }
    /**
     * Non-owning one-shot receiver authority. Only the real lifecycle callback can mint it;
     * it contributes no receiver root and expires before the graph decides resurrection.
     */
    public final class ExecutionEntry {
        private final ObjectCertificate certificate;
        private final Environment environment;
        private boolean consumed,dispatched,expired;
        private ExecutionEntry(ObjectCertificate certificate,Environment environment) {this.certificate=certificate;this.environment=environment;}
        private void open() {
            certificate.validateObject(certificate.object);
            if (expired || certificate.activeEntry!=this || domain().currentContext()!=environment
                    || certificate.node.state()!=PhpOwnership.State.FINALIZING
                    || certificate.provider.destructorProtocol==null)
                throw new IllegalArgumentException("Destructor receiver entry is not the active lifecycle origin");
            if (providers.get(certificate.provider.entity)!=certificate.provider
                    || certificate.provider.entity.methodDestruct!=certificate.provider.destructor
                    || !certificate.provider.destructorMember.equals(certificate.provider.destructor.getNativeMethod())
                    || certificate.provider.destructor.isStatic() || certificate.provider.destructor.isAbstract()
                    || certificate.provider.destructor.getParameters()!=null && certificate.provider.destructor.getParameters().length!=0)
                throw new IllegalArgumentException("Destructor receiver changed its exact method");
        }
        /** Checks the releasing environment before finalized/call-stack effects. */
        public void validateEnvironment(Environment releasing) {
            open();if (releasing!=environment) throw new IllegalArgumentException("Foreign destructor releasing environment");
        }
        public IObject receiver() {open();return certificate.object;}
        public php.runtime.reflection.MethodEntity method() {open();return certificate.provider.destructor;}
        public IObject consume(PhpOwnershipDomain requested,Environment releasing,php.runtime.reflection.MethodEntity method) {
            open();
            if (consumed || requested!=domain() || releasing!=environment || method!=certificate.provider.destructor
                    || !certificate.object.isFinalized())
                throw new IllegalArgumentException("Destructor receiver consumption needs its exact domain/environment/method");
            consumed=true;return certificate.object;
        }
        /** Revalidates a consumed receiver import; this is a borrow, never a VALUE publication. */
        public void validateReceiver(PhpOwnershipDomain requested,Environment releasing,
                php.runtime.reflection.MethodEntity method,IObject actual) {
            open();
            if (!consumed || requested!=domain() || releasing!=environment || method!=certificate.provider.destructor
                    || actual!=certificate.object || !certificate.object.isFinalized())
                throw new IllegalArgumentException("Destructor receiver import differs from its consumed lifecycle origin");
        }
        /** Lifecycle dispatch only; a registered body must consume this entry before returning. */
        public void dispatch(Environment releasing,php.runtime.env.TraceInfo trace) throws Throwable {
            open();
            if (dispatched || releasing!=environment || !certificate.object.isFinalized())
                throw new IllegalArgumentException("Destructor dispatch differs from its one-shot lifecycle callback");
            dispatched=true;
            certificate.provider.destructorProtocol.invoke(this,releasing,trace);
            if (!consumed) throw new IllegalArgumentException("Destructor body did not consume its lifecycle receiver entry");
        }
    }
    public final class PropertiesCertificate {
        private final ObjectCertificate owner;
        private final PhpObjectProperties table;
        private final Object identity;
        private final PhpOwnership.Node node;
        private IdentityHashMap<ReferenceMemory,PhpOwnershipArrays.PhysicalSlot> entries=new IdentityHashMap<>();
        private boolean constructing;
        private long removalRevision;
        private PropertiesCertificate(ObjectCertificate owner,PhpObjectProperties table) {
            this.owner=owner;this.table=table;identity=table.getStorageIdentity();node=domain().storage(identity);
        }
        public PhpObjectProperties table() {validateAll();return table;}
        public Object backingIdentity() {return identity;}
        public PhpOwnership.Node node() {domain().currentContext();return node;}
        public void validateAttachment(ArrayMemory actual) {
            domain().currentContext();
            if (actual != table || actual.getStorageIdentity() != identity || node.state() == PhpOwnership.State.DISPOSED)
                throw new IllegalArgumentException("TABLE certificate does not identify its actual backing allocation");
        }
        public void checkMutation(ArrayMemory actual) {
            validateAttachment(actual);
            if (!constructing) throw new IllegalStateException("Certified TABLE mutation requires its exact physical publisher");
        }
        private void attach(ReferenceMemory carrier,PhpOwnershipValues.Binding value) throws Throwable {
            if (entries.containsKey(carrier)) throw new IllegalStateException("Physical property slot attached twice");
            entries.put(carrier,arrays.attachPhysicalSlot(node,carrier,value));
        }
        public PhpOwnershipArrays.PhysicalSlot entry(ReferenceMemory actualCarrier) {
            validateAll();PhpOwnershipArrays.PhysicalSlot entry=entries.get(actualCarrier);
            if (entry == null) throw new IllegalArgumentException("Address is not a physical slot of this TABLE");return entry;
        }
        /** Explicit property-table insertion preserves duplicate physical keys; normal PHP mutation remains dormant. */
        public PhpOwnershipArrays.PhysicalSlot insertBucket(String key,PhpOwnershipValues.Binding value) throws Throwable {
            validateAll();Objects.requireNonNull(key);candidate(value);
            if (table.declaration(key) != null) throw new IllegalArgumentException("Declared properties require their existing typed physical publisher");
            ReferenceMemory[] result={null};
            domain().graph().deferDestruction(() -> {
                constructing=true;
                try {result[0]=table.insertBucket(key,value.rawValue());attach(result[0],value);}
                finally {constructing=false;}
            });removalRevision++;return entries.get(result[0]);
        }
        public IdentityHashMap<ReferenceMemory,PhpOwnershipArrays.PhysicalSlot> prepareRestorationEntries(PhpOwnershipArrays.RestorationPublication publication) {
            if (!publication.validatesTablePreparation(this)) throw new IllegalArgumentException("Wrong TABLE restoration origin");
            return new IdentityHashMap<>(entries);
        }
        public void publishRestorationEntries(PhpOwnershipArrays.RestorationPublication publication,IdentityHashMap<ReferenceMemory,PhpOwnershipArrays.PhysicalSlot> next) {
            if (!publication.validatesTableCommit(this)) throw new IllegalArgumentException("Wrong TABLE restoration publication");
            entries=next;removalRevision++;
        }
        public void publishRestorationClear(PhpOwnershipArrays.RestorationClearPublication publication) {
            if (!publication.validatesTableCommit(this)) throw new IllegalArgumentException("Wrong TABLE restoration cleanup");
            removalRevision++;
        }
        /** Private object-to-TABLE publisher authorizes this one actual lazy physical replacement. */
        void publishLazyReplacement(PhpLazyPropertyTransaction.Prepared placement)throws Throwable {
            validateAll();placement.storeUsingTableEdge(owner.tableEdge,this);
        }
        void replaceLazyEntries(PhpLazyPropertyTransaction.Prepared placement,IdentityHashMap<ReferenceMemory,PhpOwnershipArrays.PhysicalSlot> prepared) {
            if(!placement.permitsCertificate(this))throw new IllegalArgumentException("Wrong actual lazy TABLE publication");
            entries=prepared;removalRevision++;
        }
        public long removalRevision() {return removalRevision;}
        public PhpOwnershipArrays.UnsetPublication prepareUnset(String literalStorageKey) {
            return arrays.prepareTableUnset(this,literalStorageKey);
        }
        /** Called only by the consumed actual raw-removal token, before graph callbacks. */
        public void publishRemovalMetadata(PhpOwnershipArrays.UnsetPublication publication,ReferenceMemory carrier,boolean declared) {
            if (!publication.validatesTable(this,carrier,declared)) throw new IllegalArgumentException("Wrong TABLE removal permit");
            if (!declared) entries.remove(carrier);removalRevision++;
        }
        public void validateAll() {
            validateAttachment(table);owner.validateObject(owner.object);int count=0;
            for (ReferenceMemory carrier : table) {
                PhpOwnershipArrays.PhysicalSlot entry=entries.get(carrier);
                if (entry == null) throw new IllegalStateException("Property physical layout changed outside its publishers");
                entry.ownerPublisher().current();count++;
            }
            if (count != entries.size()) throw new IllegalStateException("Property physical layout changed outside its publishers");
        }
        private void detachSources() {for (ReferenceMemory carrier : table) carrier.detachPropertySource();}
    }
}
