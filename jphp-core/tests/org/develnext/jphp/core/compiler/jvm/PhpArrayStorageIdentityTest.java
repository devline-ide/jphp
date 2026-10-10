package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.PhpOwnership;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ReferenceMemory;

import static org.junit.Assert.*;

/** Backing allocation identity is distinct from Java view count and PHP owner registration. */
public class PhpArrayStorageIdentityTest {
    @Test public void testViewsMadeBeforeFirstLeaseShareTheCapturedBacking() {
        ArrayMemory array = new ArrayMemory();
        ArrayMemory first = (ArrayMemory) array.toImmutable();
        ArrayMemory second = (ArrayMemory) first.toImmutable();
        Object identity = array.getStorageIdentity();
        assertNotNull(identity);
        assertSame(identity, first.getStorageIdentity());
        assertSame(identity, second.getStorageIdentity());
        try (var lease = array.pinWriteFetch()) {
            assertTrue("a pre-existing empty view has the same backing", lease.isAttached(first));
            assertTrue(lease.isAttached(second));
        }
    }

    @Test public void testRepresentationConversionKeepsAllocationButClearReplacesIt() {
        ArrayMemory array = ArrayMemory.createListed(4);
        Object allocation = array.getStorageIdentity();
        array.add(1); array.refOfIndex("named").assign(2);
        assertSame("packed-to-map is a representation change", allocation, array.getStorageIdentity());
        assertEquals(1, array.valueOfIndex(0).toLong());
        assertEquals(2, array.valueOfIndex("named").toLong());
        try (var lease = array.pinWriteFetch()) {
            array.clear();
            assertFalse("cleared contents are a fresh allocation", lease.isAttached(array));
        }
        assertNotSame(allocation, array.getStorageIdentity());
        assertEquals(0, array.size());
        array.refOfIndex("new").assign(3);
        assertEquals(3, array.valueOfIndex("new").toLong());
    }

    @Test public void testCowSeparatesOnlyMutatingContentsAndKeepsOldLeaseAttached() {
        ArrayMemory array = new ArrayMemory(); array.add(1);
        ArrayMemory copy = (ArrayMemory) array.toImmutable();
        Object old = copy.getStorageIdentity();
        try (var lease = array.pinWriteFetch()) {
            ArrayMemory duplicate = array.checkCopied();
            assertNotNull(duplicate);
            assertSame(array.getStorageIdentity(), duplicate.getStorageIdentity());
            assertNotSame(old, array.getStorageIdentity());
            assertFalse(lease.isAttached(array)); assertTrue(lease.isAttached(copy));
            array.refOfIndex(0).assign(7);
            assertEquals(7, array.valueOfIndex(0).toLong());
            assertEquals(1, copy.valueOfIndex(0).toLong());
        }
        Object current = array.getStorageIdentity();
        assertNull("closing the old pin must not force another unrelated split", array.checkCopied());
        assertSame(current, array.getStorageIdentity());
    }

    @Test public void testDuplicateCreatesNewBackingWhileReferenceEntriesKeepCanonicalCell() {
        ArrayMemory array = ArrayMemory.createHashed(4);
        array.refOfIndex("shared").assign(1);
        ReferenceMemory shared = ((ReferenceMemory) array.refOfIndex("shared")).getReferenceForBinding();
        ArrayMemory duplicate = array.duplicate();
        assertNotSame(array.getStorageIdentity(), duplicate.getStorageIdentity());
        duplicate.refOfIndex("shared").assign(9);
        assertEquals(9, shared.toLong());
        assertEquals(9, array.valueOfIndex("shared").toLong());
        assertNotSame(new ArrayMemory().getStorageIdentity(), new ArrayMemory().getStorageIdentity());
        ArrayMemory constant = new ArrayMemory().toConstant();
        Object allocation = constant.getStorageIdentity();
        Memory constantView = constant.toImmutable();
        assertSame(allocation, ((ArrayMemory) constantView).getStorageIdentity());
    }

    @Test public void testStorageNodesUseAllocationIdentityWithoutCountingViewsAsOwners() throws Throwable {
        PhpOwnership graph = new PhpOwnership();
        ArrayMemory array = new ArrayMemory();
        ArrayMemory view = (ArrayMemory) array.toImmutable();
        PhpOwnership.Node original = graph.storage(array.getStorageIdentity());
        assertSame(original, graph.storage(view.getStorageIdentity()));
        PhpOwnership.Slot owner = graph.root(); owner.replace(original);
        assertEquals(1, original.incoming());
        ArrayMemory separate = array.duplicate();
        PhpOwnership.Node replacement = graph.storage(separate.getStorageIdentity());
        assertNotSame(original, replacement);
        owner.replace(replacement);
        assertEquals(PhpOwnership.State.DISPOSED, original.state());
        owner.close();
        assertEquals(0, graph.liveNodes());
    }
}
