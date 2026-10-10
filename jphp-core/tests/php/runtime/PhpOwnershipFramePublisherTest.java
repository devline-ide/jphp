package php.runtime;

import org.junit.Test;
import php.runtime.env.Context;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.ClassEntity;

import static org.junit.Assert.*;

/** Actual Memory/raw publication tests, not compiler activation or native PHP lifetime certification. */
public class PhpOwnershipFramePublisherTest {
    private static final class RawCv {Memory value;}

    private static ObjectMemory object() {
        return new ObjectMemory(new BaseObject(new ClassEntity((Context)null)) {});
    }

    private static PhpOwnershipValues.Binding scalar(PhpOwnershipDomain domain,Memory value) {
        return PhpOwnershipValues.bindValue(domain,value,null);
    }

    private static void publish(PhpOwnershipFramePublisher destination,PhpOwnershipValues.Binding binding,
                                RawCv actualCv) throws Throwable {
        Memory raw=binding.rawValue();
        try (var token=destination.prepare(destination.current(),binding)) {
            actualCv.value=raw; // The actual ASTORE/field-store; no accessors or callbacks in this interval.
            token.commit();
        }
    }

    @Test public void actualScalarArrayObjectAndCanonicalCellHaveExplicitRoles() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var publisher=domain.heap().framePublisher(frame.local("v"));
            var cv=new RawCv();publish(publisher,scalar(domain,Memory.NULL),cv);
            assertSame(Memory.NULL,cv.value);assertNotNull(publisher.current().binding());
            assertEquals(PhpOwnershipValues.Role.VALUE,publisher.current().binding().role());
            var array=new ArrayMemory();array.add(7);var storage=domain.storage(array.getStorageIdentity());
            publish(publisher,PhpOwnershipValues.bindValue(domain,array,storage),cv);
            assertSame(array,cv.value);assertSame(storage,publisher.current().binding().node());assertEquals(1,storage.incoming());
            var ref=(ReferenceMemory)ReferenceMemory.exportReference(new ReferenceMemory(array));
            var cell=domain.canonicalCell(ref);domain.graph().edge(cell).replace(storage);
            publish(publisher,PhpOwnershipValues.bindCell(domain,ref,cell),cv);
            assertSame(ref,cv.value);assertEquals(PhpOwnershipValues.Role.CELL,publisher.current().binding().role());
            assertEquals(1,cell.incoming());assertEquals(1,storage.incoming());
            var memory=object();var node=domain.object(memory.value,null);
            publish(publisher,PhpOwnershipValues.bindValue(domain,memory,node),cv);
            assertSame(memory,cv.value);assertEquals(PhpOwnership.State.DISPOSED,cell.state());
            assertEquals(PhpOwnership.State.DISPOSED,storage.state());assertEquals(1,node.incoming());
            assertEquals(4,publisher.current().revision());frame.close();
            assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
        participant.close();
    }

    @Test public void callbacksSeeActualCommittedRawAndSnapshotEvenWhenTheyThrow() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("release");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var publisher=domain.heap().framePublisher(frame.local("v"));
            var cv=new RawCv();var before=object();var failure=new Exception("destructor");
            var old=domain.object(before.value,new PhpOwnershipDomain.Lifecycle() {
                @Override public void destruct(PhpOwnership.Node node,Object context) throws Throwable {
                    assertEquals("release",context);assertSame(Memory.CONST_INT_1,cv.value);
                    assertSame(cv.value,publisher.current().binding().rawValue());
                    assertNull(publisher.current().binding().node());assertEquals(2,publisher.current().revision());
                    throw failure;
                }
            });
            publish(publisher,PhpOwnershipValues.bindValue(domain,before,old),cv);
            var next=scalar(domain,Memory.CONST_INT_1);var token=publisher.prepare(publisher.current(),next);
            cv.value=Memory.CONST_INT_1;
            try {token.commit();fail();} catch (Throwable error) {assertSame(failure,error);}
            assertTrue(token.isCommitted());token.close();assertEquals(2,publisher.current().revision());
            assertSame(cv.value,frame.local("v").rawValue());assertEquals(PhpOwnership.State.DISPOSED,old.state());frame.close();
        }
        participant.close();
    }

    @Test public void reentrantDestructorPublicationIsNotOverwrittenByOuterCommit() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var publisher=domain.heap().framePublisher(frame.local("v"));
            var cv=new RawCv();var before=object();
            var old=domain.object(before.value,new PhpOwnershipDomain.Lifecycle() {
                @Override public void destruct(PhpOwnership.Node node,Object context) throws Throwable {
                    assertSame(Memory.CONST_INT_1,cv.value);assertEquals(2,publisher.current().revision());
                    publish(publisher,scalar(domain,Memory.CONST_INT_2),cv);
                }
            });
            publish(publisher,PhpOwnershipValues.bindValue(domain,before,old),cv);
            publish(publisher,scalar(domain,Memory.CONST_INT_1),cv);
            assertSame(Memory.CONST_INT_2,cv.value);assertSame(cv.value,publisher.current().binding().rawValue());
            assertEquals(3,publisher.current().revision());frame.close();
        }
        participant.close();
    }

    @Test public void abortPreservesPublishedMemoryAndSnapshotAndReleasesCandidate() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var publisher=domain.heap().framePublisher(frame.local("v"));
            var cv=new RawCv();publish(publisher,scalar(domain,Memory.CONST_INT_1),cv);var expected=publisher.current();
            var array=new ArrayMemory();array.add(3);var storage=domain.storage(array.getStorageIdentity());
            var token=publisher.prepare(expected,PhpOwnershipValues.bindValue(domain,array,storage));
            token.close();assertFalse(token.isCommitted());assertSame(expected,publisher.current());
            assertSame(Memory.CONST_INT_1,cv.value);assertSame(cv.value,frame.local("v").rawValue());
            assertEquals(PhpOwnership.State.DISPOSED,storage.state());frame.close();
        }
        participant.close();
    }

    @Test public void staleAndForgedSnapshotsRejectBeforeActualStoreIncludingAba() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var publisher=domain.heap().framePublisher(frame.local("v"));
            var other=domain.heap().framePublisher(frame.local("other"));var cv=new RawCv();
            publish(publisher,scalar(domain,Memory.CONST_INT_1),cv);var first=publisher.current();
            publish(publisher,scalar(domain,Memory.CONST_INT_2),cv);publish(publisher,scalar(domain,Memory.CONST_INT_1),cv);
            assertThrows(IllegalArgumentException.class,()->publisher.prepare(first,scalar(domain,Memory.NULL)));
            assertThrows(IllegalArgumentException.class,()->publisher.prepare(other.current(),scalar(domain,Memory.NULL)));
            var current=publisher.current();var forged=new PhpOwnershipHeap.Snapshot(publisher,current.binding(),current.revision());
            assertThrows(IllegalArgumentException.class,()->publisher.prepare(forged,scalar(domain,Memory.NULL)));
            assertSame(current,publisher.current());assertSame(Memory.CONST_INT_1,cv.value);frame.close();
        }
        participant.close();
    }

    @Test public void heapAndSlotInternOneRevisionHistoryAndRejectUnmanagedImports() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var heap=domain.heap();assertSame(heap,domain.heap());
            assertThrows(IllegalStateException.class,()->new PhpOwnershipHeap(domain));
            var frame=new PhpOwnershipFrame(domain.graph());var slot=frame.local("v");var publisher=heap.framePublisher(slot);
            assertSame(publisher,heap.framePublisher(slot));
            assertThrows(IllegalStateException.class,()->new PhpOwnershipFramePublisher(heap,slot));
            var unmanaged=frame.local("unmanaged");unmanaged.commit(null,Memory.NULL);
            assertThrows(IllegalArgumentException.class,()->heap.framePublisher(unmanaged));
            slot.commit(null,Memory.CONST_INT_1);
            assertThrows(IllegalStateException.class,publisher::current);
            frame.close();assertThrows(IllegalStateException.class,()->heap.framePublisher(slot));
            assertThrows(IllegalStateException.class,publisher::current);
        }
        participant.close();
    }

    @Test public void foreignDomainValuesFramesAndTransfersCannotChangeRawDestination() throws Throwable {
        var first=new PhpOwnershipDomain();var second=new PhpOwnershipDomain();
        var pa=first.participant("first");var pb=second.participant("second");
        try (var ea=pa.enter();var eb=pb.enter()) {
            var a=new PhpOwnershipFrame(first.graph());var b=new PhpOwnershipFrame(second.graph());
            var left=first.heap().framePublisher(a.local("v"));var right=second.heap().framePublisher(b.local("v"));
            var cv=new RawCv();publish(left,scalar(first,Memory.CONST_INT_1),cv);
            var expected=left.current();var foreign=scalar(second,Memory.CONST_INT_2);
            assertThrows(IllegalArgumentException.class,()->left.prepare(expected,foreign));
            assertThrows(IllegalArgumentException.class,()->first.heap().framePublisher(b.local("v")));
            assertThrows(IllegalArgumentException.class,()->left.prepareTransferTo(expected,right,right.current(),Memory.CONST_INT_1));
            assertSame(expected,left.current());assertSame(Memory.CONST_INT_1,cv.value);assertNull(right.current().binding());
            a.close();b.close();
        }
        pb.close();pa.close();
    }

    @Test public void arrayViewTransferKeepsOneOwnerAndClearsSourceBeforeDestinationCleanup() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var caller=new PhpOwnershipFrame(domain.graph());var callee=new PhpOwnershipFrame(domain.graph());
            var source=domain.heap().framePublisher(callee.temporary("return"));
            var destination=domain.heap().framePublisher(caller.temporary("result"));
            var from=new RawCv();var to=new RawCv();var array=new ArrayMemory();array.add(9);
            var storage=domain.storage(array.getStorageIdentity());
            publish(source,PhpOwnershipValues.bindValue(domain,array,storage),from);
            var view=(ArrayMemory)array.toImmutable();var before=object();
            var old=domain.object(before.value,new PhpOwnershipDomain.Lifecycle() {
                @Override public void destruct(PhpOwnership.Node node,Object context) {
                    assertNull(from.value);assertSame(view,to.value);assertNull(source.current().binding());
                    assertSame(view,destination.current().binding().rawValue());assertEquals(1,storage.incoming());
                }
            });
            publish(destination,PhpOwnershipValues.bindValue(domain,before,old),to);
            try (var token=source.prepareTransferTo(source.current(),destination,destination.current(),view)) {
                to.value=view;from.value=null;token.commit();
            }
            assertEquals(1,storage.incoming());callee.close();assertEquals(1,storage.incoming());
            assertEquals(9,((ArrayMemory)to.value).valueOfIndex(0).toLong());caller.close();
            assertEquals(PhpOwnership.State.DISPOSED,storage.state());
        }
        participant.close();
    }

    @Test public void canonicalCellAndSelfViewTransfersPreserveIdentityWithoutExtraOwners() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var source=domain.heap().framePublisher(frame.local("a"));
            var destination=domain.heap().framePublisher(frame.argument("b"));
            var cell=(ReferenceMemory)ReferenceMemory.exportReference(new ReferenceMemory(Memory.CONST_INT_1));
            var node=domain.canonicalCell(cell);var from=new RawCv();var to=new RawCv();
            publish(source,PhpOwnershipValues.bindCell(domain,cell,node),from);
            var token=source.prepareTransferTo(source.current(),destination,destination.current(),cell);
            to.value=cell;from.value=null;token.commit();token.close();assertEquals(1,node.incoming());
            assertSame(cell,to.value);assertNull(source.current().binding());
            assertEquals(PhpOwnershipValues.Role.CELL,destination.current().binding().role());
            var previous=destination.current();var self=destination.prepareTransferTo(previous,destination,previous,cell);
            to.value=cell;self.commit();self.close();assertEquals(1,node.incoming());
            assertEquals(previous.revision()+1,destination.current().revision());
            assertThrows(IllegalStateException.class,()->source.prepareTransferTo(source.current(),destination,destination.current(),cell));
            frame.close();assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
        participant.close();
    }

    @Test public void realLocalEvalReceiverAndArgumentRootsUnwindPendingPreparation() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var memory=object();var node=domain.object(memory.value,null);
            var binding=PhpOwnershipValues.bindValue(domain,memory,node);
            var local=domain.heap().framePublisher(frame.local("v"));
            var eval=domain.heap().framePublisher(frame.temporary("v"));
            var receiver=domain.heap().framePublisher(frame.receiver("v"));
            var argument=domain.heap().framePublisher(frame.argument("v"));
            publish(local,binding,new RawCv());publish(eval,binding,new RawCv());
            publish(receiver,binding,new RawCv());publish(argument,binding,new RawCv());assertEquals(4,node.incoming());
            var next=new ArrayMemory();var storage=domain.storage(next.getStorageIdentity());
            var token=local.prepare(local.current(),PhpOwnershipValues.bindValue(domain,next,storage));
            var primary=new Exception("body");
            try {frame.unwind(primary);fail();} catch (Throwable error) {assertSame(primary,error);}
            assertFalse(token.isCommitted());token.close();assertThrows(IllegalStateException.class,token::commit);
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(PhpOwnership.State.DISPOSED,storage.state());
            assertTrue(frame.isClosed());assertEquals(0,domain.graph().liveNodes());
        }
        participant.close();
    }
}
