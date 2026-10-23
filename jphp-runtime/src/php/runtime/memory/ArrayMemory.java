package php.runtime.memory;

import php.runtime.Memory;
import php.runtime.common.Messages;
import php.runtime.common.Pair;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.exceptions.RecursiveException;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.StdClass;
import php.runtime.memory.helper.ArrayKeyMemory;
import php.runtime.memory.helper.ArrayValueMemory;
import php.runtime.memory.support.*;
import php.runtime.reflection.support.ReflectionUtils;

import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ArrayMemory extends Memory implements Iterable<ReferenceMemory> {
    // A custom immutable conversion owns its semantics; inherit legacy reset only with the base conversion.
    private static final ClassValue<Boolean> INHERITS_IMMUTABLE_CONVERSION = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try { return type.getMethod("toImmutable").getDeclaringClass() == ArrayMemory.class; }
            catch (NoSuchMethodException impossible) { throw new IllegalStateException("Missing public immutable conversion", impossible); }
        }
    };
    protected transient long lastLongIndex;
    private long nextFreeElement = Long.MIN_VALUE;
    private boolean pointerNeedsFirst;
    protected transient int size;
    protected transient int copies;
    protected transient ArrayMemory original;

    // One allocation identity follows backing contents across views, including empty arrays.
    // Sharing a Java view does not itself register a PHP owner. The runtime adapter owns edges.
    private static final class ArrayStorage {
        // Empty ordinary arrays pay for one field, like the previous pin counter.
        // Active leases form a private chain whose tail carries the allocation stamp.
        Object authority;
        private Object allocation() {
            Object current=authority;
            while (current instanceof WriteFetchLease) current=((WriteFetchLease)current).previous;
            return current;
        }
        Object ownership() {Object value=allocation();return value instanceof RuntimeStorage || value instanceof GlobalsTableOrigin ? null : value;}
        RuntimeStorage runtime() {Object value=allocation();return value instanceof RuntimeStorage ? (RuntimeStorage)value : null;}
        php.runtime.PhpRuntimeOwnership.ArrayOrigin runtimeOrigin() {RuntimeStorage value=runtime();return value == null ? null : value.origin;}
        void ownership(Object certificate) {
            WriteFetchLease previous=null;Object current=authority;
            while (current instanceof WriteFetchLease) {
                previous=(WriteFetchLease)current;current=previous.previous;
            }
            if (previous == null) authority=certificate;
            else previous.previous=certificate;
        }
    }
    // Ordinary origin/frontier metadata exists only after an explicitly staged producer.
    private static final class RuntimeStorage {
        final php.runtime.PhpRuntimeOwnership.ArrayOrigin origin;
        boolean initialized;
        RuntimeStorage(php.runtime.PhpRuntimeOwnership.ArrayOrigin origin) {this.origin=origin;}
    }
    private transient ArrayStorage storage;

    /** A value view may outlive its host; constructor provenance must not own the Environment. */
    private static final class GlobalsTableOrigin extends WeakReference<Environment.SyntheticGlobalsOrigin> {
        GlobalsTableOrigin(Environment.SyntheticGlobalsOrigin origin) { super(origin); }
    }
    /** Constructor metadata is structural; it never grants PHP ARRAY ownership or suppresses COW. */
    public final void attachSyntheticGlobalsOrigin(Environment.SyntheticGlobalsOrigin origin) {
        Objects.requireNonNull(origin);
        if (origin.environment().getRuntimeGlobalsTable() != this || getClass() != ArrayMemory.class
                || storage.allocation() != null || map == null || _list != null)
            throw new IllegalArgumentException("Synthetic globals origin requires its exact constructor table");
        origin.validateCarrier(origin.carrier());
        storage.ownership(new GlobalsTableOrigin(origin));
    }
    private Environment.SyntheticGlobalsOrigin constructorGlobalsOrigin() {
        Object metadata = storage.allocation();
        if (!(metadata instanceof GlobalsTableOrigin)) return null;
        Environment.SyntheticGlobalsOrigin origin = ((GlobalsTableOrigin) metadata).get();
        return origin != null && origin.environment().getRuntimeGlobalsTable() == this ? origin : null;
    }

    @FunctionalInterface public interface NativeGlobalCarrierProtocol {
        void validateCarrier(ReferenceMemory actual);
    }
    /** Structural native-table metadata: no ARRAY node, value owner, copy or COW authority. */
    public final NativeGlobals prepareNativeGlobals(Environment environment, NativeGlobalCarrierProtocol protocol) {
        Objects.requireNonNull(environment);Objects.requireNonNull(protocol);
        if (environment.getRuntimeGlobalsTable()!=this || getClass()!=ArrayMemory.class || map==null || _list!=null)
            throw new IllegalArgumentException("Native globals do not identify their actual Environment table");
        Object existing=storage.ownership();
        if (existing instanceof NativeGlobals) {
            NativeGlobals result=(NativeGlobals)existing;result.validate(environment);
            for(ReferenceMemory carrier:this)protocol.validateCarrier(carrier);
            return result;
        }
        if (existing!=null || storage.runtimeOrigin()!=null)
            throw new IllegalArgumentException("Native globals cannot adopt PHP ARRAY storage authority");
        for(ReferenceMemory carrier:this)protocol.validateCarrier(carrier);
        NativeGlobals prepared=new NativeGlobals(environment,map.sealNativeGlobals());
        storage.ownership(prepared);return prepared;
    }
    public final class NativeGlobals {
        private final Environment environment;
        private final ArrayStorage identity=storage;
        private final ArrayMemoryMap actualMap=map;
        private final ArrayMemoryMap.NativeGlobalSlots slots;
        private NativeGlobals(Environment environment,ArrayMemoryMap.NativeGlobalSlots slots){this.environment=environment;this.slots=slots;}
        public ArrayMemory actual(){return ArrayMemory.this;}
        public void validate(Environment expected) {
            if(expected!=environment || expected.getRuntimeGlobalsTable()!=ArrayMemory.this || storage!=identity
                    || storage.ownership()!=this || map!=actualMap || _list!=null || size!=actualMap.size())
                throw new IllegalArgumentException("Native global table layout or Environment changed");
        }
        public ArrayMemoryMap.NativeGlobalSlot slot(Object normalizedKey){validate(environment);return slots.slot(normalizedKey);}
        public NativeGlobalPlacement prepareLink(ArrayMemoryMap.NativeGlobalSlot slot){validate(environment);return new NativeGlobalPlacement(slots.prepareLink(slot),1);}
        public NativeGlobalPlacement prepareUnlink(ArrayMemoryMap.NativeGlobalSlot slot){validate(environment);return new NativeGlobalPlacement(slots.prepareUnlink(slot),-1);}
        public NativeGlobalPlacement prepareSyntheticRetirement(ArrayMemoryMap.NativeGlobalSlot slot,Environment.SyntheticGlobalsOrigin origin){
            validate(environment);return new NativeGlobalPlacement(slots.prepareSyntheticRetirement(slot,origin),-1);
        }
        public final class NativeGlobalPlacement implements AutoCloseable {
            private final ArrayMemoryMap.NativeGlobalPlacement placement;
            private final int delta,beforeSize=size;
            private final long beforeLast=lastLongIndex,beforeNext=nextFreeElement;
            private final Long integerKey;
            private boolean begun,stored;
            private NativeGlobalPlacement(ArrayMemoryMap.NativeGlobalPlacement placement,int delta){
                this.placement=placement;this.delta=delta;Object key=placement.slot().key();
                integerKey=delta>0 && key instanceof LongMemory ? ((LongMemory)key).toLong() : null;
            }
            public ArrayMemoryMap.NativeGlobalSlot slot(){return placement.slot();}
            public void validate(){
                NativeGlobals.this.validate(environment);placement.validate();
                if(stored || size!=beforeSize || lastLongIndex!=beforeLast || nextFreeElement!=beforeNext)
                    throw new IllegalArgumentException("Native global table mirror changed before publication");
            }
            public void beginStore(){validate();placement.beginStore();begun=true;}
            /** Pure layout/mirror stores; all address and payload checks precede the raw interval. */
            public void store(){
                if(!begun || stored)throw new IllegalStateException("Native global placement is not ready");
                placement.store();size=beforeSize+delta;
                if(integerKey!=null){lastLongIndex=Math.max(beforeLast,integerKey);recordIntegerKey(integerKey);}
                foreachIterator=null;stored=true;
            }
            @Override public void close(){placement.close();}
        }
    }

    public final Object getStorageIdentity() { return storage; }

    public final php.runtime.PhpRuntimeOwnership.ArrayOrigin getRuntimeOrigin() {return storage.runtimeOrigin();}

    /** Fresh producer protocol: normalize its actual empty/unpublished layout before stamping storage. */
    public final php.runtime.PhpRuntimeOwnership.ArrayOrigin prepareRuntimeOrigin(php.runtime.PhpRuntimeOwnership.Authority authority) {
        if (this instanceof PhpObjectProperties || storage.ownership() != null || storage.runtimeOrigin() != null
                || original != null || copies != 0)
            throw new IllegalArgumentException("Runtime ARRAY origin requires a fresh allocation producer");
        for (ReferenceMemory carrier:this) {
            if (carrier.getRuntimeHolder() != null || carrier.getOwnershipEntry() != null || carrier.getRuntimeCell() != null)
                throw new IllegalArgumentException("Fresh ARRAY contains an already published carrier");
            Memory raw=carrier.getRawValue();
            authority.value(raw,raw instanceof ReferenceMemory ? php.runtime.PhpRuntimeOwnership.Role.CELL : php.runtime.PhpRuntimeOwnership.Role.VALUE);
        }
        if (map == null) convertToMap();
        final ArrayMemoryMap actualMap=map;final int actualSize=size;
        var origin=authority.arrayOrigin(this,this,actual->{
            if (actual != this || map != actualMap || _list != null || size != actualSize || storage.runtimeOrigin() != null)
                throw new IllegalArgumentException("Fresh ARRAY physical layout changed before origin registration");
        });
        storage.ownership(new RuntimeStorage(origin));return origin;
    }

    /** EVAL is already real before physical child holders are attached. */
    public final void initializeRuntimeEntries(php.runtime.PhpRuntimeOwnership.ArrayOrigin origin,
                                               php.runtime.PhpRuntimeOwnership.Holder producer) throws Throwable {
        if (origin == null || origin != storage.runtimeOrigin() || storage.runtime().initialized || map == null || _list != null
                || producer == null || producer.domain() != origin.authority().domain() || producer.node() != origin.node()
                || producer.role() != php.runtime.PhpRuntimeOwnership.Role.VALUE
                || !(producer.raw() instanceof ArrayMemory) || ((ArrayMemory)producer.raw()).storage != storage)
            throw new IllegalArgumentException("Runtime ARRAY construction needs its exact fresh EVAL producer");
        origin.validate(this);
        producer.requireProducerEvaluation();
        for (ReferenceMemory carrier:this) {
            Memory raw=carrier.getRawValue();
            var role=raw instanceof ReferenceMemory ? php.runtime.PhpRuntimeOwnership.Role.CELL : php.runtime.PhpRuntimeOwnership.Role.VALUE;
            origin.authority().physical(origin.node(),carrier,origin.authority().value(raw,role));
        }
        storage.runtime().initialized=true;
    }

    /** Exact prepared insertion: the prospective edge is empty until this token links its carrier. */
    public final class RuntimeInsertion {
        private final php.runtime.PhpRuntimeOwnership.ArrayOrigin origin=storage.runtimeOrigin();
        private final ArrayMemoryMap actualMap=map;
        private final int actualSize=size;
        private final Object key;
        private final ArrayMemoryMap.OwnershipInsertion insertion;
        private final php.runtime.PhpRuntimeOwnership.Holder holder;
        private boolean linked;
        private RuntimeInsertion(Object key) throws Throwable {
            if (origin == null || !storage.runtime().initialized || map == null || _list != null)
                throw new IllegalStateException("Insertion needs its complete physical ARRAY origin");
            origin.validate(ArrayMemory.this);this.key=key;
            insertion=map.prepareRuntimeInsertion(this,key);
            holder=origin.authority().physical(origin.node(),insertion.carrier(),
                    origin.authority().value(Memory.NULL,php.runtime.PhpRuntimeOwnership.Role.VALUE));
        }
        public php.runtime.PhpRuntimeOwnership.Holder holder() {return holder;}
        public ReferenceMemory carrier() {return insertion.carrier();}
        public void validateMapPreparation(ArrayMemoryMap actual) {
            if (linked || actual != actualMap || map != actualMap || size != actualSize || storage.runtimeOrigin() != origin)
                throw new IllegalArgumentException("Prepared runtime insertion no longer identifies its physical table");
        }
        public void consumeMapInsertion(ArrayMemoryMap actual,ArrayMemoryMap.OwnershipInsertion prepared,
                                        php.runtime.PhpRuntimeOwnership.Publication publication) {
            validateMapPreparation(actual);
            if (prepared != insertion || publication.holder() != holder || insertion.carrier().getRawValue() != publication.next())
                throw new IllegalArgumentException("Runtime insertion does not identify its exact carrier publication");
            linked=true;
        }
        public void checkHolder(php.runtime.PhpRuntimeOwnership.Holder actual,ReferenceMemory carrier) {
            validateMapPreparation(actualMap);insertion.validate();
            if (actual != holder || carrier != insertion.carrier() || carrier.getRuntimeHolder() != holder)
                throw new IllegalArgumentException("Runtime insertion requires its exact prospective address holder");
        }
        private ReferenceMemory publish(Memory value) throws Throwable {
            return publish(value,null,-1);
        }
        private ReferenceMemory publish(Memory value,php.runtime.PhpRuntimeOwnership.Frame frame,int evalId) throws Throwable {
            try (var publication=frame == null ? holder.prepareArrayInsertion(this,value)
                    : frame.prepareArrayInsertion(this,value,evalId)) {
                insertion.validate();validateMapPreparation(actualMap);
                publication.storeRuntimeInsertion(this);
                insertion.storeRuntime(this,publication);
                size=actualSize+1;
                if (key instanceof LongMemory) {long index=((LongMemory)key).value;if(index>lastLongIndex)lastLongIndex=index;recordIntegerKey(index);}
                foreachIterator=null;publication.commit();return insertion.carrier();
            } finally {if (!linked) holder.close();}
        }
    }

    /** An existing bucket's VALUE assignment targets its payload, never rebinds its CELL facet. */
    public final class RuntimeValueAssignment {
        private final php.runtime.PhpRuntimeOwnership.ArrayOrigin origin=storage.runtimeOrigin();
        private final ArrayMemoryMap actualMap=map;
        private final Object key;
        private final ReferenceMemory bucket;
        private final Memory bucketBefore;
        private final php.runtime.PhpRuntimeOwnership.Holder facet;
        private final php.runtime.PhpRuntimeOwnership.Holder holder;
        private final ReferenceMemory carrier;
        private final php.runtime.PhpRuntimeOwnership.CellOrigin cell;
        private boolean consumed;
        private RuntimeValueAssignment(Object key,ReferenceMemory bucket) {
            if (origin == null || !storage.runtime().initialized || map == null || _list != null)
                throw new IllegalStateException("VALUE assignment needs its complete physical ARRAY origin");
            origin.validate(ArrayMemory.this);this.key=key;this.bucket=bucket;
            facet=bucket.getRuntimeHolder();bucketBefore=bucket.getRawValue();
            if (facet == null || !facet.matches(bucket))throw new IllegalArgumentException("Actual ARRAY bucket has no exact publisher");
            if (facet.role() == php.runtime.PhpRuntimeOwnership.Role.CELL) {
                if (!(bucketBefore instanceof ReferenceMemory) || ((ReferenceMemory)bucketBefore).getRuntimeCell() == null)
                    throw new IllegalArgumentException("Existing certified CELL needs its same-publisher payload protocol");
                carrier=(ReferenceMemory)bucketBefore;cell=carrier.getRuntimeCell();holder=cell.payload();
            } else {carrier=bucket;cell=null;holder=facet;}
        }
        public php.runtime.PhpRuntimeOwnership.Holder holder() {return holder;}
        public ReferenceMemory carrier() {return carrier;}
        /** Pure identity checks also run inside the private raw publication barrier. */
        public void checkHolder(php.runtime.PhpRuntimeOwnership.Holder actual,ReferenceMemory actualCarrier) {
            if (consumed || storage.runtimeOrigin() != origin || map != actualMap || actualMap.getEntry(key) != bucket
                    || bucket.getRawValue() != bucketBefore || bucket.getRuntimeHolder() != facet
                    || actual != holder || actualCarrier != carrier || cell != null && carrier.getRuntimeCell() != cell)
                throw new IllegalArgumentException("VALUE permit no longer identifies its exact physical ARRAY bucket/payload");
        }
        private Memory publish(Memory value,php.runtime.PhpRuntimeOwnership.Frame frame,int evalId) throws Throwable {
            try (var publication=frame.prepareArrayValue(this,value,evalId)) {
                checkHolder(holder,carrier);carrier.publishRuntimeRaw(publication);consumed=true;
                publication.commit();return publication.next();
            }
        }
    }
    /** A compiler-witnessed VALUE producer moves into the actual addressed ARRAY entry. */
    public final Memory assignRuntimeEntry(Memory key,Memory value,php.runtime.PhpRuntimeOwnership.Frame frame,int evalId,
                                           Environment env,TraceInfo trace) throws Throwable {
        if (frame == null || frame.authority().currentFrame() != frame || storage.runtimeOrigin() == null
                || storage.runtimeOrigin().authority().domain() != frame.authority().domain())
            throw new IllegalArgumentException("ARRAY entry transfer needs its exact current producer frame and storage");
        if (value instanceof ReferenceMemory)throw new IllegalArgumentException("VALUE entry transfer cannot publish a Java address as CELL");
        frame.requireArrayValue(evalId,value);
        storage.runtimeOrigin().validate(this);checkCopied();
        Object actualKey;
        if (key == null) {
            if (!canAppendPhp8())env.exception(trace,BaseError.class,"Cannot add element to the array as the next element is already occupied");
            actualKey=LongMemory.valueOf(nextFreeElement == Long.MIN_VALUE ? 0 : nextFreeElement);
        } else actualKey=toKey(key);
        ReferenceMemory existing=map.getEntry(actualKey);
        if (existing != null)return new RuntimeValueAssignment(actualKey,existing).publish(value,frame,evalId);
        ReferenceMemory stored=new RuntimeInsertion(actualKey).publish(value,frame,evalId);
        return stored.getRawValue();
    }

    private ReferenceMemory runtimePut(Object key,Memory value) {
        var origin=storage.runtimeOrigin();origin.validate(this);checkCopied();
        ReferenceMemory existing=map.getEntry(key);
        if (existing != null) {
            if (existing.getRuntimeHolder() == null) throw new IllegalStateException("Actual ARRAY entry has no physical publisher");
            if (value instanceof ReferenceMemory) existing.assignRef(value);else existing.assign(value);
            return existing;
        }
        try {return new RuntimeInsertion(key).publish(value);}
        catch(Throwable failure){return runtimeFailure(failure);}
    }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable>T runtimeFailure(Throwable failure)throws E{throw (E)failure;}

    /** Explicit adapters stamp the actual backing allocation, never an individual Java view. */
    public final php.runtime.PhpOwnershipArrays.StorageCertificate getOwnershipStorage() {
        Object authority=storage.ownership();
        return authority instanceof php.runtime.PhpOwnershipArrays.StorageCertificate
                ? (php.runtime.PhpOwnershipArrays.StorageCertificate)authority : null;
    }
    /** Separate actual TABLE allocation capability; property tables are never ordinary array VALUEs. */
    public final php.runtime.PhpOwnershipObjects.PropertiesCertificate getOwnershipTable() {
        Object authority=storage.ownership();
        return authority instanceof php.runtime.PhpOwnershipObjects.PropertiesCertificate
                ? (php.runtime.PhpOwnershipObjects.PropertiesCertificate)authority : null;
    }
    public final void attachOwnershipTable(php.runtime.PhpOwnershipObjects.PropertiesCertificate certificate) {
        certificate.validateAttachment(this);
        if (storage.ownership() != null) throw new IllegalStateException("Backing allocation already has an origin authority");
        storage.ownership(certificate);
    }
    public final void attachOwnershipStorage(php.runtime.PhpOwnershipArrays.StorageCertificate certificate) {
        if (storage.runtimeOrigin() != null) throw new IllegalArgumentException("Runtime storage requires exact publisher adoption, not a second edge set");
        certificate.validateAttachment(this);
        var current=storage.ownership();
        if (current != null && current != certificate)
            throw new IllegalArgumentException("Array allocation already belongs to another ownership certificate");
        storage.ownership(certificate);
    }
    public final boolean hasWriteFetchPins() { return storage.authority instanceof WriteFetchLease; }

    protected boolean hasOwnershipMutationAuthority() { return storage.ownership() != null || storage.runtimeOrigin() != null; }

    protected void rejectUnmanagedOwnershipMutation() {
        if (storage.runtimeOrigin() != null)
            throw new IllegalStateException("ARRAY mutation lacks its complete physical runtime publisher protocol");
        Object authority=storage.ownership();
        if (authority instanceof NativeGlobals)
            throw new IllegalStateException("Native global table mutation requires its exact named-slot publisher");
        if (authority instanceof php.runtime.PhpOwnershipArrays.StorageCertificate)
            ((php.runtime.PhpOwnershipArrays.StorageCertificate)authority).checkMutation(this);
        else if (authority instanceof php.runtime.PhpOwnershipObjects.PropertiesCertificate)
            ((php.runtime.PhpOwnershipObjects.PropertiesCertificate)authority).checkMutation(this);
    }

    public static final class WriteFetchLease implements AutoCloseable {
        private final ArrayStorage captured;
        private Object previous;
        private final php.runtime.PhpRuntimeOwnership.Diagnostic diagnostic;
        private boolean closed,copiedDuringDiagnostic;
        private WriteFetchLease(ArrayMemory actual) {
            captured=actual.storage;
            try {
                diagnostic=captured.runtimeOrigin() == null ? null
                        : captured.runtimeOrigin().authority().currentFrame().pinDiagnostic(actual);
            } catch(Throwable failure){throw ArrayMemory.<RuntimeException,RuntimeException>runtimeFailure(failure);}
            previous=captured.authority;captured.authority=this;
        }
        /** After the diagnostic temporary is discounted, resolve the sole actual storage publisher. */
        public ArrayMemory continuation(Memory current) {
            if (closed) throw new IllegalStateException("Diagnostic EVAL was released");
            if (captured.runtimeOrigin() != null)
                return captured.runtimeOrigin().writableLayoutAfterDiagnostic(diagnostic.holder());
            return isAttached(current) ? current.toValue(ArrayMemory.class) : null;
        }
        /** A witnessed extra published VALUE is enough to reject a shared write; this is not a full owner census. */
        public ArrayMemory continuation(Memory current,Environment env) {
            ArrayMemory writer=continuation(current);
            if(captured.runtimeOrigin()!=null || !copiedDuringDiagnostic || env==null)return writer;
            return solePublishedWriter(writer,env);
        }
        private ArrayMemory solePublishedWriter(ArrayMemory writer,Environment env) {
            Deque<Memory> pending=new ArrayDeque<>();Set<Memory> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            pending.add(env.getRuntimeGlobalsTable());
            var handler=env.getErrorHandler();if(handler!=null && handler.invokerMemory!=null)pending.add(handler.invokerMemory);
            var previousHandler=env.getPreviousErrorHandler();if(previousHandler!=null && previousHandler.invokerMemory!=null)pending.add(previousHandler.invokerMemory);
            Set<Object> objects=Collections.newSetFromMap(new IdentityHashMap<>());
            while(!pending.isEmpty()) {
                Memory value=pending.removeFirst();if(!seen.add(value))continue;
                if(value instanceof ReferenceMemory) {
                    Memory raw=((ReferenceMemory)value).getRawValue();if(raw!=null)pending.add(raw);
                }else if(value instanceof ArrayMemory) {
                    ArrayMemory array=(ArrayMemory)value;
                    if(array.storage==captured) {
                        if(writer!=null && array!=writer)return null;
                        writer=array;
                    }
                    // Direct storage traversal changes neither cursors nor user-visible values.
                    if(array.map!=null) {var entries=array.map.entriesIterator();while(entries.hasNext())pending.add((Memory)entries.next());}
                    else if(array._list!=null)pending.addAll(array._list);
                }else if(value instanceof ObjectMemory) {
                    Object object=((ObjectMemory)value).value;if(!objects.add(object))continue;
                    if(object instanceof php.runtime.lang.BaseObject) {
                        ArrayMemory properties=((php.runtime.lang.BaseObject)object).peekProperties();if(properties!=null)pending.add(properties);
                    }
                    if(object instanceof php.runtime.lang.Closure)((php.runtime.lang.Closure)object).appendMemoryRoots(pending);
                }
            }
            return writer;
        }
        private void recordValueCopy() {
            WriteFetchLease lease=this;
            while(true) {lease.copiedDuringDiagnostic=true;if(!(lease.previous instanceof WriteFetchLease))return;lease=(WriteFetchLease)lease.previous;}
        }
        /** ASSIGN_DIM continues through the current CV only while the converted allocation survives. */
        public boolean hasSurvivingValueOwners() {
            if (closed || diagnostic == null || captured.runtimeOrigin() == null)
                throw new IllegalStateException("ARRAY owner census requires its actual diagnostic publisher");
            return captured.runtimeOrigin().hasOwnersAfterDiagnostic(diagnostic.holder());
        }
        public boolean isAttached(Memory current) {
            current = current.toValue();
            return current instanceof ArrayMemory && ((ArrayMemory) current).storage == captured;
        }
        @Override public void close() {
            if (closed) return;
            WriteFetchLease parent=null;Object current=captured.authority;
            while (current instanceof WriteFetchLease && current != this) {
                parent=(WriteFetchLease)current;current=parent.previous;
            }
            if (current != this) throw new IllegalStateException("Write-fetch lease is detached from its backing allocation");
            if (parent == null) captured.authority=previous;
            else parent.previous=previous;
            closed=true;previous=null;
            if (diagnostic != null) try {diagnostic.close();}
            catch(Throwable failure){ArrayMemory.<Object,RuntimeException>runtimeFailure(failure);}
        }
    }

    public WriteFetchLease pinWriteFetch() {
        return new WriteFetchLease(this);
    }


    protected transient ArrayMemoryList<ReferenceMemory> _list;
    protected transient ArrayMemoryMap map;

    protected transient ForeachIterator foreachIterator;

    public ArrayMemory(boolean asMap) {
        super(Type.ARRAY);
        this.storage = new ArrayStorage();

        if (asMap) {
            convertToMap();
        }

        lastLongIndex = -1;
    }

    private ArrayMemory(ArrayMemoryMap map) {
        super(Type.ARRAY);
        this.storage = new ArrayStorage();

        this.map = map;
        this.lastLongIndex = -1;
    }

    private ArrayMemory(ArrayMemoryList<ReferenceMemory> list) {
        super(Type.ARRAY);
        this.storage = new ArrayStorage();

        this.map = null;
        this._list = list;
        this.lastLongIndex = -1;
    }

    private ArrayMemory(ArrayStorage storage) {
        super(Type.ARRAY);
        this.storage = storage;
        this.lastLongIndex = -1;
    }

    public ArrayMemory() {
        this(false);
    }

    public static ArrayMemory createListed(int expectedSize) {
        if (expectedSize <= 0) {
            return new ArrayMemory();
        }

        return new ArrayMemory(new ArrayMemoryList<>(expectedSize));
    }

    public static ArrayMemory createHashed() {
        return createHashed(8);
    }

    public static ArrayMemory createHashed(int expectedSize) {
        ArrayMemoryMap map = new ArrayMemoryMap(expectedSize < 4 ? 7 : (int) (expectedSize * 1.75));
        return new ArrayMemory(map);
    }

    private List<ReferenceMemory> getList() {
        if (_list != null) {
            return _list;
        }

        synchronized (this) {
            if (_list != null) {
                return _list;
            }

            _list = new ArrayMemoryList<>(7);
        }

        return _list;
    }

    @Deprecated
    public ArrayMemory(Collection collection) {
        this();
        for (Object el : collection) {
            if (el == null) {
                add(NULL);
                continue;
            }

            MemoryOperation operation = MemoryOperation.get(el.getClass(), null);

            if (operation != null) {
                add(operation.unconvertNoThow(null, null, el));
            }
        }
    }

    @Deprecated
    public ArrayMemory(Object... array) {
        this();
        for (Object el : array) {
            if (el == null) {
                getList().add(new ReferenceMemory());
                continue;
            }

            MemoryOperation operation = MemoryOperation.get(el.getClass(), null);

            if (operation != null) {
                getList().add(new ReferenceMemory(operation.unconvertNoThow(null, null, el)));
            }
        }
        size = array.length;
        lastLongIndex = size - 1;
        nextFreeElement = size == 0 ? Long.MIN_VALUE : size;
    }

    @Deprecated
    public ArrayMemory(boolean toImmutable, Memory... array) {
        this();
        if (array != null) {
            for (Memory el : array) {
                getList().add(new ReferenceMemory(toImmutable ? el.toImmutable() : el));
            }
            size = array.length;

            lastLongIndex = size - 1;
            nextFreeElement = size == 0 ? Long.MIN_VALUE : size;
        }
    }

    @Deprecated
    public ArrayMemory(String[] array) {
        this();
        for (String el : array) {
            getList().add(new ReferenceMemory(StringMemory.valueOf(el)));
        }
        size = array.length;
        lastLongIndex = size - 1;
        nextFreeElement = size == 0 ? Long.MIN_VALUE : size;
    }

    @Deprecated
    public ArrayMemory(Map map) {
        this();
        for (Object key : map.keySet()) {
            Object el = map.get(key);
            put(ArrayMemory.toKey(MemoryUtils.valueOf(key)), MemoryUtils.valueOf(el));
        }
    }

    public static Memory valueOf() {
        return new ArrayMemory();
    }

    public static ArrayMemory valueOfRef(ArrayMemory value) {
        if (value == null)
            return new ArrayMemory();
        else
            return value;
    }

    public Set<Object> keySet() {
        if (map == null) {
            Set<Object> set = new HashSet<Object>(size());

            for (int i = 0; i < size(); i++) {
                set.add(i);
            }

            return set;
        }

        Set<Object> keys=map.keySet();
        return storage.runtimeOrigin() != null ? Collections.unmodifiableSet(keys) : keys;
    }

    public ArrayMemory duplicate() {
        ArrayMemory result = new ArrayMemory();
        result.lastLongIndex = lastLongIndex;
        result.nextFreeElement = nextFreeElement;
        result.size = size;

        if (size() == 0 && (map == null || !map.hasBucketLayout())) {
            return result;
        }

        if (map == null) {
            for (ReferenceMemory item : getList()) {
                Memory value = item.getValue();
                result.getList().add(new ReferenceMemory(value.isReference() ? value : value.toImmutable()));
            }
        } else {
            result._list = null;
            if (map.hasBucketLayout()) {
                result.map = map.copyBuckets(value -> value.isReference() ? value : value.toImmutable());
            } else {
                result.map = new ArrayMemoryMap();
                for (Map.Entry<Object, Memory> entry : map.entrySet()) {
                    Memory value = entry.getValue();
                    result.map.put(entry.getKey(), value.isReference() ? value : value.toImmutable());
                }
            }
        }

        return result;
    }

    public ArrayMemory checkCopied() {
        if (storage.runtimeOrigin() != null) {
            storage.runtimeOrigin().validate(this);
            if (!storage.runtime().initialized || storage.runtimeOrigin().node().incoming() > 1)
                throw new IllegalStateException("Shared ARRAY write requires its actual writer publication");
            return null;
        }
        rejectUnmanagedOwnershipMutation();
        boolean copied = original != null || copies > 0;
        if (copied || hasWriteFetchPins()) {
            ArrayStorage shared = storage;
            Environment.SyntheticGlobalsOrigin globalsOrigin = constructorGlobalsOrigin();
            ArrayMemory dup = duplicate();
            ForeachIterator detachedCursor = forkCurrentIterator(this, dup);
            storage = dup.storage;
            if (globalsOrigin != null) storage.ownership(shared.allocation());
            this.map = dup.map;
            this._list = dup._list;
            this.lastLongIndex = dup.lastLongIndex;
            this.nextFreeElement = dup.nextFreeElement;
            foreachIterator = detachedCursor;

            if (copied) {
                if (this.original == null) {
                    this.copies = 0;
                } else {
                    if (this.original.storage == shared) this.original.copies--;
                    this.original = null;
                    this.copies = 0;
                }
            }
            return dup;
        }
        return null;
    }

    public static Object toKey(Memory key) {
        switch (key.type) {
            case STRING: {
                if (key instanceof PhpByteStringMemory) return ((PhpByteStringMemory) key).toArrayKey();
                String key1 = key.toString();
                Memory number = integerKey(key1);
                if (number == null)
                    return key1;
                else
                    return number;
            }
            case INT:
                return key;
            case NULL:
                return Memory.CONST_EMPTY_STRING;
            case REFERENCE:
                return toKey(key.toValue());
            default:
                return LongMemory.valueOf(key.toLong());
        }
    }

    private static Memory integerKey(String value) {
        int length = value.length();
        if (length == 0) return null;
        boolean negative = value.charAt(0) == '-';
        int index = negative ? 1 : 0;
        if (index == length) return null;
        char first = value.charAt(index);
        if (first == '0') return length == 1 ? CONST_INT_0 : null;
        if (first < '1' || first > '9') return null;
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long multiplicationLimit = limit / 10, result = 0;
        for (; index < length; index++) {
            int digit = value.charAt(index) - '0';
            if (digit < 0 || digit > 9 || result < multiplicationLimit) return null;
            result *= 10;
            if (result < limit + digit) return null;
            result -= digit;
        }
        return LongMemory.valueOf(negative ? result : -result);
    }

    public boolean containsLongKey(long key) {
        return containsKey(LongMemory.valueOf(key));
    }

    public boolean containsKey(Object key) {
        if (size() == 0) {
            return false;
        }

        if (_list != null) {
            long t = MemoryUtils.valueOf(key).toLong();
            return t >= 0 && t < _list.size();
        }

        return map.containsKey(key);
    }

    private void convertToMap() {
        map = new ArrayMemoryMap();
        if (_list != null && !_list.isEmpty()) {
            int i = 0;
            for (ReferenceMemory memory : _list) {
                if (memory != null) {
                    map.put(LongMemory.valueOf(i), memory.getValue());
                }
                i++;
            }
        }

        _list = null;
    }

    public void renameKey(Memory oldKey, Memory newKey) {
        rejectUnmanagedOwnershipMutation();
        checkCopied();

        if (_list != null || map == null) {
            convertToMap();
        }

        Object key1 = toKey(oldKey);
        Object key2 = toKey(newKey);

        ForeachIterator removedCursor=currentCursorForRemoval(key1);
        Memory value=map.remove(key1);
        advanceRemovedCurrent(removedCursor);
        boolean added=!map.containsKey(key2);
        map.put(key2,value);
        if(added)nativePointerInserted(key2,map.getEntry(key2));
    }

    public Memory get(Memory key) {
        return getByScalar(toKey(key));
    }

    public ReferenceMemory getOrCreate(Memory key) {
        return getByScalarOrCreate(toKey(key));
    }

    public ReferenceMemory getOrCreateAsShortcut(Memory key) {
        return getByScalarOrCreateAsShortcut(toKey(key));
    }

    public ReferenceMemory getByScalarOrCreate(Object sKey, Memory initValue) {
        if (storage.runtimeOrigin() != null) {
            ReferenceMemory existing=getByScalar(sKey);return existing == null ? runtimePut(sKey,initValue) : existing;
        }
        rejectUnmanagedOwnershipMutation();
        ReferenceMemory value = getByScalar(sKey);

        if (value == null) {
            return put(sKey, initValue);
        }

        return value;
    }

    public ReferenceMemory getByScalarOrCreateAsShortcut(Object sKey) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        ReferenceMemory value = getByScalarOrCreate(sKey);
        return value.getReferenceForBinding();
    }

    public ReferenceMemory getByScalarOrCreate(Object sKey) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        return getByScalarOrCreate(sKey, UNDEFINED);
    }

    public ReferenceMemory getByScalar(Object key) {
        if (_list != null) {
            if (key instanceof PhpByteStringMemory) return null;
            if (key instanceof Memory) {
                long numericKey = ((Memory) key).toLong();
                if (numericKey < 0 || numericKey >= _list.size()) return null;
                int index = (int) numericKey;
                if (index >= 0 && index < _list.size()) {
                    return _list.get(index);
                } else
                    return null;
            } else
                return null;
        } else if (map != null) {
            return map.getEntry(key);
        } else {
            return null;
        }
    }

    public void add(IObject object) {
        rejectUnmanagedOwnershipMutation();
        add(new ObjectMemory(object));
    }

    public void add(long value) {
        rejectUnmanagedOwnershipMutation();
        add(LongMemory.valueOf(value));
    }

    public void add(String value) {
        rejectUnmanagedOwnershipMutation();
        add(StringMemory.valueOf(value));
    }

    public void add(double value) {
        rejectUnmanagedOwnershipMutation();
        add(new DoubleMemory(value));
    }

    public void add(boolean value) {
        rejectUnmanagedOwnershipMutation();
        add(value ? TRUE : FALSE);
    }

    public void addNull() {
        add(NULL);
    }

    public ReferenceMemory add(Memory value) {
        if (storage.runtimeOrigin() != null) {
            if (value instanceof KeyValueMemory) {
                KeyValueMemory pair=(KeyValueMemory)value;return runtimePut(pair.getArrayKey(),pair.getValue());
            }
            return runtimePut(LongMemory.valueOf(lastLongIndex+1),value);
        }
        rejectUnmanagedOwnershipMutation();
        if (value instanceof KeyValueMemory) {
            KeyValueMemory keyValue = (KeyValueMemory) value;
            Memory entry = keyValue.getValue();
            return put(keyValue.getArrayKey(), entry.isReference() ? entry : entry.toImmutable());
        }

        ReferenceMemory ref;

        if (map == null) {
            lastLongIndex++;
            recordIntegerKey(lastLongIndex);
            ref = new ReferenceMemory(value);
            getList().add(ref);
            size++;
            nativePointerInserted(LongMemory.valueOf(lastLongIndex),ref);
        } else {
            ref = put(LongMemory.valueOf(++lastLongIndex), value);
        }

        return ref;
    }

    /** Explicit certified-list construction only; no extra CELL payload publisher is attached. */
    public ReferenceMemory appendOwnershipCanonical(ReferenceMemory cell) {
        if (getOwnershipStorage() == null || !isList()) throw new IllegalStateException("Canonical carrier needs a certified list");
        rejectUnmanagedOwnershipMutation();cell.getOwnershipCell().validateAttachment(cell);
        if (cell.getOwnershipCell().domain() != getOwnershipStorage().domain()) throw new IllegalArgumentException("Foreign canonical carrier");
        lastLongIndex++;recordIntegerKey(lastLongIndex);getList().add(cell);size++;return cell;
    }
    public void replaceOwnershipConstructionCarrier(int index,ReferenceMemory cell) {
        if (getOwnershipStorage() == null || !isList()) throw new IllegalStateException("Canonical carrier needs a certified list");
        rejectUnmanagedOwnershipMutation();cell.getOwnershipCell().validateAttachment(cell);
        if (cell.getOwnershipCell().domain() != getOwnershipStorage().domain()) throw new IllegalArgumentException("Foreign canonical carrier");
        getList().set(index,cell);
    }
    public void publishOwnershipCarrier(php.runtime.PhpOwnershipArrays.CarrierPublication publication) {
        int index=publication.index(this);_list.set(index,publication.consume(this));
    }

    /** Actual layout publication; the permit has already validated all edges and addresses. */
    public final void publishOwnershipUnset(php.runtime.PhpOwnershipArrays.UnsetPublication publication) {
        ArrayMemoryMap replacement=publication.consumeArray(this,map);
        if (replacement != null) {map=replacement;_list=null;}
        else map.publishOwnershipUnlink(publication);
        size--;foreachIterator=null;
    }

    /** Native parser arrays start mixed before any view is published, keeping physical addresses stable. */
    public void initializeOwnershipRestorationMixed(php.runtime.PhpOwnershipArrays.RestorationAssembly assembly) {
        assembly.validateFreshLayout(this);if (map == null) {map=new ArrayMemoryMap();_list=null;}
    }
    /** Prepared physical layout, never an independently owning PHP copy. */
    public final class OwnershipRestorationLayout {
        private final ArrayMemoryMap beforeMap=map;
        private final ArrayMemoryList<ReferenceMemory> beforeList=_list;
        private final int beforeSize=size;
        private final Object key;
        private final ReferenceMemory oldCarrier,carrier;
        private final ArrayMemoryMap replacement;
        private final ArrayMemoryList<ReferenceMemory> preparedList;
        private final ArrayMemoryMap.OwnershipInsertion insertion;
        private final IdentityHashMap<ReferenceMemory,ReferenceMemory> relocations=new IdentityHashMap<>();
        private OwnershipRestorationLayout(php.runtime.PhpOwnershipArrays.RestorationPublication publication,Object key) {
            this.key=key;oldCarrier=publication.existingCarrier();
            long numeric=key instanceof LongMemory ? ((LongMemory)key).value : -1;
            if (map != null) {
                replacement=null;preparedList=null;
                insertion=oldCarrier == null ? map.prepareOwnershipInsertion(publication,key) : null;
                carrier=insertion == null ? oldCarrier : insertion.carrier();
            } else if (key instanceof LongMemory && numeric >= 0 && numeric <= size) {
                replacement=null;insertion=null;
                preparedList=_list == null ? new ArrayMemoryList<>(size+1) : _list;
                preparedList.ensureCapacity(size+1);
                carrier=oldCarrier == null || oldCarrier.isCanonicalReferenceCell() ? new ReferenceMemory(Memory.NULL) : oldCarrier;
                if (carrier != oldCarrier && oldCarrier != null) relocations.put(oldCarrier,carrier);
            } else {
                preparedList=null;insertion=null;replacement=new ArrayMemoryMap();int index=0;
                if (_list != null) for (ReferenceMemory existing:_list) {
                    ReferenceMemory fresh=replacement.putWithEntry(LongMemory.valueOf(index++),publication.relocationValue(existing)).getB();
                    relocations.put(existing,fresh);
                }
                carrier=replacement.putWithEntry(key,Memory.NULL).getB();
            }
        }
        public ReferenceMemory carrier() {return carrier;}
        public IdentityHashMap<ReferenceMemory,ReferenceMemory> relocations() {return relocations;}
        public void validate() {
            if (map != beforeMap || _list != beforeList || size != beforeSize)
                throw new IllegalArgumentException("Prepared actual array layout is stale");
            if (insertion != null) insertion.validate();
        }
        public void store(php.runtime.PhpOwnershipArrays.RestorationPublication publication) {
            publication.consumeLayout(ArrayMemory.this,this);
            if (replacement != null) {map=replacement;_list=null;}
            else if (insertion != null) insertion.store(publication);
            else if (map == null) {
                _list=preparedList;
                int index=(int)((LongMemory)key).value;
                if (oldCarrier == null) _list.add(carrier);else if (carrier != oldCarrier) _list.set(index,carrier);
            }
            if (oldCarrier == null) size++;
            if (key instanceof LongMemory) {long index=((LongMemory)key).value;if (index > lastLongIndex) lastLongIndex=index;recordIntegerKey(index);}
            foreachIterator=null;
        }
    }
    public OwnershipRestorationLayout prepareOwnershipRestorationLayout(php.runtime.PhpOwnershipArrays.RestorationPublication publication,Object key) {
        publication.validateArrayPreparation(this);return new OwnershipRestorationLayout(publication,key);
    }
    public void unpack(Memory source, Environment env, TraceInfo trace) {
        rejectUnmanagedOwnershipMutation();
        unpack(source, env, trace, env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5);
    }

    public void unpack(Memory source, Environment env, TraceInfo trace, boolean php8) {
        rejectUnmanagedOwnershipMutation();
        source = source.toValue();
        if (!source.isTraversable()) {
            env.exception(trace, BaseError.class, "Only arrays and Traversables can be unpacked");
            return;
        }

        ForeachIterator iterator = source.getNewIterator(env, false, false);
        if (iterator == null) {
            env.exception(trace, BaseError.class, "Only arrays and Traversables can be unpacked");
            return;
        }
        checkCopied();
        while (iterator.next()) {
            Memory key = iterator.getMemoryKey();
            Memory value = iterator.getValue();
            Memory entry = source.isArray() && value.isReference() ? value : value.toImmutable();
            if (key.getRealType() == Type.INT) {
                if (php8) addPhp8(entry, env, trace); else add(entry);
            } else if (key.isString()) {
                Object normalizedKey = toKey(key);
                if (!(normalizedKey instanceof LongMemory)) {
                    put(normalizedKey, entry);
                } else {
                    if (php8) addPhp8(entry, env, trace); else add(entry);
                }
            } else {
                env.exception(trace, BaseError.class, "Keys must be of type int|string during array unpacking");
                return;
            }
        }
    }

    /**
     * @param array
     * @param recursive
     */
    public void merge(ArrayMemory array, boolean recursive, Set<Integer> done) {
        rejectUnmanagedOwnershipMutation();
        checkCopied();

        if (recursive && done == null) {
            done = new HashSet<>();
        }

        if (map == null && array.map == null) {
            int firstNewIndex=getList().size();
            for (ReferenceMemory reference : array.getList())
                getList().add(new ReferenceMemory(reference.toImmutable()));
            if(firstNewIndex < getList().size())nativePointerInserted(LongMemory.valueOf(firstNewIndex),getList().get(firstNewIndex));

            size = getList().size();
            lastLongIndex = size - 1;
            nextFreeElement = size;
        } else {
            if (map == null) {
                convertToMap();
            }

            if (array.map == null) {
                for (ReferenceMemory reference : array.getList()) {
                    add(reference.toImmutable());
                }
            } else {
                for (Map.Entry<Object, Memory> entry : array.map.entrySet()) {
                    Object key = entry.getKey();
                    if (key instanceof LongMemory) {
                        add(entry.getValue().toImmutable());
                    } else {
                        Memory value = entry.getValue();
                        if (recursive && value.isArray()) {
                            if (done.contains(value.getPointer()))
                                throw new RecursiveException();

                            Memory current = getByScalar(key).toImmutable();
                            if (current.isArray()) {
                                value = value.toImmutable();

                                int pointer = value.getPointer();
                                done.add(pointer); // for check recursive

                                ArrayMemory result = (ArrayMemory) value; // already array immutable above
                                result.merge((ArrayMemory) current, recursive, done);
                                put(key, result);

                                done.remove(pointer);
                            } else
                                put(key, value.toImmutable());
                        } else {
                            put(key, value.toImmutable());
                        }
                    }
                }
            }
        }
    }

    public void putAll(ArrayMemory array) {
        rejectUnmanagedOwnershipMutation();
        if (array.map == null) {
            int i = 0;
            for (ReferenceMemory memory : array.getList()) {
                if (memory != null)
                    put(LongMemory.valueOf(i), memory.toImmutable());
                i++;
            }
        } else {
            if (map == null) {
                convertToMap();
            }

            if (array.lastLongIndex > lastLongIndex)
                lastLongIndex = array.lastLongIndex;

            for (Map.Entry<Object, Memory> entry : array.map.entrySet()) {
                put(entry.getKey(), entry.getValue().toImmutable());
            }
        }
    }

    public void putAllRef(ArrayMemory array) {
        rejectUnmanagedOwnershipMutation();
        if (array.map == null) {
            int i = 0;
            for (ReferenceMemory memory : array.getList()) {
                if (memory != null)
                    put(LongMemory.valueOf(i), memory);
                i++;
            }
        } else {
            if (map == null) {
                convertToMap();
            }

            if (array.lastLongIndex > lastLongIndex) {
                lastLongIndex = array.lastLongIndex;
            }

            if (array.hasBucketLayout()) enableBucketLayout();
            Object firstNewKey=null;
            if(foreachIterator instanceof NativePointerCursor && foreachIterator.getKey() == null)
                for(Object key:array.map.keySet())if(!map.containsKey(key)){firstNewKey=key;break;}
            map.putAll(array.map);
            if(firstNewKey != null)nativePointerInserted(firstNewKey,map.getEntry(firstNewKey));
        }
    }

    /** Insert a physical PHP bucket, including a duplicate key, without rebinding older cells. */
    public ReferenceMemory insertBucket(Object key, Memory value) {
        rejectUnmanagedOwnershipMutation();
        enableBucketLayout();
        if (key instanceof LongMemory) recordIntegerKey(((LongMemory) key).value);
        ReferenceMemory result = map.insertBucket(key, value);
        size++;
        nativePointerInserted(key,result);
        return result;
    }

    public void enableBucketLayout() {
        rejectUnmanagedOwnershipMutation();
        if (hasBucketLayout()) return;
        // Layout publication is writer-local too: sibling VALUE views retain their original cursor/backing pair.
        checkCopied();
        if (map == null) convertToMap();
        if (foreachIterator instanceof ListMapCurrentCursor)
            foreachIterator=((ListMapCurrentCursor)foreachIterator).materializeBuckets(this);
        else if(foreachIterator instanceof NativePointerCursor)
            ((NativePointerCursor)foreachIterator).materializeBuckets();
        else map.enableBucketLayout();
    }

    public boolean hasNumericStringKeys() {
        if (map == null) return false;
        for (Object key : map.keySet()) if (key instanceof String && integerKey((String) key) != null) return true;
        return false;
    }

    public void appendBucketHole() { enableBucketLayout(); map.appendBucketHole(); }

    public boolean hasBucketLayout() { return map != null && map.hasBucketLayout(); }
    public int bucketLimit() { return map == null ? 0 : map.bucketLimit(); }
    public ReferenceMemory bucketAt(int ordinal) { return map == null ? null : map.bucketAt(ordinal); }
    public int bucketOrdinal(ReferenceMemory entry) {
        return map == null || !(entry instanceof ArrayMapEntryMemory) ? -1
                : map.bucketOrdinal((ArrayMapEntryMemory) entry);
    }
    public Object bucketKey(int ordinal) {
        ArrayMapEntryMemory entry = map == null ? null : map.bucketAt(ordinal);
        return entry == null ? null : entry.getKey();
    }

    public ReferenceMemory putAsKeyString(String key, Memory value) {
        if (storage.runtimeOrigin() != null) return runtimePut(key,value);
        rejectUnmanagedOwnershipMutation();
        if (map == null) {
            convertToMap();
        }

        Pair<Memory, ArrayMapEntryMemory> result = map.putWithEntry(key, value);

        if (!result.hasA()) {
            size++;
            nativePointerInserted(key,result.getB());
        }

        return result.getB();
    }

    public ReferenceMemory put(Object key, Memory value) {
        if (key instanceof PhpByteStringMemory) key = ((PhpByteStringMemory) key).toArrayKey();
        if (storage.runtimeOrigin() != null) return runtimePut(key instanceof LongMemory || key instanceof String || key instanceof PhpByteStringMemory ? key : key.toString(),value);
        rejectUnmanagedOwnershipMutation();
        if (key instanceof LongMemory) {
            ReferenceMemory mem = new ReferenceMemory(value);

            long numericKey = ((LongMemory) key).value;
            int index = numericKey >= 0 && numericKey <= Integer.MAX_VALUE ? (int) numericKey : -1;

            if (numericKey > lastLongIndex)
                lastLongIndex = numericKey;
            recordIntegerKey(numericKey);

            if (map == null) {
                int size = getList().size();
                if (index >= 0) {
                    if (index < size) {
                        getList().set(index, mem);
                        //this.size++;
                        return mem;
                    } else if (index == size) {
                        getList().add(mem);
                        this.size++;
                        nativePointerInserted(key,mem);
                        return mem;
                    } else {
                        convertToMap();
                    }
                } else
                    convertToMap();
            }
        } else {
            if (!(key instanceof String) && !(key instanceof PhpByteStringMemory))
                key = key.toString();

            if (map == null) {
                convertToMap();
            }
        }

        Pair<Memory, ArrayMapEntryMemory> result = map.putWithEntry(key, value);

        if (!result.hasA()) {
            size++;
            nativePointerInserted(key,result.getB());
        }

        return result.getB();
    }

    /** Bucket identity, not an equal key/value, decides whether the internal pointer moves. */
    private boolean cursorMatchesRemoval(ForeachIterator cursor, Object key) {
        if (cursor == null || cursor.getKey() == null) return false;
        if (hasBucketLayout()) {
            int ordinal = cursor instanceof BucketCursor ? ((BucketCursor) cursor).currentOrdinal()
                    : cursor instanceof NativeReadCursor ? ((NativeReadCursor) cursor).ordinal
                    : cursor instanceof NativePointerCursor ? ((NativePointerCursor) cursor).ordinal
                    : cursor instanceof ListMapCurrentCursor ? ((ListMapCurrentCursor) cursor).physicalOrdinal : -1;
            ReferenceMemory entry = bucketAt(ordinal);
            return entry != null && entry == map.getEntry(key);
        }
        Object currentKey = cursor.getKey();
        if (currentKey instanceof Long) currentKey = LongMemory.valueOf((Long) currentKey);
        else if (currentKey instanceof Integer) currentKey = LongMemory.valueOf((Integer) currentKey);
        if (map == null) return Objects.equals(currentKey, key);
        ReferenceMemory entry = map.getEntry(currentKey);
        return entry != null && entry == map.getEntry(key);
    }

    private ForeachIterator currentCursorForRemoval(Object key) {
        ForeachIterator cursor = foreachIterator;
        if (!cursorMatchesRemoval(cursor, key)) return null;
        if(cursor instanceof NativePointerCursor) {
            NativePointerCursor nativeCursor=(NativePointerCursor)cursor;
            if(nativeCursor.readOnly())nativeCursor=(NativePointerCursor)nativeCursor.fork(this,this,this);
            nativeCursor.prepareRemoval();return nativeCursor;
        }
        if (cursor instanceof CurrentCursor && ((CurrentCursor) cursor).readOnly())
            return ((CurrentCursor) cursor).fork(this, this, this);
        if (cursor instanceof NativeReadCursor) {
            // This VALUE snapshot has a frozen position, without an edge to the source array.
            if (hasBucketLayout()) {
                BucketCursor live = new BucketCurrentCursor(this,false);
                live.seekOrdinal(((NativeReadCursor) cursor).ordinal);
                return live;
            }
            cursor = foreachIterator(false, true);
            while (cursor.next()) {
                if (cursorMatchesRemoval(cursor, key)) return cursor;
            }
            return null;
        }
        return cursor;
    }

    private void advanceRemovedCurrent(ForeachIterator cursor) {
        if (cursor != null) {
            if(cursor instanceof NativePointerCursor)((NativePointerCursor)cursor).removed();else cursor.next();
            foreachIterator = cursor;
            pointerNeedsFirst = false;
        }
    }

    public Memory removeByScalar(Object key) {
        if (storage.runtimeOrigin() != null) return runtimeRemove(key instanceof Memory ? toKey((Memory)key) : key);
        rejectUnmanagedOwnershipMutation();
        if (map == null) {
            long numericKey = -1;
            if (key instanceof Long)
                numericKey = (Long) key;
            else if (key instanceof Integer)
                numericKey = (Integer) key;
            else if (key instanceof LongMemory)
                numericKey = ((LongMemory) key).value;
            else if (key instanceof String) {
                Memory tmp = integerKey((String) key);
                if (tmp != null)
                    numericKey = tmp.toLong();
            }
            int index = numericKey >= 0 && numericKey <= Integer.MAX_VALUE ? (int) numericKey : -1;

            if (index < 0 || _list == null || index >= getList().size())
                return null;

            if (index == size - 1) {
                ForeachIterator cursor = currentCursorForRemoval(LongMemory.valueOf(index));
                size--;
                lastLongIndex = size - 1;
                ReferenceMemory remove = getList().remove(index);

                if (getList().isEmpty()) {
                    _list = null;
                }

                advanceRemovedCurrent(cursor);
                return remove;
            } else {
                key = (long) index;
                convertToMap();
            }
        }

        {
            if (key instanceof Long)
                key = LongMemory.valueOf((Long) key);
            else if (key instanceof Integer)
                key = LongMemory.valueOf((Integer) key);
            else if (key instanceof String) {
                Memory tmp = integerKey((String) key);
                if (tmp != null)
                    key = tmp;
            }

            ForeachIterator cursor = currentCursorForRemoval(key);
            Memory memory = map.remove(key);
            if (memory != null) {
                size--;
                advanceRemovedCurrent(cursor);
            }

            return memory;
        }
    }

    public Memory remove(Memory key) {
        if (storage.runtimeOrigin() != null) return runtimeRemove(toKey(key));
        rejectUnmanagedOwnershipMutation();
        Object _key = toKey(key);
        if (map == null) {
            long numericKey = _key instanceof LongMemory ? ((LongMemory) _key).value : -1;
            int index = numericKey >= 0 && numericKey <= Integer.MAX_VALUE ? (int) numericKey : -1;
            if (index < 0 || _list == null || index >= getList().size())
                return null;

            if (index == size - 1) {
                ForeachIterator cursor = currentCursorForRemoval(LongMemory.valueOf(index));
                size--;
                lastLongIndex = index - 1;
                ReferenceMemory remove = getList().remove(index);

                if (getList().isEmpty()) {
                    _list = null;
                }

                advanceRemovedCurrent(cursor);
                return remove;
            }

            //key = LongMemory.valueOf(index);
            convertToMap();
        }

        {
            ForeachIterator cursor = currentCursorForRemoval(_key);
            Memory memory = map.remove(_key);
            if (memory != null) {
                size--;
                advanceRemovedCurrent(cursor);
            }

            return memory;
        }
    }

    /** Only the exact array publisher may unlink this physical address; the CELL payload stays intact. */
    public final class RuntimeRemoval {
        private final ArrayMemoryMap actualMap=map;
        private final int actualSize=size;
        private final Object key;
        private final ReferenceMemory carrier;
        private final php.runtime.PhpRuntimeOwnership.Holder holder;
        private boolean unlinked;
        private RuntimeRemoval(Object key,ReferenceMemory carrier) {
            this.key=key;this.carrier=carrier;holder=carrier.getRuntimeHolder();
            if (storage.runtimeOrigin() == null || holder == null || !holder.matches(carrier))
                throw new IllegalStateException("Actual ARRAY entry has no physical publisher");
        }
        public ReferenceMemory mapEntry(ArrayMemoryMap actual) {
            if (unlinked || actual != actualMap || map != actualMap || size != actualSize
                    || actualMap.getEntry(key) != carrier || carrier.getRuntimeHolder() != holder)
                throw new IllegalArgumentException("Runtime unlink no longer identifies its physical address");
            return carrier;
        }
        public void checkHolder(php.runtime.PhpRuntimeOwnership.Holder actual,ReferenceMemory address) {
            if (mapEntry(actualMap) != address || actual != holder)
                throw new IllegalArgumentException("Runtime removal requires its exact actual address holder");
        }
        public void consumeMap(ArrayMemoryMap actual,ReferenceMemory wanted) {
            if (mapEntry(actual) != wanted) throw new IllegalArgumentException("Runtime unlink has another bucket");
            unlinked=true;
        }
    }
    private Memory runtimeRemove(Object key) {
        storage.runtimeOrigin().validate(this);checkCopied();
        ReferenceMemory carrier=map.getEntry(key);
        if (carrier == null) return null;
        RuntimeRemoval removal=new RuntimeRemoval(key,carrier);Memory raw=carrier.getRawValue();
        try {
            try (var publication=removal.holder.prepareArrayRemoval(removal)) {
                publication.storeRuntimeRemoval(removal);
                map.publishRuntimeUnlink(removal);size--;foreachIterator=null;publication.commit();
            }
            removal.holder.close();return raw;
        } catch(Throwable failure){return runtimeFailure(failure);}
    }

    public int size() {
        return size;
    }

    public void unshift(Memory value, int count) {
        rejectUnmanagedOwnershipMutation();
        if (count < 0) throw new IllegalArgumentException("count must be non-negative");
        Memory[] values = new Memory[count];
        Arrays.fill(values, value);
        unshift(values);
    }

    public void unshift(Memory... values) {
        rejectUnmanagedOwnershipMutation();
        Objects.requireNonNull(values);
        checkCopied();
        if (map == null) {
            List<ReferenceMemory> prefix = new ArrayList<>(values.length);
            for (Memory value : values) prefix.add(new ReferenceMemory(value));
            getList().addAll(0, prefix);
            size = _list.size();
            lastLongIndex = size - 1;
            nextFreeElement = size == 0 ? Long.MIN_VALUE : size;
            resetToFirst();
            return;
        }
        ArrayMemory result = new ArrayMemory();
        for (Memory value : values) result.add(value);
        copyReindexed(result);
        replaceStorage(result);
    }

    // Preserve shared PHP reference cells while rebuilding numeric keys.
    private void copyReindexed(ArrayMemory result) {
        if (map == null) {
            if (_list != null) for (ReferenceMemory cell : _list) result.add(cell.getValue());
        } else {
            for (Map.Entry<Object, Memory> entry : map.entrySet()) {
                Memory value = entry.getValue();
                if (entry.getKey() instanceof String || entry.getKey() instanceof PhpByteStringMemory) result.put(entry.getKey(), value);
                else result.add(value);
            }
        }
    }

    private void replaceStorage(ArrayMemory result) {
        _list = result._list;
        map = result.map;
        size = result.size;
        lastLongIndex = result.lastLongIndex;
        nextFreeElement = result.nextFreeElement;
        resetToFirst();
    }

    public Memory shift() {
        rejectUnmanagedOwnershipMutation();
        checkCopied();
        if (size < 1) return null;
        Memory value = map == null ? _list.remove(0) : map.remove(map.firstKey());
        size--;
        if (map == null) {
            lastLongIndex = size - 1;
            nextFreeElement = size;
            resetToFirst();
            return value.toValue();
        }
        ArrayMemory result = new ArrayMemory();
        copyReindexed(result);
        if (result.nextFreeElement == Long.MIN_VALUE) result.nextFreeElement = 0;
        replaceStorage(result);
        return value.toValue();
    }

    public Memory pop() {
        rejectUnmanagedOwnershipMutation();
        checkCopied();
        if (size < 1) return null;
        Memory key = peekKey();
        Memory value = map == null ? _list.remove(size - 1) : map.remove(map.lastKey());
        size--;
        if (key.getRealType() == Memory.Type.INT) {
            long index = key.toLong();
            if (nextFreeElement != Long.MIN_VALUE && index == nextFreeElement - 1) nextFreeElement = index;
            if (index == lastLongIndex) lastLongIndex = Math.max(-1, index - 1);
        }
        resetToFirst();
        return value.toValue();
    }

    private void resetToFirst() {
        reset();
        pointerNeedsFirst = true;
    }

    public Memory peek() {
        if (size < 1)
            return null;

        Memory value;
        if (map == null)
            value = getList().get(size - 1);
        else {
            value = map.get(map.lastKey());
        }

        return value.toValue();
    }

    /** Carrier-preserving permutation for unmanaged object property tables. */
    public void reorderKeys(java.util.List<Object> keys) {
        rejectUnmanagedOwnershipMutation();
        if (map == null) {
            if (keys.size() != size) throw new IllegalArgumentException("Incomplete table permutation");
            for (int i = 0; i < size; i++) if (!LongMemory.valueOf(i).equals(keys.get(i)))
                throw new IllegalArgumentException("A list permutation requires keyed storage");
        } else map.reorderKeys(keys);
        foreachIterator = null;
    }

    /** First physical key without allocating an iterator or changing the internal pointer. */
    public Memory peekFirstKey() {
        if (size < 1)
            return null;
        if (map == null)
            return LongMemory.valueOf(0);
        Object key = map.firstKey();
        return key instanceof Memory ? (Memory) key : StringMemory.valueOf((String) key);
    }

    public Memory peekKey() {
        if (size < 1)
            return null;

        Memory value;
        if (map == null)
            return LongMemory.valueOf(size - 1);
        else {
            Object key = map.lastKey();
            if (key instanceof Memory) {
                return (Memory) key;
            } else if (key instanceof String) {
                return StringMemory.valueOf(key.toString());
            }
        }

        return null;
    }

    public Memory getRandomElementKey(Random rnd) {
        int index = rnd.nextInt(size);
        if (map == null) {
            return LongMemory.valueOf(index);
        } else {
            Iterator<Object> keys = map.keySet().iterator();
            for (int i = 0; i < index; i++) {
                keys.next();
            }

            Object key = keys.next();
            if (key instanceof Memory)
                return (Memory) key;
            else
                return new StringMemory((String) key);
        }
    }

    public void shuffle(Random rnd) {
        rejectUnmanagedOwnershipMutation();
        checkCopied();
        if (map == null) {
            if (_list != null) {
                Collections.shuffle(getList(), rnd);
            }
        } else {
            Set<Object> keys = map.keySet();

            List<Memory> values = new ArrayList<>(map.values());
            Collections.shuffle(values, rnd);

            int i = 0;
            for (Object key : keys) {
                map.put(key, values.get(i));
                i++;
            }
        }
    }

    public void clear() {
        rejectUnmanagedOwnershipMutation();
        Object globalsMarker = constructorGlobalsOrigin() == null ? null : storage.allocation();
        storage = new ArrayStorage();
        if (globalsMarker != null) storage.ownership(globalsMarker);
        _list = null;
        map = null;

        size = 0;
        nextFreeElement = Long.MIN_VALUE;
    }

    public int compare(ArrayMemory otherRef, boolean strict) {
        return compare(otherRef, strict, null);
    }

    public int compare(ArrayMemory otherRef, boolean strict, Set<Integer> used) {
        int size1 = size(), size2 = otherRef.size();

        if (size1 < size2)
            return -1;
        else if (size1 > size2)
            return 1;

        ForeachIterator iterator = this.foreachIterator(false, false);
        ForeachIterator iterator2 = null;
        if (strict)
            iterator2 = otherRef.foreachIterator(false, false);

        if (used == null)
            used = new HashSet<Integer>();

        while (iterator.next()) {
            Memory value1 = iterator.getValue();
            Memory key = iterator.getMemoryKey();
            Memory value2;
            if (iterator2 == null)
                value2 = otherRef.get(key);
            else {
                if (!iterator2.next())
                    return -2;
                Object key2 = iterator2.getKey();
                if (!iterator.getKey().equals(key2))
                    return -2;

                value2 = iterator2.getValue();
            }

            if (value2 == null) {
                value2 = UNDEFINED;
                //return -2;
            }

            if (value1.isArray() && value2.isArray()) {
                ArrayMemory arr1 = value1.toValue(ArrayMemory.class);
                if (used.add(value2.getPointer())) {
                    int r = arr1.compare(value2.toValue(ArrayMemory.class), strict, used);
                    if (r == 0) {
                        used.remove(value2.getPointer());
                        continue;
                    }
                    return r;
                }
                used.remove(value2.getPointer());
            } else if (value1.isObject() && value2.isObject()) {
                ObjectMemory o1 = value1.toValue(ObjectMemory.class);
                ObjectMemory o2 = value2.toValue(ObjectMemory.class);

                return o1.compare(o2.value, strict, used);
            } else {
                if ((strict && value1.identical(value2)) || (!strict && value1.equal(value2)))
                    continue;

                if (value1.smaller(value2))
                    return -1;
                else
                    return 1;
            }
        }
        return 0;
    }

    /** The actual symbol table is structural, never a shared modern PHP ARRAY value. */
    private ArrayMemory snapshotModernGlobals() {
        Environment.SyntheticGlobalsOrigin origin = constructorGlobalsOrigin();
        Object authority = storage.ownership();
        NativeGlobals nativeGlobals = authority instanceof NativeGlobals ? (NativeGlobals) authority : null;
        Environment environment = origin != null ? origin.environment()
                : nativeGlobals != null && nativeGlobals.actual() == this ? nativeGlobals.environment : null;
        if (environment == null) return null;
        if (nativeGlobals != null) nativeGlobals.validate(environment);
        if (environment.getModuleManager().getPhpLanguageProfile(environment.trace())
                != php.runtime.common.PhpLanguageProfile.PHP_8_5) return null;
        // Active ownership requires its existing complete allocation/publication protocol.
        // A structural read here must not manufacture an uncertified ARRAY allocation.
        if (php.runtime.PhpRuntimeOwnership.current(environment) != null) return null;
        ReferenceMemory synthetic = null;
        if (origin != null && !origin.isRetired() && map != null
                && map.getEntry("GLOBALS") == origin.carrier()) {
            origin.validateCarrier(origin.carrier());
            synthetic = origin.carrier();
        }
        ArrayMemory snapshot = ArrayMemory.createHashed(size - (synthetic == null ? 0 : 1));
        if (map != null) for (Map.Entry<Object, Memory> entry : map.entrySet()) {
            if (synthetic != null && "GLOBALS".equals(entry.getKey())) continue;
            Memory value = entry.getValue();
            snapshot.put(entry.getKey(), value.isReference() ? value : value.toImmutable());
        }
        else if (_list != null) for (ReferenceMemory entry : _list) {
            Memory value = entry.getValue();
            snapshot.add(value.isReference() ? value : value.toImmutable());
        }
        snapshot.lastLongIndex = lastLongIndex;
        snapshot.nextFreeElement = nextFreeElement;
        return snapshot;
    }

    @Override
    public Memory toImmutable() {
        return valueCopy(true, false);
    }

    @Override
    public Memory toImmutableForLegacy() {
        if (getClass() != ArrayMemory.class && !INHERITS_IMMUTABLE_CONVERSION.get(getClass()))
            return toImmutable();
        return valueCopy(false, true);
    }

    // Native read-only copies capture one frozen cursor, rather than a mutable cursor then a replacement.
    private ArrayMemory valueCopy(boolean copyCursor, boolean legacyReset) {
        ArrayMemory globalsSnapshot = snapshotModernGlobals();
        if (globalsSnapshot != null) return globalsSnapshot;
        if (copies >= 0) {
            ArrayMemory mem = new ArrayMemory(storage);
            if(storage.authority instanceof WriteFetchLease)((WriteFetchLease)storage.authority).recordValueCopy();
            mem._list = _list;
            // All VALUE views of this backing charge its still-attached origin, not a mutable intermediate view.
            ArrayMemory origin = original != null && original.storage == storage ? original : this;
            mem.original = origin;
            mem.size = size;
            mem._list = _list;
            mem.map = map;
            mem.lastLongIndex = lastLongIndex;
            mem.nextFreeElement = nextFreeElement;
            origin.copies++;
            if (legacyReset) {
                // Historical assignment resets both views; early GLOBALS/constant returns stay untouched.
                reset();
            } else {
                mem.pointerNeedsFirst = pointerNeedsFirst;
                if (copyCursor) mem.foreachIterator = forkCurrentIterator(mem, mem);
            }
            return mem;
        } else {
            copies++;
            return this;
        }
    }

    /** Modern PHP arrays start on their first element; exhausted cursors stay exhausted. */
    public ForeachIterator getCurrentIteratorForNative() {
        if (foreachIterator == null) {
            boolean alreadyStartsFirst=pointerNeedsFirst;
            ForeachIterator cursor=getCurrentIterator();
            if (!alreadyStartsFirst) cursor.next();
        }
        return foreachIterator;
    }

    /** Explicit native pointer operations do not replace generic snapshot continuation. */
    public ForeachIterator getCurrentPointerForNative() {
        if(!(foreachIterator instanceof NativePointerCursor)) {
            ForeachIterator previous=foreachIterator;
            NativePointerCursor cursor=new NativePointerCursor(this);
            if(previous == null)cursor.first();
            else {
                int position=previous instanceof BucketCursor ? ((BucketCursor)previous).currentOrdinal()
                        : previous instanceof NativeReadCursor ? ((NativeReadCursor)previous).ordinal
                        : previous instanceof ListMapCurrentCursor ? ((ListMapCurrentCursor)previous).physicalOrdinal : -1;
                cursor.select(previous.getKey(),position);
            }
            foreachIterator=cursor;pointerNeedsFirst=false;
        }
        return foreachIterator;
    }

    /** Moving a read-only VALUE snapshot materializes only its optional cursor, never its backing. */
    public ForeachIterator getMutableCurrentIteratorForNative() {
        NativePointerCursor cursor=(NativePointerCursor)getCurrentPointerForNative();
        if(cursor.readOnly())foreachIterator=cursor.fork(this,this,this);
        return foreachIterator;
    }

    public boolean hasNativeCurrentIterator() {return foreachIterator instanceof NativePointerCursor;}

    /** COW snapshot for native read-only inputs; copying does not reset the caller's PHP cursor. */
    public ArrayMemory toImmutableForNativeRead() {
        ForeachIterator cursor=getCurrentIteratorForNative();
        Memory keyMemory=cursor == null ? null : cursor.getMemoryKey().toValue().toImmutable();
        Object key=cursor == null ? null : cursor.getKey();
        int ordinal=cursor instanceof BucketCursor ? ((BucketCursor)cursor).currentOrdinal()
                : cursor instanceof NativeReadCursor ? ((NativeReadCursor)cursor).ordinal
                : cursor instanceof NativePointerCursor ? ((NativePointerCursor)cursor).ordinal
                : cursor instanceof ListMapCurrentCursor ? ((ListMapCurrentCursor)cursor).physicalOrdinal : -1;
        Memory current=cursor == null ? null : cursor.getValue();
        ArrayMemory snapshot=valueCopy(false, false);
        if (snapshot != this && cursor != null) {
            if (cursor instanceof CurrentCursor && snapshot.storage == storage)
                snapshot.foreachIterator=((CurrentCursor)cursor).fork(this,null,this);
            else snapshot.foreachIterator=new NativeReadCursor(key,keyMemory,current,ordinal);
        }
        return snapshot;
    }

    // Native current/key/pos only read this captured position. No iterator edge to the original array.
    private static final class NativeReadCursor extends ForeachIterator {
        private final int ordinal;
        NativeReadCursor(Object key, Memory keyMemory, Memory value, int ordinal) {
            super(false,false,true);
            this.ordinal=ordinal;
            currentKey=key;currentKeyMemory=keyMemory;currentValue=value;
            init=true;
        }
        @Override protected boolean init() {return false;}
        @Override protected boolean nextValue() {return false;}
        @Override protected boolean prevValue() {return false;}
        @Override public void reset() {}
    }

    public ArrayMemory toConstant() {
        if (copies == 0)
            copies--;
        else
            throw new RuntimeException("Cannot convert array to a constant value with copies != 0");
        return this;
    }

    public Memory[] values(boolean asImmutable) {
        Memory[] result = new Memory[size];
        int i = 0;
        for (ReferenceMemory el : this) {
            result[i++] = asImmutable ? el.toImmutable() : el.toValue();
        }
        return result;
    }

    public Memory[] values() {
        return values(false);
    }

    public Memory[] valuesForCall() {
        Memory[] result = new Memory[size];
        int i = 0;
        for (ReferenceMemory cell : this) {
            Memory value = cell.getValue();
            result[i++] = value.isReference() ? ReferenceMemory.exportReference(value) : value.toImmutable();
        }
        return result;
    }

    @Override
    public long toLong() {
        return size == 0 ? 0 : 1;
    }

    @Override
    public double toDouble() {
        return size == 0 ? 0 : 1;
    }

    @Override
    public boolean toBoolean() {
        return size != 0;
    }

    @Override
    public Memory toNumeric() {
        return size == 0 ? CONST_INT_0 : CONST_INT_1;
    }

    @Override
    public String toString() {
        return "Array";
    }

    @Override
    public Memory inc() {
        return toNumeric().inc();
    }

    @Override
    public Memory dec() {
        return toNumeric().dec();
    }

    @Override
    public Memory negative() {
        return toNumeric().negative();
    }

    @Override
    public Memory plus(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                ArrayMemory left = (ArrayMemory) toImmutable();
                ArrayMemory other = (ArrayMemory) memory;
                ForeachIterator iterator = other.foreachIterator(false, false);
                while (iterator.next()) {
                    Object key = iterator.getKey();
                    Memory origin = left.getByScalar(key);
                    if (origin == null) {
                        left.checkCopied();
                        Memory value = iterator.getValue();
                        left.put(key, value.isReference() ? value : value.toImmutable());
                    }
                }
                return left;
            case REFERENCE:
                return plus(memory.toValue());
            default:
                return toNumeric().plus(memory);
        }
    }

    @Override
    public Memory minus(Memory memory) {
        return toNumeric().minus(memory);
    }

    @Override
    public Memory mul(Memory memory) {
        return toNumeric().mul(memory);
    }

    @Override
    public Memory pow(Memory memory) {
        return toNumeric().pow(memory);
    }

    @Override
    public Memory div(Memory memory) {
        return toNumeric().div(memory);
    }

    @Override
    public boolean equal(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                return compare((ArrayMemory) memory, false) == 0;
            case REFERENCE:
                return equal(memory.toValue());
            default:
                return false;
        }
    }

    @Override
    public boolean notEqual(Memory memory) {
        return !equal(memory);
    }

    @Override
    public boolean smaller(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                return compare((ArrayMemory) memory, false) == -1;
            case REFERENCE:
                return equal(memory.toValue());
            default:
                return false;
        }
    }

    @Override
    public boolean smallerEq(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                int r = compare((ArrayMemory) memory, false);
                return r == 0 || r == -1;
            case REFERENCE:
                return equal(memory.toValue());
            default:
                return false;
        }
    }

    @Override
    public boolean greater(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                return compare((ArrayMemory) memory, false) == 1;
            case REFERENCE:
                return equal(memory.toValue());
            default:
                return false;
        }
    }

    @Override
    public boolean greaterEq(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                int r = compare((ArrayMemory) memory, false);
                return r == 0 || r == 1;
            case REFERENCE:
                return equal(memory.toValue());
            default:
                return false;
        }
    }

    @Override
    public void unset() {
        rejectUnmanagedOwnershipMutation();
        if (original != null) {
            if (original.storage == storage) original.copies--;
            original = null;
        } else
            copies--;

        /*if (_list != null) {
            for (ReferenceMemory memory : getList()) {
                if (memory.type == type) {
                    memory.unset();
                }
            }
        }

        if (map != null) {
            for (Memory memory : map.values()) {
                if (memory.type == type) {
                    memory.unset();
                }
            }
        }*/

        clear();
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, Memory index) {
        switch (index.getRealType()) {
            case OBJECT:
            case ARRAY:
                return UNDEFINED; // TODO ADD WARNING
        }
        Memory e = get(index);
        return e == null ? UNDEFINED : e;
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, long index) {
        Memory e = getByScalar(LongMemory.valueOf(index));
        return e == null ? UNDEFINED : e;
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, double index) {
        Memory e = getByScalar(LongMemory.valueOf((long) index));
        return e == null ? UNDEFINED : e;
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, boolean index) {
        Memory e = getByScalar(index ? CONST_INT_1 : CONST_INT_0);
        return e == null ? UNDEFINED : e;
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, String index) {
        Memory number = integerKey(index);
        Memory e = number == null ? getByScalar(index) : getByScalar(number);
        return e == null ? UNDEFINED : e;
    }

    @Override
    public void unsetOfIndex(TraceInfo trace, Memory index) {
        checkCopied();
        remove(index);
    }

    @Override
    public Memory issetOfIndex(TraceInfo trace, Memory index) {
        Memory value = get(index);
        return value == null ? NULL : value;
    }

    @Override
    public Memory refOfPush(TraceInfo trace) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        checkCopied();
        return add(UNDEFINED);
    }

    private void recordIntegerKey(long key) {
        if (key >= nextFreeElement) nextFreeElement = key == Long.MAX_VALUE ? key : key + 1;
    }

    public boolean canAppendPhp8() {
        long key = nextFreeElement == Long.MIN_VALUE ? 0 : nextFreeElement;
        return getByScalar(LongMemory.valueOf(key)) == null;
    }

    public ReferenceMemory addPhp8(Memory value, Environment env, TraceInfo trace) {
        if (value instanceof KeyValueMemory) {
            Memory key = ((KeyValueMemory) value).key.toValue();
            if (key.isArray() || key.isObject()) env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                    "Cannot access offset of type %s on array", php.runtime.reflection.support.PhpTypeDeclaration.valueType(key));
        }
        checkCopied();
        if (value instanceof KeyValueMemory) return add(value);
        if (!canAppendPhp8()) env.exception(trace, BaseError.class,
                "Cannot add element to the array as the next element is already occupied");
        long key = nextFreeElement == Long.MIN_VALUE ? 0 : nextFreeElement;
        return put(LongMemory.valueOf(key), value);
    }

    public static Memory appendReferencePhp8(Memory root, Environment env, TraceInfo trace) {
        if(root instanceof ReferenceMemory) ((ReferenceMemory)root).checkPhpReadonlyArrayWrite();
        Memory actual = root.toValue();
        if (actual.isNull() || actual.getRealType() == Memory.Type.BOOL && !actual.toBoolean()) {
            if (!actual.isNull()) env.error(trace, php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                    "Automatic conversion of false to array is deprecated");
            if (root instanceof ReferenceMemory) ((ReferenceMemory)root).initializeArray();
            else root.assign(new ArrayMemory());
            actual = root.toValue();
        }
        if (actual.isArray()) return actual.toValue(ArrayMemory.class).refOfPushPhp8(env, trace);
        if (actual.isString()) env.exception(trace, BaseError.class, "[] operator not supported for strings");
        if (actual.isObject()) {
            if (!(actual.toValue(ObjectMemory.class).value instanceof php.runtime.lang.spl.ArrayAccess))
                env.exception(trace, BaseError.class, "Cannot use object of type %s as array", actual.toValue(ObjectMemory.class).value.getReflection().getName());
            return actual.refOfPush(trace);
        }
        env.exception(trace, BaseError.class, "Cannot use a scalar value as an array");
        return Memory.NULL;
    }

    public Memory refOfPushPhp8(Environment env, TraceInfo trace) {
        return addPhp8(UNDEFINED, env, trace);
    }

    @Override
    public Memory refOfIndexAsShortcut(TraceInfo trace, Memory index) {
        switch (index.getRealType()) {
            case OBJECT:
            case ARRAY:
                return new ReferenceMemory(); // TODO ADD WARNING
        }
        checkCopied();
        return getOrCreateAsShortcut(index);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, Memory index) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        switch (index.getRealType()) {
            case OBJECT:
            case ARRAY:
                return new ReferenceMemory(); // TODO ADD WARNING
        }
        checkCopied();
        return getOrCreate(index);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, long index) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        checkCopied();
        return getOrCreate(LongMemory.valueOf(index));
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, double index) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        return refOfIndex(null, LongMemory.valueOf((long) index));
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, boolean index) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        checkCopied();
        return getOrCreate(index ? CONST_INT_1 : CONST_INT_0);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, String index) {
        if (storage.runtimeOrigin() == null) rejectUnmanagedOwnershipMutation();
        checkCopied();
        Memory number = integerKey(index);
        return number == null ? getByScalarOrCreate(index) : getByScalarOrCreate(number);
    }

    @Override
    public boolean identical(Memory memory) {
        switch (memory.type) {
            case ARRAY:
                return compare((ArrayMemory) memory, true) == 0;
            case REFERENCE:
                return identical(memory.toValue());
            default:
                return false;
        }
    }

    @Override
    public boolean identical(long value) {
        return false;
    }

    @Override
    public boolean identical(double value) {
        return false;
    }

    @Override
    public boolean identical(boolean value) {
        return false;
    }

    @Override
    public boolean identical(String value) {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<ReferenceMemory> iterator() {
        Iterator<ReferenceMemory> iterator = map == null ? getList().iterator() : (Iterator<ReferenceMemory>) map.entriesIterator();
        if (!hasOwnershipMutationAuthority()) return iterator;
        return new Iterator<ReferenceMemory>() {
            public boolean hasNext() { return iterator.hasNext(); }
            public ReferenceMemory next() { return iterator.next(); }
            public void remove() { rejectUnmanagedOwnershipMutation(); iterator.remove(); }
        };
    }

    public Stream<ReferenceMemory> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    public ForeachIterator foreachIterator(boolean getReferences, boolean withPrevious) {
        return foreachIterator(getReferences, false, withPrevious, true);
    }


    public ForeachIterator foreachIterator(boolean getReferences, boolean getKeyReferences, boolean withPrevious) {
        return foreachIterator(getReferences, getKeyReferences, withPrevious, true);
    }

    public ForeachIterator foreachIterator(boolean getReferences, boolean getKeyReferences, boolean withPrevious, final boolean freeze) {
        if (hasBucketLayout()) return bucketIterator(getReferences, getKeyReferences, withPrevious, freeze);
        return new ForeachIterator(getReferences, getKeyReferences, withPrevious) {
            protected int cursor = 0;
            protected int listMax;
            protected Iterator<Object> keys;

            @Override
            public void reset() {
                if (getKeyReferences && map == null)
                    ArrayMemory.this.convertToMap();

                if (map != null) {
                    if (withPrevious || getKeyReferences)
                        keys = new ArrayList<Object>(map.keySet()).listIterator();
                    else {
                        if (freeze) {
                            keys = new ArrayList<Object>(map.keySet()).iterator();
                        } else {
                            keys = map.keySet().iterator();
                        }
                    }
                } else {
                    listMax = _list == null ? 0 : getList().size();
                }
            }

            @Override
            protected boolean init() {
                if (getKeyReferences && map == null)
                    ArrayMemory.this.convertToMap();

                if (map != null) {
                    if (withPrevious || getKeyReferences) {
                        keys = new ArrayList<Object>(map.keySet()).listIterator();
                    } else {
                        keys = new ArrayList<Object>(map.keySet()).iterator();
                    }
                } else {
                    listMax = _list == null ? 0 : getList().size();
                }

                return true;
            }

            private void setCurrentValue(ReferenceMemory value) {
                if (getReferences) {
                    if (plainReferences)
                        currentValue = value;
                    else
                        currentValue = new ArrayValueMemory(getMemoryKey(), ArrayMemory.this, value);
                } else
                    currentValue = value.getValue();

                if (getKeyReferences) {
                    currentKeyMemory = new ArrayKeyMemory(ArrayMemory.this, getMemoryKey());
                }
            }

            @Override
            public boolean end() {
                if (ArrayMemory.this.size == 0)
                    return false;

                if (ArrayMemory.this.map == null) {
                    cursor = ArrayMemory.this.size - 1;
                    currentKey = (long) cursor;
                    setCurrentValue(getList().get(cursor));
                    return true;
                } else {
                    init = true;

                    ArrayList<Object> tmp = new ArrayList<Object>(map.keySet());
                    keys = tmp.listIterator(tmp.size() - 1);

                    if (keys.hasNext() && next()) {
                        return true;
                    } else {
                        return false;
                    }
                }
            }

            @Override
            protected boolean prevValue() {
                if (ArrayMemory.this.map == null) {
                    if (cursor <= 0) {
                        currentKey = null;
                        currentValue = null;
                        cursor--;
                        keys = null;
                        return false;
                    } else {
                        cursor--;
                        currentKey = LongMemory.valueOf((long) cursor);
                        setCurrentValue(getList().get(cursor));
                        return true;
                    }
                } else {
                    ListIterator<Object> keyIterator = (ListIterator) keys;
                    if (keyIterator.hasPrevious()) {
                        currentKey = keyIterator.previous();
                        setCurrentValue(map.getEntry(currentKey));
                        return true;
                    } else {
                        currentKey = null;
                        currentValue = null;
                        keys = null;
                        cursor = -1;
                        return false;
                    }
                }
            }

            @Override
            protected boolean nextValue() {
                if (withPrevious && (keys == null && cursor < 0))
                    return false;

                if (ArrayMemory.this.map == null) {
                    if (((cursor >= listMax && freeze) || (cursor >= size && !freeze)) || size < listMax) {
                        currentKey = null;
                        currentValue = null;
                        return false;
                    }

                    currentKey = LongMemory.valueOf((long) cursor);
                    setCurrentValue(getList().get(cursor));
                    cursor++;
                    return true;
                } else {
                    if (keys == null) {
                        ArrayList<Object> tmp = new ArrayList<Object>(map.keySet());
                        keys = tmp.listIterator(cursor - 1);
                    }

                    while (keys.hasNext()) {
                        currentKey = keys.next();
                        ReferenceMemory entry = map.getEntry(currentKey);
                        if (entry == null) continue;
                        setCurrentValue(entry);
                        return true;
                    }
                    currentKey = null;
                    currentValue = null;
                    return false;
                }
            }
        };
    }

    /** Optional current-pointer state; copying it never traverses the array. */
    private abstract static class CurrentCursor extends ForeachIterator {
        CurrentCursor(boolean references, boolean keyReferences, boolean previous) {
            super(references, keyReferences, previous);
        }
        abstract ForeachIterator fork(ArrayMemory source, ArrayMemory owner, ArrayMemory backing);
        abstract boolean readOnly();
    }

    private ForeachIterator forkCurrentIterator(ArrayMemory owner, ArrayMemory backing) {
        if (foreachIterator instanceof CurrentCursor)
            return ((CurrentCursor)foreachIterator).fork(this,owner,backing);
        if (foreachIterator instanceof NativeReadCursor) {
            NativeReadCursor cursor=(NativeReadCursor)foreachIterator;
            return new NativeReadCursor(cursor.getKey(),cursor.getMemoryKey(),cursor.getValue(),cursor.ordinal);
        }
        return null;
    }

    /** PHP's live internal pointer; the generic/legacy foreach continuation stays independent. */
    private static final class NativePointerCursor extends CurrentCursor {
        private final ArrayMemory owner;
        private int ordinal=-1;
        private Object removalNextKey;
        private int removalNextOrdinal=-1;

        NativePointerCursor(ArrayMemory owner) { super(false,false,true);this.owner=owner;init=true; }
        @Override boolean readOnly() {return owner == null;}
        @Override ForeachIterator fork(ArrayMemory source,ArrayMemory newOwner,ArrayMemory backing) {
            NativePointerCursor copy=new NativePointerCursor(newOwner);
            copy.ordinal=ordinal;copy.currentKey=currentKey;copy.currentKeyMemory=currentKeyMemory;
            copy.currentValue=newOwner == null ? getValue() : null;copy.trace=trace;
            if(currentKey != null && source.storage != backing.storage) {
                ReferenceMemory value=backing.hasBucketLayout() ? backing.bucketAt(ordinal)
                        : backing.getByScalar(nativePointerKey(currentKey));
                if(value == null)copy.invalidate();else if(newOwner == null)copy.currentValue=value.getValue();
            }
            return copy;
        }
        private boolean invalidate() {ordinal=-1;currentKey=null;currentKeyMemory=null;currentValue=null;return false;}
        private boolean select(Object key,int position) {
            if(owner == null || key == null)return invalidate();
            ReferenceMemory value=owner.hasBucketLayout() ? owner.bucketAt(position)
                    : owner.getByScalar(nativePointerKey(key));
            if(value == null)return invalidate();
            currentKey=key;currentKeyMemory=null;ordinal=position;currentValue=null;return true;
        }
        private int adjacentOrdinal(int position,int direction) {
            for(int i=position+direction;i>=0 && i<owner.bucketLimit();i+=direction)
                if(owner.bucketAt(i) != null)return i;
            return -1;
        }
        private boolean selectOrdinal(int position) {
            return position >= 0 && select(owner.bucketKey(position),position);
        }
        private boolean first() {
            if(owner == null)return false;
            if(owner.hasBucketLayout()) {
                int first=adjacentOrdinal(-1,1);return first < 0 ? invalidate() : selectOrdinal(first);
            }
            if(owner.map != null)return owner.map.isEmpty() ? invalidate() : select(owner.map.firstKey(),-1);
            return owner._list == null || owner._list.isEmpty() ? invalidate() : select(LongMemory.valueOf(0),-1);
        }
        @Override public Memory getValue() {
            if(owner == null || currentKey == null)return currentValue;
            ReferenceMemory value=owner.hasBucketLayout() ? owner.bucketAt(ordinal)
                    : owner.getByScalar(nativePointerKey(currentKey));
            return value == null ? null : value.getValue();
        }
        @Override public boolean next() {return owner != null && nextValue();}
        @Override public boolean prev() {return owner != null && prevValue();}
        @Override protected boolean init() {return true;}
        @Override public void reset() {if(owner != null)first();}
        @Override protected boolean nextValue() {
            if(currentKey == null)return false;
            if(owner.hasBucketLayout()) {
                int next=adjacentOrdinal(ordinal,1);return next < 0 ? invalidate() : selectOrdinal(next);
            }
            if(owner.map != null)return select(owner.map.nextKey(nativePointerKey(currentKey)),-1);
            long next=getMemoryKey().toLong()+1;
            return owner._list != null && next < owner._list.size() ? select(LongMemory.valueOf(next),-1) : invalidate();
        }
        @Override protected boolean prevValue() {
            if(currentKey == null)return false;
            if(owner.hasBucketLayout()) {
                int previous=adjacentOrdinal(ordinal,-1);return previous < 0 ? invalidate() : selectOrdinal(previous);
            }
            if(owner.map != null)return select(owner.map.previousKey(nativePointerKey(currentKey)),-1);
            long previous=getMemoryKey().toLong()-1;
            return previous >= 0 ? select(LongMemory.valueOf(previous),-1) : invalidate();
        }
        @Override public boolean end() {
            if(owner == null)return false;
            if(owner.hasBucketLayout()) {
                int last=adjacentOrdinal(owner.bucketLimit(),-1);return last < 0 ? invalidate() : selectOrdinal(last);
            }
            if(owner.map != null)return owner.map.isEmpty() ? invalidate() : select(owner.map.lastKey(),-1);
            return owner._list == null || owner._list.isEmpty() ? invalidate() : select(LongMemory.valueOf(owner._list.size()-1),-1);
        }
        private void materializeBuckets() {
            ArrayMapEntryMemory selected=currentKey == null ? null : owner.map.getEntry(nativePointerKey(currentKey));
            owner.map.enableBucketLayout();ordinal=owner.map.bucketOrdinal(selected);
        }
        private void inserted(Object key,ReferenceMemory entry) {
            if(currentKey == null)select(key,owner.hasBucketLayout() ? owner.bucketOrdinal(entry) : -1);
        }
        // The linked entry's neighbors must be observed before unlink destroys them.
        private void prepareRemoval() {
            removalNextKey=null;removalNextOrdinal=-1;
            if(owner.hasBucketLayout()) {
                removalNextOrdinal=adjacentOrdinal(ordinal,1);
                if(removalNextOrdinal >= 0)removalNextKey=owner.bucketKey(removalNextOrdinal);
            } else if(owner.map != null)removalNextKey=owner.map.nextKey(nativePointerKey(currentKey));
            else {
                long next=getMemoryKey().toLong()+1;
                if(owner._list != null && next < owner._list.size())removalNextKey=LongMemory.valueOf(next);
            }
        }
        private void removed() {
            select(removalNextKey,removalNextOrdinal);removalNextKey=null;removalNextOrdinal=-1;
        }
    }

    private static Object nativePointerKey(Object key) {
        if(key instanceof Long)return LongMemory.valueOf((Long)key);
        if(key instanceof Integer)return LongMemory.valueOf((Integer)key);
        return key;
    }

    private void nativePointerInserted(Object key,ReferenceMemory entry) {
        if(foreachIterator instanceof NativePointerCursor) {
            NativePointerCursor cursor=(NativePointerCursor)foreachIterator;
            if(cursor.readOnly())cursor=(NativePointerCursor)cursor.fork(this,this,this);
            cursor.inserted(key,entry);foreachIterator=cursor;
        }
    }

    // The key snapshot is never mutated after publication. Copies share it, but not its position.
    private static final class ListMapCurrentCursor extends CurrentCursor {
        private final ArrayMemory owner;
        private int cursor;
        private int listMax;
        private List<Object> keys;
        private int keyPosition;
        private int[] physicalPositions;
        private int physicalOrdinal=-1;

        ListMapCurrentCursor(ArrayMemory owner) { super(false,false,true); this.owner=owner; }
        @Override boolean readOnly() { return owner == null; }
        @Override ForeachIterator fork(ArrayMemory source, ArrayMemory newOwner, ArrayMemory backing) {
            ListMapCurrentCursor copy=new ListMapCurrentCursor(newOwner);
            copy.cursor=cursor;copy.listMax=listMax;copy.keys=keys;copy.keyPosition=keyPosition;
            copy.physicalPositions=physicalPositions;copy.physicalOrdinal=physicalOrdinal;
            copy.init=init;copy.currentKey=currentKey;copy.currentKeyMemory=currentKeyMemory;
            copy.currentValue=newOwner == null ? getValue() : null;copy.trace=trace;
            if (newOwner == null && source.storage != backing.storage && currentKey != null) {
                ReferenceMemory rebound;
                if (source.hasBucketLayout() && physicalOrdinal >= 0) rebound=backing.bucketAt(physicalOrdinal);
                else if (source.map == null) {
                    int index=(int)getMemoryKey().toLong();
                    rebound=backing._list != null && index >= 0 && index < backing._list.size()
                            ? backing._list.get(index) : null;
                } else {
                    ArrayMapEntryMemory originalEntry=source.map.getEntry(currentKey);
                    ArrayMapEntryMemory copiedEntry=backing.map == null ? null : backing.map.getEntry(currentKey);
                    rebound=originalEntry != null && copiedEntry != null
                            && originalEntry.getKey() == copiedEntry.getKey() ? copiedEntry : null;
                }
                if (rebound != null) copy.currentValue=rebound.getValue();
            }
            return copy;
        }
        // Capture actual entries while keys are still unique, before publication can append a duplicate.
        private ListMapCurrentCursor materializeBuckets(ArrayMemory array) {
            ListMapCurrentCursor copy=(ListMapCurrentCursor)fork(array,owner,array);
            ArrayMapEntryMemory current=currentKey == null ? null : array.map.getEntry(currentKey);
            ArrayMapEntryMemory[] entries=keys == null ? null : new ArrayMapEntryMemory[keys.size()];
            if (entries != null) for(int i=0;i<entries.length;i++) entries[i]=array.map.getEntry(keys.get(i));
            int[] positions=entries == null ? null : new int[entries.length];
            array.map.enableBucketLayout();
            copy.physicalOrdinal=array.map.bucketOrdinal(current);
            if (entries != null) {
                for(int i=0;i<positions.length;i++) positions[i]=array.map.bucketOrdinal(entries[i]);
                copy.physicalPositions=positions;
            }
            return copy;
        }
        private void snapshotKeys() {
            if (owner.hasBucketLayout()) {
                ArrayList<Object> nextKeys=new ArrayList<>();
                int[] positions=new int[owner.size];int count=0;
                for(int i=0;i<owner.bucketLimit();i++) {
                    ReferenceMemory entry=owner.bucketAt(i);
                    if(entry != null){nextKeys.add(owner.bucketKey(i));positions[count++]=i;}
                }
                keys=nextKeys;physicalPositions=positions;
            } else {keys=new ArrayList<Object>(owner.map.keySet());physicalPositions=null;}
        }
        private ReferenceMemory selectPosition(int position) {
            if (physicalPositions == null) {physicalOrdinal=-1;return owner.map.getEntry(keys.get(position));}
            physicalOrdinal=physicalPositions[position];return owner.bucketAt(physicalOrdinal);
        }
        @Override public boolean next() { return owner != null && super.next(); }
        @Override public boolean prev() { return owner != null && super.prev(); }
        @Override public void reset() {
            if (owner == null) return;
            if (owner.map != null) {snapshotKeys();keyPosition=0;}
            else listMax=owner._list == null ? 0 : owner.getList().size();
        }
        @Override protected boolean init() {reset();return true;}
        // Live current pointers name a slot; only readonly forks retain its captured payload.
        @Override public Memory getValue() {
            if (owner == null || currentKey == null) return currentValue;
            ReferenceMemory value=owner.hasBucketLayout() ? owner.bucketAt(physicalOrdinal)
                    : owner.getByScalar(nativePointerKey(currentKey));
            return value == null ? null : value.getValue();
        }
        private void select(ReferenceMemory value) {Objects.requireNonNull(value);currentValue=null;}
        @Override public boolean end() {
            if (owner == null || owner.size == 0) return false;
            if (owner.map == null) {
                cursor=owner.size-1;currentKey=(long)cursor;select(owner.getList().get(cursor));return true;
            }
            init=true;snapshotKeys();keyPosition=keys.size()-1;
            return keyPosition < keys.size() && next();
        }
        @Override protected boolean prevValue() {
            if (owner.map == null) {
                if (cursor <= 0) {currentKey=null;currentValue=null;physicalOrdinal=-1;cursor--;keys=null;return false;}
                cursor--;currentKey=LongMemory.valueOf(cursor);select(owner.getList().get(cursor));return true;
            }
            while (keyPosition > 0) {
                currentKey=keys.get(--keyPosition);ReferenceMemory entry=selectPosition(keyPosition);
                if (physicalPositions != null && entry == null) continue;
                select(entry);return true;
            }
            currentKey=null;currentValue=null;physicalOrdinal=-1;keys=null;cursor=-1;return false;
        }
        @Override protected boolean nextValue() {
            if (keys == null && cursor < 0) return false;
            if (owner.map == null) {
                if (cursor >= listMax || owner.size < listMax) {currentKey=null;currentValue=null;physicalOrdinal=-1;return false;}
                currentKey=LongMemory.valueOf(cursor);select(owner.getList().get(cursor));cursor++;return true;
            }
            if (keys == null) {snapshotKeys();keyPosition=cursor-1;}
            while (keyPosition < keys.size()) {
                currentKey=keys.get(keyPosition);
                ReferenceMemory entry=selectPosition(keyPosition++);
                if (entry == null) continue;
                select(entry);return true;
            }
            currentKey=null;currentValue=null;physicalOrdinal=-1;return false;
        }
    }

    /** Internal cursor metadata; ordinary Memory/ArrayMemory instances gain no fields. */
    private abstract static class BucketCursor extends CurrentCursor {
        BucketCursor(boolean references, boolean keyReferences, boolean previous) {
            super(references, keyReferences, previous);
        }
        abstract int currentOrdinal();
        abstract void seekOrdinal(int ordinal);
    }

    private ForeachIterator bucketIterator(boolean references, boolean keyReferences, boolean previous, final boolean freeze) {
        return new BucketIterator(this,references,keyReferences,previous,freeze);
    }

    private static final class BucketIterator extends BucketCursor {
        private final ArrayMemory owner;
        private final boolean freeze;
        private int cursor=-1;
        private int limit;
        private boolean invalid;
        BucketIterator(ArrayMemory owner,boolean references,boolean keyReferences,boolean previous,boolean freeze) {
            super(references,keyReferences,previous);this.owner=owner;this.freeze=freeze;
        }
        @Override boolean readOnly() {return owner == null;}
        @Override ForeachIterator fork(ArrayMemory source,ArrayMemory newOwner,ArrayMemory backing) {
            BucketIterator copy=new BucketIterator(newOwner,getReferences,getKeyReferences,withPrevious,freeze);
            copy.cursor=cursor;copy.limit=limit;copy.invalid=invalid;copy.init=init;
            copy.currentKey=currentKey;copy.currentKeyMemory=currentKeyMemory;copy.currentValue=currentValue;
            copy.trace=trace;copy.plainReferences=plainReferences;
            if (source.storage != backing.storage && currentOrdinal() >= 0) {
                ReferenceMemory entry=backing.bucketAt(cursor);
                if (entry != null) copy.currentValue=entry.getValue();
            }
            return copy;
        }
        @Override public boolean next() {return owner != null && super.next();}
        @Override public boolean prev() {return owner != null && super.prev();}
        @Override public void reset() {if(owner != null){cursor=-1;limit=owner.bucketLimit();invalid=false;}}
        @Override protected boolean init() {reset();return true;}
        @Override int currentOrdinal() {return invalid || currentKey == null ? -1 : cursor;}
        @Override void seekOrdinal(int ordinal) {
            reset();init=true;cursor=ordinal;
            if (!select(ordinal)) {invalid=true;currentKey=null;currentValue=null;}
        }
        private boolean select(int ordinal) {
            ReferenceMemory entry=owner.bucketAt(ordinal);
            if (entry == null) return false;
            currentKey=owner.bucketKey(ordinal);currentKeyMemory=null;
            currentValue=getReferences ? (plainReferences ? entry
                    : new ArrayValueMemory(getMemoryKey(),owner,entry,ordinal)) : entry.getValue();
            if(getKeyReferences)currentKeyMemory=new ArrayKeyMemory(owner,getMemoryKey());
            return true;
        }
        @Override protected boolean nextValue() {
            if(invalid)return false;
            int end=freeze?limit:owner.bucketLimit();
            while(++cursor<end)if(select(cursor))return true;
            cursor=end;invalid=true;currentKey=null;currentValue=null;return false;
        }
        @Override protected boolean prevValue() {
            if(invalid)return false;
            while(--cursor>=0)if(select(cursor))return true;
            cursor=-1;invalid=true;currentKey=null;currentValue=null;return false;
        }
        @Override public boolean end() {
            if(owner == null)return false;
            init=true;limit=owner.bucketLimit();cursor=limit;invalid=false;
            while(--cursor>=0)if(select(cursor))return true;
            invalid=true;currentKey=null;currentValue=null;return false;
        }
    }

    /** Internal live current slot; ordinary foreach retains the accepted captured VALUE path. */
    private static final class BucketCurrentCursor extends BucketCursor {
        private final ArrayMemory owner;
        private final boolean freeze;
        private int cursor=-1;
        private int limit;
        private boolean invalid;
        BucketCurrentCursor(ArrayMemory owner,boolean freeze) {
            super(false,false,true);this.owner=owner;this.freeze=freeze;
        }
        @Override boolean readOnly() {return owner == null;}
        @Override ForeachIterator fork(ArrayMemory source,ArrayMemory newOwner,ArrayMemory backing) {
            BucketCurrentCursor copy=new BucketCurrentCursor(newOwner,freeze);
            copy.cursor=cursor;copy.limit=limit;copy.invalid=invalid;copy.init=init;
            copy.currentKey=currentKey;copy.currentKeyMemory=currentKeyMemory;
            copy.currentValue=newOwner == null ? getValue() : null;
            copy.trace=trace;
            if (newOwner == null && source.storage != backing.storage && currentOrdinal() >= 0) {
                ReferenceMemory entry=backing.bucketAt(cursor);
                if (entry != null) copy.currentValue=entry.getValue();
            }
            return copy;
        }
        @Override public boolean next() {return owner != null && super.next();}
        @Override public boolean prev() {return owner != null && super.prev();}
        @Override public Memory getValue() {
            if (owner == null) return currentValue;
            ReferenceMemory entry=currentOrdinal() < 0 ? null : owner.bucketAt(cursor);
            return entry == null ? null : entry.getValue();
        }
        @Override public void reset() {if(owner != null){cursor=-1;limit=owner.bucketLimit();invalid=false;}}
        @Override protected boolean init() {reset();return true;}
        @Override int currentOrdinal() {return invalid || currentKey == null ? -1 : cursor;}
        @Override void seekOrdinal(int ordinal) {
            reset();init=true;cursor=ordinal;
            if (!select(ordinal)) {invalid=true;currentKey=null;currentValue=null;}
        }
        private boolean select(int ordinal) {
            if (owner.bucketAt(ordinal) == null) return false;
            currentKey=owner.bucketKey(ordinal);currentKeyMemory=null;currentValue=null;
            return true;
        }
        @Override protected boolean nextValue() {
            if(invalid)return false;
            int end=freeze?limit:owner.bucketLimit();
            while(++cursor<end)if(select(cursor))return true;
            cursor=end;invalid=true;currentKey=null;currentValue=null;return false;
        }
        @Override protected boolean prevValue() {
            if(invalid)return false;
            while(--cursor>=0)if(select(cursor))return true;
            cursor=-1;invalid=true;currentKey=null;currentValue=null;return false;
        }
        @Override public boolean end() {
            if(owner == null)return false;
            init=true;limit=owner.bucketLimit();cursor=limit;invalid=false;
            while(--cursor>=0)if(select(cursor))return true;
            invalid=true;currentKey=null;currentValue=null;return false;
        }
    }

    @Override
    public ForeachIterator getNewIterator(Environment env, boolean getReferences, boolean getKeyReferences) {
        return foreachIterator(getReferences, getKeyReferences, false);
    }

    public ForeachIterator getCurrentIterator() {
        if (foreachIterator == null) {
            foreachIterator = hasBucketLayout() ? new BucketCurrentCursor(this,true) : new ListMapCurrentCursor(this);
            if (pointerNeedsFirst) {
                foreachIterator.next();
                pointerNeedsFirst = false;
            }
        }

        return foreachIterator;
    }

    protected void reset() {
        foreachIterator = null;
        pointerNeedsFirst = false;
    }

    /** Native reset selects the first item and leaves next() on its successor, independently of legacy each(). */
    public Memory resetCurrentIteratorForNative() {
        NativePointerCursor cursor=new NativePointerCursor(this);
        foreachIterator=cursor;pointerNeedsFirst=false;
        return cursor.first() ? cursor.getValue() : FALSE;
    }

    public Memory resetCurrentIterator() {
        reset();

        ForeachIterator iterator = getCurrentIterator();
        if (size == 0)
            return FALSE;
        else {
            iterator.next();
            Memory tmp = iterator.getValue();
            if (!hasBucketLayout()) iterator.prev();
            return tmp;
        }
    }

    @Override
    public byte[] getBinaryBytes(Charset charset) {
        return MemoryStringUtils.getBinaryBytes(this);
    }

    public boolean isList() {
        return map == null;
    }

    public boolean isMap() {
        return map != null;
    }

    @Override
    public Memory toArray() {
        return this;
    }

    @Override
    public Memory toObject(Environment env) {
        StdClass stdClass = new StdClass(env);
        ArrayMemory props = stdClass.getProperties();
        ForeachIterator iterator = getNewIterator(env, false, false);
        while (iterator.next()) {
            if (hasBucketLayout()) {
                Memory value = iterator.getValue();
                props.insertBucket(iterator.getMemoryKey().toString(), value.isReference() ? value : value.toImmutable());
            } else props.refOfIndex(null, iterator.getMemoryKey()).assign(iterator.getValue());
        }

        return new ObjectMemory(stdClass);
    }

    public int[] toIntArray() {
        int[] r = new int[size];
        int i = 0;
        for (Memory e : this) {
            r[i] = e.toInteger();
            i++;
        }
        return r;
    }

    public long[] toLongArray() {
        long[] r = new long[size];
        int i = 0;
        for (Memory e : this) {
            r[i] = e.toLong();
            i++;
        }
        return r;
    }

    public String[] toStringArray() {
        String[] r = new String[size];
        int i = 0;
        for (Memory e : this) {
            r[i] = e.toString();
            i++;
        }
        return r;
    }

    public float[] toFloatArray() {
        float[] r = new float[size];
        int i = 0;
        for (Memory e : this) {
            r[i] = e.toFloat();
            i++;
        }
        return r;
    }

    public double[] toDoubleArray() {
        double[] r = new double[size];
        int i = 0;
        for (Memory e : this) {
            r[i] = e.toDouble();
            i++;
        }
        return r;
    }

    public boolean[] toBoolArray() {
        boolean[] r = new boolean[size];
        int i = 0;
        for (Memory e : this) {
            r[i] = e.toBoolean();
            i++;
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    public <T extends IObject> List<T> toObjectArray(Class<T> clazz) {
        List<T> r = new ArrayList<T>();

        int i = 0;
        for (Memory e : this) {
            if (e.instanceOf(clazz))
                r.add(e.toObject(clazz));
            else {
                throw new IllegalArgumentException(Messages.ERR_INVALID_ARRAY_ELEMENT_TYPE.fetch(
                        ReflectionUtils.getClassName(clazz), ReflectionUtils.getGivenName(e)
                ));
            }
        }

        return r;
    }

    public Map<String, String> toStringMap() {
        Map<String, String> r = new LinkedHashMap<String, String>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            r.put(iterator.getKey().toString(), iterator.getValue().toString());
        }

        return r;
    }

    public Map<String, Integer> toIntMap() {
        Map<String, Integer> r = new LinkedHashMap<String, Integer>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            r.put(iterator.getKey().toString(), iterator.getValue().toInteger());
        }

        return r;
    }

    public Map<String, Long> toLongMap() {
        Map<String, Long> r = new LinkedHashMap<String, Long>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            r.put(iterator.getKey().toString(), iterator.getValue().toLong());
        }

        return r;
    }

    public Map<String, Double> toDoubleMap() {
        Map<String, Double> r = new LinkedHashMap<String, Double>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            r.put(iterator.getKey().toString(), iterator.getValue().toDouble());
        }

        return r;
    }

    public Map<String, Boolean> toBooleanMap() {
        Map<String, Boolean> r = new LinkedHashMap<String, Boolean>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            r.put(iterator.getKey().toString(), iterator.getValue().toBoolean());
        }

        return r;
    }

    public <T extends IObject> Map<String, T> toObjectMap(Class<T> clazz) {
        Map<String, T> r = new LinkedHashMap<String, T>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            Memory e = iterator.getValue();

            if (e.instanceOf(clazz))
                r.put(iterator.getKey().toString(), e.toObject(clazz));
            else {
                throw new IllegalArgumentException(Messages.ERR_INVALID_ARRAY_ELEMENT_TYPE.fetch(
                        ReflectionUtils.getClassName(clazz), ReflectionUtils.getGivenName(e)
                ));
            }
        }

        return r;
    }

    public Map toMap(Environment env) {
        Map<String, Object> r = new LinkedHashMap<>();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            r.put(iterator.getKey().toString(), Memory.unwrap(env, iterator.getValue(), true));
        }

        return r;
    }

    public Object toMapOrList(Environment env) {
        if (isList()) {
            List<Object> result = new ArrayList<>();
            ForeachIterator iterator = foreachIterator(false, false);

            while (iterator.next()) {
                result.add(Memory.unwrap(env, iterator.getValue(), true));
            }

            return result;
        } else {
            return toMap(env);
        }
    }

    public static ArrayMemory ofPair(String key, Memory value) {
        ArrayMemory r = new ArrayMemory();
        r.refOfIndex(key).assign(value.toImmutable());
        return r;
    }

    public static ArrayMemory ofPair(String key, String value) {
        return ofPair(key, StringMemory.valueOf(value));
    }

    public static ArrayMemory ofPair(String key, long value) {
        return ofPair(key, LongMemory.valueOf(value));
    }

    public static ArrayMemory ofPair(String key, double value) {
        return ofPair(key, DoubleMemory.valueOf(value));
    }

    public static ArrayMemory ofPair(String key, boolean value) {
        return ofPair(key, TrueMemory.valueOf(value));
    }

    public static ArrayMemory ofPair(String key, IObject value) {
        return ofPair(key, ObjectMemory.valueOf(value));
    }

    public static ArrayMemory ofShorts(short... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (int el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofIntegers(int... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (int el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofBytes(byte... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (int el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofLongs(long... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (long el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofFloats(float... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (float el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofDoubles(double... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (double el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofBooleans(boolean... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (boolean el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofChars(char... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (char el : array) result.add(StringMemory.valueOf(el));
        }

        return result;
    }

    public static ArrayMemory ofStrings(String... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (String el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofStringEnumeration(Enumeration<String> enumeration) {
        ArrayMemory result = new ArrayMemory();

        if (enumeration != null) {
            while (enumeration.hasMoreElements()) {
                result.add(enumeration.nextElement());
            }
        }

        return result;
    }

    public static ArrayMemory ofStringCollection(Collection<String> list) {
        ArrayMemory result = new ArrayMemory();

        if (list != null) {
            for (String el : list) result.add(el);
        }

        return result;
    }

    public static ArrayMemory ofObjects(IObject... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (IObject el : array) result.add(el);
        }

        return result;
    }

    public static ArrayMemory of(Memory... array) {
        ArrayMemory result = new ArrayMemory();

        if (array != null) {
            for (Memory el : array) {
                result.add(el.toImmutable());
            }
        }

        return result;
    }

    public static ArrayMemory ofCollection(Collection<Memory> list) {
        ArrayMemory result = new ArrayMemory();

        for (Memory el : list) {
            result.add(el.toImmutable());
        }

        return result;
    }

    public static ArrayMemory ofMap(Map<String, Memory> map) {
        ArrayMemory result = new ArrayMemory();

        for (Map.Entry<String, Memory> entry : map.entrySet()) {
            result.putAsKeyString(entry.getKey(), entry.getValue().toImmutable());
        }

        return result;
    }

    public static ArrayMemory ofStringMap(Map<String, String> map) {
        ArrayMemory result = new ArrayMemory();

        for (Map.Entry<String, String> entry : map.entrySet()) {
            result.putAsKeyString(entry.getKey(), StringMemory.valueOf(entry.getValue()));
        }

        return result;
    }

    public static <T> ArrayMemory ofBean(Environment env, T anyObject) {
        return ofBean(env, env.trace(), anyObject);
    }

    public static <T> ArrayMemory ofBean(Environment env, TraceInfo trace, T anyObject) {
        Method[] methods = anyObject.getClass().getMethods();
        Field[] fields = anyObject.getClass().getFields();

        ArrayMemory value = new ArrayMemory(true);

        for (Field field : fields) {
            int fieldModifiers = field.getModifiers();

            if (Modifier.isPublic(fieldModifiers) && !Modifier.isStatic(fieldModifiers)) {
                try {
                    String key = field.getName();
                    Object invoke = field.get(anyObject);

                    MemoryOperation operation = MemoryOperation.get(field.getType(), field.getGenericType());

                    if (operation == null) {
                        if (isLikeBean(field.getType())) {
                            value.put(key, ofNullableBean(env, invoke));
                        }

                        continue;
                    }

                    value.put(key, operation.unconvert(env, trace, invoke));
                } catch (IllegalAccessException e) {
                    throw new CriticalException(e);
                } catch (Throwable throwable) {
                    env.wrapThrow(throwable);
                }
            }
        }

        for (Method method : methods) {
            String name = method.getName();
            String key = null;

            if (name.startsWith("get") && name.length() > 3 && method.getParameterTypes().length == 0) {
                key = name.substring(3);

                if (name.length() == 4) {
                    key = key.toLowerCase();
                } else {
                    key = key.substring(0, 1).toLowerCase() + key.substring(1);
                }
            } else if (name.startsWith("is") && name.length() > 2 && method.getParameterTypes().length == 0 &&
                    (method.getReturnType() == Boolean.class || method.getReturnType() == Boolean.TYPE)) {

                key = name.substring(2);

                if (name.length() == 3) {
                    key = key.toLowerCase();
                } else {
                    key = key.substring(0, 1).toLowerCase() + key.substring(1);
                }
            }

            if (key == null) continue;
            if ("class".equals(key)) continue;

            try {
                method.setAccessible(true);
                Object invoke = method.invoke(anyObject);
                MemoryOperation operation = MemoryOperation.get(method.getReturnType(), method.getGenericReturnType());

                if (operation == null) {
                    if (isLikeBean(method.getReturnType())) {
                        value.put(key, ofNullableBean(env, invoke));
                    }

                    continue;
                }

                Memory memory = operation.unconvert(env, trace, invoke);

                value.put(key, memory);
            } catch (IllegalAccessException e) {
                throw new CriticalException(e);
            } catch (InvocationTargetException e) {
                env.__throwException(e);
            } catch (Throwable throwable) {
                env.wrapThrow(throwable);
            }
        }

        return value;
    }

    public static <T> Memory ofNullableBean(Environment env, T anyObject) {
        return ofNullableBean(env, env.trace(), anyObject);
    }

    public static <T> Memory ofNullableBean(Environment env, TraceInfo trace, T anyObject) {
        if (anyObject == null) {
            return NULL;
        }

        return ofBean(env, trace, anyObject);
    }

    public static <T> boolean isLikeBean(Class<T> anyClass) {
        Constructor<?>[] constructors = anyClass.getConstructors();

        for (Constructor<?> constructor : constructors) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                return true;
            }
        }

        return false;
    }

    public <T> T toBean(Environment env, Class<T> beanClass) {
        return toBean(env, env.trace(), beanClass);
    }

    @SuppressWarnings("unchecked")
    public <T> T toBean(Environment env, T instance) {
        return toBean(env, env.trace(), instance);
    }

    @SuppressWarnings("unchecked")
    public <T> T toBean(Environment env, TraceInfo trace, T instance) {
        Class<T> beanClass = (Class<T>) instance.getClass();

        ForeachIterator iterator = foreachIterator(false, false);

        while (iterator.next()) {
            String key = iterator.getStringKey();

            try {
                Field field = beanClass.getField(key);
                int fieldModifiers = field.getModifiers();

                if (Modifier.isPublic(fieldModifiers) && !Modifier.isStatic(fieldModifiers)) {
                    MemoryOperation operation = MemoryOperation.get(field.getType(), field.getGenericType());
                    field.set(instance, operation.convert(env, trace, iterator.getValue()));

                    continue;
                }
            } catch (NoSuchFieldException e) {
                // nop;
            } catch (Throwable throwable) {
                env.wrapThrow(throwable);
            }

            String name = key.substring(0, 1).toUpperCase() + key.substring(1);

            String getterName = "get" + name;
            String getterIsName = "is" + name;
            String setterName = "set" + name;

            try {
                Method method = null;

                try {
                    method = beanClass.getMethod(getterName);
                } catch (NoSuchMethodException e) {
                    method = beanClass.getMethod(getterIsName);

                    if (method.getReturnType() != Boolean.TYPE && method.getReturnType() != Boolean.class) {
                        continue;
                    }
                }

                Class<?> returnType = method.getReturnType();

                MemoryOperation operation = MemoryOperation.get(returnType, method.getGenericReturnType());

                if (operation == null) {
                    continue;
                }

                method = beanClass.getMethod(setterName, returnType);

                Object value = operation.convert(env, trace, iterator.getValue());
                method.invoke(instance, value);

            } catch (NoSuchMethodException e) {
                continue;
            } catch (Throwable throwable) {
                env.wrapThrow(throwable);
            }
        }

        return instance;
    }

    public <T> T toBean(Environment env, TraceInfo trace, Class<T> beanClass) {
        try {
            Constructor<T> constructor = beanClass.getConstructor();

            T instance = constructor.newInstance();

            return toBean(env, trace, instance);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new CriticalException(e);
        }
    }

    public ArrayMemory slice(int offset) {
        return slice(offset, false);
    }

    public ArrayMemory slice(int offset, int length) {
        return slice(offset, length, false);
    }

    public ArrayMemory slice(int offset, boolean saveKeys) {
        ArrayMemory result = new ArrayMemory();

        int size = size();
        offset = Math.max(0, Math.min(size, offset < 0 ? size + offset : offset));

        if (isList()) {
            int i = 0;
            try {
                for (ReferenceMemory referenceMemory : getList().subList(offset, getList().size())) {
                    if (saveKeys) {
                        result.refOfIndex(i + offset).assign(referenceMemory.toImmutable());
                    } else {
                        result.add(referenceMemory.toImmutable());
                    }

                    i++;
                }
            } catch (IllegalArgumentException e) {
                throw new IndexOutOfBoundsException(e.getMessage());
            }
        } else {
            int i = 0;

            ForeachIterator iterator = foreachIterator(false, false);

            while (iterator.next()) {
                if (i >= offset) {
                    if (saveKeys || !iterator.isLongKey()) {
                        result.put(iterator.getKey(), iterator.getValue().toImmutable());
                    } else {
                        result.add(iterator.getValue().toImmutable());
                    }
                }

                i++;
            }
        }

        return result;
    }

    public ArrayMemory slice(int offset, int length, boolean saveKeys) {
        ArrayMemory result = new ArrayMemory();

        int size = size();
        offset = Math.max(0, Math.min(size, offset < 0 ? size + offset : offset));
        length = length < 0 ? Math.max(0, size + length - offset) : Math.min(length, size - offset);
        if (length == 0) {
            return result;
        }

        if (isList()) {
            int i = 0;
            try {
                for (ReferenceMemory referenceMemory : getList().subList(offset, offset + length)) {
                    if (saveKeys) {
                        result.refOfIndex(i + offset).assign(referenceMemory.toImmutable());
                    } else {
                        result.add(referenceMemory.toImmutable());
                    }

                    i++;
                }
            } catch (IllegalArgumentException e) {
                throw new IndexOutOfBoundsException(e.getMessage());
            }
        } else {
            int i = 0, count = 0;

            ForeachIterator iterator = foreachIterator(false, false);

            while (iterator.next()) {
                if (i >= offset) {
                    count++;

                    if (saveKeys || !iterator.isLongKey()) {
                        result.put(iterator.getKey(), iterator.getValue().toImmutable());
                    } else {
                        result.add(iterator.getValue().toImmutable());
                    }

                    if (count >= length) {
                        break;
                    }
                }

                i++;
            }
        }

        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        ArrayMemory that = (ArrayMemory) o;

        if (original != null ? !original.equals(that.original) : that.original != null) return false;
        if (_list != null ? !_list.equals(that._list) : that._list != null) return false;
        return map != null ? map.equals(that.map) : that.map == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (original != null ? original.hashCode() : 0);
        result = 31 * result + (_list != null ? _list.hashCode() : 0);
        result = 31 * result + (map != null ? map.hashCode() : 0);
        return result;
    }
}
