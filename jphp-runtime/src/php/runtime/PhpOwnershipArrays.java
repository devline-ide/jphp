package php.runtime;

import php.runtime.memory.ArrayMemory;
import php.runtime.memory.PhpObjectProperties;
import php.runtime.memory.ReferenceMemory;
import java.util.*;

/** Explicit real-Memory allocation/COW adapter; ordinary container APIs are not implicitly activated. */
public final class PhpOwnershipArrays {
    /** Ordinary fresh allocation lowering. The frame is supplied by its actual compiler producer. */
    /** Addressed reference fetch over the existing certified storage and its actual bucket. */
    public static Memory arrayReference(PhpRuntimeOwnership.Frame frame,int siteId,int rootLocalId,Memory actualRoot,
                                        String literalKey,php.runtime.env.Environment env,php.runtime.env.TraceInfo trace) throws Throwable {
        if (frame == null) return actualRoot.refOfIndexAsShortcut(trace,new php.runtime.memory.StringMemory(literalKey));
        if (frame.authority().environment() != env) throw new IllegalArgumentException("ARRAY reference uses another Environment");
        var writer=frame.arrayReferenceHolder(siteId,rootLocalId,actualRoot,literalKey);
        var arrays=frame.authority().arrays();var original=(ArrayMemory)writer.raw();
        Object key=ArrayMemory.toKey(new php.runtime.memory.StringMemory(literalKey));
        if (original.getRuntimeOrigin() != null) {
            var writable=runtimeWritable(writer);
            var bucket=writable.getByScalarOrCreate(key,Memory.NULL);
            var owner=bucket.getRuntimeHolder();
            if (owner == null || owner.domain() != frame.authority().domain() || !owner.matches(bucket))
                throw new IllegalArgumentException("ARRAY reference lacks its actual bucket publisher");
            return frame.stageArrayReferenceResult(siteId,owner.reference());
        }
        arrays.certificate(original);
        if (original.getByScalar(key) == null)
            throw new IllegalArgumentException("Certified ARRAY reference needs its existing physical bucket");
        var publisher=writer.borrowValuePublisher();
        try (var write=arrays.prepareWrite(publisher,publisher.current())) {
            if (write.separated()) {write.stageAllocation(frame,siteId);frame.publishArrayCow(writer,write,siteId);}
            var slot=write.physicalSlot(key);
            var owner=frame.authority().adopt(slot.ownerPublisher().runtimeFacet());
            return frame.stageArrayReferenceResult(siteId,owner.reference());
        } catch (Throwable failure) {
            try {frame.releaseEval(siteId);} catch (Throwable cleanup) {failure=frame.authority().domain().graph().composeFailure(failure,cleanup);}
            throw failure;
        }
    }
    public static ArrayMemory runtimeFresh(PhpRuntimeOwnership.Frame frame,int evalId,ArrayMemory fresh) {
        if (frame == null) return fresh;
        boolean stored=false;
        try {
            if (frame.authority().currentFrame() != frame)
                throw new IllegalArgumentException("Fresh ARRAY producer requires its actual current evaluation frame");
            var origin=fresh.prepareRuntimeOrigin(frame.authority());
            try(var publication=frame.prepareEval(evalId,fresh,PhpRuntimeOwnership.Role.VALUE)) {
                publication.storeDirect();stored=true;publication.commit();
            }
            fresh.initializeRuntimeEntries(origin,frame.evalHolder(evalId));
            frame.authority().collectGarbageIfNeeded();return fresh;
        } catch(Throwable failure){
            if (stored)try {frame.releaseEval(evalId);}
            catch(Throwable cleanup){failure=frame.authority().domain().graph().composeFailure(failure,cleanup);}
            return runtimeFailure(failure);
        }
    }
    private static PhpRuntimeOwnership.Holder runtimePayload(PhpRuntimeOwnership.Holder holder) {
        if (holder.role() == PhpRuntimeOwnership.Role.CELL) {
            ReferenceMemory cell=(ReferenceMemory)holder.raw();
            if (cell.getRuntimeCell() == null) throw new IllegalArgumentException("Ordinary ARRAY write needs the canonical payload publisher");
            return cell.getRuntimeCell().payload();
        }
        if (holder.role() != PhpRuntimeOwnership.Role.VALUE) throw new IllegalArgumentException("A borrowed address cannot own an ARRAY");
        return holder;
    }
    /** Pure copy selection; the caller must publish the new value through its exact JVM CV/carrier. */
    public static ArrayMemory runtimeWriteCandidate(PhpRuntimeOwnership.Holder writer) {
        var holder=runtimePayload(writer);
        if (!(holder.raw() instanceof ArrayMemory)) throw new IllegalArgumentException("Actual writer does not contain ARRAY");
        ArrayMemory source=(ArrayMemory)holder.raw();var origin=source.getRuntimeOrigin();
        if (origin == null || origin.authority().domain() != holder.domain() || holder.node() != origin.node())
            throw new IllegalArgumentException("Actual ARRAY writer and complete physical origin differ");
        origin.validate(source);return origin.node().incoming() > 1 ? source.duplicate() : source;
    }
    /** Carrier publication only. Direct JVM CV callers use their compiler's pure ASTORE permit. */
    public static ArrayMemory runtimeWritable(PhpRuntimeOwnership.Holder writer) {
        var holder=runtimePayload(writer);
        if (!holder.hasPhysicalCarrier()) throw new IllegalArgumentException("Direct JVM ARRAY write needs its compiler ASTORE publication");
        ArrayMemory candidate=runtimeWriteCandidate(holder);
        if (candidate == holder.raw()) return candidate;
        return runtimeAssignFresh(holder,candidate);
    }
    public static ArrayMemory runtimeEmpty(PhpRuntimeOwnership.Holder writer) {
        return runtimeAssignFresh(runtimePayload(writer),new ArrayMemory());
    }
    private static ArrayMemory runtimeAssignFresh(PhpRuntimeOwnership.Holder holder,ArrayMemory fresh) {
        if (!holder.hasPhysicalCarrier()) throw new IllegalArgumentException("Fresh ARRAY assignment needs its actual carrier publisher");
        var frame=holder.authority().currentFrame();
        if (frame == null) throw new IllegalStateException("ARRAY producer needs its actual evaluation frame");
        try {
            var origin=fresh.prepareRuntimeOrigin(holder.authority());
            try(var temporary=frame.pinTemporary(fresh,PhpRuntimeOwnership.Role.VALUE)) {
                fresh.initializeRuntimeEntries(origin,temporary.holder());holder.assign(fresh);return fresh;
            }
        } catch(Throwable failure){return runtimeFailure(failure);}
    }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable>T runtimeFailure(Throwable failure)throws E{throw (E)failure;}
    private final PhpOwnershipHeap heap;
    public PhpOwnershipArrays(PhpOwnershipHeap heap) {this.heap=Objects.requireNonNull(heap);}
    public PhpOwnershipDomain domain() {return heap.domain();}

    public Allocation allocate(boolean map) throws Throwable {
        domain().currentContext();return new Allocation(new ArrayMemory(map));
    }
    public CellAllocation allocateCell(PhpOwnershipValues.Binding value) throws Throwable {
        candidate(value);
        if (value.role() != PhpOwnershipValues.Role.VALUE)
            throw new IllegalArgumentException("A canonical CELL payload must be a semantic VALUE");
        if (value.rawValue().isUndefined()) value=PhpOwnershipValues.bindValue(domain(),Memory.NULL,null);
        return new CellAllocation(value);
    }
    public StorageCertificate certificate(ArrayMemory array) {
        domain().currentContext();
        if (array instanceof PhpObjectProperties)
            throw new IllegalArgumentException("An object table needs its separate object-owner adapter");
        StorageCertificate certificate=array.getOwnershipStorage();
        if (certificate == null || certificate.adapter.domain() != domain())
            throw new IllegalArgumentException("Array backing allocation has no certificate in this domain");
        certificate.validateAll(array);return certificate;
    }
    public CellCertificate certificate(ReferenceMemory cell) {
        domain().currentContext();CellCertificate certificate=cell.getOwnershipCell();
        if (certificate == null || certificate.adapter.domain() != domain())
            throw new IllegalArgumentException("Canonical CELL allocation has no certificate in this domain");
        certificate.validateAttachment(cell);certificate.value.current();return certificate;
    }
    private void candidate(PhpOwnershipValues.Binding binding) {
        domain().currentContext();Objects.requireNonNull(binding);
        if (binding.domain() != domain()) throw new IllegalArgumentException("Ownership candidate belongs to another domain");
        binding.requireCertifiedObject();Memory raw=binding.rawValue();binding.node();
        if (raw instanceof ArrayMemory) {
            ArrayMemory array=(ArrayMemory)raw;var origin=array.getRuntimeOrigin();
            if (array.getOwnershipStorage() == null && origin != null) {
                if (origin.authority().domain() != domain() || origin.node() != binding.node())
                    throw new IllegalArgumentException("Runtime ARRAY origin does not identify the candidate");
                origin.validate(array);
            } else if (certificate(array).node != binding.node())
                throw new IllegalArgumentException("Array certificate does not identify the candidate");
        } else if (binding.role() == PhpOwnershipValues.Role.CELL) {
            ReferenceMemory cell=(ReferenceMemory)raw;var origin=cell.getRuntimeCell();
            if (origin != null) {
                if (origin.authority().domain() != domain() || origin.node() != binding.node())
                    throw new IllegalArgumentException("Runtime CELL origin does not identify the candidate");
                origin.authority().value(cell,PhpRuntimeOwnership.Role.CELL);
            } else if (certificate(cell).node != binding.node())
                throw new IllegalArgumentException("CELL certificate does not identify the candidate");
        }
    }
    /** An already rooted ARRAY callable receiver is read from its actual physical publisher, never raw heap lookup. */
    PhpOwnershipValues.Binding lazyCallableReceiver(PhpOwnershipValues.Binding owner,Object actualKey) {
        candidate(owner);
        if(owner.role()!=PhpOwnershipValues.Role.VALUE||!(owner.rawValue()instanceof ArrayMemory array))
            throw new IllegalArgumentException("Callable receiver lacks its actual ARRAY VALUE holder");
        var storage=certificate(array);storage.validateAll(array);var carrier=array.getByScalar(actualKey);
        if(carrier==null)throw new IllegalArgumentException("Callable receiver placement is absent");
        var slot=new PhysicalSlot(storage.entries.get(carrier));return slot.payloadPublisher().current().binding();
    }
    public ArrayWrite prepareWrite(PhpOwnershipHeap.Publisher owner, PhpOwnershipHeap.Snapshot expected) throws Throwable {
        domain().currentContext();Objects.requireNonNull(owner);
        if (owner.domain() != domain() || expected == null || expected.publisher() != owner || owner.current() != expected)
            throw new IllegalArgumentException("Array address is stale or belongs to another domain");
        PhpOwnershipValues.Binding binding=expected.binding();candidate(binding);
        if (binding.role() != PhpOwnershipValues.Role.VALUE || !(binding.rawValue() instanceof ArrayMemory))
            throw new IllegalArgumentException("Array COW requires the exact VALUE owner, not an alias to its CELL");
        ArrayMemory source=(ArrayMemory)binding.rawValue();StorageCertificate original=certificate(source);
        if (original.node.incoming() <= 1)
            return new ArrayWrite(owner,expected,source,original,null);
        // Clone the actual layout before any holder publication; the old Java view is never altered.
        ArrayMemory copy=source.duplicate();Allocation staged=new Allocation(copy);
        try {
            Iterator<ReferenceMemory> old=source.iterator(), fresh=copy.iterator();
            while (old.hasNext()) {
                ReferenceMemory oldEntry=old.next(), newEntry=fresh.next();
                Entry previous=original.entries.get(oldEntry);PhpOwnershipValues.Binding value=previous.current().binding();
                if (previous.selfCell) {
                    int index=staged.certificate.entries.size();staged.certificate.constructing=true;
                    try {copy.replaceOwnershipConstructionCarrier(index,oldEntry);}
                    finally {staged.certificate.constructing=false;}
                    staged.certificate.attach(oldEntry,value);
                } else staged.certificate.attach(newEntry,value.view(newEntry.getRawValue()));
            }
            if (fresh.hasNext()) throw new IllegalStateException("COW physical layout did not preserve entry count");
            staged.certificate.validateAll(copy);staged.sealed=true;
            return new ArrayWrite(owner,expected,copy,staged.certificate,staged);
        } catch (Throwable failure) {
            try {staged.close();} catch (Throwable cleanup) {failure=domain().graph().composeFailure(failure,cleanup);}
            throw failure;
        }
    }

    public final class Allocation implements AutoCloseable {
        private final ArrayMemory array;
        private final StorageCertificate certificate;
        private final PhpOwnershipDomain.OwnedValue root;
        private boolean closed,sealed;
        private Allocation(ArrayMemory array) throws Throwable {
            this.array=array;certificate=new StorageCertificate(PhpOwnershipArrays.this,array);
            array.attachOwnershipStorage(certificate);
            root=domain().hostValue(certificate.node,array);
        }
        public ArrayMemory array() {open();return array;}
        private boolean restorationClaimed;
        public RestorationAssembly restoration() {
            building();if (restorationClaimed) throw new IllegalStateException("Restoration assembly was already claimed");
            restorationClaimed=true;return new RestorationAssembly(array,certificate,null);
        }
        public StorageCertificate certificate() {open();return certificate;}
        public PhpOwnershipValues.Binding binding() {open();return PhpOwnershipValues.bindValue(domain(),array,certificate.node);}
        private void open() {domain().currentContext();if (closed) throw new IllegalStateException("Array preparation is closed");}
        private void building() {
            open();if (sealed || certificate.node.incoming() != 1)
                throw new IllegalStateException("Only an unpublished staged array can be populated");
            certificate.validateAll(array);
        }
        /** Construction only; arbitrary existing-array insertion needs the later backend mutation hooks. */
        public Entry append(PhpOwnershipValues.Binding value) throws Throwable {
            building();candidate(value);Entry[] result={null};
            domain().graph().deferDestruction(() -> {
                certificate.constructing=true;
                try {result[0]=certificate.attach(array.add(value.rawValue()),value);}
                finally {certificate.constructing=false;}
            });
            return result[0];
        }
        public Entry insertBucket(String key, PhpOwnershipValues.Binding value) throws Throwable {
            building();candidate(value);Objects.requireNonNull(key);
            if (array.size() != 0 && array.isList())
                throw new IllegalStateException("List-to-map edge transfer needs the complete backend adapter");
            Entry[] result={null};
            domain().graph().deferDestruction(() -> {
                certificate.constructing=true;
                try {result[0]=certificate.attach(array.insertBucket(key,value.rawValue()),value);}
                finally {certificate.constructing=false;}
            });
            return result[0];
        }
        public PhysicalSlot physicalSlot(Object actualScalarKey) {
            open();certificate.validateAll(array);ReferenceMemory carrier=array.getByScalar(actualScalarKey);
            if (carrier == null) return null;Entry entry=certificate.entries.get(carrier);
            return entry.selfCell ? new PhysicalSlot(entry,array,((Memory)actualScalarKey).toInteger(),certificate) : new PhysicalSlot(entry);
        }
        /** A list holder can point at the exact canonical CELL without giving that CELL a second stamp. */
        public PhysicalSlot appendCanonical(PhpOwnershipValues.Binding cell) throws Throwable {
            building();candidate(cell);
            if (cell.role() != PhpOwnershipValues.Role.CELL || !array.isList())
                throw new IllegalArgumentException("Canonical-carrier insertion requires an unpublished list and CELL");
            if (certificate.entries.containsKey(cell.rawValue()))
                throw new IllegalArgumentException("Repeated exact CELL carriers need distinct physical wrappers");
            Entry[] result={null};
            domain().graph().deferDestruction(() -> {
                certificate.constructing=true;
                try {result[0]=certificate.attach(array.appendOwnershipCanonical((ReferenceMemory)cell.rawValue()),cell);}
                finally {certificate.constructing=false;}
            });
            return new PhysicalSlot(result[0],array,array.size()-1,certificate);
        }
        public PhpOwnershipHeap.Publication preparePublication(PhpOwnershipHeap.Publisher destination, PhpOwnershipHeap.Snapshot expected) {
            open();certificate.validateAll(array);
            if (destination.domain() != domain()) throw new IllegalArgumentException("Destination belongs to another domain");
            PhpOwnershipHeap.Publication publication=destination.prepare(expected,binding());sealed=true;return publication;
        }
        @Override public void close() throws Exception {if (!closed) {domain().currentContext();closed=true;root.close();}}
    }
    public final class ArrayWrite implements AutoCloseable {
        private final PhpOwnershipHeap.Publisher owner;
        private final PhpOwnershipHeap.Snapshot expected;
        private final ArrayMemory array;
        private final StorageCertificate certificate;
        private final Allocation staged;
        private boolean closed, stagedEvaluation;
        private ArrayWrite(PhpOwnershipHeap.Publisher owner, PhpOwnershipHeap.Snapshot expected, ArrayMemory array,
                           StorageCertificate certificate, Allocation staged) {
            this.owner=owner;this.expected=expected;this.array=array;this.certificate=certificate;this.staged=staged;
        }
        private void open() {domain().currentContext();if (closed) throw new IllegalStateException("Array write preparation is closed");}
        public void requireOwner(PhpOwnershipHeap.Publisher actual) {
            open();
            if (actual != owner || owner.current() != expected)
                throw new IllegalArgumentException("Array write does not identify this current exact owner");
            candidate(expected.binding());certificate.validateAll(array);
        }
        public ArrayMemory array() {open();return array;}
        public PhpOwnershipValues.Binding binding() {open();return PhpOwnershipValues.bindValue(domain(),array,certificate.node);}
        public boolean separated() {open();return staged != null;}
        /** Convert this fresh COW allocation's lease into its actual ordinary producer EVAL. */
        public Memory stageAllocation(PhpRuntimeOwnership.Frame frame,int evaluationId) throws Throwable {
            open();
            if (staged == null || stagedEvaluation || frame == null || frame.authority().domain() != domain()
                    || frame.authority().currentFrame() != frame || owner.current() != expected)
                throw new IllegalArgumentException("COW staging requires its current exact owner and fresh allocation");
            certificate.validateAll(array);stagedEvaluation=true;
            return frame.stageAllocation(evaluationId,staged);
        }
        public StorageCertificate certificate() {open();return certificate;}
        public Entry entry(Object actualScalarKey) {
            open();certificate.validateAll(array);ReferenceMemory carrier=array.getByScalar(actualScalarKey);
            return carrier == null ? null : certificate.entries.get(carrier);
        }
        public PhysicalSlot physicalSlot(Object actualScalarKey) {
            Entry entry=entry(actualScalarKey);if (entry == null) return null;
            if (entry.selfCell) {
                if (!array.isList() || !(actualScalarKey instanceof Memory))
                    throw new IllegalStateException("Canonical carrier needs its certified list address");
                return new PhysicalSlot(entry,array,((Memory)actualScalarKey).toInteger(),certificate);
            }
            return new PhysicalSlot(entry);
        }
        /** Append through this exact addressed storage, including reentry after a committed removal. */
        public Entry append(PhpOwnershipValues.Binding value) throws Throwable {
            open();var current=owner.current();
            if (current.binding().rawValue() != array || current.binding().node() != certificate.node)
                throw new IllegalArgumentException("Append requires its currently published exact VALUE owner");
            certificate.validateAll(array);candidate(value);Entry[] result={null};
            domain().graph().deferDestruction(() -> {
                certificate.constructing=true;
                try {result[0]=certificate.attach(array.add(value.rawValue()),value);}
                finally {certificate.constructing=false;}
            });return result[0];
        }
        /** Caller supplies an already evaluated scalar key; no PHP diagnostic runs inside publication. */
        public UnsetPublication prepareUnset(Object actualScalarKey) {
            open();
            if (!(actualScalarKey instanceof String) && !(actualScalarKey instanceof php.runtime.memory.LongMemory)
                    && !(actualScalarKey instanceof Long) && !(actualScalarKey instanceof Integer))
                throw new IllegalArgumentException("Unset requires an already normalized integer/string key");
            var current=owner.current();
            if (current.binding().rawValue() != array || current.binding().node() != certificate.node)
                throw new IllegalArgumentException("Removal requires its currently published exact VALUE owner");
            return new UnsetPublication(array,certificate,null,actualScalarKey,owner,current);
        }
        public PhpOwnershipHeap.Publication preparePublication() {
            open();
            if (stagedEvaluation) throw new IllegalStateException("Staged COW requires its actual EVAL transfer publication");
            certificate.validateAll(array);return owner.prepare(expected,binding());
        }
        @Override public void close() throws Exception {if (!closed) {domain().currentContext();closed=true;if (staged != null) staged.close();}}
    }
    public static final class StorageCertificate {
        private final PhpOwnershipArrays adapter;
        private final Object identity;
        private final PhpOwnership.Node node;
        private IdentityHashMap<ReferenceMemory,Entry> entries=new IdentityHashMap<>();
        private boolean constructing;
        private long revision;
        private StorageCertificate(PhpOwnershipArrays adapter, ArrayMemory array) {
            this.adapter=adapter;identity=array.getStorageIdentity();node=adapter.domain().storage(identity);
        }
        public PhpOwnershipDomain domain() {return adapter.domain();}
        public Object backingIdentity() {return identity;}
        public PhpOwnership.Node node() {domain().currentContext();return node;}
        public void validateAttachment(ArrayMemory array) {
            domain().currentContext();
            if (array.getClass() != ArrayMemory.class || array.getStorageIdentity() != identity || node.state() != PhpOwnership.State.LIVE)
                throw new IllegalArgumentException("Certificate does not identify this ordinary backing allocation");
        }
        /** Ordinary APIs cannot mutate certified backing data before the full backend hooks exist. */
        public void checkMutation(ArrayMemory array) {
            validateAttachment(array);
            if (!constructing) throw new IllegalStateException("Certified array mutation requires its exact ownership publisher");
        }
        private Entry attach(ReferenceMemory carrier, PhpOwnershipValues.Binding binding) throws Throwable {
            if (entries.containsKey(carrier)) throw new IllegalStateException("Physical entry was attached twice");
            boolean self=carrier == binding.rawValue() && binding.role() == PhpOwnershipValues.Role.CELL;
            if (!self && carrier.getRawValue() != binding.rawValue()) throw new IllegalArgumentException("Entry payload differs from certificate");
            Entry entry=adapter.new Entry(node,carrier,binding,self);
            if (self) adapter.certificate(carrier).attachOwnerFacet(node,entry);
            entries.put(carrier,entry);revision++;return entry;
        }
        private void validateAll(ArrayMemory array) {
            validateAttachment(array);int count=0;
            for (ReferenceMemory carrier : array) {
                Entry entry=entries.get(carrier);
                if (entry == null) throw new IllegalStateException("Array changed outside its explicit entry publishers");
                entry.current();count++;
            }
            if (count != entries.size() || count != array.size()) throw new IllegalStateException("Array physical layout changed outside its certificate");
        }
    }
    /** Parser construction authority; metadata addresses never themselves own PHP values. */
    public final class RestorationAssembly implements AutoCloseable {
        private final ArrayMemory array;
        private final StorageCertificate storage;
        private final PhpOwnershipObjects.PropertiesCertificate table;
        private final long freshRevision;
        private boolean closed,preparingFresh=true,producerClaimed;
        private RestorationAssembly(ArrayMemory array,StorageCertificate storage,PhpOwnershipObjects.PropertiesCertificate table) {
            this.array=array;this.storage=storage;this.table=table;validate();
            if (storage != null) {
                if (array.size() != 0) throw new IllegalArgumentException("Native restoration requires a fresh empty ARRAY producer");
                array.initializeOwnershipRestorationMixed(this);
            }
            freshRevision=storage == null ? table.removalRevision() : storage.revision;
            preparingFresh=false;
        }
        public void validateFreshLayout(ArrayMemory actual) {
            if (!preparingFresh || actual != array || storage == null || array.size() != 0)
                throw new IllegalArgumentException("Not this fresh parser ARRAY allocation");validate();
        }
        private void validate() {
            domain().currentContext();if (closed) throw new IllegalStateException("Restoration assembly is closed");
            if (storage != null) storage.validateAll(array);else table.validateAll();
        }
        public PhpOwnershipDomain domain() {return PhpOwnershipArrays.this.domain();}
        public boolean matchesArray(ArrayMemory actual) {validate();return storage != null && actual == array;}
        public boolean matchesTable(ArrayMemory actual) {validate();return table != null && actual == array;}
        private void freshProducer(PhpOwnershipValues.Binding value) {
            validate();candidate(value);
            if (producerClaimed || value.role() != PhpOwnershipValues.Role.VALUE || value.node() == null
                    || value.node().incoming() != 1 || freshRevision != (storage == null ? table.removalRevision() : storage.revision)
                    || (storage != null ? value.rawValue() != array || value.node() != storage.node
                    : !(value.rawValue() instanceof php.runtime.memory.ObjectMemory)
                    || ((php.runtime.memory.ObjectMemory)value.rawValue()).value.getProperties() != array))
                throw new IllegalArgumentException("Pending restoration needs its exact fresh ARRAY/OBJECT producer assembly");
        }
        public RestorationAddress address(Object normalizedKey) {
            validate();
            if (table == null && !(normalizedKey instanceof php.runtime.memory.LongMemory) && !(normalizedKey instanceof String))
                throw new IllegalArgumentException("Restoration array key must be a normalized integer or string");
            if (table != null && !(normalizedKey instanceof String)) throw new IllegalArgumentException("Restoration TABLE keys are literal strings");
            return new RestorationAddress(this,normalizedKey);
        }
        @Override public void close() {domain().currentContext();closed=true;}
    }
    RestorationAssembly restorationTable(PhpOwnershipObjects.PropertiesCertificate table) {
        Objects.requireNonNull(table);if (table.node().graph() != domain().graph()) throw new IllegalArgumentException("Foreign restoration TABLE");
        return new RestorationAssembly(table.table(),null,table);
    }
    public final class RestorationAddress {
        private final RestorationAssembly assembly;
        private final Object key;
        private RestorationAddress(RestorationAssembly assembly,Object key) {this.assembly=assembly;this.key=key;}
        private ReferenceMemory carrier() {
            ReferenceMemory current=assembly.array.getByScalar(key);
            if (current == null && assembly.table != null && ((PhpObjectProperties)assembly.array).declaration((String)key) != null)
                for (ReferenceMemory entry:assembly.array) if (entry instanceof php.runtime.memory.support.ArrayMapEntryMemory
                        && key.equals(((php.runtime.memory.support.ArrayMapEntryMemory)entry).getKey())) return entry;
            return current;
        }
        public RestorationSnapshot current() {
            assembly.validate();ReferenceMemory carrier=carrier();
            Entry entry=carrier == null ? null : assembly.storage != null ? assembly.storage.entries.get(carrier)
                    : assembly.table.entry(carrier).owner;
            return new RestorationSnapshot(this,assembly.array.getStorageIdentity(),assembly.storage == null ? assembly.table.removalRevision() : assembly.storage.revision,
                    carrier,entry,entry == null ? null : entry.current());
        }
        private void expected(RestorationSnapshot expected) {
            assembly.validate();
            if (expected == null || expected.address != this || expected.identity != assembly.array.getStorageIdentity()
                    || expected.revision != (assembly.storage == null ? assembly.table.removalRevision() : assembly.storage.revision)
                    || expected.carrier != carrier()) throw new IllegalArgumentException("Restoration placement address is stale");
            if (expected.entry != null) expected.entry.expected(expected.owner);
        }
        public PhysicalSlot resolve() {
            var snapshot=current();if (snapshot.entry == null) return null;
            return snapshot.entry.selfCell ? new PhysicalSlot(snapshot.entry,assembly.array,(int)((php.runtime.memory.LongMemory)key).value,assembly.storage)
                    : new PhysicalSlot(snapshot.entry);
        }
        public RestorationPublication preparePut(RestorationSnapshot expected,PhpOwnershipValues.Binding value) throws Throwable {
            if (value.role() != PhpOwnershipValues.Role.VALUE) throw new IllegalArgumentException("VALUE restoration requires a VALUE binding");
            return new RestorationPublication(this,expected,value,null,null,null);
        }
        public RestorationPublication prepareReference(RestorationSnapshot expected,PhpOwnershipValues.Binding cell) throws Throwable {
            if (cell.role() != PhpOwnershipValues.Role.CELL) throw new IllegalArgumentException("R placement requires a canonical CELL");
            return new RestorationPublication(this,expected,cell,null,null,null);
        }
        public ReferencePublication preparePromotion(RestorationSnapshot expected) throws Throwable {
            expected(expected);var physical=resolve();if (physical == null) throw new IllegalArgumentException("R refers to a missing placement");
            return physical.preparePromotion(expected.owner);
        }
        public RestorationClearPublication prepareClearTo(RestorationSnapshot expected,PhpOwnershipFramePublisher cleanup,PhpOwnershipHeap.Snapshot expectedCleanup) {
            return new RestorationClearPublication(this,expected,cleanup,expectedCleanup);
        }
        public RestorationClearPublication prepareClearTo(RestorationSnapshot expected,PhpRuntimeOwnership.NativeProducer cleanup,PhpOwnershipHeap.Snapshot expectedCleanup) {
            return new RestorationClearPublication(this,expected,null,cleanup,expectedCleanup);
        }
        public RestorationPublication prepareMoveFrom(RestorationSnapshot expected,PhpRuntimeOwnership.NativeProducer source,PhpOwnershipHeap.Snapshot expectedSource) throws Throwable {
            if (source == null || source.domain() != domain() || expectedSource == null || source.current() != expectedSource || expectedSource.binding() == null)
                throw new IllegalArgumentException("Foreign, stale or empty native restoration producer");
            return new RestorationPublication(this,expected,expectedSource.binding(),null,source,expectedSource,null);
        }
        public RestorationPublication preparePendingMoveFrom(RestorationSnapshot expected,PhpRuntimeOwnership.NativeProducer source,
                PhpOwnershipHeap.Snapshot expectedSource,RestorationAssembly childOrigin) throws Throwable {
            if (source == null || source.domain() != domain() || expectedSource == null || source.current() != expectedSource || expectedSource.binding() == null
                    || childOrigin == null || childOrigin == assembly || childOrigin.domain() != domain() || source.assembly() != childOrigin)
                throw new IllegalArgumentException("Pending native restoration requires its exact fresh producer assembly");
            childOrigin.freshProducer(expectedSource.binding());
            return new RestorationPublication(this,expected,expectedSource.binding(),null,source,expectedSource,childOrigin);
        }
        public RestorationPublication prepareMoveFrom(RestorationSnapshot expected,PhpOwnershipFramePublisher source,PhpOwnershipHeap.Snapshot expectedSource) throws Throwable {
            if (source == null || source.domain() != domain() || source.slot().role() != PhpOwnershipFrame.Role.EVAL_TEMP
                    || expectedSource == null || source.current() != expectedSource)
                throw new IllegalArgumentException("Foreign or stale restoration producer");
            if (expectedSource.binding() == null) throw new IllegalArgumentException("Restoration producer is empty");
            return new RestorationPublication(this,expected,expectedSource.binding(),source,expectedSource,null);
        }
        public RestorationPublication preparePendingMoveFrom(RestorationSnapshot expected,PhpOwnershipFramePublisher source,
                PhpOwnershipHeap.Snapshot expectedSource,RestorationAssembly childOrigin) throws Throwable {
            if (source == null || source.domain() != domain() || source.slot().role() != PhpOwnershipFrame.Role.EVAL_TEMP
                    || expectedSource == null || source.current() != expectedSource || expectedSource.binding() == null
                    || childOrigin == null || childOrigin == assembly || childOrigin.domain() != domain())
                throw new IllegalArgumentException("Pending restoration requires an exact fresh EVAL_TEMP producer");
            childOrigin.freshProducer(expectedSource.binding());
            return new RestorationPublication(this,expected,expectedSource.binding(),source,expectedSource,childOrigin);
        }
    }
    public static final class RestorationSnapshot {
        private final RestorationAddress address;
        private final Object identity;
        private final long revision;
        private final ReferenceMemory carrier;
        private final Entry entry;
        private final PhpOwnershipHeap.Snapshot owner;
        private RestorationSnapshot(RestorationAddress address,Object identity,long revision,ReferenceMemory carrier,Entry entry,PhpOwnershipHeap.Snapshot owner) {
            this.address=address;this.identity=identity;this.revision=revision;this.carrier=carrier;this.entry=entry;this.owner=owner;
        }
        public PhpOwnershipValues.Binding binding() {return owner == null ? null : owner.binding();}
        public long revision() {return revision;}
    }
    /** One real overwritten zval moves to parser cleanup custody before the next value is parsed. */
    public final class RestorationClearPublication implements PhpOwnershipHeap.Publication {
        private final RestorationAddress address;
        private final RestorationSnapshot expected;
        private final PhpOwnershipFramePublisher cleanup;
        private final PhpRuntimeOwnership.NativeDestination nativeDestination;
        private final PhpOwnershipHeap.Snapshot expectedCleanup,cleanupAfter,entryAfter;
        private final ReferenceMemory.OwnershipRestorationClear sourceChange;
        private final Memory oldRaw;
        private PhpOwnershipFrame.Publication delegate;
        private PhpOwnership.Publication nativeDelegate;
        private boolean stored,finished,committed,committing;
        private RestorationClearPublication(RestorationAddress address,RestorationSnapshot expected,PhpOwnershipFramePublisher cleanup,PhpOwnershipHeap.Snapshot expectedCleanup) {
            this(address,expected,Objects.requireNonNull(cleanup),null,expectedCleanup);
        }
        private RestorationClearPublication(RestorationAddress address,RestorationSnapshot expected,PhpOwnershipFramePublisher cleanup,
                PhpRuntimeOwnership.NativeProducer nativeCleanup,PhpOwnershipHeap.Snapshot expectedCleanup) {
            this.address=address;this.expected=expected;this.cleanup=cleanup;this.expectedCleanup=expectedCleanup;
            address.expected(expected);
            if (expected.entry == null || expected.entry.selfCell || expectedCleanup == null || expectedCleanup.binding() != null
                    || cleanup != null && (cleanup.domain() != domain() || cleanup.slot().role() != PhpOwnershipFrame.Role.EVAL_TEMP || cleanup.current() != expectedCleanup)
                    || nativeCleanup != null && nativeCleanup.domain() != domain() || (cleanup == null) == (nativeCleanup == null))
                throw new IllegalArgumentException("Cleanup requires an existing physical placement and empty exact TEMP");
            nativeDestination=nativeCleanup == null ? null : nativeCleanup.requireRestorationDestination(expectedCleanup);
            if (expected.revision == Long.MAX_VALUE || expected.owner.revision() == Long.MAX_VALUE || expectedCleanup.revision() == Long.MAX_VALUE)
                throw new IllegalStateException("Restoration cleanup revision overflow");
            var binding=expected.binding();candidate(binding);oldRaw=binding.rawValue();
            sourceChange=expected.carrier.prepareOwnershipRestorationClear(expected.entry.unsetSource);
            entryAfter=new PhpOwnershipHeap.Snapshot(expected.entry,PhpOwnershipValues.bindValue(domain(),Memory.NULL,null),expected.owner.revision()+1);
            cleanupAfter=new PhpOwnershipHeap.Snapshot(cleanup == null ? nativeCleanup : cleanup,binding,expectedCleanup.revision()+1);
            if (nativeDestination != null) nativeDestination.prepareAfter(cleanupAfter);
            validate();
        }
        private void validate() {
            address.expected(expected);sourceChange.validate();
            if (cleanup != null && cleanup.current() != expectedCleanup) throw new IllegalArgumentException("Cleanup owner was changed");
            if (nativeDestination != null) nativeDestination.validate();
        }
        public ReferenceMemory.OwnershipRestorationClear sourceChange() {return sourceChange;}
        public void consumeCarrier(ReferenceMemory carrier) {
            domain().graph().checkExecutionAccess();
            if (finished || stored || carrier != expected.carrier || delegate == null && nativeDelegate == null
                    || delegate != null && delegate.isClosed() || nativeDelegate != null && nativeDelegate.isClosed())
                throw new IllegalArgumentException("Wrong pending restoration cleanup carrier");
            stored=true;
        }
        public void store() {
            if (stored || finished) throw new IllegalStateException("Restoration cleanup was consumed");validate();
            if (nativeDestination == null) delegate=cleanup.prepareTransferFromEdge(expectedCleanup,expected.entry.edge,oldRaw);
            else nativeDelegate=expected.entry.edge.prepareTransferTo(nativeDestination.slot());
            expected.carrier.publishOwnershipRestorationClear(this);
        }
        public boolean validatesTableCommit(PhpOwnershipObjects.PropertiesCertificate table) {return table == address.assembly.table && committing && stored && !finished;}
        @Override public boolean isCommitted() {return committed;}
        @Override public void commit() throws Throwable {
            domain().graph().checkExecutionAccess();if (!stored || finished) throw new IllegalStateException("Raw NULL publication must precede cleanup commit");
            expected.entry.snapshot=entryAfter;expected.entry.unsetSource=sourceChange.declaration();
            if (address.assembly.storage != null) address.assembly.storage.revision++;
            else {committing=true;address.assembly.table.publishRestorationClear(this);committing=false;}
            if (nativeDestination == null) cleanup.publishTransferredSnapshot(cleanupAfter);else nativeDestination.publishAfterRaw(cleanupAfter);
            finished=true;committed=true;if (nativeDelegate != null) nativeDelegate.commit();else delegate.commit();
        }
        @Override public void close() throws Exception {
            if (finished) return;if (stored) throw new IllegalStateException("Stored cleanup cannot be aborted");
            if (delegate != null) delegate.close();if (nativeDelegate != null) nativeDelegate.close();finished=true;
        }
    }
    /** Exact producer-to-physical-edge publication. Owner replacement never assigns through an old CELL. */
    public final class RestorationPublication implements PhpOwnershipHeap.Publication {
        private final RestorationAddress address;
        private final RestorationSnapshot expected;
        private final PhpOwnershipFramePublisher source;
        private final PhpRuntimeOwnership.FacetSource nativeSource;
        private final RestorationAssembly childOrigin;
        private final PhpOwnershipHeap.Snapshot sourceExpected,sourceAfter;
        private final PhpOwnershipValues.Binding next;
        private final Memory nextRaw;
        private final ReferenceMemory.OwnershipRestoration sourceChange;
        private final ReferenceMemory.OwnershipReferenceChange referenceChange;
        private final Entry referencePayload;
        private final PhpOwnershipHeap.Snapshot referenceBefore;
        private final ArrayMemory.OwnershipRestorationLayout layout;
        private final Entry destination;
        private final RestorationCompletion completion;
        private final PhpOwnershipHeap.Snapshot after;
        private final IdentityHashMap<ReferenceMemory,Entry> nextEntries;
        private final IdentityHashMap<ReferenceMemory,PhysicalSlot> nextTableEntries;
        private final ReferenceMemory[] oldCarriers,freshCarriers;
        private final Entry[] relocated;
        private final PhpOwnershipHeap.Snapshot[] relocationBefore,relocationAfter;
        private php.runtime.memory.support.ArrayMemoryMap preparedMap;
        private PhpOwnership.Publication delegate;
        private PhpOwnershipFrame.Publication transfer;
        private PhpOwnership.Publication nativeTransfer;
        private boolean preparing=true,stored,layoutStored,finished,committed,newEntry,committing;
        private RestorationPublication(RestorationAddress address,RestorationSnapshot expected,PhpOwnershipValues.Binding value,
                PhpOwnershipFramePublisher source,PhpOwnershipHeap.Snapshot sourceExpected,RestorationAssembly childOrigin) throws Throwable {
            this(address,expected,value,source,null,sourceExpected,childOrigin);
        }
        private RestorationPublication(RestorationAddress address,RestorationSnapshot expected,PhpOwnershipValues.Binding value,
                PhpOwnershipFramePublisher source,PhpRuntimeOwnership.NativeProducer nativeProducer,PhpOwnershipHeap.Snapshot sourceExpected,RestorationAssembly childOrigin) throws Throwable {
            this.address=address;this.expected=expected;this.source=source;this.sourceExpected=sourceExpected;this.childOrigin=childOrigin;
            address.expected(expected);candidate(value);
            nativeSource=nativeProducer == null ? null : nativeProducer.requireRestorationSource(sourceExpected,value);
            if (expected.revision == Long.MAX_VALUE || sourceExpected != null && sourceExpected.revision() == Long.MAX_VALUE)
                throw new IllegalStateException("Restoration revision overflow");
            sourceChange=value.role() != PhpOwnershipValues.Role.VALUE || address.assembly.table == null || expected.carrier == null ? null
                    : childOrigin == null ? expected.carrier.prepareOwnershipRestoration(value.rawValue(),expected.entry.unsetSource)
                    : expected.carrier.prepareOwnershipPendingRestoration(this,value.rawValue(),expected.entry.unsetSource);
            Memory checked=sourceChange == null ? value.rawValue() : sourceChange.checked();
            next=checked == value.rawValue() ? value : PhpOwnershipValues.bindValue(domain(),checked,null);candidate(next);nextRaw=next.rawValue();
            if ((source != null || nativeSource != null) && next.node() != sourceExpected.binding().node()) throw new IllegalArgumentException("Restoration coercion changed the producer allocation");
            address.expected(expected);if (source != null && source.current() != sourceExpected) throw new IllegalArgumentException("Restoration producer changed during validation");
            layout=address.assembly.array.prepareOwnershipRestorationLayout(this,address.key);preparing=false;
            if (next.role() == PhpOwnershipValues.Role.CELL) {
                var canonical=(ReferenceMemory)next.rawValue();referencePayload=certificate(canonical).value;referenceBefore=referencePayload.current();
                referenceChange=expected.entry != null && expected.entry.unsetSource != null
                        ? layout.carrier().prepareOwnershipRevivedReference(canonical,false,expected.entry.unsetSource)
                        : layout.carrier().prepareOwnershipReference(canonical,false);
            } else {referenceChange=null;referencePayload=null;referenceBefore=null;}
            var storage=address.assembly.storage;
            nextEntries=storage == null ? null : new IdentityHashMap<>(storage.entries);
            nextTableEntries=storage == null ? address.assembly.table.prepareRestorationEntries(this) : null;
            var relocations=layout.relocations();oldCarriers=relocations.keySet().toArray(new ReferenceMemory[0]);
            freshCarriers=new ReferenceMemory[oldCarriers.length];relocated=new Entry[oldCarriers.length];
            relocationBefore=new PhpOwnershipHeap.Snapshot[oldCarriers.length];relocationAfter=new PhpOwnershipHeap.Snapshot[oldCarriers.length];
            for (int i=0;i<oldCarriers.length;i++) {
                var old=oldCarriers[i];freshCarriers[i]=relocations.get(old);relocated[i]=storage.entries.get(old);relocationBefore[i]=relocated[i].current();
                if (relocationBefore[i].revision() == Long.MAX_VALUE) throw new IllegalStateException("Relocated holder revision overflow");
                relocationAfter[i]=new PhpOwnershipHeap.Snapshot(relocated[i],relocationBefore[i].binding(),relocationBefore[i].revision()+1);
                nextEntries.remove(old);nextEntries.put(freshCarriers[i],relocated[i]);
            }
            destination=expected.entry == null ? new Entry(storage == null ? address.assembly.table.node() : storage.node,layout.carrier(),PhpOwnershipValues.bindValue(domain(),Memory.NULL,null)) : expected.entry;
            newEntry=expected.entry == null;
            if (destination.snapshot.revision() == Long.MAX_VALUE) throw new IllegalStateException("Restoration holder revision overflow");
            after=new PhpOwnershipHeap.Snapshot(destination,next,destination.snapshot.revision()+1);
            completion=childOrigin == null ? null : new RestorationCompletion(address,destination,next,sourceChange == null ? null : sourceChange.pendingDeclaration());
            if (nextEntries != null) nextEntries.put(layout.carrier(),destination);
            else nextTableEntries.put(layout.carrier(),new PhysicalSlot(destination));
            sourceAfter=source == null ? null : new PhpOwnershipHeap.Snapshot(source,null,sourceExpected.revision()+1);
            validate();
        }
        public ReferenceMemory existingCarrier() {if (!preparing) throw new IllegalStateException("Layout preparation is over");return expected.carrier;}
        public void validatePendingPreparation(ReferenceMemory actual,Memory value) {
            if (!preparing || childOrigin == null || actual != expected.carrier || value != sourceExpected.binding().rawValue())
                throw new IllegalArgumentException("Wrong pending typed restoration origin");
            address.expected(expected);childOrigin.freshProducer(sourceExpected.binding());
        }
        public RestorationCompletion pendingCompletion() {
            if (!committed || completion == null) throw new IllegalStateException("Pending placement has not committed");return completion;
        }
        public Memory relocationValue(ReferenceMemory carrier) {
            if (!preparing || address.assembly.storage == null) throw new IllegalArgumentException("Not an actual ordinary array relocation");
            return address.assembly.storage.entries.get(carrier).current().binding().rawValue();
        }
        public void validateArrayPreparation(ArrayMemory actual) {
            if (!preparing || actual != address.assembly.array) throw new IllegalArgumentException("Wrong raw layout preparation origin");address.expected(expected);
        }
        public void validateMapPreparation(php.runtime.memory.support.ArrayMemoryMap actual) {
            if (!preparing || preparedMap != null) throw new IllegalArgumentException("Wrong actual map preparation");preparedMap=actual;
        }
        public boolean validatesTablePreparation(PhpOwnershipObjects.PropertiesCertificate table) {return table == address.assembly.table && !stored && !finished;}
        private void validate() {
            address.expected(expected);layout.validate();candidate(next);
            if (sourceChange != null) sourceChange.validate();
            if (referenceChange != null) {referencePayload.expected(referenceBefore);referenceChange.validate();}
            if (source != null && source.current() != sourceExpected) throw new IllegalArgumentException("Restoration producer snapshot is stale");
            if (nativeSource != null) nativeSource.validate();
            if (childOrigin != null) childOrigin.freshProducer(sourceExpected.binding());
            for (int i=0;i<relocated.length;i++) relocated[i].expected(relocationBefore[i]);
        }
        public ReferenceMemory.OwnershipRestoration sourceChange() {return sourceChange;}
        public Memory consumeCarrier(ReferenceMemory carrier) {
            domain().graph().checkExecutionAccess();
            if (finished || stored || carrier != layout.carrier() || (transfer == null && nativeTransfer == null && delegate == null)) throw new IllegalArgumentException("Wrong pending restoration carrier");
            stored=true;return nextRaw;
        }
        public void consumeLayout(ArrayMemory actual,ArrayMemory.OwnershipRestorationLayout plan) {
            domain().graph().checkExecutionAccess();
            if (actual != address.assembly.array || plan != layout || !stored || layoutStored || finished) throw new IllegalArgumentException("Wrong pending restoration layout");layoutStored=true;
        }
        public void consumeMapInsertion(php.runtime.memory.support.ArrayMemoryMap actual,php.runtime.memory.support.ArrayMemoryMap.OwnershipInsertion insertion) {
            domain().graph().checkExecutionAccess();
            if (actual != preparedMap || !layoutStored || finished || insertion.carrier() != layout.carrier()) throw new IllegalArgumentException("Wrong pending restoration bucket");
        }
        public Entry consumeRelocation(ReferenceMemory old,ReferenceMemory fresh) {
            domain().graph().checkExecutionAccess();
            if (!layoutStored || finished || layout.relocations().get(old) != fresh) throw new IllegalArgumentException("Wrong restoration relocation");
            return address.assembly.storage.entries.get(old);
        }
        public void store() throws Throwable {
            if (stored || finished) throw new IllegalStateException("Restoration publication was consumed");validate();
            if (referenceChange != null && referenceChange.checkedTarget() != referenceBefore.binding().rawValue())
                throw new IllegalArgumentException("Strict restoration must not coerce an existing canonical payload");
            if (nativeSource != null) nativeTransfer=nativeSource.slot().prepareTransferTo(destination.edge);
            else if (source == null) delegate=destination.edge.preparePublication(next.node());
            else transfer=source.prepareTransferToEdge(sourceExpected,destination.edge);
            if (referenceChange == null) layout.carrier().publishOwnershipRestoration(this);
            else layout.carrier().publishOwnershipRestorationReference(referenceChange,this);
            layout.store(this);
            if (childOrigin != null) childOrigin.producerClaimed=true;
            for (int i=0;i<oldCarriers.length;i++) {
                var old=oldCarriers[i];var entry=relocated[i];old.relocateOwnershipRestoration(this,freshCarriers[i]);entry.carrier=freshCarriers[i];
                if (entry.selfCell) {var cell=old.getOwnershipCell();if (cell.ownerFacets != null) cell.ownerFacets.remove(address.assembly.storage.node);}
                entry.selfCell=false;entry.snapshot=relocationAfter[i];
            }
        }
        @Override public boolean isCommitted() {return committed;}
        @Override public void commit() throws Throwable {
            domain().graph().checkExecutionAccess();if (!stored || !layoutStored || finished) throw new IllegalStateException("Actual placement store must precede commit");
            destination.carrier=layout.carrier();destination.selfCell=false;destination.snapshot=after;destination.unsetSource=null;
            destination.pendingRestoration=completion;
            if (address.assembly.storage != null) {address.assembly.storage.entries=nextEntries;address.assembly.storage.revision++;}
            else {committing=true;address.assembly.table.publishRestorationEntries(this,nextTableEntries);committing=false;}
            if (source != null) source.publishTransferredSnapshot(sourceAfter);
            if (nativeSource != null) nativeSource.consumeAfterRaw();
            finished=true;committed=true;if (nativeTransfer != null) nativeTransfer.commit();else if (transfer != null) transfer.commit();else delegate.commit();
        }
        public boolean validatesTableCommit(PhpOwnershipObjects.PropertiesCertificate table) {return table == address.assembly.table && committing && !finished && layoutStored;}
        @Override public void close() throws Exception {
            if (finished) return;if (stored) throw new IllegalStateException("Actual placement cannot be aborted");
            if (delegate != null) delegate.close();if (transfer != null) transfer.close();if (nativeTransfer != null) nativeTransfer.close();finished=true;
            if (newEntry) try {destination.edge.close();} catch (Exception error) {throw error;} catch (Error error) {throw error;} catch (Throwable error) {throw new RuntimeException(error);}
        }
    }
    /** A pending parse owns through its real placement, while declaration metadata owns no old CELL. */
    public final class RestorationCompletion implements AutoCloseable {
        private final RestorationAddress address;
        private final Entry entry;
        private final PhpOwnershipValues.Binding child;
        private final ReferenceMemory.OwnershipDeclaration declaration;
        private boolean finished;
        private RestorationCompletion(RestorationAddress address,Entry entry,PhpOwnershipValues.Binding child,ReferenceMemory.OwnershipDeclaration declaration) {
            this.address=address;this.entry=entry;this.child=child;this.declaration=declaration;
        }
        private Entry validate(RestorationSnapshot expected) {
            address.expected(expected);
            if (finished || expected.entry != entry || entry.pendingRestoration != this)
                throw new IllegalArgumentException("Pending restoration placement was replaced or consumed");
            var payload=address.resolve().payloadPublisher();var value=payload.current().binding();
            if (value.role() != PhpOwnershipValues.Role.VALUE || value.node() != child.node() || value.rawValue() != child.rawValue())
                throw new IllegalArgumentException("Pending restoration payload was changed");return payload;
        }
        public RestorationCompletionPublication prepare(RestorationSnapshot expected) {
            return new RestorationCompletionPublication(this,expected,false);
        }
        public UnsetPublication prepareUnset(RestorationSnapshot expected) {
            validate(expected);
            if (address.assembly.table == null || declaration == null)
                throw new IllegalArgumentException("Only a pending typed declaration can fail completion");
            return new UnsetPublication(address.assembly.array,null,address.assembly.table,address.key,null,null);
        }
        /** Native malformed input retains a partial reference and restores its Source without a type check. */
        public void abort(RestorationSnapshot expected) throws Throwable {
            validate(expected);
            if (expected.binding().role() == PhpOwnershipValues.Role.CELL && declaration != null)
                try (var p=new RestorationCompletionPublication(this,expected,true)) {p.store();p.commit();}
            else close();
        }
        @Override public void close() {
            domain().currentContext();retire();
        }
        private void retire() {
            if (finished) return;
            if (entry.pendingRestoration == this) entry.pendingRestoration=null;finished=true;
        }
    }
    public final class RestorationCompletionPublication implements PhpOwnershipHeap.Publication {
        private final RestorationCompletion completion;
        private final RestorationSnapshot expected;
        private final Entry payload;
        private final PhpOwnershipHeap.Snapshot payloadExpected;
        private final ReferenceMemory.OwnershipRestorationCompletion change;
        private final boolean unchecked;
        private boolean preparing=true,stored,finished,committed;
        private RestorationCompletionPublication(RestorationCompletion completion,RestorationSnapshot expected,boolean unchecked) {
            this.completion=completion;this.expected=expected;this.unchecked=unchecked;
            payload=completion.validate(expected);payloadExpected=payload.current();
            change=completion.declaration == null ? null : completion.entry.carrier.prepareOwnershipRestorationCompletion(this);
            preparing=false;validate();
        }
        public ReferenceMemory.OwnershipDeclaration declaration() {return completion.declaration;}
        public boolean unchecked() {return unchecked;}
        public void validatePreparation(ReferenceMemory origin) {
            if (!preparing || origin != completion.entry.carrier || origin.getReference() != payload.carrier)
                throw new IllegalArgumentException("Wrong pending Source completion carrier");
            completion.validate(expected);payload.expected(payloadExpected);
        }
        private void validate() {
            if (finished || stored) throw new IllegalStateException("Source completion was consumed");
            completion.validate(expected);payload.expected(payloadExpected);if (change != null) change.validate();
        }
        public void consume(ReferenceMemory origin,ReferenceMemory.OwnershipRestorationCompletion actual) {
            domain().graph().checkExecutionAccess();validate();
            if (origin != completion.entry.carrier || actual != change) throw new IllegalArgumentException("Wrong pending Source completion");stored=true;
        }
        public void store() {
            validate();if (change == null) stored=true;else completion.entry.carrier.publishOwnershipRestorationCompletion(this,change);
        }
        @Override public boolean isCommitted() {return committed;}
        @Override public void commit() {
            domain().graph().checkExecutionAccess();if (!stored || finished) throw new IllegalStateException("Source store must precede completion commit");
            completion.close();finished=true;committed=true;
        }
        @Override public void close() {
            if (finished) return;if (stored) throw new IllegalStateException("Stored Source completion cannot be aborted");finished=true;
        }
    }
    /** TABLE has its own origin certificate; this adapter never invents a property declaration. */
    public UnsetPublication prepareTableUnset(PhpOwnershipObjects.PropertiesCertificate table,String literalKey) {
        Objects.requireNonNull(literalKey);table.validateAll();if (table.node().graph() != domain().graph()) throw new IllegalArgumentException("Foreign TABLE");
        return new UnsetPublication(table.table(),null,table,literalKey,null,null);
    }
    /** Private-origin exact address. Raw unlink/anchor store precedes metadata/edge retirement and drain. */
    public final class UnsetPublication implements PhpOwnershipHeap.Publication {
        private final ArrayMemory array;
        private final StorageCertificate storage;
        private final PhpOwnershipObjects.PropertiesCertificate table;
        private final Object identity,key;
        private final PhpOwnershipHeap.Publisher addressOwner;
        private final PhpOwnershipHeap.Snapshot addressSnapshot;
        private final ReferenceMemory removed;
        private final Entry entry;
        private final PhpOwnershipHeap.Snapshot expected,following;
        private final long revision;
        private final boolean declared;
        private final ReferenceMemory.OwnershipSourceRemoval sourceRemoval;
        private final ReferenceMemory.OwnershipDeclaration declaration;
        private final php.runtime.memory.support.ArrayMemoryMap replacement;
        private final IdentityHashMap<ReferenceMemory,Entry> nextEntries;
        private final IdentityHashMap<ReferenceMemory,ReferenceMemory> relocations=new IdentityHashMap<>();
        private final Set<String> nextUnset;
        private final ReferenceMemory[] oldCarriers,freshCarriers;
        private final Entry[] relocatedEntries;
        private final PhpOwnershipHeap.Snapshot[] relocationBefore,relocationAfter;
        private PhpOwnership.Publication delegate;
        private php.runtime.memory.support.ArrayMemoryMap capturedMap;
        private boolean started,stored,finished,committed,committing;
        private UnsetPublication(ArrayMemory array,StorageCertificate storage,PhpOwnershipObjects.PropertiesCertificate table,Object key,PhpOwnershipHeap.Publisher owner,PhpOwnershipHeap.Snapshot ownerSnapshot) {
            this.array=array;this.storage=storage;this.table=table;this.key=key;identity=array.getStorageIdentity();addressOwner=owner;addressSnapshot=ownerSnapshot;
            if (storage != null) storage.validateAll(array);else table.validateAll();
            revision=storage == null ? table.removalRevision() : storage.revision;
            if (revision == Long.MAX_VALUE) throw new IllegalStateException("Storage layout revision overflow");
            removed=array.getByScalar(key);
            entry=removed == null ? null : storage != null ? storage.entries.get(removed) : table.entry(removed).owner;
            expected=entry == null ? null : entry.current();
            declared=table != null && ((PhpObjectProperties)array).declaration((String)key) != null && removed != null;
            sourceRemoval=table == null || removed == null ? null : removed.prepareOwnershipSourceRemoval();
            declaration=declared && entry.pendingRestoration != null ? entry.pendingRestoration.declaration
                    : declared && sourceRemoval != null ? sourceRemoval.declaration() : null;
            if (expected != null && expected.revision() == Long.MAX_VALUE) throw new IllegalStateException("Entry revision overflow");
            following=declared ? new PhpOwnershipHeap.Snapshot(entry,PhpOwnershipValues.bindValue(domain(),Memory.UNDEFINED,null),expected.revision()+1) : null;
            nextUnset=declared ? Collections.unmodifiableSet(((PhpObjectProperties)array).prepareOwnershipUnsetKeys((String)key)) : null;
            nextEntries=storage == null ? null : new IdentityHashMap<>(storage.entries);
            if (nextEntries != null && removed != null) nextEntries.remove(removed);
            if (removed != null && array.isList()) {
                replacement=new php.runtime.memory.support.ArrayMemoryMap();int index=0;
                for (ReferenceMemory carrier : array) {
                    if (carrier != removed) {
                        Entry old=storage.entries.get(carrier);var binding=old.current().binding();
                        ReferenceMemory fresh=replacement.putWithEntry(new php.runtime.memory.LongMemory(index),binding.rawValue()).getB();
                        relocations.put(carrier,fresh);nextEntries.remove(carrier);nextEntries.put(fresh,old);
                    }
                    index++;
                }
            } else replacement=null;
            oldCarriers=relocations.keySet().toArray(new ReferenceMemory[0]);freshCarriers=new ReferenceMemory[oldCarriers.length];
            relocatedEntries=new Entry[oldCarriers.length];relocationBefore=new PhpOwnershipHeap.Snapshot[oldCarriers.length];relocationAfter=new PhpOwnershipHeap.Snapshot[oldCarriers.length];
            for (int i=0;i<oldCarriers.length;i++) {
                freshCarriers[i]=relocations.get(oldCarriers[i]);relocatedEntries[i]=storage.entries.get(oldCarriers[i]);
                relocationBefore[i]=relocatedEntries[i].current();
                if (relocationBefore[i].revision() == Long.MAX_VALUE) throw new IllegalStateException("Relocated holder revision overflow");
                relocationAfter[i]=new PhpOwnershipHeap.Snapshot(relocatedEntries[i],relocationBefore[i].binding(),relocationBefore[i].revision()+1);
            }
        }
        public boolean existed() {return removed != null;}
        public ReferenceMemory.OwnershipSourceRemoval sourceRemoval() {return sourceRemoval;}
        private void validate() {
            domain().currentContext();
            if (finished || stored || started || array.getStorageIdentity() != identity) throw new IllegalStateException("Removal permit is stale or consumed");
            if (addressOwner != null && addressOwner.current() != addressSnapshot) throw new IllegalArgumentException("Removal VALUE owner was rebound");
            if (storage != null) {storage.validateAll(array);if (storage.revision != revision) throw new IllegalArgumentException("Array layout revision changed");}
            else {table.validateAll();if (table.removalRevision() != revision) throw new IllegalArgumentException("TABLE layout revision changed");}
            if (array.getByScalar(key) != removed) throw new IllegalArgumentException("Removal address changed");
            if (entry != null) entry.expected(expected);
            if (sourceRemoval != null) sourceRemoval.validate();
            for (int i=0;i<relocatedEntries.length;i++) relocatedEntries[i].expected(relocationBefore[i]);
        }
        private void pending() {
            domain().graph().checkExecutionAccess();
            if (!started || finished || stored || delegate == null || delegate.isClosed()) throw new IllegalStateException("Removal has no pending raw permit");
        }
        public php.runtime.memory.support.ArrayMemoryMap consumeArray(ArrayMemory actual,php.runtime.memory.support.ArrayMemoryMap actualMap) {
            pending();if (actual != array || declared) throw new IllegalArgumentException("Wrong removal backing");
            capturedMap=actualMap;if (replacement != null) stored=true;
            return replacement;
        }
        public php.runtime.memory.support.ArrayMapEntryMemory mapEntry(php.runtime.memory.support.ArrayMemoryMap actual) {
            pending();if (actual != capturedMap || replacement != null || !(removed instanceof php.runtime.memory.support.ArrayMapEntryMemory)) throw new IllegalArgumentException("Not an actual map bucket");
            return (php.runtime.memory.support.ArrayMapEntryMemory)removed;
        }
        public void consumeMap(php.runtime.memory.support.ArrayMemoryMap actual,ReferenceMemory carrier) {
            pending();if (actual != capturedMap || carrier != removed) throw new IllegalArgumentException("Wrong physical removal carrier");stored=true;
        }
        public void consumeCarrier(ReferenceMemory carrier) {
            pending();if (!declared || carrier != removed) throw new IllegalArgumentException("Wrong declared anchor");stored=true;
        }
        public void consumeSource(ReferenceMemory carrier) {
            domain().graph().checkExecutionAccess();
            if (!stored || finished || sourceRemoval == null || carrier != removed) throw new IllegalArgumentException("Wrong property Source removal");
        }
        public Set<String> unsetKeys(PhpObjectProperties actual) {
            if (actual != array || !declared) throw new IllegalArgumentException("Wrong declared TABLE");return nextUnset;
        }
        public Entry consumeRelocation(ReferenceMemory old,ReferenceMemory fresh) {
            domain().graph().checkExecutionAccess();
            if (!stored || finished || relocations.get(old) != fresh) throw new IllegalArgumentException("Wrong carrier relocation permit");
            return storage.entries.get(old);
        }
        public void store() {
            validate();
            if (removed == null) {stored=true;return;}
            delegate=declared ? entry.edge.preparePublication(null) : entry.edge.prepareRetirement();started=true;
            if (declared) ((PhpObjectProperties)array).publishOwnershipDeclaredUnset(this,removed);
            else {array.publishOwnershipUnset(this);if (sourceRemoval != null) removed.publishOwnershipRemovalSource(this);}
            for (int i=0;i<oldCarriers.length;i++) {
                ReferenceMemory old=oldCarriers[i],fresh=freshCarriers[i];Entry address=relocatedEntries[i];
                old.relocateOwnershipEntry(this,fresh);address.carrier=fresh;
                if (address.selfCell) {var cell=old.getOwnershipCell();if (cell.ownerFacets != null) cell.ownerFacets.remove(storage.node);}
                address.selfCell=false;address.snapshot=relocationAfter[i];
            }
        }
        @Override public boolean isCommitted() {return committed;}
        @Override public void commit() throws Throwable {
            domain().graph().checkExecutionAccess();if (!stored || finished) throw new IllegalStateException("Actual unlink must precede commit");
            if (storage != null && removed != null) {storage.entries=nextEntries;storage.revision++;}
            if (table != null && removed != null) {committing=true;table.publishRemovalMetadata(this,removed,declared);committing=false;}
            if (declared) {entry.snapshot=following;entry.unsetSource=declaration;if (entry.pendingRestoration != null) entry.pendingRestoration.retire();}
            finished=true;committed=true;
            if (delegate != null) delegate.commit();
        }
        @Override public void close() throws Exception {
            if (finished) return;if (stored) throw new IllegalStateException("Actual unlink cannot be aborted");
            if (delegate != null) delegate.close();finished=true;
        }
        public boolean validatesTable(PhpOwnershipObjects.PropertiesCertificate actual,ReferenceMemory carrier,boolean anchor) {return actual == table && carrier == removed && anchor == declared && committing && stored && !finished;}
    }

    public final class CellAllocation implements AutoCloseable {
        private final ReferenceMemory cell;
        private final CellCertificate certificate;
        private final PhpOwnershipDomain.OwnedCell root;
        private boolean closed;
        private CellAllocation(PhpOwnershipValues.Binding value) throws Throwable {
            cell=new ReferenceMemory(value.rawValue()).getReferenceForBinding();
            certificate=new CellCertificate(PhpOwnershipArrays.this,cell);
            cell.attachOwnershipCell(certificate);root=domain().hostCell(certificate.node,cell);
            try {certificate.value=new Entry(certificate.node,cell,value);}
            catch (Throwable failure) {try {root.close();} catch (Throwable cleanup) {failure=domain().graph().composeFailure(failure,cleanup);}throw failure;}
        }
        private void open() {domain().currentContext();if (closed) throw new IllegalStateException("CELL preparation is closed");}
        public ReferenceMemory cell() {open();return cell;}
        public PhpOwnershipValues.Binding binding() {open();return PhpOwnershipValues.bindCell(domain(),cell,certificate.node);}
        public Entry value() {open();return certificate.value;}
        @Override public void close() throws Exception {if (!closed) {domain().currentContext();closed=true;root.close();}}
    }
    public static final class CellCertificate {
        private final PhpOwnershipArrays adapter;
        private final ReferenceMemory cell;
        private final PhpOwnership.Node node;
        private Entry value;
        // Address-facet cache only; weak metadata links never acquire or release PHP owners.
        private WeakHashMap<PhpOwnership.Node,java.lang.ref.WeakReference<Entry>> ownerFacets;
        private Entry ownerFacet(PhpOwnership.Node owner) {
            if (ownerFacets == null) return null;
            var reference=ownerFacets.get(owner);var entry=reference == null ? null : reference.get();
            return entry == null || entry.edge.isClosed() || owner.state() != PhpOwnership.State.LIVE ? null : entry;
        }
        private void attachOwnerFacet(PhpOwnership.Node owner,Entry entry) {
            if (ownerFacets == null) ownerFacets=new WeakHashMap<>();
            ownerFacets.put(owner,new java.lang.ref.WeakReference<>(entry));
        }
        private CellCertificate(PhpOwnershipArrays adapter, ReferenceMemory cell) {
            this.adapter=adapter;this.cell=cell;node=adapter.domain().canonicalCell(cell);
        }
        public PhpOwnershipDomain domain() {return adapter.domain();}
        public PhpOwnership.Node node() {domain().currentContext();return node;}
        public PhpOwnershipHeap.Publisher value() {domain().currentContext();value.current();return value;}
        public void validateAttachment(ReferenceMemory target) {
            domain().currentContext();
            if (target != cell || !cell.isCanonicalReferenceCell() || node.state() != PhpOwnership.State.LIVE)
                throw new IllegalArgumentException("Certificate does not identify this canonical CELL");
        }
    }

    /** Attach one actual container field, never a second payload stamp on an existing canonical CELL. */
    public PhysicalSlot attachPhysicalSlot(PhpOwnership.Node owner,ReferenceMemory carrier,PhpOwnershipValues.Binding value) throws Throwable {
        domain().currentContext();candidate(value);
        if (owner.graph() != domain().graph() || owner.kind() == PhpOwnership.Kind.CELL || owner.state() != PhpOwnership.State.LIVE)
            throw new IllegalArgumentException("Physical owner belongs to another domain or was released");
        if (carrier == value.rawValue() && value.role() == PhpOwnershipValues.Role.CELL) {
            var certificate=certificate(carrier);var existing=certificate.ownerFacet(owner);
            if (existing != null) throw new IllegalArgumentException("Canonical holder facet was already attached");
            var entry=new Entry(owner,carrier,value,true);certificate.attachOwnerFacet(owner,entry);return new PhysicalSlot(entry);
        }
        var existing=carrier.getOwnershipEntry();
        if (existing != null) {
            var current=existing.current().binding();
            if (existing.edge.owner() != owner || current.role() != value.role() || current.rawValue() != value.rawValue() || current.node() != value.node())
                throw new IllegalArgumentException("Physical field already belongs to another holder");
            throw new IllegalArgumentException("Physical field was already attached");
        }
        if (carrier.getRawValue() != value.rawValue())
            throw new IllegalArgumentException("Physical carrier does not contain the certified value");
        return new PhysicalSlot(new Entry(owner,carrier,value));
    }
    public final class PhysicalSlot {
        private final Entry owner;
        private final ArrayMemory containingArray;
        private final int index;
        private final StorageCertificate storage;
        private PhysicalSlot(Entry owner) {this(owner,null,-1,null);}
        private PhysicalSlot(Entry owner,ArrayMemory array,int index,StorageCertificate storage) {
            this.owner=owner;containingArray=array;this.index=index;this.storage=storage;
            if (owner.originalPhysicalSlot == null
                    || owner.runtimeFacet == null && owner.selfCell && owner.originalPhysicalSlot.containingArray == null && array != null)
                owner.originalPhysicalSlot=this;
        }
        public Entry ownerPublisher() {owner.current();return owner;}
        public Entry payloadPublisher() {
            var binding=owner.current().binding();
            return binding.role() == PhpOwnershipValues.Role.CELL ? certificate((ReferenceMemory)binding.rawValue()).value : owner;
        }
        public ReferencePublication preparePromotion(PhpOwnershipHeap.Snapshot expected) throws Throwable {
            owner.expected(expected);
            if (owner.unsetSource != null) owner.unsetSource.initializeReference();
            if (expected.binding().role() != PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Physical owner already contains a canonical CELL");
            var cell=allocateCell(expected.binding());
            try {return new ReferencePublication(this,expected,cell.binding(),cell,true);}
            catch (Throwable error) {try {cell.close();} catch (Throwable cleanup) {error=domain().graph().composeFailure(error,cleanup);}throw error;}
        }
        public PhysicalPublication prepareRebind(PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding cell) {
            owner.expected(expected);candidate(cell);
            if (cell.role() != PhpOwnershipValues.Role.CELL)
                throw new IllegalArgumentException("Physical reference binding requires a canonical CELL");
            if (owner.selfCell) {
                if (containingArray == null) throw new IllegalStateException("Rebinding an in-place CELL requires its certified containing address");
                return new CarrierPublication(this,expected,cell);
            }
            return new ReferencePublication(this,expected,cell,null,false);
        }
        /** A real frame CV drops its owner pointer, while aliases keep the canonical payload untouched. */
        void clearLexicalCv(PhpOwnershipExecution.Entry entry,int id)throws Throwable {
            domain().currentContext();
            if(!entry.permitsLexicalCvClear(this,id) || owner.selfCell || owner.cellCertificate!=null || owner.unsetSource!=null)
                throw new IllegalArgumentException("Not this actual lexical CV unlink");
            owner.current();
            try(var publication=owner.prepareChecked(PhpOwnershipValues.bindValue(domain(),Memory.UNDEFINED,null))) {
                owner.carrier.publishOwnershipRaw(publication);publication.commit();
            }
        }
        /** Actual lazy TABLE raw replacement has already unlinked this exact physical slot. */
        void retireLazyReplacement(PhpLazyPropertyTransaction.Prepared replacement)throws Throwable {
            domain().currentContext();
            if(!replacement.permitsRetirement(this))throw new IllegalArgumentException("Physical slot was not unlinked by this lazy TABLE replacement");
            owner.edge.close();
        }
        /** Unpublished fresh carriers retain children only during the actual preparation interval. */
        void abortLazyPreparation(PhpLazyPropertyTransaction.Prepared replacement)throws Throwable {
            domain().currentContext();
            if(!replacement.permitsAbort(this))throw new IllegalArgumentException("Not this abandoned fresh lazy slot");
            owner.edge.close();
        }
        /** Container destruction releases this edge through graph cleanup; live removals need an address operation. */
        public void detachContainer() {
            domain().currentContext();
            if (owner.edge.owner().state() == PhpOwnership.State.LIVE)
                throw new IllegalStateException("Live container removal requires its certified physical address");
        }
    }
    public interface PhysicalPublication extends PhpOwnershipHeap.Publication {
        void store() throws Throwable;
        ReferenceMemory cell();
        PhpOwnershipValues.Binding binding();
    }
    /** Certified list address replacement; the old canonical payload/aliases are never overwritten. */
    public final class CarrierPublication implements PhysicalPublication {
        private final PhysicalSlot slot;
        private final PhpOwnershipHeap.Snapshot expected,nextSnapshot;
        private final PhpOwnershipValues.Binding next;
        private final ReferenceMemory oldCarrier,nextCarrier;
        private final IdentityHashMap<ReferenceMemory,Entry> nextEntries;
        private final Memory key;
        private final PhpOwnership graph;
        private final CellCertificate oldCertificate,nextCertificate;
        private final WeakHashMap<PhpOwnership.Node,java.lang.ref.WeakReference<Entry>> oldFacets,nextFacets;
        private PhpOwnership.Publication delegate;
        private boolean stored,finished;
        private CarrierPublication(PhysicalSlot slot,PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding next) {
            this.slot=slot;this.expected=expected;this.next=next;
            key=new php.runtime.memory.LongMemory(slot.index);graph=domain().graph();
            oldCarrier=slot.owner.carrier;nextCarrier=(ReferenceMemory)next.rawValue();
            oldCertificate=certificate(oldCarrier);nextCertificate=certificate(nextCarrier);
            var occupied=nextCertificate.ownerFacet(slot.storage.node);
            if (occupied != null && occupied != slot.owner) throw new IllegalArgumentException("Canonical carrier already has a different physical address in this storage");
            oldFacets=oldCertificate.ownerFacets == null ? new WeakHashMap<>() : new WeakHashMap<>(oldCertificate.ownerFacets);
            nextFacets=nextCertificate.ownerFacets == null ? new WeakHashMap<>() : new WeakHashMap<>(nextCertificate.ownerFacets);
            oldFacets.remove(slot.storage.node);nextFacets.put(slot.storage.node,new java.lang.ref.WeakReference<>(slot.owner));
            if (expected.revision() == Long.MAX_VALUE) throw new IllegalStateException("Physical holder revision overflow");
            nextSnapshot=new PhpOwnershipHeap.Snapshot(slot.owner,next,expected.revision()+1);
            nextEntries=new IdentityHashMap<>(slot.storage.entries);nextEntries.remove(oldCarrier);
            if (nextEntries.put(nextCarrier,slot.owner) != null)
                throw new IllegalArgumentException("One exact canonical carrier cannot occupy two physical list positions");
            validateAddress();
        }
        private void validateAddress() {
            slot.owner.expected(expected);slot.storage.validateAll(slot.containingArray);
            if (!slot.containingArray.isList() || slot.containingArray.getByScalar(key) != oldCarrier)
                throw new IllegalArgumentException("Certified physical list address is stale");
        }
        public int index(ArrayMemory array) {
            if (array != slot.containingArray) throw new IllegalArgumentException("Carrier permit belongs to another actual array");
            if (stored || finished) throw new IllegalStateException("Carrier publication was consumed");
            return slot.index;
        }
        public ReferenceMemory consume(ArrayMemory array) {
            graph.checkExecutionAccess();index(array);
            if (delegate == null || delegate.isClosed() || array.getByScalar(key) != oldCarrier)
                throw new IllegalArgumentException("Carrier address no longer matches its pending permit");
            stored=true;return nextCarrier;
        }
        @Override public ReferenceMemory cell() {return nextCarrier;}
        @Override public PhpOwnershipValues.Binding binding() {return next;}
        @Override public void store() {
            if (finished || stored) throw new IllegalStateException("Carrier publication was consumed");
            validateAddress();candidate(next);delegate=slot.owner.edge.preparePublication(next.node());
            slot.containingArray.publishOwnershipCarrier(this);
        }
        @Override public boolean isCommitted() {return delegate != null && delegate.isCommitted();}
        @Override public void commit() throws Throwable {
            graph.checkExecutionAccess();
            if (!stored || finished) throw new IllegalStateException("Actual list pointer store must precede commit");
            slot.owner.carrier=nextCarrier;slot.owner.snapshot=nextSnapshot;slot.storage.entries=nextEntries;
            if (oldCertificate != nextCertificate) oldCertificate.ownerFacets=oldFacets;
            nextCertificate.ownerFacets=nextFacets;finished=true;
            delegate.commit();
        }
        @Override public void close() throws Exception {
            if (finished) return;
            if (stored) throw new IllegalStateException("Actual list pointer store cannot be aborted");
            if (delegate != null) delegate.close();finished=true;
        }
    }

    /** Validation may call PHP before preparation. Actual source/pointer stores and graph commit cannot. */
    public final class ReferencePublication implements PhysicalPublication {
        private final PhysicalSlot slot;
        private final PhpOwnershipHeap.Snapshot expected,targetExpected;
        private final PhpOwnershipValues.Binding next;
        private final CellAllocation staged;
        private final ReferenceMemory.OwnershipReferenceChange change;
        private final Entry payload;
        private RawPublication publication;
        private boolean stored,finished;
        private ReferencePublication(PhysicalSlot slot,PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding next,
                                     CellAllocation staged,boolean promotion) {
            this.slot=slot;this.expected=expected;this.next=next;this.staged=staged;
            payload=certificate((ReferenceMemory)next.rawValue()).value;targetExpected=payload.current();
            change=slot.owner.unsetSource != null
                    ? slot.owner.carrier.prepareOwnershipRevivedReference((ReferenceMemory)next.rawValue(),promotion,slot.owner.unsetSource)
                    : slot.owner.carrier.prepareOwnershipReference((ReferenceMemory)next.rawValue(),promotion);
            slot.owner.expected(expected);payload.expected(targetExpected);change.validate();
        }
        public ReferenceMemory cell() {if (finished) throw new IllegalStateException("Reference publication is closed");return (ReferenceMemory)next.rawValue();}
        public PhpOwnershipValues.Binding binding() {if (finished) throw new IllegalStateException("Reference publication is closed");return next;}
        public void store() throws Throwable {
            if (finished || stored) throw new IllegalStateException("Reference publication was consumed");
            slot.owner.expected(expected);payload.expected(targetExpected);change.validate();
            Memory checked=change.checkedTarget();
            if (checked != targetExpected.binding().rawValue()) {
                // PHP coercion here only replaces a scalar with a scalar; it cannot invoke a destructor.
                var coerced=PhpOwnershipValues.bindValue(domain(),checked,null);candidate(coerced);
                if (targetExpected.binding().node() != null)
                    throw new IllegalStateException("Reference coercion cannot replace an allocation before holder publication");
                try (var token=payload.prepareChecked(coerced)) {payload.carrier.publishOwnershipRaw(token);token.commit();}
            }
            slot.owner.expected(expected);change.validate();
            publication=slot.owner.prepareChecked(next);
            slot.owner.carrier.publishOwnershipReference(change,publication);stored=true;
        }
        @Override public boolean isCommitted() {return publication != null && publication.isCommitted();}
        @Override public void commit() throws Throwable {
            if (!stored || finished) throw new IllegalStateException("Reference pointer store must precede commit");
            finished=true;publication.commit();
        }
        @Override public void close() throws Exception {
            if (!finished && stored) throw new IllegalStateException("A reference pointer store cannot be aborted");
            if (!finished) {if (publication != null) publication.close();finished=true;}
            if (staged != null) staged.close();
        }
    }

    /** Borrowed facade over the existing exact publisher; adoption never adds an owner edge. */
    public final class PublisherFacet {
        private final Entry entry;
        private final PhysicalSlot physical;
        private PublisherFacet(Entry entry,PhysicalSlot physical) {this.entry=entry;this.physical=physical;}
        public PhpOwnershipDomain domain() {return entry.domain();}
        public PhpOwnershipHeap.Snapshot current() {return entry.current();}
        public ReferenceMemory carrier() {return entry.carrier();}
        public PhpOwnership.Node owner() {entry.current();return entry.edge.owner();}
        public boolean isDisposed() {
            domain().currentContext();return entry.edge.isClosed() || entry.edge.owner().state() != PhpOwnership.State.LIVE;
        }
        PhpOwnership.Slot borrowedSlot() {entry.current();return entry.edge;}
        public void assign(PhpOwnershipValues.Binding next) throws Throwable {entry.assign(next);}
        public PhysicalSlot physicalSlot() {entry.current();return physical;}
        public boolean isCanonicalPayload() {entry.current();return entry.cellCertificate != null;}
        public RawPublication prepareValue(PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding next,
                                           PhpRuntimeOwnership.Holder source) {
            if (source == null) return (RawPublication)entry.prepare(expected,next);
            entry.expected(expected);candidate(next);
            if (entry.unsetSource != null || expected.binding().role() != PhpOwnershipValues.Role.VALUE
                    || next.role() != PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Facet VALUE transfer requires its checked initialized VALUE publisher");
            var producer=source.requireFacetSource(domain(),next);
            Memory checked=entry.carrier.validateOwnershipAssignment(next.rawValue());
            if (checked != next.rawValue())
                throw new IllegalArgumentException("Use checked assignment when property coercion changes the candidate");
            entry.expected(expected);producer.validate();
            if (expected.revision() == Long.MAX_VALUE) throw new IllegalStateException("Entry revision overflow");
            var after=new PhpOwnershipHeap.Snapshot(entry,next,expected.revision()+1);
            var publication=new RawPublication(entry,expected,after);publication.source=producer;
            publication.delegate=producer.slot().prepareTransferTo(entry.edge);return publication;
        }
    }

    /** One actual physical entry or canonical CELL-value field. No synthetic carrier owner is added. */
    public final class Entry implements PhpOwnershipHeap.Publisher {
        private ReferenceMemory carrier;
        private final PhpOwnership.Slot edge;
        private boolean selfCell;
        private final CellCertificate cellCertificate;
        private PhpOwnershipHeap.Snapshot snapshot;
        private ReferenceMemory.OwnershipDeclaration unsetSource;
        private RestorationCompletion pendingRestoration;
        private PhysicalSlot originalPhysicalSlot;
        private PublisherFacet runtimeFacet;
        private Entry(PhpOwnership.Node owner, ReferenceMemory carrier, PhpOwnershipValues.Binding value) throws Throwable {
            this(owner,carrier,value,false);
        }
        private Entry(PhpOwnership.Node owner, ReferenceMemory carrier, PhpOwnershipValues.Binding value,boolean selfCell) throws Throwable {
            if (carrier.getClass() != ReferenceMemory.class && !(carrier instanceof php.runtime.memory.support.ArrayMapEntryMemory))
                throw new IllegalArgumentException("A virtual address is not a physical ownership entry");
            this.carrier=carrier;this.selfCell=selfCell;cellCertificate=selfCell ? null : carrier.getOwnershipCell();
            if (cellCertificate != null && (cellCertificate.adapter.domain() != domain() || cellCertificate.node != owner))
                throw new IllegalArgumentException("Canonical payload authority does not identify this owner");
            edge=domain().graph().edge(owner);
            snapshot=new PhpOwnershipHeap.Snapshot(this,value,0);edge.replace(value.node());
            if (!selfCell) carrier.attachOwnershipEntry(this);
        }
        @Override public PhpOwnershipDomain domain() {return PhpOwnershipArrays.this.domain();}
        public ReferenceMemory carrier() {current();return carrier;}
        public CellCertificate cellCertificate() {return cellCertificate;}
        public PublisherFacet runtimeFacet() {
            current();
            if (runtimeFacet == null) {
                if (originalPhysicalSlot == null) {
                    if (selfCell) throw new IllegalArgumentException("Canonical facet needs its exact certified container address");
                    new PhysicalSlot(this);
                }
                runtimeFacet=new PublisherFacet(this,originalPhysicalSlot);
            }
            return runtimeFacet;
        }
        public void validateCarrierAttachment(ReferenceMemory actual) {
            domain().currentContext();
            if (actual != carrier || edge.owner().state() != PhpOwnership.State.LIVE)
                throw new IllegalArgumentException("Publisher does not identify this physical field");
        }
        @Override public PhpOwnershipHeap.Snapshot current() {
            domain().currentContext();PhpOwnershipValues.Binding binding=snapshot.binding();
            if (edge.isClosed() || edge.owner().state() != PhpOwnership.State.LIVE)
                throw new IllegalStateException("Entry owner was released");
            if ((selfCell ? carrier != binding.rawValue() : carrier.getRawValue() != binding.rawValue()) || edge.target() != binding.node())
                throw new IllegalStateException("Raw entry changed outside its publisher");
            if (selfCell) certificate(carrier).value.current();
            return snapshot;
        }
        private void expected(PhpOwnershipHeap.Snapshot expected) {
            if (expected == null || expected.publisher() != this || expected != current())
                throw new IllegalArgumentException("Entry address is stale or belongs to another publisher");
        }
        @Override public PhpOwnershipHeap.Publication prepare(PhpOwnershipHeap.Snapshot expected, PhpOwnershipValues.Binding next) {
            expected(expected);candidate(next);
            if (unsetSource != null)
                throw new IllegalArgumentException("Unset typed declaration needs checked Entry.assign revival");
            if (snapshot.binding().role() != PhpOwnershipValues.Role.VALUE || next.role() != PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Reference rebinding needs its separate canonical promotion publisher");
            Memory checked=carrier.validateOwnershipAssignment(next.rawValue());
            if (checked != next.rawValue())
                throw new IllegalArgumentException("Use checked Entry.assign when property coercion changes the candidate");
            expected(expected);return prepareChecked(next);
        }
        private RawPublication prepareChecked(PhpOwnershipValues.Binding next) {
            if (snapshot.revision() == Long.MAX_VALUE) throw new IllegalStateException("Entry revision overflow");
            PhpOwnershipHeap.Snapshot following=new PhpOwnershipHeap.Snapshot(this,next,snapshot.revision()+1);
            RawPublication publication=new RawPublication(this,snapshot,following);
            publication.delegate=edge.preparePublication(next.node());return publication;
        }
        /** Existing property-source checks occur before preparation and are revalidated against the address. */
        public void assign(PhpOwnershipValues.Binding next) throws Throwable {
            PhpOwnershipHeap.Snapshot before=current();candidate(next);
            if (before.binding().role() == PhpOwnershipValues.Role.CELL) {
                certificate((ReferenceMemory)before.binding().rawValue()).value.assign(next);return;
            }
            if (next.role() != PhpOwnershipValues.Role.VALUE)
                throw new IllegalArgumentException("Assigning a reference needs canonical rebinding, not VALUE assignment");
            var revival=unsetSource == null ? null : unsetSource.prepareRevival(next.rawValue());
            Memory checked=revival == null ? carrier.validateOwnershipAssignment(next.rawValue()) : revival.checked();expected(before);
            if (revival != null) revival.validate();
            PhpOwnershipValues.Binding result=checked == next.rawValue() ? next
                    : PhpOwnershipValues.bindValue(domain(),checked,null);
            candidate(result);
            try (RawPublication publication=prepareChecked(result)) {
                publication.revival=revival;carrier.publishOwnershipRaw(publication);publication.commit();
            }
        }
    }

    /** Target authority cannot be forged from an arbitrary Binding, and is consumed by one private-field store. */
    public static final class RawPublication implements PhpOwnershipHeap.Publication {
        private final Entry publisher;
        private final PhpOwnershipHeap.Snapshot before,after;
        private final Memory oldRaw,nextRaw;
        private final PhpOwnership graph;
        private PhpOwnership.Publication delegate;
        private ReferenceMemory.OwnershipRevival revival;
        private PhpRuntimeOwnership.FacetSource source;
        public ReferenceMemory.OwnershipRevival revival() {return revival;}
        private boolean stored,finished;
        private RawPublication(Entry publisher, PhpOwnershipHeap.Snapshot before, PhpOwnershipHeap.Snapshot after) {
            this.publisher=publisher;this.before=before;this.after=after;
            oldRaw=before.binding().rawValue();nextRaw=after.binding().rawValue();graph=publisher.domain().graph();
        }
        public Memory consume(ReferenceMemory target) {
            graph.checkExecutionAccess();
            if (finished || stored || delegate.isClosed() || target != publisher.carrier
                    || target.getRawValue() != oldRaw)
                throw new IllegalArgumentException("Raw publication target/identity does not match its pending permit");
            stored=true;return nextRaw;
        }
        @Override public boolean isCommitted() {return delegate.isCommitted();}
        @Override public void commit() throws Throwable {
            graph.checkExecutionAccess();
            if (!stored || finished || delegate.isClosed() || publisher.carrier.getRawValue() != nextRaw)
                throw new IllegalStateException("Actual Memory store must precede its ownership commit");
            publisher.snapshot=after;publisher.unsetSource=null;finished=true;
            if (source != null) source.consumeAfterRaw();
            try {delegate.commit();}
            finally {if (!delegate.isCommitted()) publisher.snapshot=before;}
        }
        @Override public void close() throws Exception {
            if (finished) return;
            if (stored) throw new IllegalStateException("An actual raw store cannot be aborted");
            try {delegate.close();} finally {finished=true;}
        }
    }
}
