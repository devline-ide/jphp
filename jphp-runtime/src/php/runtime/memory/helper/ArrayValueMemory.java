package php.runtime.memory.helper;

import php.runtime.Memory;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ReferenceMemory;

public class ArrayValueMemory extends ReferenceMemory {
    private Memory key;
    private ArrayMemory array;
    private int bucketOrdinal = -1;

    public ArrayValueMemory(Memory key, ArrayMemory array, ReferenceMemory value) {
        super(value);
        this.key   = key;
        this.array = array;
    }

    public ArrayValueMemory(Memory key, ArrayMemory array, ReferenceMemory value, int bucketOrdinal) {
        this(key, array, value);
        this.bucketOrdinal = bucketOrdinal;
    }

    private ReferenceMemory copiedEntry(ArrayMemory copied) {
        ReferenceMemory entry = bucketOrdinal < 0 ? copied.getByScalar(ArrayMemory.toKey(key)) : copied.bucketAt(bucketOrdinal);
        if (bucketOrdinal >= 0) {
            if (entry == null) entry = getValue() instanceof ReferenceMemory ? (ReferenceMemory) getValue()
                    : new ReferenceMemory(getValue());
            setValue(entry);
        }
        return entry;
    }

    @Override
    public Memory assign(Memory memory) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).assign(memory);
        return super.assign(memory);
    }

    @Override
    public Memory assign(long memory) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).assign(memory);
        return super.assign(memory);
    }

    @Override
    public Memory assign(String memory) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).assign(memory);
        return super.assign(memory);
    }

    @Override
    public Memory assign(boolean memory) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).assign(memory);
        return super.assign(memory);
    }

    @Override
    public Memory assign(double memory) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).assign(memory);
        return super.assign(memory);
    }

    @Override
    public Memory assignRef(Memory reference) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).assignRef(reference);
        return super.assignRef(reference);
    }

    @Override
    public Memory refOfPush(TraceInfo trace) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).refOfPush();
        return super.refOfPush(trace);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, Memory index) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).refOfIndex(index);
        return super.refOfIndex(trace, index);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, long index) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).refOfIndex(trace, index);
        return super.refOfIndex(trace, index);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, double index) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).refOfIndex(trace, index);
        return super.refOfIndex(trace, index);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, String index) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).refOfIndex(trace, index);
        return super.refOfIndex(trace, index);
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, boolean index) {
        ArrayMemory dup = array.checkCopied();
        if (dup != null) return copiedEntry(dup).refOfIndex(trace, index);
        return super.refOfIndex(trace, index);
    }

    @Override
    public ReferenceMemory getReference() {
        return this;
    }
}
