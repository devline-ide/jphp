package php.runtime;

import org.junit.Test;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Graph infrastructure tests, deliberately independent of incomplete compiler/storage integration. */
public class PhpOwnershipTest {
    private static PhpOwnership.Lifecycle events(List<String> events,String name) {
        return new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) {events.add("destruct:"+name);}
            @Override public void destroy(PhpOwnership.Node node) {events.add("destroy:"+name);}
        };
    }
    private static PhpOwnership.Slot root(PhpOwnership graph,PhpOwnership.Node node) throws Throwable {
        var root=graph.root();root.replace(node);return root;
    }

    @Test public void identityAndDisposedHandles() throws Throwable {
        var graph=new PhpOwnership();Object first=new String("same"),second=new String("same");
        var a=graph.object(first,null);var b=graph.object(second,null);
        assertSame(a,graph.object(first,null));assertNotSame(a,b);
        try {graph.storage(first);fail();} catch (IllegalArgumentException expected) {}
        var owner=root(graph,a);owner.close();
        assertEquals(PhpOwnership.State.DISPOSED,a.state());assertNull(graph.find(first));
        try {graph.object(first,null);fail();} catch (IllegalStateException expected) {}
        try {graph.root().replace(a);fail();} catch (IllegalStateException expected) {}
        root(graph,b).close();assertEquals(0,graph.liveNodes());
    }

    @Test public void sharedBackingStorageKeepsNestedObject() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();
        var object=graph.object(new Object(),events(log,"object"));
        var storage=graph.storage(new Object());var cell=graph.cell(new ReferenceMemory());
        graph.edge(storage).replace(cell);graph.edge(cell).replace(object);
        var first=root(graph,storage);var second=root(graph,storage);
        assertEquals(2,storage.incoming());assertEquals(1,object.incoming());
        first.close();assertTrue(log.isEmpty());assertEquals(1,storage.incoming());
        second.close();assertEquals(Arrays.asList("destruct:object","destroy:object"),log);
        assertEquals(0,graph.liveNodes());
    }

    @Test public void aliasesOwnCanonicalCellOnce() throws Throwable {
        var graph=new PhpOwnership();var value=new ReferenceMemory(LongMemory.valueOf(1));
        var cell=graph.cell(value);assertSame(cell,graph.cell(new ReferenceMemory(value)));
        var log=new ArrayList<String>();var object=graph.object(new Object(),events(log,"object"));
        graph.edge(cell).replace(object);var a=root(graph,cell);var b=root(graph,cell);
        assertEquals(2,cell.incoming());assertEquals(1,object.incoming());
        a.close();assertTrue(log.isEmpty());b.close();assertEquals(2,log.size());
    }

    @Test public void transferCommitsBothSlotsBeforeDestruction() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();
        var moving=graph.object(new Object(),events(log,"moving"));var source=root(graph,moving);
        var destination=graph.root();
        var replaced=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) {
                assertNull(source.target());assertSame(moving,destination.target());
                assertEquals(1,moving.incoming());log.add("replaced");
            }
        });
        destination.replace(replaced);source.transferTo(destination);
        assertEquals(Collections.singletonList("replaced"),log);assertFalse(moving.destructorStarted());
        destination.close();assertEquals(Arrays.asList("replaced","destruct:moving","destroy:moving"),log);
        source.close();
    }

    @Test public void failedValidationPreservesOwnership() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();
        var old=graph.object(new Object(),events(log,"old"));var next=graph.object(new Object(),events(log,"next"));
        var slot=root(graph,old);var temporary=root(graph,next);var failure=new Exception("invalid write");
        try {slot.replaceChecked(next,()->{throw failure;});fail();} catch (Throwable error) {assertSame(failure,error);}
        assertSame(old,slot.target());assertEquals(1,old.incoming());assertEquals(1,next.incoming());assertTrue(log.isEmpty());
        slot.close();temporary.close();
    }

    @Test public void destructionWaitsForStorageCommit() throws Throwable {
        var graph=new PhpOwnership();var committed=new AtomicReference<Object>();var log=new ArrayList<String>();
        var next=graph.object(new Object(),null);
        var old=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) {assertSame(next.identity(),committed.get());log.add("destruct");}
        });
        var slot=root(graph,old);committed.set(old.identity());
        graph.deferDestruction(()->{slot.replace(next);assertTrue(log.isEmpty());committed.set(next.identity());});
        assertEquals(Collections.singletonList("destruct"),log);slot.close();
    }

    @Test public void destructorResurrectionRunsOnce() throws Throwable {
        var graph=new PhpOwnership();var saved=graph.root();var log=new ArrayList<String>();
        var object=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("destruct");saved.replace(node);}
            @Override public void destroy(PhpOwnership.Node node) {log.add("destroy");}
        });
        root(graph,object).close();assertEquals(PhpOwnership.State.LIVE,object.state());
        assertEquals(1,object.incoming());assertTrue(object.destructorStarted());assertEquals(Collections.singletonList("destruct"),log);
        saved.close();assertEquals(Arrays.asList("destruct","destroy"),log);
    }

    @Test public void nestedReleaseRunsDestructorSynchronously() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();var edge=new AtomicReference<PhpOwnership.Slot>();
        var parent=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {
                log.add("parent:start");edge.get().close();log.add("parent:end");
            }
            @Override public void destroy(PhpOwnership.Node node) {log.add("parent:destroy");}
        });
        var child=graph.object(new Object(),events(log,"child"));edge.set(graph.edge(parent));edge.get().replace(child);
        root(graph,parent).close();
        assertEquals(Arrays.asList("parent:start","destruct:child","destroy:child","parent:end","parent:destroy"),log);
    }

    @Test public void exceptionsDoNotSkipCleanupOrDependentDestructors() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();
        Exception first=new Exception("parent destructor"),second=new Exception("parent cleanup"),third=new Exception("child destructor");
        var parent=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("parent:destruct");throw first;}
            @Override public void destroy(PhpOwnership.Node node) throws Throwable {log.add("parent:destroy");throw second;}
        });
        var child=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("child:destruct");throw third;}
            @Override public void destroy(PhpOwnership.Node node) {log.add("child:destroy");}
        });
        graph.edge(parent).replace(child);
        try {root(graph,parent).close();fail();} catch (Throwable failure) {
            assertSame(first,failure);assertArrayEquals(new Throwable[]{second,third},failure.getSuppressed());
        }
        assertEquals(Arrays.asList("parent:destruct","parent:destroy","child:destruct","child:destroy"),log);
        assertEquals(0,graph.liveNodes());assertEquals(0,parent.incoming());assertEquals(0,child.incoming());
    }

    @Test public void collectorReclaimsObjectCellStorageCycle() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();var object=graph.object(new Object(),events(log,"object"));
        var storage=graph.storage(new Object());var cell=graph.cell(new ReferenceMemory());
        graph.edge(object).replace(storage);graph.edge(storage).replace(cell);graph.edge(cell).replace(object);
        var owner=root(graph,object);owner.close();assertTrue(log.isEmpty());assertEquals(3,graph.collectCycles());
        assertEquals(Arrays.asList("destruct:object","destroy:object"),log);assertEquals(0,graph.liveNodes());
        assertEquals(0,graph.collectCycles());
    }

    @Test public void cyclicResurrectionPreservesGraphAfterAllDestructors() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();var saved=graph.root();
        var a=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("destruct:a");saved.replace(node);}
            @Override public void destroy(PhpOwnership.Node node) {log.add("destroy:a");}
        });
        var b=graph.object(new Object(),events(log,"b"));graph.edge(a).replace(b);graph.edge(b).replace(a);
        root(graph,a).close();assertEquals(0,graph.collectCycles());
        assertEquals(Arrays.asList("destruct:a","destruct:b"),log);assertEquals(2,graph.liveNodes());
        saved.close();assertEquals(2,graph.collectCycles());
        assertEquals(Arrays.asList("destruct:a","destruct:b","destroy:a","destroy:b"),log);
    }

    @Test public void cycleDestructorExceptionStillCollectsOtherCandidate() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();var failure=new Exception("cycle destructor");
        var a=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {log.add("destruct:a");throw failure;}
            @Override public void destroy(PhpOwnership.Node node) {log.add("destroy:a");}
        });
        var b=graph.object(new Object(),events(log,"b"));graph.edge(a).replace(b);graph.edge(b).replace(a);root(graph,a).close();
        try {graph.collectCycles();fail();} catch (Throwable error) {assertSame(failure,error);}
        assertEquals(Arrays.asList("destruct:a","destruct:b","destroy:a","destroy:b"),log);
        assertEquals(0,graph.liveNodes());assertEquals(0,graph.collectCycles());
    }

    @Test public void javaPinIsAnExplicitRoot() throws Throwable {
        var graph=new PhpOwnership();var log=new ArrayList<String>();var node=graph.object(new Object(),events(log,"object"));
        var variable=root(graph,node);var pin=graph.pin(node);variable.close();
        assertSame(node,pin.target());assertEquals(1,node.incoming());assertEquals(0,graph.collectCycles());assertTrue(log.isEmpty());
        pin.close();pin.close();assertEquals(Arrays.asList("destruct:object","destroy:object"),log);
    }

    @Test public void crossThreadPinReleaseRequiresInterpreterScheduling() throws Throwable {
        var graph=new PhpOwnership();var node=graph.object(new Object(),null);var pin=graph.pin(node);
        var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{try {pin.close();} catch (Throwable error) {failure.set(error);}});
        thread.start();thread.join();assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(1,node.incoming());assertSame(node,pin.target());pin.close();assertEquals(0,graph.liveNodes());
    }

    @Test public void foreignGraphMutationDoesNotChangeSlots() throws Throwable {
        var a=new PhpOwnership();var b=new PhpOwnership();var value=a.object(new Object(),null);
        var source=root(a,value);var destination=b.root();
        try {destination.replace(value);fail();} catch (IllegalArgumentException expected) {}
        try {source.transferTo(destination);fail();} catch (IllegalArgumentException expected) {}
        assertSame(value,source.target());assertNull(destination.target());assertEquals(1,value.incoming());source.close();destination.close();
    }

    @Test public void deferredWorkFailureStillDrainsCommittedReleases() throws Throwable {
        var graph=new PhpOwnership();Exception workFailure=new Exception("work"),destroyFailure=new Exception("destroy");
        var object=graph.object(new Object(),new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node node) throws Throwable {throw destroyFailure;}
        });
        var slot=root(graph,object);
        try {graph.deferDestruction(()->{slot.close();throw workFailure;});fail();} catch (Throwable failure) {
            assertSame(workFailure,failure);assertArrayEquals(new Throwable[]{destroyFailure},failure.getSuppressed());
        }
        assertEquals(0,graph.liveNodes());
    }
}
