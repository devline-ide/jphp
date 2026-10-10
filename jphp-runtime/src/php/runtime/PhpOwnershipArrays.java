package php.runtime;

import php.runtime.memory.ArrayMemory;
import php.runtime.memory.PhpObjectProperties;
import php.runtime.memory.ReferenceMemory;
import java.util.*;

/** Explicit real-Memory allocation/COW adapter; ordinary container APIs are not implicitly activated. */
public final class PhpOwnershipArrays {
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
            if (certificate((ArrayMemory)raw).node != binding.node())
                throw new IllegalArgumentException("Array certificate does not identify the candidate");
        } else if (binding.role() == PhpOwnershipValues.Role.CELL) {
            if (certificate((ReferenceMemory)raw).node != binding.node())
                throw new IllegalArgumentException("CELL certificate does not identify the candidate");
        }
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
        private boolean closed;
        private ArrayWrite(PhpOwnershipHeap.Publisher owner, PhpOwnershipHeap.Snapshot expected, ArrayMemory array,
                           StorageCertificate certificate, Allocation staged) {
            this.owner=owner;this.expected=expected;this.array=array;this.certificate=certificate;this.staged=staged;
        }
        private void open() {domain().currentContext();if (closed) throw new IllegalStateException("Array write preparation is closed");}
        public ArrayMemory array() {open();return array;}
        public PhpOwnershipValues.Binding binding() {open();return PhpOwnershipValues.bindValue(domain(),array,certificate.node);}
        public boolean separated() {open();return staged != null;}
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
            open();var current=owner.current();
            if (current.binding().rawValue() != array || current.binding().node() != certificate.node)
                throw new IllegalArgumentException("Removal requires its currently published exact VALUE owner");
            return new UnsetPublication(array,certificate,null,actualScalarKey,owner,current);
        }
        public PhpOwnershipHeap.Publication preparePublication() {
            open();certificate.validateAll(array);return owner.prepare(expected,binding());
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
    /** TABLE has its own origin certificate; this adapter never invents a property declaration. */
    public UnsetPublication prepareTableUnset(PhpOwnershipObjects.PropertiesCertificate table,String literalKey) {
        table.validateAll();if (table.node().graph() != domain().graph()) throw new IllegalArgumentException("Foreign TABLE");
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
        private PhpOwnership.Publication delegate;
        private php.runtime.memory.support.ArrayMemoryMap capturedMap;
        private boolean started,stored,finished,committed,committing;
        private UnsetPublication(ArrayMemory array,StorageCertificate storage,PhpOwnershipObjects.PropertiesCertificate table,Object key,PhpOwnershipHeap.Publisher owner,PhpOwnershipHeap.Snapshot ownerSnapshot) {
            this.array=array;this.storage=storage;this.table=table;this.key=key;identity=array.getStorageIdentity();addressOwner=owner;addressSnapshot=ownerSnapshot;
            if (storage != null) storage.validateAll(array);else table.validateAll();
            revision=storage == null ? table.removalRevision() : storage.revision;
            removed=array.getByScalar(key);
            entry=removed == null ? null : storage != null ? storage.entries.get(removed) : table.entry(removed).owner;
            expected=entry == null ? null : entry.current();
            declared=table != null && ((PhpObjectProperties)array).declaration((String)key) != null && removed != null;
            sourceRemoval=table == null || removed == null ? null : removed.prepareOwnershipSourceRemoval();
            declaration=declared && sourceRemoval != null ? sourceRemoval.declaration() : null;
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
            for (int i=0;i<oldCarriers.length;i++) freshCarriers[i]=relocations.get(oldCarriers[i]);
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
                ReferenceMemory old=oldCarriers[i],fresh=freshCarriers[i];Entry address=storage.entries.get(old);
                old.relocateOwnershipEntry(this,fresh);address.carrier=fresh;
                if (address.selfCell) {var cell=old.getOwnershipCell();if (cell.ownerFacets != null) cell.ownerFacets.remove(storage.node);}
                address.selfCell=false;
            }
        }
        @Override public boolean isCommitted() {return committed;}
        @Override public void commit() throws Throwable {
            domain().graph().checkExecutionAccess();if (!stored || finished) throw new IllegalStateException("Actual unlink must precede commit");
            if (storage != null && removed != null) {storage.entries=nextEntries;storage.revision++;}
            if (table != null && removed != null) {committing=true;table.publishRemovalMetadata(this,removed,declared);committing=false;}
            if (declared) {entry.snapshot=following;entry.unsetSource=declaration;}
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

    /** One actual physical entry or canonical CELL-value field. No synthetic carrier owner is added. */
    public final class Entry implements PhpOwnershipHeap.Publisher {
        private ReferenceMemory carrier;
        private final PhpOwnership.Slot edge;
        private boolean selfCell;
        private final CellCertificate cellCertificate;
        private PhpOwnershipHeap.Snapshot snapshot;
        private ReferenceMemory.OwnershipDeclaration unsetSource;
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