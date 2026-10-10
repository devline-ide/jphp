package php.runtime;

import org.junit.Test;
import php.runtime.memory.ArrayMemory;
import static org.junit.Assert.*;

/** Pins stabilize an address; they do not add PHP owners or change its allocation stamp. */
public class PhpOwnershipStorageLeaseTest {
    @Test public void ordinaryLeasesCloseInAnyOrderWithoutChangingBackingIdentity() {
        var array=new ArrayMemory();Object identity=array.getStorageIdentity();
        var oldest=array.pinWriteFetch();var middle=array.pinWriteFetch();var newest=array.pinWriteFetch();
        assertTrue(array.hasWriteFetchPins());assertNull(array.getOwnershipStorage());
        middle.close();assertTrue(array.hasWriteFetchPins());
        oldest.close();oldest.close();assertTrue(array.hasWriteFetchPins());
        assertSame(identity,array.getStorageIdentity());assertTrue(newest.isAttached(array));
        newest.close();newest.close();assertFalse(array.hasWriteFetchPins());assertNull(array.getOwnershipStorage());
    }

    @Test public void certificateSurvivesActiveLeaseRemovalAndRepeatedAttachment() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("lease-test");
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(domain.heap());
            try (var allocation=arrays.allocate(false)) {
                var array=allocation.array();var certificate=allocation.certificate();
                var oldest=array.pinWriteFetch();var newest=array.pinWriteFetch();
                assertSame(certificate,array.getOwnershipStorage());array.attachOwnershipStorage(certificate);
                assertSame(certificate,arrays.certificate(array));
                oldest.close();assertTrue(array.hasWriteFetchPins());assertSame(certificate,array.getOwnershipStorage());
                newest.close();assertFalse(array.hasWriteFetchPins());assertSame(certificate,array.getOwnershipStorage());
                assertEquals(1,certificate.node().incoming());
            }
        }
        participant.close();assertEquals(0,domain.graph().liveNodes());
    }

    @Test public void legacyCopyMovesOnlyWriterAndOldLeasesRemainAttachedToOldView() {
        var array=new ArrayMemory();Object oldBacking=array.getStorageIdentity();
        var first=array.pinWriteFetch();var second=array.pinWriteFetch();
        ArrayMemory immutable=array.toImmutable().toValue(ArrayMemory.class);
        assertSame(oldBacking,immutable.getStorageIdentity());
        assertNotNull(array.checkCopied());assertNotSame(oldBacking,array.getStorageIdentity());
        assertFalse(first.isAttached(array));assertTrue(first.isAttached(immutable));
        assertFalse(array.hasWriteFetchPins());assertTrue(immutable.hasWriteFetchPins());
        first.close();assertTrue(immutable.hasWriteFetchPins());second.close();assertFalse(immutable.hasWriteFetchPins());
    }
}
