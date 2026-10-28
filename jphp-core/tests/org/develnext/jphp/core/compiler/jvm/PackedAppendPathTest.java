package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.PhpOwnershipArrays;
import php.runtime.PhpOwnershipDomain;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.KeyValueMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;

import java.io.OutputStream;

import static org.junit.Assert.*;

/** Ordinary packed append and the state transitions that must keep the existing path. */
public class PackedAppendPathTest extends JvmCompilerCase {
    private ReferenceMemory append(ArrayMemory array, Memory value) {
        return array.addPhp8(value, null, TraceInfo.UNKNOWN);
    }

    @Test public void appendReturnsItsPhysicalCarrierAndPreservesReferencedPayload() {
        for (int capacity : new int[]{0, 4, 64}) {
            ArrayMemory array = capacity == 0 ? new ArrayMemory() : ArrayMemory.createListed(capacity);
            ReferenceMemory shared = new ReferenceMemory(LongMemory.valueOf(4)).getReferenceForBinding();
            ReferenceMemory entry = append(array, shared);
            assertSame(entry, array.getByScalar(Memory.CONST_INT_0));
            assertNotSame(shared, entry);
            assertSame(shared, entry.getRawValue());
            for (int i = 1; i < 65; i++) append(array, LongMemory.valueOf(i));
            shared.assign(9);
            assertEquals(9, array.valueOfIndex(0).toLong());
            assertEquals(64, array.valueOfIndex(64).toLong());
            assertEquals(65, array.size());
        }
    }

    @Test public void cowSnapshotsAndPinnedBackingsStillSeparateOnAppend() {
        ArrayMemory array = new ArrayMemory();
        append(array, Memory.CONST_INT_1);
        ArrayMemory snapshot = (ArrayMemory) array.toImmutable();
        Object original = array.getStorageIdentity();
        append(array, LongMemory.valueOf(2));
        assertNotSame(original, array.getStorageIdentity());
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.valueOfIndex(1).isUndefined());
        Object beforePin = array.getStorageIdentity();
        try (var lease = array.pinWriteFetch()) {
            append(array, LongMemory.valueOf(3));
            assertNotSame(beforePin, array.getStorageIdentity());
            assertFalse(lease.isAttached(array));
        }
        assertEquals(3, array.size());
        assertEquals(3, array.valueOfIndex(2).toLong());
    }

    @Test public void exhaustedNativePointerStillSelectsTheNewlyInsertedEntry() {
        ArrayMemory array = new ArrayMemory();
        var cursor = array.getCurrentPointerForNative();
        assertNull(cursor.getKey());
        append(array, Memory.CONST_INT_1);
        assertEquals(0, cursor.getMemoryKey().toLong());
        assertEquals(1, cursor.getValue().toLong());
        assertFalse(cursor.next());
        append(array, LongMemory.valueOf(2));
        assertSame(cursor, array.getCurrentPointerForNative());
        assertEquals(1, cursor.getMemoryKey().toLong());
        assertEquals(2, cursor.getValue().toLong());
    }

    @Test public void keyHistoryNegativeIndicesAndKeyValueEntriesRemainDistinct() {
        ArrayMemory array = new ArrayMemory();
        for (int i = 0; i < 3; i++) append(array, LongMemory.valueOf(i));
        array.remove(LongMemory.valueOf(2));
        append(array, LongMemory.valueOf(7));
        assertTrue(array.valueOfIndex(2).isUndefined());
        assertEquals(7, array.valueOfIndex(3).toLong());
        ArrayMemory negative = new ArrayMemory();
        negative.put(LongMemory.valueOf(-5), Memory.CONST_INT_1);
        append(negative, LongMemory.valueOf(2));
        assertEquals(2, negative.valueOfIndex(-4).toLong());
        assertTrue(negative.valueOfIndex(0).isUndefined());
        ArrayMemory keyed = new ArrayMemory();
        append(keyed, new KeyValueMemory(new StringMemory("named"), LongMemory.valueOf(3)));
        assertEquals(3, keyed.valueOfIndex("named").toLong());
        assertTrue(keyed.valueOfIndex(0).isUndefined());
    }

    @Test public void occupiedMaximumKeyRejectsAppendBeforeChangingTheArray() throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try {
            ArrayMemory array = new ArrayMemory();
            array.put(LongMemory.valueOf(Long.MAX_VALUE), Memory.CONST_INT_1);
            assertThrows(BaseError.class, () -> array.addPhp8(LongMemory.valueOf(2), env, TraceInfo.UNKNOWN));
            assertEquals(1, array.size());
            assertEquals(1, array.valueOfIndex(Long.MAX_VALUE).toLong());
        } finally { env.doFinal(); }
    }

    @Test public void subclassesKeepTheirVirtualChecksAndInsertion() {
        class ObservedArray extends ArrayMemory {
            int copyChecks, appendChecks, insertions;
            @Override public ArrayMemory checkCopied() { copyChecks++; return super.checkCopied(); }
            @Override public boolean canAppendPhp8() { appendChecks++; return super.canAppendPhp8(); }
            @Override public ReferenceMemory put(Object key, Memory value) { insertions++; return super.put(key, value); }
        }
        ObservedArray array = new ObservedArray();
        append(array, Memory.CONST_INT_1);
        assertEquals(1, array.copyChecks);
        assertEquals(1, array.appendChecks);
        assertEquals(1, array.insertions);
        assertEquals(1, array.valueOfIndex(0).toLong());
    }

    @SuppressWarnings("deprecation")
    @Test public void legacyDeclaredSizeDoesNotMoveAnAppendIntoAPhysicalGap() {
        class UnsupportedValue {}
        ArrayMemory array = new ArrayMemory(new Object[]{new UnsupportedValue(), "kept"});
        append(array, LongMemory.valueOf(7));
        assertEquals("kept", array.valueOfIndex(0).toString());
        assertTrue(array.valueOfIndex(1).isUndefined());
        assertEquals(7, array.valueOfIndex(2).toLong());
    }

    @Test public void certifiedStorageRejectsOutOfProtocolAppend() throws Throwable {
        PhpOwnershipDomain domain = new PhpOwnershipDomain();
        var participant = domain.participant("packed-append-test");
        try (var execution = participant.enter(); var allocation = new PhpOwnershipArrays(domain.heap()).allocate(false)) {
            ArrayMemory array = allocation.array();
            Object identity = array.getStorageIdentity();
            assertThrows(IllegalStateException.class, () -> append(array, Memory.CONST_INT_1));
            assertEquals(0, array.size());
            assertSame(identity, array.getStorageIdentity());
        } finally { participant.close(); }
    }
}
