package php.runtime;

import org.junit.Test;
import php.runtime.memory.ReferenceMemory;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import static org.junit.Assert.*;

/** Publication/execution contracts only; these tests do not activate PHP storage or destructor adapters. */
public class PhpOwnershipPublicationTest {
    private interface Work {void run() throws Throwable;}
    private void illegal(Work work) throws Throwable {
        try {work.run();fail("Expected an execution/publication barrier");} catch (IllegalStateException expected) {}
    }
    private PhpOwnership.Node object(PhpOwnership graph,PhpOwnership.Work destruction) {
        return graph.object(new Object(),new PhpOwnership.Lifecycle() {
            public void destruct(PhpOwnership.Node node) throws Throwable {destruction.run();}
        });
    }
    private static final class Carrier extends ReferenceMemory {
        int getters;
        @Override public Memory getValue() {getters++;throw new AssertionError("virtual value getter");}
        @Override public ReferenceMemory getReference() {getters++;throw new AssertionError("virtual reference getter");}
    }
    @Test public void canonicalIdentityNeverEvaluatesMemoryCarriers() throws Throwable {
        var graph=new PhpOwnership();var carrier=new Carrier();
        var explicit=graph.canonicalCell(carrier);assertSame(explicit,graph.canonicalCell(carrier));assertEquals(0,carrier.getters);
        try {graph.cell(carrier);fail();} catch (IllegalArgumentException expected) {}
        try {graph.cell(new ReferenceMemory(carrier));fail();} catch (IllegalArgumentException expected) {}
        assertEquals(0,carrier.getters);
        var plain=new ReferenceMemory();assertSame(graph.cell(plain),graph.cell(new ReferenceMemory(plain)));
        var cycle=new ReferenceMemory();cycle.setValue(cycle);
        try {graph.cell(cycle);fail();} catch (IllegalArgumentException expected) {}
    }
    @Test public void callbackPublishesRealStorageBeforeDestruction() throws Throwable {
        var graph=new PhpOwnership();var slot=graph.root();var actual=new AtomicReference<Object>();
        var next=graph.storage(new Object());var old=object(graph,()->{
            assertSame(next.identity(),actual.get());assertSame(next,slot.target());
            graph.root().close(); // Cleanup executes after the publication barrier was removed.
        });
        actual.set(old.identity());slot.replace(old);slot.replacePublished(next,()->actual.set(next.identity()));
        assertEquals(1,next.incoming());assertEquals(PhpOwnership.State.DISPOSED,old.state());slot.close();
    }
    @Test public void abortReleasesOnlyCandidateAndAllowsCleanupReentrancy() throws Throwable {
        var graph=new PhpOwnership();var slot=graph.root();var actual=new Object[]{"old"};var calls=new AtomicInteger();
        var old=graph.storage(new Object());slot.replace(old);
        var candidate=object(graph,()->{calls.incrementAndGet();assertEquals("old",actual[0]);assertSame(old,slot.target());graph.root().close();});
        var pending=slot.preparePublication(candidate);assertEquals(1,candidate.incoming());
        pending.close();pending.close();assertFalse(pending.isCommitted());assertTrue(pending.isClosed());
        assertSame(old,slot.target());assertEquals(1,old.incoming());assertEquals(1,calls.get());illegal(pending::commit);slot.close();
    }
    @Test public void rawSectionRejectsMutationNestedPrepareAndHandoff() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("value");
        var old=graph.storage(new Object());var next=graph.storage(new Object());slot.commit(old,"old");
        var token=slot.prepareCommit(next,"next");
        illegal(()->graph.root());illegal(()->slot.commit(null,"bad"));illegal(()->slot.prepareCommit(null,"bad"));
        illegal(graph::handoff);illegal(()->graph.deferDestruction(()->fail("work must not run")));
        illegal(()->new PhpOwnershipFrame(graph));token.close();frame.close();
    }
    @Test public void directFrameTokenCommitsMirrorAndEdgesBeforeCallbacks() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("cv");var actual=new Object[]{"old"};
        var next=graph.storage(new Object());var old=object(graph,()->{
            assertEquals("published",actual[0]);assertEquals("published",slot.rawValue());assertSame(next,slot.node());
            frame.temporary("cleanup").commit(null,"allowed");
        });
        slot.commit(old,"old");var token=slot.prepareCommit(next,"published");actual[0]="published";token.commit();
        assertTrue(token.isCommitted());assertTrue(token.isClosed());assertEquals(1,next.incoming());illegal(token::commit);token.close();frame.close();
    }
    @Test public void transferUsesActualPublishedViewAndConsumesOneSourceOwner() throws Throwable {
        var graph=new PhpOwnership();var caller=new PhpOwnershipFrame(graph);var callee=new PhpOwnershipFrame(graph);
        var source=callee.temporary("result");var destination=caller.local("cv");var actual=new Object[]{"old"};
        var value=graph.storage(new Object());Object sourceView=new Object(),publishedView=new Object();source.commit(value,sourceView);
        var old=object(graph,()->{
            assertSame(publishedView,actual[0]);assertSame(publishedView,destination.rawValue());assertNull(source.rawValue());assertNull(source.node());
            assertEquals(1,value.incoming());
        });
        destination.commit(old,"old");var token=source.prepareTransferTo(destination,publishedView);actual[0]=publishedView;token.commit();
        assertEquals(1,value.incoming());callee.close();assertEquals(PhpOwnership.State.LIVE,value.state());caller.close();
        assertEquals(PhpOwnership.State.DISPOSED,value.state());
    }
    @Test public void scalarAndSameSlotPublicationStillUpdateRawMirror() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("value");var actual=new Object[]{"old"};
        var old=object(graph,()->{assertEquals(7,actual[0]);assertEquals(7,slot.rawValue());assertNull(slot.node());});slot.commit(old,"old");
        var token=slot.prepareCommit(null,7);actual[0]=7;token.commit();
        var storage=graph.storage(new Object());slot.commit(storage,"view1");
        token=slot.prepareTransferTo(slot,"view2");actual[0]="view2";token.commit();
        assertEquals("view2",slot.rawValue());assertSame(storage,slot.node());assertEquals(1,storage.incoming());frame.close();
    }
    @Test public void validationAndPreStoreCallbackFailurePreserveActualOldStorage() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("value");var actual=new Object[]{"old"};
        var old=graph.storage(new Object());var next=graph.storage(new Object());slot.commit(old,"old");var candidate=graph.root();candidate.replace(next);
        var validation=new Exception("validation");
        try {slot.commitChecked(next,"next",()->{throw validation;},()->actual[0]="next");fail();} catch (Throwable error) {assertSame(validation,error);}
        assertEquals("old",actual[0]);assertSame(old,slot.node());assertEquals(1,next.incoming());
        var publication=new Exception("before store");
        try {slot.commit(next,"next",()->{throw publication;});fail();} catch (Throwable error) {assertSame(publication,error);}
        assertEquals("old",actual[0]);assertEquals("old",slot.rawValue());assertSame(old,slot.node());assertEquals(1,next.incoming());
        candidate.close();frame.close();
    }
    @Test public void unwindAbortsPendingPublicationBeforeClosingOrdinaryRoots() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("value");var order=new ArrayList<String>();
        var oldFailure=new Exception("old");var candidateFailure=new Exception("candidate");
        var old=object(graph,()->{order.add("old");throw oldFailure;});var candidate=object(graph,()->{order.add("candidate");throw candidateFailure;});
        slot.commit(old,"old");var token=slot.prepareCommit(candidate,"not stored");var primary=new Exception("unwind");
        try {frame.unwind(primary);fail();} catch (Throwable error) {assertSame(primary,error);assertArrayEquals(new Throwable[]{candidateFailure,oldFailure},error.getSuppressed());}
        assertEquals(Arrays.asList("candidate","old"),order);assertTrue(frame.isClosed());assertTrue(token.isClosed());assertFalse(token.isCommitted());
        assertEquals(PhpOwnership.State.DISPOSED,candidate.state());assertEquals(PhpOwnership.State.DISPOSED,old.state());
    }
    private static final class PhpFailure extends Exception {
        Throwable previous;
        PhpFailure(String message) {super(message);}
    }
    private PhpOwnership phpPolicy() {
        return new PhpOwnership((pending,cleanup)->{((PhpFailure)cleanup).previous=pending;return cleanup;});
    }
    @Test public void explicitPhpCompositionPromotesCleanupWithoutInferringSuppression() throws Throwable {
        var graph=phpPolicy();var slot=graph.root();var pending=new PhpFailure("pending");var cleanup=new PhpFailure("cleanup");
        slot.replace(object(graph,()->{throw cleanup;}));
        try {graph.deferDestruction(()->{slot.close();throw pending;});fail();} catch (Throwable error) {assertSame(cleanup,error);assertSame(pending,cleanup.previous);assertEquals(0,error.getSuppressed().length);}
        var frame=new PhpOwnershipFrame(graph);var frameCleanup=new PhpFailure("frame-cleanup");frame.local("value").commit(object(graph,()->{throw frameCleanup;}),"value");
        try {frame.unwind(pending);fail();} catch (Throwable error) {assertSame(frameCleanup,error);assertSame(pending,frameCleanup.previous);assertEquals(0,error.getSuppressed().length);}
    }
    @Test public void failedPinAcquisitionUsesConfiguredCleanupComposition() throws Throwable {
        var graph=phpPolicy();var cleanup=new PhpFailure("pin-cleanup");var pending=new PhpFailure("pending-cleanup");
        var pinned=object(graph,()->{throw cleanup;});var first=graph.root();var second=graph.root();
        first.replace(object(graph,()->graph.pin(pinned)));second.replace(object(graph,()->{throw pending;}));
        try {graph.deferDestruction(()->{first.close();second.close();});fail();}
        catch (Throwable error) {assertSame(cleanup,error);assertSame(pending,cleanup.previous);}
        assertEquals(0,graph.liveNodes());
    }
    @Test public void faultyCompositionPreservesBothFailuresAndAdapterError() throws Throwable {
        var adapter=new IllegalStateException("adapter");var graph=new PhpOwnership((pending,cleanup)->{throw adapter;});
        var pending=new Exception("pending");var cleanup=new Exception("cleanup");assertSame(pending,graph.composeFailure(pending,cleanup));
        assertArrayEquals(new Throwable[]{cleanup,adapter},pending.getSuppressed());
        var nullPolicy=new PhpOwnership((a,b)->null);var first=new Exception("first");var second=new Exception("second");
        assertSame(first,nullPolicy.composeFailure(first,second));assertSame(second,first.getSuppressed()[0]);assertTrue(first.getSuppressed()[1] instanceof NullPointerException);
    }
    @Test public void publicationAbortPreservesNonExceptionThrowableIdentity() throws Throwable {
        var graph=new PhpOwnership();var root=graph.root();var failure=new Throwable("custom throwable");var candidate=object(graph,()->{throw failure;});
        var token=root.preparePublication(candidate);
        try {token.close();fail();} catch (Throwable error) {assertSame(failure,error);}
        assertTrue(token.isClosed());assertNull(root.target());root.close();
    }
    @Test public void handoffMovesExistingFrameDomainAndRootsWithoutClosingThem() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("held");var value=graph.storage(new Object());slot.commit(value,"main");
        var ticket=graph.handoff();illegal(()->slot.commit(null,"old-owner"));
        var returned=new AtomicReference<PhpOwnership.Handoff>();var failure=new AtomicReference<Throwable>();
        var worker=new Thread(()->{try {ticket.claim();slot.commit(value,"worker");returned.set(graph.handoff());} catch (Throwable error) {failure.set(error);}},"ownership-domain-worker");
        worker.setDaemon(true);worker.start();worker.join(5000);assertFalse(worker.isAlive());assertNull(failure.get());assertNotNull(returned.get());
        illegal(ticket::claim);returned.get().claim();assertEquals("worker",slot.rawValue());assertEquals(1,value.incoming());frame.close();
    }
    @Test public void ticketHasExactlyOneWinnerDuringConcurrentClaim() throws Throwable {
        var graph=new PhpOwnership();var ticket=graph.handoff();var start=new CountDownLatch(1);var wins=new AtomicInteger();var losses=new AtomicInteger();
        var returned=new AtomicReference<PhpOwnership.Handoff>();var failed=new AtomicReference<Throwable>();
        Runnable claim=()->{try {assertTrue(start.await(5,TimeUnit.SECONDS));ticket.claim();wins.incrementAndGet();returned.set(graph.handoff());}
            catch (IllegalStateException expected) {losses.incrementAndGet();} catch (Throwable error) {failed.set(error);}};
        var a=new Thread(claim,"ownership-claim-a");var b=new Thread(claim,"ownership-claim-b");a.setDaemon(true);b.setDaemon(true);a.start();b.start();start.countDown();a.join(5000);b.join(5000);
        assertFalse(a.isAlive());assertFalse(b.isAlive());assertNull(failed.get());assertEquals(1,wins.get());assertEquals(1,losses.get());returned.get().claim();assertEquals(0,graph.liveNodes());
    }
    @Test public void cleanupDeferredWorkAndCompositionCannotHandoffExecution() throws Throwable {
        var graph=new PhpOwnership();graph.deferDestruction(()->illegal(graph::handoff));
        var slot=graph.root();slot.replace(object(graph,()->illegal(graph::handoff)));slot.close();
        var holder=new PhpOwnership[1];holder[0]=new PhpOwnership((pending,cleanup)->{
            try {illegal(holder[0]::handoff);} catch (Throwable error) {throw new AssertionError(error);}pending.addSuppressed(cleanup);return pending;
        });
        holder[0].composeFailure(new Exception("pending"),new Exception("cleanup"));
    }
    @Test public void foreignThreadCannotConsumePublicationOrPartiallyCloseFrame() throws Throwable {
        var graph=new PhpOwnership();var frame=new PhpOwnershipFrame(graph);var slot=frame.local("value");var value=graph.storage(new Object());
        var token=slot.prepareCommit(value,"new");var failures=new AtomicInteger();var unexpected=new AtomicReference<Throwable>();
        var worker=new Thread(()->{try {illegal(token::commit);failures.incrementAndGet();illegal(token::close);failures.incrementAndGet();illegal(frame::close);failures.incrementAndGet();}
            catch (Throwable error) {unexpected.set(error);}},"ownership-foreign-publication");worker.setDaemon(true);worker.start();worker.join(5000);
        assertFalse(worker.isAlive());assertNull(unexpected.get());assertEquals(3,failures.get());assertFalse(frame.isClosed());assertFalse(token.isClosed());
        token.commit();assertEquals("new",slot.rawValue());frame.close();
    }
}
