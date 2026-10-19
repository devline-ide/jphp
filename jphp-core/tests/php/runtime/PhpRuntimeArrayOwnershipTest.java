package php.runtime;

import org.junit.Test;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Actual ordinary holders in the shared heap; these tests do not claim whole compiler admission. */
public class PhpRuntimeArrayOwnershipTest {
    private static final class Fixture implements AutoCloseable {
        final Environment env=new Environment(new ByteArrayOutputStream());
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
        final PhpOwnershipDomain.Execution execution=authority.enter();
        final PhpRuntimeOwnership.Frame frame=authority.frame();
        private int nextLocal,nextEval=100;
        ReferenceMemory local(String name) throws Throwable {
            ReferenceMemory carrier=new ReferenceMemory(Memory.UNDEFINED);
            try(var publication=frame.prepareLocal(nextLocal++,name,carrier,PhpRuntimeOwnership.Role.BORROW_ADDRESS)) {
                publication.afterLocalStore();publication.commit();
            }
            return carrier;
        }
        ArrayMemory fresh(ReferenceMemory carrier) throws Throwable {
            int id=nextEval++;ArrayMemory array=PhpOwnershipArrays.runtimeFresh(frame,id,new ArrayMemory());
            carrier.assign(array);frame.releaseEval(id);return array;
        }
        @Override public void close() throws Exception {
            frame.close();authority.close();execution.close();
            assertEquals(0,domain.graph().liveNodes());
        }
    }
    @Test public void nativeReadSnapshotsDoNotOwnStorage() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var array=f.fresh(a);var borrowed=array.toImmutableForNativeRead();
            assertSame(array.getStorageIdentity(),borrowed.getStorageIdentity());
            assertEquals(1,array.getRuntimeOrigin().node().incoming());
            assertSame(array,PhpOwnershipArrays.runtimeWritable(a.getRuntimeHolder()));
        }
    }
    @Test public void copyOnWriteMovesOnlyActualWriterAndKeepsChildEdges() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var copy=f.local("copy");var array=f.fresh(a);
            array.put("value",LongMemory.valueOf(9));copy.assign(array.toImmutable());
            assertEquals(2,array.getRuntimeOrigin().node().incoming());
            var writable=PhpOwnershipArrays.runtimeWritable(a.getRuntimeHolder());
            assertNotSame(array.getStorageIdentity(),writable.getStorageIdentity());
            assertSame(writable,a.toValue());assertSame(array.getStorageIdentity(),copy.toValue(ArrayMemory.class).getStorageIdentity());
            writable.put("value",LongMemory.valueOf(10));
            assertEquals(9,copy.toValue(ArrayMemory.class).getByScalar("value").toLong());
            assertEquals(10,a.toValue(ArrayMemory.class).getByScalar("value").toLong());
        }
    }
    @Test public void diagnosticIgnoresReleasedLocalAndAbortsForRetainedValue() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var copy=f.local("copy");var array=f.fresh(a);
            try(var lease=array.pinWriteFetch()) {
                copy.assign(array.toImmutable());assertNull(lease.continuation(a));
                copy.unset();assertSame(array,lease.continuation(a));
            }
            assertEquals(1,array.getRuntimeOrigin().node().incoming());
        }
    }
    @Test public void continuationFindsOldBackingAfterOriginalCvRebind() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var copy=f.local("copy");var array=f.fresh(a);
            try(var lease=array.pinWriteFetch()) {
                copy.assign(array.toImmutable());f.fresh(a);
                var continuation=lease.continuation(a);assertSame(copy.toValue(),continuation);
                assertSame(array.getStorageIdentity(),continuation.getStorageIdentity());
            }
            var saved=copy.toValue(ArrayMemory.class);saved.put(LongMemory.valueOf(2),LongMemory.valueOf(1));
            assertEquals(0,a.toValue(ArrayMemory.class).size());assertEquals(1,saved.get(LongMemory.valueOf(2)).toLong());
        }
    }
    @Test public void canonicalCellAliasesHaveOneArrayPayloadOwner() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var alias=f.local("alias");var array=f.fresh(a);
            var cell=a.getReferenceForBinding();alias.assignRef(cell);
            assertEquals(2,cell.getRuntimeCell().node().incoming());
            assertEquals(1,array.getRuntimeOrigin().node().incoming());
            assertSame(array,PhpOwnershipArrays.runtimeWritable(a.getRuntimeHolder()));
            try(var lease=array.pinWriteFetch()) {assertSame(array,lease.continuation(a));}
        }
    }
    @Test public void nestedArrayHasPhysicalHolderBeforeProducerRelease() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var parent=f.fresh(a);
            var child=PhpOwnershipArrays.runtimeFresh(f.frame,900,new ArrayMemory());
            parent.put("child",child);f.frame.releaseEval(900);
            assertEquals(1,child.getRuntimeOrigin().node().incoming());
            var node=child.getRuntimeOrigin().node();parent.removeByScalar("child");assertEquals(PhpOwnership.State.DISPOSED,node.state());
            assertEquals(0,parent.size());
        }
    }
    @Test public void linkedEnvironmentResolvesStorageAfterAllocatorParticipantCloses() throws Throwable {
        var f=new Fixture();var a=f.local("a");var array=f.fresh(a);
        array.put("value",LongMemory.valueOf(9));
        var linkedEnv=new Environment(f.env,new ByteArrayOutputStream());
        var linked=new PhpRuntimeOwnership.Authority(linkedEnv,f.domain);
        var execution=linked.enter();var frame=linked.frame();
        try {
            ReferenceMemory carrier=new ReferenceMemory(Memory.UNDEFINED);
            try(var publication=frame.prepareLocal(0,"linked",carrier,PhpRuntimeOwnership.Role.BORROW_ADDRESS)) {
                publication.afterLocalStore();publication.commit();
            }
            carrier.assign(array.toImmutable());f.frame.close();f.authority.close();
            assertSame(linked,array.getRuntimeOrigin().authority());
            assertSame(carrier.toValue(),PhpOwnershipArrays.runtimeWritable(carrier.getRuntimeHolder()));
            try(var lease=carrier.toValue(ArrayMemory.class).pinWriteFetch()) {
                assertSame(carrier.toValue(),lease.continuation(carrier));
            }
            carrier.toValue(ArrayMemory.class).put("value",LongMemory.valueOf(10));
            assertEquals(10,carrier.toValue(ArrayMemory.class).getByScalar("value").toLong());
        } finally {
            frame.close();linked.close();execution.close();f.execution.close();
        }
        assertEquals(0,f.domain.graph().liveNodes());
    }

    @Test public void cellInsertionPublishesItsActualBucketBeforeReferenceWrites() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var scalar=f.local("scalar");var array=f.fresh(a);
            scalar.assign(LongMemory.valueOf(9));var cell=scalar.getReferenceForBinding();
            array.put("ref",cell);
            assertEquals(2,cell.getRuntimeCell().node().incoming());
            array.refOfIndex("ref").assign(LongMemory.valueOf(10));assertEquals(10,scalar.toLong());
            array.removeByScalar("ref");assertEquals(1,cell.getRuntimeCell().node().incoming());
            assertEquals("Unlink only the bucket facet, never its canonical payload",10,scalar.toLong());
        }
    }
    @Test public void cowDuplicatedBucketsRetainOneEdgePerActualCellAddress() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var copy=f.local("copy");var scalar=f.local("scalar");var array=f.fresh(a);
            scalar.assign(LongMemory.valueOf(9));var cell=scalar.getReferenceForBinding();array.put("ref",cell);
            copy.assign(array.toImmutable());var writable=PhpOwnershipArrays.runtimeWritable(a.getRuntimeHolder());
            assertEquals(3,cell.getRuntimeCell().node().incoming());
            writable.refOfIndex("ref").assign(LongMemory.valueOf(10));
            assertEquals(10,copy.toValue(ArrayMemory.class).getByScalar("ref").toLong());
            writable.removeByScalar("ref");assertEquals(2,cell.getRuntimeCell().node().incoming());assertEquals(10,scalar.toLong());
            copy.toValue(ArrayMemory.class).removeByScalar("ref");assertEquals(1,cell.getRuntimeCell().node().incoming());assertEquals(10,scalar.toLong());
        }
    }
    @Test public void managedKeyViewCannotUnlinkAnOwnedPhysicalBucket() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var array=f.fresh(a);array.put("value",LongMemory.valueOf(9));
            try {array.keySet().remove("value");fail("Raw key view unlinked a managed bucket");}
            catch(UnsupportedOperationException expected) {assertEquals(9,array.getByScalar("value").toLong());}
            var backingField=ArrayMemory.class.getDeclaredField("map");backingField.setAccessible(true);
            var map=(php.runtime.memory.support.ArrayMemoryMap)backingField.get(array);
            assertThrows(IllegalStateException.class,()->map.remove("value"));
            assertThrows(IllegalStateException.class,map::clear);
            var keys=map.keySet().iterator();keys.next();assertThrows(IllegalStateException.class,keys::remove);
            assertEquals(1,array.size());assertEquals(9,array.getByScalar("value").toLong());
        }
    }

    @Test public void unknownNestedHostValueIsRejectedBeforeOriginOrProducerRegistration() throws Throwable {
        try(var f=new Fixture()) {
            var host=new ArrayMemory();host.add(new ArrayMemory());
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipArrays.runtimeFresh(f.frame,999,host));
            assertNull(host.getRuntimeOrigin());assertEquals(0,f.domain.graph().liveNodes());
        }
    }
    @Test public void originCannotBeMintedFromBorrowedView() throws Throwable {
        try(var f=new Fixture()) {
            var a=f.local("a");var array=f.fresh(a);var view=array.toImmutable();
            try {view.toValue(ArrayMemory.class).prepareRuntimeOrigin(f.authority);fail("Borrowed view became a producer");}
            catch(IllegalArgumentException expected) {assertEquals(1,array.getRuntimeOrigin().node().incoming());}
        }
    }
    @Test public void certifiedEntryFacetTransfersOnlyExactEvalWithoutAnotherEdge() throws Throwable {
        try(var f=new Fixture();var allocation=new PhpOwnershipArrays(f.domain.heap()).allocate(true)) {
            var entry=allocation.insertBucket("value",PhpOwnershipValues.bindValue(f.domain,LongMemory.valueOf(0),null));
            var child=PhpOwnershipArrays.runtimeFresh(f.frame,950,new ArrayMemory());var node=child.getRuntimeOrigin().node();
            var facet=entry.runtimeFacet();assertSame(facet,entry.runtimeFacet());assertEquals(1,node.incoming());
            var facade=f.authority.adopt(facet);assertSame(facade,f.authority.adopt(facet));assertFalse(facet.isCanonicalPayload());
            assertSame(entry,facet.carrier().getOwnershipEntry());assertNull(facet.carrier().getRuntimeHolder());
            var before=facade.facetSnapshot();var destination=facet.carrier();var binding=PhpOwnershipValues.bindValue(f.domain,child,node);
            try(var publication=facade.prepareFacetValue(before,binding,f.frame.evalHolder(950))){destination.publishOwnershipRaw(publication);publication.commit();}
            assertTrue(f.frame.evalHolder(950).raw().isUndefined());assertEquals(1,node.incoming());assertSame(child,entry.current().binding().rawValue());
            assertThrows(IllegalArgumentException.class,()->facet.prepareValue(before,binding,null));
            try(var promotion=facet.physicalSlot().preparePromotion(facet.current())){promotion.store();promotion.commit();}
            assertEquals(PhpOwnershipValues.Role.CELL,facet.current().binding().role());assertEquals(1,node.incoming());
            assertEquals(PhpRuntimeOwnership.Role.CELL,facade.role());assertSame(facet.current().binding().rawValue(),facade.raw());
            var payload=facet.physicalSlot().payloadPublisher().runtimeFacet();assertTrue(payload.isCanonicalPayload());
            var payloadFacade=f.authority.adopt(payload);assertSame(child,payloadFacade.raw());
            assertThrows(IllegalStateException.class,payloadFacade::reference);
            allocation.close();assertTrue(facet.isDisposed());assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void certifiedFacetRejectsCvAsEvalTransferSourceBeforeRawStore() throws Throwable {
        try(var f=new Fixture();var allocation=new PhpOwnershipArrays(f.domain.heap()).allocate(true)) {
            var entry=allocation.insertBucket("value",PhpOwnershipValues.bindValue(f.domain,LongMemory.valueOf(0),null));
            var carrier=f.local("child");var child=f.fresh(carrier);var node=child.getRuntimeOrigin().node();
            var binding=PhpOwnershipValues.bindValue(f.domain,child,node);var facet=entry.runtimeFacet();var before=facet.current();
            assertThrows(IllegalArgumentException.class,()->facet.prepareValue(before,binding,carrier.getRuntimeHolder()));
            assertSame(before,facet.current());assertEquals(0,facet.carrier().toLong());assertEquals(1,node.incoming());
        }
    }

    @Test public void diagnosticCannotAdmitPhysicalArrayChildrenBeforeProducerRetry() throws Throwable {
        try(var f=new Fixture()) {
            var child=new ArrayMemory();child.put("value",LongMemory.valueOf(9));
            var origin=child.prepareRuntimeOrigin(f.authority);var carrier=child.getByScalar("value");
            try(var publication=f.frame.prepareEval(953,child,PhpRuntimeOwnership.Role.VALUE)){publication.storeDirect();publication.commit();}
            try(var diagnostic=f.frame.pinDiagnostic(child)) {
                assertEquals(2,origin.node().incoming());assertNull(carrier.getRuntimeHolder());
                assertThrows(IllegalArgumentException.class,()->child.initializeRuntimeEntries(origin,diagnostic.holder()));
                assertNull(carrier.getRuntimeHolder());assertEquals(9,carrier.toLong());assertEquals(2,origin.node().incoming());
                child.initializeRuntimeEntries(origin,f.frame.evalHolder(953));
                assertNotNull(carrier.getRuntimeHolder());assertEquals(2,origin.node().incoming());
            }
            assertEquals(1,origin.node().incoming());
        }
    }
    @Test public void producerCannotBeExcludedAsDiagnosticOwner() throws Throwable {
        try(var f=new Fixture()) {
            var array=PhpOwnershipArrays.runtimeFresh(f.frame,954,new ArrayMemory());var origin=array.getRuntimeOrigin();
            assertThrows(IllegalArgumentException.class,()->origin.writableLayoutAfterDiagnostic(f.frame.evalHolder(954)));
            assertSame(array,origin.writableLayoutAfterDiagnostic());assertEquals(1,origin.node().incoming());
            try(var diagnostic=f.frame.pinDiagnostic(array)) {
                assertSame(array,origin.writableLayoutAfterDiagnostic(diagnostic.holder()));assertEquals(2,origin.node().incoming());
            }
            assertEquals(1,origin.node().incoming());
        }
    }
}
