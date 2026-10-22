package php.runtime.memory.support;

import php.runtime.Memory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.common.Pair;
import php.runtime.common.collections.*;
import php.runtime.common.collections.iterators.*;
import php.runtime.common.collections.list.UnmodifiableList;

import java.io.Serializable;
import java.util.*;

public class ArrayMemoryMap extends AbstractMap<Object, Memory>
        implements IterableMap<Object, Memory>, OrderedMap<Object, Memory>, Serializable {
    /**
     * Serialisation version
     */
    private static final long serialVersionUID = 9077238323521161066L;

    protected static final String NO_NEXT_ENTRY = "No next() entry in the iteration";
    protected static final String NO_PREVIOUS_ENTRY = "No previous() entry in the iteration";
    protected static final String REMOVE_INVALID = "remove() can only be called once after next()";
    protected static final String GETKEY_INVALID = "getKey() can only be called after next() and before remove()";
    protected static final String GETVALUE_INVALID = "getValue() can only be called after next() and before remove()";
    protected static final String SETVALUE_INVALID = "setValue() can only be called after next() and before remove()";

    /**
     * The default capacity to use
     */
    protected static final int DEFAULT_CAPACITY = 11;
    /**
     * The default threshold to use
     */
    protected static final int DEFAULT_THRESHOLD = 8;
    /**
     * The default load factor to use
     */
    protected static final float DEFAULT_LOAD_FACTOR = 0.75f;
    /**
     * The maximum capacity allowed
     */
    protected static final int MAXIMUM_CAPACITY = 1 << 30;
    /**
     * An object for masking null
     */
    protected static final Object NULL = new Object();

    /**
     * Load factor, normally 0.75
     */
    protected transient float loadFactor;
    /**
     * The size of the map
     */
    protected transient int size;
    /**
     * Map entries
     */
    protected transient ArrayMapEntryMemory[] data;
    /**
     * Size at which to rehash
     */
    protected transient int threshold;
    /**
     * Modification count for iterators
     */
    protected transient int modCount;
    /**
     * Entry set
     */
    protected transient EntrySet entrySet;
    /**
     * Key set
     */
    protected transient KeySet keySet;
    /**
     * Values
     */
    protected transient Values values;

    protected transient ArrayMapEntryMemory header;

    // Zend object tables can contain two physical buckets with the same key.
    // Ordinary maps keep their usual Java Map semantics and allocate no layout.
    private transient ArrayList<ArrayMapEntryMemory> buckets;
    private transient IdentityHashMap<ArrayMapEntryMemory, Integer> bucketOrdinals;

    // A native symbol table is not PHP ARRAY storage. No authority exists until explicitly sealed.
    private transient NativeGlobalSlots nativeGlobals;

    /** Structural authority only; carrier payload and Environment admission remain separate. */
    public NativeGlobalSlots sealNativeGlobals() {
        if (nativeGlobals != null) throw new IllegalStateException("Native global map is already sealed");
        if (getClass() != ArrayMemoryMap.class || buckets != null)
            throw new IllegalArgumentException("Native globals require an exact ordinary map");
        NativeGlobalSlots prepared = new NativeGlobalSlots();
        ReferenceMemory.NativeGlobalAttachment[] attachments = new ReferenceMemory.NativeGlobalAttachment[prepared.slots.size()];
        int ordinal=0;
        for (NativeGlobalSlot slot : prepared.slots.values()) attachments[ordinal++]=slot.carrier.prepareNativeGlobalSlot(slot);
        for (ReferenceMemory.NativeGlobalAttachment attachment : attachments) attachment.validate();
        nativeGlobals = prepared;
        for (ReferenceMemory.NativeGlobalAttachment attachment : attachments) attachment.store();
        return prepared;
    }
    private void rejectNativeGlobalMutation() {
        if (nativeGlobals != null) throw new IllegalStateException("Native globals require their exact placement token");
    }

    public final class NativeGlobalSlots {
        private Map<Object, NativeGlobalSlot> slots = new LinkedHashMap<>();
        private NativeGlobalPlacement pending;
        private NativeGlobalSlots() {
            for (ArrayMapEntryMemory entry = header.after; entry != header; entry = entry.after) {
                requireNativeKey(entry.getKey());
                if (entry.getClass() != ArrayMapEntryMemory.class)
                    throw new IllegalArgumentException("Native globals require plain physical carriers");
                slots.put(entry.getKey(), new NativeGlobalSlot(this, entry.getKey(), entry, true));
            }
        }
        public ArrayMemoryMap map() { return ArrayMemoryMap.this; }
        public NativeGlobalSlot slot(Object key) {
            requireNativeKey(key);
            if (pending != null) throw new IllegalStateException("Native global placement is in progress");
            NativeGlobalSlot result = slots.get(key);
            if (result == null) {
                result = new NativeGlobalSlot(this, key, new ArrayMapEntryMemory(null, hash(key), key, Memory.UNDEFINED), false);
                result.carrier.prepareNativeGlobalSlot(result).store();
                slots.put(key, result);
            }
            result.validate(); return result;
        }
        /** Retire only the authentic constructor bucket; its distinct user slot remains absent. */
        public NativeGlobalPlacement prepareSyntheticRetirement(NativeGlobalSlot oldSlot, php.runtime.env.Environment.SyntheticGlobalsOrigin origin) {
            if (nativeGlobals != this || oldSlot == null || oldSlot.owner != this || origin == null)
                throw new IllegalArgumentException("Synthetic global retirement belongs to another map");
            if (pending != null) throw new IllegalStateException("Native global placement is in progress");
            oldSlot.validate();origin.validateCarrier(oldSlot.carrier);
            if (!oldSlot.linked) throw new IllegalArgumentException("Synthetic global bucket is no longer linked");
            NativeGlobalSlot fresh = new NativeGlobalSlot(this, oldSlot.key,
                    new ArrayMapEntryMemory(null, oldSlot.carrier.hashCode, oldSlot.key, Memory.UNDEFINED), false);
            fresh.carrier.prepareNativeGlobalSlot(fresh).store();
            Map<Object, NativeGlobalSlot> replacement = new LinkedHashMap<>(slots);
            replacement.put(oldSlot.key, fresh);
            NativeGlobalPlacement result = new NativeGlobalPlacement(this, oldSlot, false, fresh, replacement, origin);
            pending = result;return result;
        }
        public NativeGlobalPlacement prepareLink(NativeGlobalSlot slot) { return prepare(slot, true); }
        public NativeGlobalPlacement prepareUnlink(NativeGlobalSlot slot) { return prepare(slot, false); }
        private NativeGlobalPlacement prepare(NativeGlobalSlot slot, boolean link) {
            if (nativeGlobals != this || slot == null || slot.owner != this)
                throw new IllegalArgumentException("Native global slot belongs to another map");
            if (pending != null) throw new IllegalStateException("Native global placement is in progress");
            slot.validate();
            if (slot.linked == link) throw new IllegalArgumentException("Native global membership already matches");
            NativeGlobalPlacement result = new NativeGlobalPlacement(this, slot, link);
            pending = result; return result;
        }
    }
    private static void requireNativeKey(Object key) {
        if (key == null || key.getClass() != String.class && key.getClass() != php.runtime.memory.LongMemory.class)
            throw new IllegalArgumentException("Native global key must already be normalized");
        if (key instanceof String && !(php.runtime.memory.ArrayMemory.toKey(new php.runtime.memory.StringMemory((String) key)) instanceof String))
            throw new IllegalArgumentException("Numeric native global key must already be canonical");
    }
    public final class NativeGlobalSlot {
        private final NativeGlobalSlots owner;
        private final Object key;
        private final ArrayMapEntryMemory carrier;
        private boolean linked;
        private long generation;
        private NativeGlobalSlot(NativeGlobalSlots owner, Object key, ArrayMapEntryMemory carrier, boolean linked) {
            this.owner = owner; this.key = key; this.carrier = carrier; this.linked = linked;
        }
        /** Private-minted slot identity only; this proof adds no payload or graph ownership. */
        public void validateCarrierAttachment(ReferenceMemory actual) {
            if (actual != carrier || carrier.getClass() != ArrayMapEntryMemory.class
                    || nativeGlobals != null && nativeGlobals != owner)
                throw new IllegalArgumentException("Native global structural slot belongs to another carrier or map");
        }
        public Object key() { return key; }
        public ArrayMapEntryMemory carrier() { return carrier; }
        public boolean isLinked() { return linked; }
        public long generation() { return generation; }
        public void validate() {
            if (nativeGlobals != owner || owner.slots.get(key) != this || carrier.getKey() != key || carrier.hashCode != hash(key)
                    || (linked ? getEntry(key) != carrier : getEntry(key) != null || carrier.before != null || carrier.after != null))
                throw new IllegalArgumentException("Native global carrier or placement is stale");
        }
    }

    /** Allocation and validation precede beginStore. store() changes only map pointers/metadata. */
    public final class NativeGlobalPlacement implements AutoCloseable {
        private final NativeGlobalSlots owner;
        private final NativeGlobalSlot slot;
        private final boolean link;
        private final NativeGlobalSlot replacement;
        private final Map<Object, NativeGlobalSlot> replacementSlots;
        private final php.runtime.env.Environment.SyntheticGlobalsOrigin syntheticOrigin;
        private final ArrayMapEntryMemory[] beforeData, nextData;
        private final int beforeSize, beforeModCount, nextThreshold, index;
        private final long beforeGeneration;
        private final ArrayMapEntryMemory previousHash, nextHash, beforeOrder, afterOrder;
        private boolean begun, stored, closed;
        private NativeGlobalPlacement(NativeGlobalSlots owner, NativeGlobalSlot slot, boolean link) {
            this(owner,slot,link,null,null,null);
        }
        private NativeGlobalPlacement(NativeGlobalSlots owner, NativeGlobalSlot slot, boolean link,
                NativeGlobalSlot replacement, Map<Object, NativeGlobalSlot> replacementSlots,
                php.runtime.env.Environment.SyntheticGlobalsOrigin syntheticOrigin) {
            this.owner = owner; this.slot = slot; this.link = link;
            this.replacement=replacement;this.replacementSlots=replacementSlots;this.syntheticOrigin=syntheticOrigin;
            beforeData = data; beforeSize = size; beforeModCount = modCount; beforeGeneration = slot.generation;
            int capacity = data.length;
            if (link && size + 1 >= threshold && capacity <= MAXIMUM_CAPACITY / 2) capacity *= 2;
            nextData = capacity == data.length ? data : new ArrayMapEntryMemory[capacity];
            nextThreshold = capacity == data.length ? threshold : calculateThreshold(capacity, loadFactor);
            index = hashIndex(slot.carrier.hashCode, data.length);
            ArrayMapEntryMemory previous = null;
            if (!link) for (ArrayMapEntryMemory entry = data[index]; entry != slot.carrier; entry = entry.next) previous = entry;
            previousHash = previous; nextHash = slot.carrier.next;
            beforeOrder = link ? header.before : slot.carrier.before;
            afterOrder = link ? header : slot.carrier.after;
        }
        public NativeGlobalSlot slot() { return replacement == null ? slot : replacement; }
        public void validate() {
            if (closed || stored || owner.pending != this || data != beforeData || size != beforeSize
                    || modCount != beforeModCount || slot.generation != beforeGeneration)
                throw new IllegalArgumentException("Prepared native global placement is stale");
            slot.validate();
            if (syntheticOrigin != null) {
                syntheticOrigin.validateCarrier(slot.carrier);
                if (replacement.carrier.getKey() != replacement.key || replacement.carrier.hashCode != slot.carrier.hashCode
                        || replacement.carrier.getRawValue() != Memory.UNDEFINED || replacement.carrier.next != null
                        || replacement.carrier.before != null || replacement.carrier.after != null)
                    throw new IllegalArgumentException("Prepared replacement global carrier changed before retirement");
            }
        }
        /** Must run before the owning ReferenceMemory's private raw publication. */
        public void beginStore() {
            validate();
            if (begun) throw new IllegalStateException("Native global placement is already begun");
            begun = true;
        }
        /** No payload store, allocation, key hashing, destroyEntry or user callback. */
        public void store() {
            if (!begun || stored || closed) throw new IllegalStateException("Native global placement is not ready");
            ArrayMapEntryMemory entry = slot.carrier;
            if (link) {
                if (nextData != beforeData) {
                    for (ArrayMapEntryMemory current = header.after; current != header; current = current.after) {
                        int bucket = current.hashCode & (nextData.length - 1);
                        current.next = nextData[bucket]; nextData[bucket] = current;
                    }
                }
                int bucket = entry.hashCode & (nextData.length - 1);
                entry.next = nextData[bucket]; nextData[bucket] = entry;
                entry.before = beforeOrder; entry.after = afterOrder; beforeOrder.after = entry; afterOrder.before = entry;
                data = nextData; threshold = nextThreshold; size = beforeSize + 1;
            } else {
                if (previousHash == null) beforeData[index] = nextHash; else previousHash.next = nextHash;
                beforeOrder.after = afterOrder; afterOrder.before = beforeOrder;
                entry.next = null; entry.before = null; entry.after = null; size = beforeSize - 1;
            }
            modCount = beforeModCount + 1; slot.linked = link; slot.generation = beforeGeneration + 1;
            if (replacement != null) {
                owner.slots=replacementSlots;
                slot.carrier.retireSyntheticGlobalsOrigin();
            }
            stored = true; owner.pending = null;
        }
        @Override public void close() {
            if (closed) return;
            if (begun && !stored) throw new IllegalStateException("Abort raw publication before closing its placement");
            if (!stored && owner.pending == this) owner.pending = null;
            closed = true;
        }
    }

    public void enableBucketLayout() {
        rejectNativeGlobalMutation();
        if (buckets != null) return;
        buckets = new ArrayList<>();
        bucketOrdinals = new IdentityHashMap<>();
        for (ArrayMapEntryMemory entry = header.after; entry != header; entry = entry.after) {
            bucketOrdinals.put(entry, buckets.size());
            buckets.add(entry);
        }
    }

    public boolean hasBucketLayout() { return buckets != null; }
    public int bucketLimit() { return buckets == null ? 0 : buckets.size(); }
    public ArrayMapEntryMemory bucketAt(int ordinal) {
        return buckets == null || ordinal < 0 || ordinal >= buckets.size() ? null : buckets.get(ordinal);
    }
    public int bucketOrdinal(ArrayMapEntryMemory entry) {
        Integer ordinal = bucketOrdinals == null ? null : bucketOrdinals.get(entry);
        return ordinal == null ? -1 : ordinal;
    }

    public void appendBucketHole() { rejectNativeGlobalMutation(); enableBucketLayout(); buckets.add(null); }

    public ArrayMapEntryMemory insertBucket(Object key, Memory value) {
        rejectNativeGlobalMutation();
        enableBucketLayout();
        int hashCode = hash(key == null ? NULL : key);
        return addMapping(hashIndex(hashCode, data.length), hashCode, key, value);
    }

    /** Preallocation only; actual insertion requires the private-origin restoration permit. */
    public final class OwnershipInsertion {
        private final ArrayMapEntryMemory[] beforeData,nextData;
        private final int beforeSize,beforeModCount,nextThreshold;
        private final ArrayMapEntryMemory entry;
        private final ArrayList<ArrayMapEntryMemory> nextBuckets;
        private final IdentityHashMap<ArrayMapEntryMemory,Integer> nextOrdinals;
        private OwnershipInsertion(Object key) {
            beforeData=data;beforeSize=size;beforeModCount=modCount;
            if (getEntry(key) != null) throw new IllegalArgumentException("Insertion key is already present");
            entry=new ArrayMapEntryMemory(null,hash(key == null ? NULL : key),key,Memory.NULL);
            int capacity=data.length;
            if (size+1 >= threshold && capacity <= MAXIMUM_CAPACITY/2) capacity*=2;
            nextData=capacity == data.length ? data : new ArrayMapEntryMemory[capacity];
            nextThreshold=capacity == data.length ? threshold : calculateThreshold(capacity,loadFactor);
            if (buckets == null) {nextBuckets=null;nextOrdinals=null;}
            else {nextBuckets=new ArrayList<>(buckets);nextOrdinals=new IdentityHashMap<>(bucketOrdinals);nextOrdinals.put(entry,nextBuckets.size());nextBuckets.add(entry);}
        }
        public ArrayMapEntryMemory carrier() {return entry;}
        public void validate() {
            if (data != beforeData || size != beforeSize || modCount != beforeModCount || getEntry(entry.getKey()) != null)
                throw new IllegalArgumentException("Prepared map insertion is stale");
        }
        public void store(php.runtime.PhpOwnershipArrays.RestorationPublication publication) {
            rejectNativeGlobalMutation();publication.consumeMapInsertion(ArrayMemoryMap.this,this);storePrepared();
        }
        public void storeRuntime(php.runtime.memory.ArrayMemory.RuntimeInsertion insertion,php.runtime.PhpRuntimeOwnership.Publication publication) {
            rejectNativeGlobalMutation();insertion.consumeMapInsertion(ArrayMemoryMap.this,this,publication);storePrepared();
        }
        private void storePrepared() {
            if (nextData != beforeData) {
                for (ArrayMapEntryMemory existing=header.after;existing != header;existing=existing.after) {
                    int index=hashIndex(existing.hashCode,nextData.length);existing.next=nextData[index];nextData[index]=existing;
                }
            }
            int index=hashIndex(entry.hashCode,nextData.length);entry.next=nextData[index];nextData[index]=entry;
            entry.after=header;entry.before=header.before;header.before.after=entry;header.before=entry;
            data=nextData;threshold=nextThreshold;buckets=nextBuckets;bucketOrdinals=nextOrdinals;size=beforeSize+1;modCount=beforeModCount+1;
        }
    }
    public OwnershipInsertion prepareOwnershipInsertion(php.runtime.PhpOwnershipArrays.RestorationPublication publication,Object key) {
        rejectNativeGlobalMutation();
        publication.validateMapPreparation(this);return new OwnershipInsertion(key);
    }
    public OwnershipInsertion prepareRuntimeInsertion(php.runtime.memory.ArrayMemory.RuntimeInsertion insertion,Object key) {
        rejectNativeGlobalMutation();
        insertion.validateMapPreparation(this);return new OwnershipInsertion(key);
    }
    public ArrayMemoryMap copyBuckets(java.util.function.Function<Memory, Memory> copyValue) {
        ArrayMemoryMap result = new ArrayMemoryMap();
        result.enableBucketLayout();
        for (ArrayMapEntryMemory entry : buckets) {
            if (entry == null) {
                result.buckets.add(null);
            } else {
                result.insertBucket(entry.getKey(), copyValue.apply(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Constructor only used in deserialization, do not use otherwise.
     */
    public ArrayMemoryMap() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, DEFAULT_THRESHOLD);
    }

    /**
     * Constructor which performs no validation on the passed in parameters.
     *
     * @param initialCapacity the initial capacity, must be a power of two
     * @param loadFactor      the load factor, must be &gt; 0.0f and generally &lt; 1.0f
     * @param threshold       the threshold, must be sensible
     */
    protected ArrayMemoryMap(int initialCapacity, float loadFactor, int threshold) {
        super();
        this.loadFactor = loadFactor;
        this.data = new ArrayMapEntryMemory[initialCapacity];
        this.threshold = threshold;
        init();
    }

    /**
     * Constructs a new, empty map with the specified initial capacity and
     * default load factor.
     *
     * @param initialCapacity the initial capacity
     * @throws IllegalArgumentException if the initial capacity is less than one
     */
    public ArrayMemoryMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    /**
     * Constructs a new, empty map with the specified initial capacity and
     * load factor.
     *
     * @param initialCapacity the initial capacity
     * @param loadFactor      the load factor
     * @throws IllegalArgumentException if the initial capacity is less than one
     * @throws IllegalArgumentException if the load factor is less than or equal to zero
     */
    public ArrayMemoryMap(int initialCapacity, float loadFactor) {
        super();
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be greater than 0");
        }
        if (loadFactor <= 0.0f || Float.isNaN(loadFactor)) {
            throw new IllegalArgumentException("Load factor must be greater than 0");
        }
        this.loadFactor = loadFactor;
        this.threshold = calculateThreshold(initialCapacity, loadFactor);
        initialCapacity = calculateNewCapacity(initialCapacity);
        this.data = new ArrayMapEntryMemory[initialCapacity];
        init();
    }

    /**
     * Constructor copying elements from another map.
     *
     * @param map the map to copy
     * @throws NullPointerException if the map is null
     */
    public ArrayMemoryMap(ArrayMemoryMap map) {
        this(Math.max(2 * map.size(), DEFAULT_CAPACITY), DEFAULT_LOAD_FACTOR);
        if (map.hasBucketLayout()) {
            enableBucketLayout();
            for (ArrayMapEntryMemory entry : map.buckets) {
                if (entry == null) appendBucketHole();
                else insertBucket(entry.getKey(), entry.getValue());
            }
        } else putAll(map);
    }

    /**
     * Initialise subclasses during construction, cloning or deserialization.
     */
    protected void init() {
        header = new ArrayMapEntryMemory(null, -1, null, null);
        header.before = header.after = header;
    }

    /**
     * Gets the key at the specified index.
     *
     * @param index the index to retrieve
     * @return the key at the specified index
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    public Object get(int index) {
        return getEntry(index).getKey();
    }

    /**
     * Gets the value at the specified index.
     *
     * @param index the index to retrieve
     * @return the key at the specified index
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    public Memory getValue(int index) {
        return getEntry(index).getValue();
    }

    /**
     * Gets the index of the specified key.
     *
     * @param key the key to find the index of
     * @return the index, or -1 if not found
     */
    public int indexOf(Object key) {
        int i = 0;
        for (ArrayMapEntryMemory entry = header.after; entry != header; entry = entry.after, i++) {
            if (isEqualKey(key, entry.getKey())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Removes the element at the specified index.
     *
     * @param index the index of the object to remove
     * @return the previous value corresponding the <code>key</code>,
     *         or <code>null</code> if none existed
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    public Memory remove(int index) {
        return remove(get(index));
    }

    /**
     * Gets an unmodifiable List view of the keys.
     * <p/>
     * The returned list is unmodifiable because changes to the values of
     * the list (using {@link java.util.ListIterator#set(Object)}) will
     * effectively remove the value from the list and reinsert that value at
     * the end of the list, which is an unexpected side effect of changing the
     * value of a list.  This occurs because changing the key, changes when the
     * mapping is added to the map and thus where it appears in the list.
     * <p/>
     * An alternative to this method is to use {@link #keySet()}.
     *
     * @return The ordered list of keys.
     * @see #keySet()
     */
    public List<Object> asList() {
        return new LinkedMapList(this);
    }

    static class LinkedMapList extends AbstractList<Object> {

        final ArrayMemoryMap parent;

        LinkedMapList(ArrayMemoryMap parent) {
            this.parent = parent;
        }

        public int size() {
            return parent.size();
        }

        public Object get(int index) {
            return parent.get(index);
        }

        public boolean contains(Object obj) {
            return parent.containsKey(obj);
        }

        public int indexOf(Object obj) {
            return parent.indexOf(obj);
        }

        public int lastIndexOf(Object obj) {
            return parent.indexOf(obj);
        }

        public boolean containsAll(Collection<?> coll) {
            return parent.keySet().containsAll(coll);
        }

        public Object remove(int index) {
            throw new UnsupportedOperationException();
        }

        public boolean remove(Object obj) {
            throw new UnsupportedOperationException();
        }

        public boolean removeAll(Collection<?> coll) {
            throw new UnsupportedOperationException();
        }

        public boolean retainAll(Collection<?> coll) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        public Object[] toArray() {
            return parent.keySet().toArray();
        }

        public <T> T[] toArray(T[] array) {
            return parent.keySet().toArray(array);
        }

        public Iterator<Object> iterator() {
            return UnmodifiableIterator.decorate(parent.keySet().iterator());
        }

        public ListIterator<Object> listIterator() {
            return UnmodifiableListIterator.decorate(super.listIterator());
        }

        public ListIterator<Object> listIterator(int fromIndex) {
            return UnmodifiableListIterator.decorate(super.listIterator(fromIndex));
        }

        public List<Object> subList(int fromIndexInclusive, int toIndexExclusive) {
            return UnmodifiableList.decorate(super.subList(fromIndexInclusive, toIndexExclusive));
        }
    }

    //-----------------------------------------------------------------------
    /**
     * Gets the value mapped to the key specified.
     *
     * @param key the key
     * @return the mapped value, null if no match
     */
    public Memory get(Object key) {
        int hashCode = hash((key == null) ? NULL : key);
        ArrayMapEntryMemory entry = data[hashIndex(hashCode, data.length)]; // no local for hash index
        while (entry != null) {
            if (entry.hashCode == hashCode && isEqualKey(key, entry.getKey())) {
                return entry.getValue();
            }
            entry = entry.next;
        }
        return null;
    }

    /**
     * @return the size
     */
    public int size() {
        return size;
    }

    /**
     * @return true if the map is currently size zero
     */
    public boolean isEmpty() {
        return (size == 0);
    }
    /**
     * Checks whether the map contains the specified key.
     *
     * @param key the key to search for
     * @return true if the map contains the key
     */
    public boolean containsKey(Object key) {
        int hashCode = hash((key == null) ? NULL : key);
        ArrayMapEntryMemory entry = data[hashIndex(hashCode, data.length)]; // no local for hash index
        while (entry != null) {
            if (entry.hashCode == hashCode && isEqualKey(key, entry.getKey())) {
                return true;
            }
            entry = entry.next;
        }
        return false;
    }

    /**
     * Checks whether the map contains the specified value.
     *
     * @param value the value to search for
     * @return true if the map contains the value
     */
    public boolean containsValue(Object value) {
        // override uses faster iterator
        if (value == null) {
            for (ArrayMapEntryMemory entry = header.after; entry != header; entry = entry.after) {
                if (entry.getValue() == null) {
                    return true;
                }
            }
        } else {
            for (ArrayMapEntryMemory entry = header.after; entry != header; entry = entry.after) {
                if (isEqualValue(value, entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Puts a key-value mapping into this map.
     *
     * @param key   the key to add
     * @param value the value to add
     * @return the value previously mapped to this key, null if none
     */
    public Memory put(Object key, Memory value) {
        return putWithEntry(key, value).getA();
    }

    public Pair<Memory, ArrayMapEntryMemory> putWithEntry(Object key, Memory value) {
        rejectNativeGlobalMutation();
        int hashCode = hash((key == null) ? NULL : key);
        int index = hashIndex(hashCode, data.length);
        ArrayMapEntryMemory entry = data[index];

        while (entry != null) {
            if (entry.hashCode == hashCode && isEqualKey(key, entry.getKey())) {
                Memory oldValue = entry.getValue();
                updateEntry(entry, value);
                return new Pair<>(oldValue, entry);
            }

            entry = entry.next;
        }

        entry = addMapping(index, hashCode, key, value);
        return new Pair<>(null, entry);
    }

    /**
     * Puts all the values from the specified map into this map.
     *
     * @param map the map to add
     * @throws NullPointerException if the map is null
     */
    public void putAll(ArrayMemoryMap map) {
        rejectNativeGlobalMutation();
        int mapSize = map.size();
        if (mapSize == 0) {
            return;
        }
        int newSize = (int) ((size + mapSize) / loadFactor + 1);
        ensureCapacity(calculateNewCapacity(newSize));
        // Have to cast here because of compiler inference problems.
        for (Iterator it = map.entrySet().iterator(); it.hasNext();) {
            ArrayMapEntryMemory entry = (ArrayMapEntryMemory) it.next();
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Removes the specified mapping from this map.
     *
     * @param key the mapping to remove
     * @return the value mapped to the removed key, null if key not in map
     */
    private void removePhysicalEntry(ArrayMapEntryMemory wanted) {
        int index = hashIndex(wanted.hashCode, data.length);
        ArrayMapEntryMemory previous = null;
        for (ArrayMapEntryMemory entry = data[index]; entry != null; entry = entry.next) {
            if (entry == wanted) { removeMapping(entry, index, previous); return; }
            previous = entry;
        }
    }

    public Memory remove(Object key) {
        rejectNativeGlobalMutation();
        int hashCode = hash((key == null) ? NULL : key);
        int index = hashIndex(hashCode, data.length);
        ArrayMapEntryMemory entry = data[index];
        ArrayMapEntryMemory previous = null;
        while (entry != null) {
            if (entry.hashCode == hashCode && isEqualKey(key, entry.getKey())) {
                Memory oldValue = entry.getValue();
                removeMapping(entry, index, previous);
                return oldValue;
            }
            previous = entry;
            entry = entry.next;
        }
        return null;
    }

    /**
     * Clears the map, resetting the size to zero and nullifying references
     * to avoid garbage collection issues.
     */
    public void clear() {
        rejectNativeGlobalMutation();
        // Validate every carrier before any bulk unlink, including retained collection views.
        for (ArrayMapEntryMemory entry = header.after; entry != header; entry = entry.after)
            rejectCertifiedUnlink(entry);
        modCount++;
        ArrayMapEntryMemory[] data = this.data;
        for (int i = data.length - 1; i >= 0; i--) {
            data[i] = null;
        }
        size = 0;

        header.before = header.after = header;
        if (buckets != null) {
            buckets.clear();
            bucketOrdinals.clear();
        }
    }

    /**
     * Gets the first key in the map, which is the most recently inserted.
     *
     * @return the most recently inserted key
     */
    public Object firstKey() {
        if (size == 0) {
            throw new NoSuchElementException("Map is empty");
        }

        return header.after.getKey();
    }

    /**
     * Gets the last key in the map, which is the first inserted.
     *
     * @return the eldest key
     */
    public Object lastKey() {
        if (size == 0) {
            throw new NoSuchElementException("Map is empty");
        }

        return header.before.getKey();
    }

    /**
     * Gets the next key in sequence.
     *
     * @param key the key to get after
     * @return the next key
     */
    public Object nextKey(Object key) {
        ArrayMapEntryMemory entry = (ArrayMapEntryMemory) getEntry(key);
        return (entry == null || entry.after == header ? null : entry.after.getKey());
    }

    /**
     * Gets the previous key in sequence.
     *
     * @param key the key to get before
     * @return the previous key
     */
    public Object previousKey(Object key) {
        ArrayMapEntryMemory entry = (ArrayMapEntryMemory) getEntry(key);
        return (entry == null || entry.before == header ? null : entry.before.getKey());
    }

    /**
     * Gets the hash code for the key specified.
     * This implementation uses the additional hashing routine from JDK1.4.
     * Subclasses can override this to return alternate hash codes.
     *
     * @param key the key to get a hash code for
     * @return the hash code
     */
    protected int hash(Object key) {
        // same as JDK 1.4
        /*int h = key.hashCode();
        h += ~(h << 9);
        h ^= (h >>> 14);
        h += (h << 4);
        h ^= (h >>> 10);*/
        return key.hashCode();
    }

    /**
     * Compares two keys, in internal converted form, to see if they are equal.
     * This implementation uses the equals method.
     * Subclasses can override this to match differently.
     *
     * @param key1 the first key to compare passed in from outside
     * @param key2 the second key extracted from the entry via <code>entry.key</code>
     * @return true if equal
     */
    protected boolean isEqualKey(Object key1, Object key2) {
        return (key1 == key2 || ((key1 != null) && key1.equals(key2)));
    }

    /**
     * Compares two values, in external form, to see if they are equal.
     * This implementation uses the equals method and assumes neither value is null.
     * Subclasses can override this to match differently.
     *
     * @param value1 the first value to compare passed in from outside
     * @param value2 the second value extracted from the entry via <code>getValue()</code>
     * @return true if equal
     */
    protected boolean isEqualValue(Object value1, Object value2) {
        return (value1 == value2 || value1.equals(value2));
    }

    /**
     * Gets the index into the data storage for the hashCode specified.
     * This implementation uses the least significant bits of the hashCode.
     * Subclasses can override this to return alternate bucketing.
     *
     * @param hashCode the hash code to use
     * @param dataSize the size of the data to pick a bucket from
     * @return the bucket index
     */
    protected int hashIndex(int hashCode, int dataSize) {
        return hashCode & (dataSize - 1);
    }

    /**
     * Gets the entry mapped to the key specified.
     * <p/>
     * This method exists for subclasses that may need to perform a multi-step
     * process accessing the entry. The public methods in this class don't use this
     * method to gain a small performance boost.
     *
     * @param key the key
     * @return the entry, null if no match
     */
    public ArrayMapEntryMemory getEntry(Object key) {
        int hashCode = hash((key == null) ? NULL : key);
        ArrayMapEntryMemory entry = data[hashIndex(hashCode, data.length)]; // no local for hash index
        while (entry != null) {
            if (entry.hashCode == hashCode && isEqualKey(key, entry.getKey())) {
                return entry;
            }
            entry = entry.next;
        }
        return null;
    }

    protected ArrayMapEntryMemory getEntry(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index " + index + " is less than zero");
        }
        if (index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " is invalid for size " + size);
        }

        ArrayMapEntryMemory entry;
        if (index < (size / 2)) {
            // Search forwards
            entry = header.after;
            for (int currentIndex = 0; currentIndex < index; currentIndex++) {
                entry = entry.after;
            }
        } else {
            // Search backwards
            entry = header;
            for (int currentIndex = size; currentIndex > index; currentIndex--) {
                entry = entry.before;
            }
        }
        return entry;
    }

    /**
     * Updates an existing key-value mapping to change the value.
     * <p/>
     * This implementation calls <code>setValue()</code> on the entry.
     * Subclasses could override to handle changes to the map.
     *
     * @param entry    the entry to update
     * @param newValue the new value to store
     */
    protected void updateEntry(ArrayMapEntryMemory entry, Memory newValue) {
        entry.setValue(newValue);
    }

    /**
     * Reuses an existing key-value mapping, storing completely new data.
     * <p/>
     * This implementation sets all the data fields on the entry.
     * Subclasses could populate additional entry fields.
     *
     * @param entry     the entry to update, not null
     * @param hashIndex the index in the data array
     * @param hashCode  the hash code of the key to add
     * @param key       the key to add
     * @param value     the value to add
     */
    protected void reuseEntry(ArrayMapEntryMemory entry, int hashIndex, int hashCode, Object key, Memory value) {
        entry.next = data[hashIndex];
        entry.hashCode = hashCode;
        entry.setKey(key);
        entry.setValue(value);
    }

    /**
     * Adds a new key-value mapping into this map.
     * <p/>
     * This implementation calls <code>createEntry()</code>, <code>addEntry()</code>
     * and <code>checkCapacity()</code>.
     * It also handles changes to <code>modCount</code> and <code>size</code>.
     * Subclasses could override to fully control adds to the map.
     *
     * @param hashIndex the index into the data array to store at
     * @param hashCode  the hash code of the key to add
     * @param key       the key to add
     * @param value     the value to add
     */
    protected ArrayMapEntryMemory addMapping(int hashIndex, int hashCode, Object key, Memory value) {
        modCount++;
        ArrayMapEntryMemory entry = new ArrayMapEntryMemory(data[hashIndex], hashCode, key, value);
        addEntry(entry, hashIndex);
        size++;
        checkCapacity();

        return entry;
    }

    /**
     * Adds an entry into this map.
     * <p/>
     * This implementation adds the entry to the data storage table.
     * Subclasses could override to handle changes to the map.
     *
     * @param entry     the entry to add
     * @param hashIndex the index into the data array to store at
     */
    protected void addEntry(ArrayMapEntryMemory entry, int hashIndex) {
        ArrayMapEntryMemory link = entry;
        link.after = header;
        link.before = header.before;
        header.before.after = link;
        header.before = link;
        data[hashIndex] = entry;
        if (buckets != null) {
            bucketOrdinals.put(entry, buckets.size());
            buckets.add(entry);
        }
    }


    /**
     * Removes a mapping from the map.
     * <p/>
     * This implementation calls <code>removeEntry()</code> and <code>destroyEntry()</code>.
     * It also handles changes to <code>modCount</code> and <code>size</code>.
     * Subclasses could override to fully control removals from the map.
     *
     * @param entry     the entry to remove
     * @param hashIndex the index into the data structure
     * @param previous  the previous entry in the chain
     */
    private void rejectCertifiedUnlink(ArrayMapEntryMemory entry) {
        if (entry.getOwnershipEntry() != null || entry.getRuntimeHolder() != null)
            throw new IllegalStateException("Certified map unlink requires its actual ownership publisher");
    }

    /** A private-origin token authorizes only this actual bucket, never payload destruction. */
    public void publishOwnershipUnlink(php.runtime.PhpOwnershipArrays.UnsetPublication publication) {
        rejectNativeGlobalMutation();
        ArrayMapEntryMemory wanted=publication.mapEntry(this);
        int index=hashIndex(wanted.hashCode,data.length);ArrayMapEntryMemory previous=null;
        for (ArrayMapEntryMemory entry=data[index];entry != null;entry=entry.next) {
            if (entry == wanted) {
                publication.consumeMap(this,wanted);modCount++;removeEntry(entry,index,previous);size--;return;
            }
            previous=entry;
        }
        throw new IllegalArgumentException("Removal bucket is no longer attached");
    }

    public void publishRuntimeUnlink(php.runtime.memory.ArrayMemory.RuntimeRemoval removal) {
        rejectNativeGlobalMutation();
        ReferenceMemory wanted=removal.mapEntry(this);
        int index=hashIndex(((ArrayMapEntryMemory)wanted).hashCode,data.length);ArrayMapEntryMemory previous=null;
        for (ArrayMapEntryMemory entry=data[index];entry != null;entry=entry.next) {
            if (entry == wanted) {
                removal.consumeMap(this,wanted);modCount++;removeEntry(entry,index,previous);size--;return;
            }
            previous=entry;
        }
        throw new IllegalArgumentException("Removal bucket is no longer attached");
    }
    protected void removeMapping(ArrayMapEntryMemory entry, int hashIndex, ArrayMapEntryMemory previous) {
        rejectCertifiedUnlink(entry);
        var syntheticOrigin = entry.getSyntheticGlobalsOrigin();
        if (syntheticOrigin != null) syntheticOrigin.validateCarrier(entry);
        modCount++;
        removeEntry(entry, hashIndex, previous);
        size--;
        if (syntheticOrigin != null) entry.retireSyntheticGlobalsOrigin();
        destroyEntry(entry);
    }

    /**
     * Removes an entry from the chain stored in a particular index.
     * <p/>
     * This implementation removes the entry from the data storage table.
     * The size is not updated.
     * Subclasses could override to handle changes to the map.
     *
     * @param entry     the entry to remove
     * @param hashIndex the index into the data structure
     * @param previous  the previous entry in the chain
     */
    protected void removeEntry(ArrayMapEntryMemory entry, int hashIndex, ArrayMapEntryMemory previous) {
        if (buckets != null) {
            Integer ordinal = bucketOrdinals.remove(entry);
            if (ordinal != null) buckets.set(ordinal, null);
        }
        ArrayMapEntryMemory link = entry;
        link.before.after = link.after;
        link.after.before = link.before;
        link.after = null;
        link.before = null;

        if (previous == null) {
            data[hashIndex] = entry.next;
        } else {
            previous.next = entry.next;
        }
    }

    /**
     * Gets the <code>before</code> field from a <code>LinkEntry</code>.
     * Used in subclasses that have no visibility of the field.
     *
     * @param entry the entry to query, must not be null
     * @return the <code>before</code> field of the entry
     * @throws NullPointerException if the entry is null
     * @since Commons Collections 3.1
     */
    protected ArrayMapEntryMemory entryBefore(ArrayMapEntryMemory entry) {
        return entry.before;
    }

    /**
     * Gets the <code>after</code> field from a <code>LinkEntry</code>.
     * Used in subclasses that have no visibility of the field.
     *
     * @param entry the entry to query, must not be null
     * @return the <code>after</code> field of the entry
     * @throws NullPointerException if the entry is null
     * @since Commons Collections 3.1
     */
    protected ArrayMapEntryMemory entryAfter(ArrayMapEntryMemory entry) {
        return entry.after;
    }


    /**
     * Kills an entry ready for the garbage collector.
     * <p/>
     * This implementation prepares the HashEntry for garbage collection.
     * Subclasses can override this to implement caching (override clear as well).
     *
     * @param entry the entry to destroy
     */
    protected void destroyEntry(ArrayMapEntryMemory entry) {
        entry.next = null;
        entry.setKey(null);
        entry.detachPropertySource();
        entry.setValue(null);
    }

    /**
     * Checks the capacity of the map and enlarges it if necessary.
     * <p/>
     * This implementation uses the threshold to check if the map needs enlarging
     */
    protected void checkCapacity() {
        if (size >= threshold) {
            int newCapacity = data.length * 2;
            if (newCapacity <= MAXIMUM_CAPACITY) {
                ensureCapacity(newCapacity);
            }
        }
    }

    /**
     * Changes the size of the data structure to the capacity proposed.
     *
     * @param newCapacity the new capacity of the array (a power of two, less or equal to max)
     */
    protected void ensureCapacity(int newCapacity) {
        int oldCapacity = data.length;
        if (newCapacity <= oldCapacity) {
            return;
        }
        if (size == 0) {
            threshold = calculateThreshold(newCapacity, loadFactor);
            data = new ArrayMapEntryMemory[newCapacity];
        } else {
            ArrayMapEntryMemory oldEntries[] = data;
            ArrayMapEntryMemory newEntries[] = new ArrayMapEntryMemory[newCapacity];

            modCount++;
            if (buckets != null) {
                // Insert in physical order: the newest duplicate must stay the hash head.
                for (ArrayMapEntryMemory entry : buckets) {
                    if (entry == null) continue;
                    int index = hashIndex(entry.hashCode, newCapacity);
                    entry.next = newEntries[index];
                    newEntries[index] = entry;
                }
            } else for (int i = oldCapacity - 1; i >= 0; i--) {
                ArrayMapEntryMemory entry = oldEntries[i];
                if (entry != null) {
                    oldEntries[i] = null;  // gc
                    do {
                        ArrayMapEntryMemory next = entry.next;
                        int index = hashIndex(entry.hashCode, newCapacity);
                        entry.next = newEntries[index];
                        newEntries[index] = entry;
                        entry = next;
                    } while (entry != null);
                }
            }
            threshold = calculateThreshold(newCapacity, loadFactor);
            data = newEntries;
        }
    }

    /**
     * Calculates the new capacity of the map.
     * This implementation normalizes the capacity to a power of two.
     *
     * @param proposedCapacity the proposed capacity
     * @return the normalized new capacity
     */
    protected int calculateNewCapacity(int proposedCapacity) {
        int newCapacity = 1;
        if (proposedCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        } else {
            while (newCapacity < proposedCapacity) {
                newCapacity <<= 1;  // multiply by two
            }
            if (newCapacity > MAXIMUM_CAPACITY) {
                newCapacity = MAXIMUM_CAPACITY;
            }
        }
        return newCapacity;
    }

    /**
     * Calculates the new threshold of the map, where it will be resized.
     * This implementation uses the load factor.
     *
     * @param newCapacity the new capacity
     * @param factor      the load factor
     * @return the new resize threshold
     */
    protected int calculateThreshold(int newCapacity, float factor) {
        return (int) (newCapacity * factor);
    }

    /**
     * Gets the <code>next</code> field from a <code>HashEntry</code>.
     * Used in subclasses that have no visibility of the field.
     *
     * @param entry the entry to query, must not be null
     * @return the <code>next</code> field of the entry
     * @throws NullPointerException if the entry is null
     * @since Commons Collections 3.1
     */
    protected ArrayMapEntryMemory entryNext(ArrayMapEntryMemory entry) {
        return entry.next;
    }

    /**
     * Gets the <code>hashCode</code> field from a <code>HashEntry</code>.
     * Used in subclasses that have no visibility of the field.
     *
     * @param entry the entry to query, must not be null
     * @return the <code>hashCode</code> field of the entry
     * @throws NullPointerException if the entry is null
     * @since Commons Collections 3.1
     */
    protected int entryHashCode(ArrayMapEntryMemory entry) {
        return entry.hashCode;
    }

    /**
     * Gets the <code>key</code> field from a <code>HashEntry</code>.
     * Used in subclasses that have no visibility of the field.
     *
     * @param entry the entry to query, must not be null
     * @return the <code>key</code> field of the entry
     * @throws NullPointerException if the entry is null
     * @since Commons Collections 3.1
     */
    protected Object entryKey(ArrayMapEntryMemory entry) {
        return entry.getKey();
    }

    /**
     * Gets the <code>value</code> field from a <code>HashEntry</code>.
     * Used in subclasses that have no visibility of the field.
     *
     * @param entry the entry to query, must not be null
     * @return the <code>value</code> field of the entry
     * @throws NullPointerException if the entry is null
     * @since Commons Collections 3.1
     */
    protected Memory entryValue(ArrayMapEntryMemory entry) {
        return entry.getValue();
    }

    /**
     * Gets an iterator over the map.
     * Changes made to the iterator affect this map.
     * <p/>
     * A MapIterator returns the keys in the map. It also provides convenient
     * methods to get the key and value, and set the value.
     * It avoids the need to create an entrySet/keySet/values object.
     * It also avoids creating the Map.Entry object.
     *
     * @return the map iterator
     */
    public MapIterator<Object, Memory> mapIterator() {
        if (size == 0) {
            return EmptyMapIterator.INSTANCE;
        }
        return new LinkMapIterator(this);
    }

    public Iterator entriesIterator() {
        return new EntriesIterator(this);
    }

    /**
     * Gets a bidirectional iterator over the map.
     * Changes made to the iterator affect this map.
     * <p/>
     * A MapIterator returns the keys in the map. It also provides convenient
     * methods to get the key and value, and set the value.
     * It avoids the need to create an entrySet/keySet/values object.
     *
     * @return the map iterator
     */
    /** Reorder an unmanaged table without replacing its physical value carriers. */
    public void reorderKeys(java.util.List<Object> keys) {
        rejectNativeGlobalMutation();
        int expectedVersion = modCount;
        if (keys.size() != size) throw new IllegalArgumentException("Incomplete table permutation");
        ArrayMapEntryMemory[] ordered = new ArrayMapEntryMemory[size];
        IdentityHashMap<ArrayMapEntryMemory, Boolean> seen = new IdentityHashMap<>();
        ArrayList<ArrayMapEntryMemory> newBuckets = buckets == null ? null : new ArrayList<>(size);
        IdentityHashMap<ArrayMapEntryMemory, Integer> newOrdinals = buckets == null ? null : new IdentityHashMap<>();
        for (int i = 0; i < ordered.length; i++) {
            ArrayMapEntryMemory entry = getEntry(keys.get(i));
            if (entry == null || seen.put(entry, Boolean.TRUE) != null)
                throw new IllegalArgumentException("Invalid or duplicate table permutation");
            rejectCertifiedUnlink(entry);
            ordered[i] = entry;
            if (newBuckets != null) { newBuckets.add(entry); newOrdinals.put(entry, i); }
        }
        if (seen.size() != size || modCount != expectedVersion)
            throw new IllegalArgumentException("Stale table permutation");
        // All lookups, authority checks and allocations precede this pointer-only interval.
        ArrayMapEntryMemory previous = header;
        for (int i = 0; i < ordered.length; i++) {
            ArrayMapEntryMemory entry = ordered[i];
            previous.after = entry; entry.before = previous; previous = entry;
        }
        previous.after = header; header.before = previous;
        if (ordered.length == 0) header.after = header;
        if (newBuckets != null) { buckets = newBuckets; bucketOrdinals = newOrdinals; }
        modCount++;
    }

    public OrderedMapIterator<Object, Memory> orderedMapIterator() {
        if (size == 0) {
            return EmptyOrderedMapIterator.INSTANCE;
        }
        return new LinkMapIterator(this);
    }


    /**
     * Gets the entrySet view of the map.
     * Changes made to the view affect this map.
     * To simply iterate through the entries, use {@link #mapIterator()}.
     *
     * @return the entrySet view
     */
    public Set<Entry<Object, Memory>> entrySet() {
        if (entrySet == null) {
            entrySet = new EntrySet(this);
        }

        return entrySet;
    }

    /**
     * Creates an entry set iterator.
     * Subclasses can override this to return iterators with different properties.
     *
     * @return the entrySet iterator
     */
    protected Iterator<Entry<Object, Memory>> createEntrySetIterator() {
        if (size() == 0) {
            return EmptyIterator.INSTANCE;
        }
        return new EntrySetIterator(this);
    }

    /**
     * Gets the keySet view of the map.
     * Changes made to the view affect this map.
     * To simply iterate through the keys, use {@link #mapIterator()}.
     *
     * @return the keySet view
     */
    public Set<Object> keySet() {
        if (keySet == null) {
            keySet = new KeySet(this);
        }
        return keySet;
    }

    /**
     * Creates a key set iterator.
     * Subclasses can override this to return iterators with different properties.
     *
     * @return the keySet iterator
     */
    protected Iterator<Object> createKeySetIterator() {
        if (size() == 0) {
            return EmptyIterator.INSTANCE;
        }
        return new KeySetIterator(this);
    }

    /**
     * Gets the values view of the map.
     * Changes made to the view affect this map.
     * To simply iterate through the values, use {@link #mapIterator()}.
     *
     * @return the values view
     */
    public Collection<Memory> values() {
        if (values == null) {
            values = new Values(this);
        }
        return values;
    }

    /**
     * Creates a values iterator.
     * Subclasses can override this to return iterators with different properties.
     *
     * @return the values iterator
     */
    protected Iterator<Memory> createValuesIterator() {
        if (size() == 0) {
            return EmptyIterator.INSTANCE;
        }
        return new ValuesIterator(this);
    }

    /**
     * MapIterator implementation.
     */
    protected static class LinkMapIterator extends LinkIterator
            implements OrderedMapIterator<Object, Memory>, OrderedIterator<Object>, ResettableIterator<Object> {

        protected LinkMapIterator(ArrayMemoryMap parent) {
            super(parent);
        }

        public Object next() {
            return super.nextEntry().getKey();
        }

        public Object previous() {
            return super.previousEntry().getKey();
        }

        public Object getKey() {
            ArrayMapEntryMemory current = currentEntry();
            if (current == null) {
                throw new IllegalStateException(GETKEY_INVALID);
            }
            return current.getKey();
        }

        public Memory getValue() {
            ArrayMapEntryMemory current = currentEntry();
            if (current == null) {
                throw new IllegalStateException(GETVALUE_INVALID);
            }
            return current.getValue();
        }

        public Memory setValue(Memory value) {
            parent.rejectNativeGlobalMutation();
            ArrayMapEntryMemory current = currentEntry();
            if (current == null) {
                throw new IllegalStateException(SETVALUE_INVALID);
            }
            return current.setValue(value);
        }
    }

    protected static class EntriesIterator extends LinkIterator implements OrderedIterator<ArrayMapEntryMemory>, ResettableIterator<ArrayMapEntryMemory> {
        protected EntriesIterator(ArrayMemoryMap parent) {
            super(parent);
        }

        public ArrayMapEntryMemory next() {
            return super.nextEntry();
        }
        public ArrayMapEntryMemory previous() {
            return super.previousEntry();
        }
    }

    protected static class ValuesIterator extends LinkIterator implements OrderedIterator<Memory>, ResettableIterator<Memory> {
        protected ValuesIterator(ArrayMemoryMap parent) {
            super(parent);
        }

        public Memory next() {
            return super.nextEntry().getValue();
        }
        public Memory previous() {
            return super.previousEntry().getValue();
        }
    }

    protected static class KeySetIterator extends LinkIterator implements OrderedIterator<Object>, ResettableIterator<Object> {
        protected KeySetIterator(ArrayMemoryMap parent) {
            super(parent);
        }

        public Object next() {
            return super.nextEntry().getKey();
        }
        public Object previous() {
            return super.previousEntry().getKey();
        }
    }

    protected static class EntrySetIterator extends LinkIterator
            implements OrderedIterator<Entry<Object, Memory>>, ResettableIterator<Entry<Object, Memory>> {
        protected EntrySetIterator(ArrayMemoryMap parent) {
            super(parent);
        }

        public Entry<Object, Memory> next() {
            ArrayMapEntryMemory entry = super.nextEntry();
            return parent.nativeGlobals == null ? entry : new SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
        }
        public Entry<Object, Memory> previous() {
            ArrayMapEntryMemory entry = super.previousEntry();
            return parent.nativeGlobals == null ? entry : new SimpleImmutableEntry<>(entry.getKey(), entry.getValue());
        }
    }


    protected static class EntrySet extends AbstractSet<Entry<Object, Memory>> {
        /**
         * The parent map
         */
        protected final ArrayMemoryMap parent;

        protected EntrySet(ArrayMemoryMap parent) {
            super();
            this.parent = parent;
        }

        public int size() {
            return parent.size();
        }

        public void clear() {
            parent.clear();
        }

        public boolean contains(ArrayMapEntryMemory entry) {
            ArrayMapEntryMemory e = entry;
            ArrayMapEntryMemory match = parent.getEntry(e.getKey());
            return (match != null && match.equals(e));
        }

        public boolean remove(Object obj) {
            if (!(obj instanceof ArrayMapEntryMemory)) {
                return false;
            }
            if (!contains(obj)) {
                return false;
            }
            ArrayMapEntryMemory entry = (ArrayMapEntryMemory) obj;
            Object key = entry.getKey();
            parent.remove(key);
            return true;
        }

        public Iterator<Entry<Object, Memory>> iterator() {
            return parent.createEntrySetIterator();
        }
    }

    /**
     * KeySet implementation.
     */
    protected static class KeySet extends AbstractSet<Object> {
        /**
         * The parent map
         */
        protected final ArrayMemoryMap parent;

        protected KeySet(ArrayMemoryMap parent) {
            super();
            this.parent = parent;
        }

        public int size() {
            return parent.size();
        }

        public void clear() {
            parent.clear();
        }

        public boolean contains(Object key) {
            return parent.containsKey(key);
        }

        public boolean remove(Object key) {
            boolean result = parent.containsKey(key);
            parent.remove(key);
            return result;
        }

        public Iterator<Object> iterator() {
            return parent.createKeySetIterator();
        }
    }


    /**
     * Values implementation.
     */
    protected static class Values extends AbstractCollection<Memory> {
        /**
         * The parent map
         */
        protected final ArrayMemoryMap parent;

        protected Values(ArrayMemoryMap parent) {
            super();
            this.parent = parent;
        }

        public int size() {
            return parent.size();
        }

        public void clear() {
            parent.clear();
        }

        public boolean contains(Object value) {
            return parent.containsValue(value);
        }

        public Iterator<Memory> iterator() {
            return parent.createValuesIterator();
        }
    }

    /**
     * Base Iterator that iterates in link order.
     */
    protected static abstract class LinkIterator {
        /**
         * The parent map
         */
        protected final ArrayMemoryMap parent;
        /**
         * The current (last returned) entry
         */
        protected ArrayMapEntryMemory last;
        /**
         * The next entry
         */
        protected ArrayMapEntryMemory next;
        /**
         * The modification count expected
         */
        protected int expectedModCount;

        protected LinkIterator(ArrayMemoryMap parent) {
            super();
            this.parent = parent;
            this.next = parent.header.after;
            this.expectedModCount = parent.modCount;
        }

        public boolean hasNext() {
            return (next != parent.header);
        }

        public boolean hasPrevious() {
            return (next.before != parent.header);
        }

        protected ArrayMapEntryMemory nextEntry() {
            if (parent.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (next == parent.header) {
                throw new NoSuchElementException(NO_NEXT_ENTRY);
            }
            last = next;
            next = next.after;
            return last;
        }

        protected ArrayMapEntryMemory previousEntry() {
            if (parent.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            ArrayMapEntryMemory previous = next.before;
            if (previous == parent.header) {
                throw new NoSuchElementException(NO_PREVIOUS_ENTRY);
            }
            next = previous;
            last = previous;
            return last;
        }

        protected ArrayMapEntryMemory currentEntry() {
            return last;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException(REMOVE_INVALID);
            }
            if (parent.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (parent.hasBucketLayout()) {
                if (next == last) next = last.after;
                parent.removePhysicalEntry(last);
            } else parent.remove(last.getKey());
            last = null;
            expectedModCount = parent.modCount;
        }

        public void reset() {
            last = null;
            next = parent.header.after;
        }

        public String toString() {
            if (last != null) {
                return "Iterator[" + last.getKey() + "=" + last.getValue() + "]";
            } else {
                return "Iterator[]";
            }
        }
    }
}
