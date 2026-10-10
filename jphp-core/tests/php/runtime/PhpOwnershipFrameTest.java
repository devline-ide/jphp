package php.runtime;

import org.junit.Test;
import php.runtime.memory.ReferenceMemory;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Dormant frame adapter tests; these do not claim compiler or PHP lifecycle integration. */
public class PhpOwnershipFrameTest {
    private static PhpOwnership.Node object(PhpOwnership graph,List<String> events,String name) {
        return graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) {events.add(name);}
        });
    }

    @Test public void namedRolesAreStableIndependentRoots() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var log=new ArrayList<String>();
        var node=object(graph,log,"destroy");
        var local=frame.local("value");var temporary=frame.temporary("value");
        var receiver=frame.receiver("this");var argument=frame.argument("arg0");
        assertSame(local,frame.local("value"));assertNotSame(local,temporary);
        assertEquals(PhpOwnershipFrame.Role.RECEIVER,receiver.role());assertEquals("arg0",argument.name());
        local.commit(node,"local");temporary.commit(node,"temp");receiver.commit(node,"receiver");argument.commit(node,"argument");
        assertEquals(4,node.incoming());temporary.commit(null,42);assertEquals(42,temporary.rawValue());
        assertEquals(3,node.incoming());assertTrue(log.isEmpty());frame.close();frame.close();
        assertEquals(Collections.singletonList("destroy"),log);assertEquals(0,graph.liveNodes());
        assertTrue(local.isClosed());assertNull(local.rawValue());
    }

    @Test public void returnTransferCommitsCallerBeforeCalleeClose() throws Throwable {
        var graph=new PhpOwnership();var caller=new PhpOwnershipFrame(graph);var callee=new PhpOwnershipFrame(graph);
        var log=new ArrayList<String>();var result=callee.temporary("return");var destination=caller.temporary("call-result");
        var moving=object(graph,log,"moving");
        var old=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) {
                assertNull(result.rawValue());assertNull(result.node());assertSame(moving,destination.node());
                assertEquals("returned",destination.rawValue());assertEquals(1,moving.incoming());log.add("old");
            }
        });
        destination.commit(old,"old-value");result.commit(moving,"returned");result.transferTo(destination);callee.close();
        assertEquals(Collections.singletonList("old"),log);assertFalse(moving.destructorStarted());
        assertEquals("returned",destination.rawValue());caller.close();assertEquals(Arrays.asList("old","moving"),log);
    }

    @Test public void failedValidationPreservesOldRawValueAndOwnership() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var log=new ArrayList<String>();
        var old=object(graph,log,"old");var next=object(graph,log,"next");var slot=frame.local("value");
        slot.commit(old,"old-value");frame.temporary("candidate").commit(next,"next-value");var failure=new Exception("invalid");
        try {slot.commitChecked(next,"next-value",()->{throw failure;});fail();} catch (Throwable error) {assertSame(failure,error);}
        assertSame(old,slot.node());assertEquals("old-value",slot.rawValue());assertEquals(1,old.incoming());
        assertEquals(1,next.incoming());assertTrue(log.isEmpty());frame.close();
    }

    @Test public void validatorSideEffectsAreNotDeferredByTheWrite() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var log=new ArrayList<String>();
        var slot=frame.local("value");var side=frame.temporary("side-effect");var old=object(graph,log,"old");
        var next=object(graph,log,"next");
        var sideNode=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) {
                assertSame(old,slot.node());assertEquals("old-value",slot.rawValue());log.add("side");
            }
        });
        slot.commit(old,"old-value");side.commit(sideNode,"side-value");
        slot.commitChecked(next,"next-value",()->{side.close();assertEquals(Collections.singletonList("side"),log);});
        assertEquals(Arrays.asList("side","old"),log);assertSame(next,slot.node());frame.close();
    }

    @Test public void callbacksSeeCommittedValueEvenWhenDestructionThrows() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("value");
        var next=graph.object(new Object(),null);var failure=new Exception("old destructor");
        var old=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {
                assertSame(next,slot.node());assertEquals("new-value",slot.rawValue());throw failure;
            }
        });
        slot.commit(old,"old-value");
        try {slot.commit(next,"new-value");fail();} catch (Throwable error) {assertSame(failure,error);}
        assertSame(next,slot.node());assertEquals("new-value",slot.rawValue());assertEquals(1,next.incoming());
        assertEquals(PhpOwnership.State.DISPOSED,old.state());frame.close();assertEquals(0,graph.liveNodes());
    }

    @Test public void unwindPreservesPrimaryAndClosesEveryRoot() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var log=new ArrayList<String>();
        Exception primary=new Exception("body"),first=new Exception("a"),second=new Exception("b");
        var a=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("a");throw first;}
        });
        var b=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("b");throw second;}
        });
        var local=frame.local("a");var argument=frame.argument("b");local.commit(a,"a");argument.commit(b,"b");
        try {frame.unwind(primary);fail();} catch (Throwable error) {
            assertSame(primary,error);assertArrayEquals(new Throwable[]{second,first},error.getSuppressed());
        }
        assertEquals(Arrays.asList("b","a"),log);assertTrue(frame.isClosed());assertEquals(0,graph.liveNodes());
        assertNull(local.rawValue());assertNull(argument.rawValue());frame.close();
        try {frame.local("new");fail();} catch (IllegalStateException expected) {}
    }

    @Test public void referenceAliasesRetainOneCanonicalCellReferent() throws Throwable {
        var graph=new PhpOwnership();var caller=new PhpOwnershipFrame(graph);var callee=new PhpOwnershipFrame(graph);
        var value=new ReferenceMemory();var alias=new ReferenceMemory(value);var cell=graph.cell(value);
        assertSame(cell,graph.cell(alias));var log=new ArrayList<String>();var referent=object(graph,log,"referent");
        graph.edge(cell).replace(referent);caller.local("a").commit(cell,value);callee.argument("by-ref").commit(graph.cell(alias),alias);
        assertEquals(2,cell.incoming());assertEquals(1,referent.incoming());callee.close();assertTrue(log.isEmpty());
        assertEquals(1,cell.incoming());caller.close();assertEquals(Collections.singletonList("referent"),log);
        assertEquals(0,graph.liveNodes());
    }

    @Test public void foreignGraphTransferAndClosedDestinationPreserveSource() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var foreign=new PhpOwnershipFrame(new PhpOwnership());
        var node=graph.object(new Object(),null);var source=frame.temporary("return");source.commit(node,"value");
        var destination=foreign.local("result");
        try {source.transferTo(destination);fail();} catch (IllegalArgumentException expected) {}
        assertSame(node,source.node());assertEquals("value",source.rawValue());assertNull(destination.node());
        var sameGraph=new PhpOwnershipFrame(graph);var closed=sameGraph.local("result");closed.close();
        try {source.transferTo(closed);fail();} catch (IllegalStateException expected) {}
        assertSame(node,source.node());assertEquals(1,node.incoming());frame.close();foreign.close();sameGraph.close();
    }

    @Test public void targetDisposedByValidationCannotBeCommitted() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var old=graph.object(new Object(),null);
        var next=graph.object(new Object(),null);var slot=frame.local("value");var candidate=frame.temporary("candidate");
        slot.commit(old,"old");candidate.commit(next,"next");
        try {slot.commitChecked(next,"next",candidate::close);fail();} catch (IllegalStateException expected) {}
        assertSame(old,slot.node());assertEquals("old",slot.rawValue());assertEquals(1,old.incoming());
        assertEquals(PhpOwnership.State.DISPOSED,next.state());frame.close();
    }

    @Test public void foreignThreadCloseDoesNotPartiallyCloseFrame() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var node=graph.object(new Object(),null);
        var slot=frame.receiver("this");slot.commit(node,"receiver");var failure=new AtomicReference<Throwable>();
        var worker=new Thread(()->{try {frame.close();} catch (Throwable error) {failure.set(error);}});
        worker.start();worker.join();assertTrue(failure.get() instanceof IllegalStateException);
        assertFalse(frame.isClosed());assertFalse(slot.isClosed());assertSame(node,slot.node());assertEquals(1,node.incoming());
        frame.close();assertEquals(0,graph.liveNodes());
    }
}
