package php.runtime;

import org.junit.Test;
import php.runtime.memory.ReferenceMemory;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real graph roots/domain boundaries only; PHP compiler/runtime activation remains a separate contract. */
public class PhpOwnershipDomainTest {
    private interface Work {void run() throws Throwable;}
    private void rejected(Class<? extends Throwable> type, Work work) throws Throwable {
        try {work.run();fail("Expected " + type.getSimpleName());}
        catch (Throwable failure) {if (!type.isInstance(failure)) throw failure;}
    }
    private PhpOwnershipDomain.Lifecycle log(List<String> events, String name) {
        return new PhpOwnershipDomain.Lifecycle() {
            public void destruct(PhpOwnership.Node node, Object context) {events.add(name + ":destruct:" + context);}
            public void destroy(PhpOwnership.Node node, Object context) {events.add(name + ":destroy:" + context);}
        };
    }
    @Test public void registrationNeedsExplicitExecutionAndStableIdentity() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("env");var identity = new Object();
        rejected(IllegalStateException.class, () -> domain.object(identity, null));
        try (var ignored = participant.enter()) {
            var node = domain.object(identity, null);assertSame(node, domain.object(identity, null));
            rejected(IllegalArgumentException.class, () -> domain.storage(identity));
            var root = participant.ownValue(node, identity);assertEquals(1, node.incoming());root.close();
            rejected(IllegalStateException.class, () -> domain.object(identity, null));
        }
        participant.close();assertEquals(0, domain.graph().liveNodes());
    }
    @Test public void linkedParticipantsShareOneGraphButCloseOnlyTheirOwnRoots() throws Throwable {
        var domain = new PhpOwnershipDomain();var first = domain.participant("first");var second = domain.participant("second");
        List<String> events = new ArrayList<>();PhpOwnership.Node node;
        try (var ignored = first.enter()) {
            node = domain.object(new Object(), log(events, "object"));first.ownValue(node, "first view");second.ownValue(node, "second view");
        }
        assertSame(domain.graph(), second.domain().graph());assertEquals(2, node.incoming());
        first.close();assertEquals(1, node.incoming());assertTrue(events.isEmpty());
        second.close();assertEquals(List.of("object:destruct:second", "object:destroy:second"), events);
    }
    @Test public void foreignObjectCellStorageAreRejectedBeforeAnyRootPublication() throws Throwable {
        var source = new PhpOwnershipDomain();var target = new PhpOwnershipDomain();
        var from = source.participant("source");var to = target.participant("target");List<PhpOwnership.Node> nodes = new ArrayList<>();
        try (var ignored = from.enter()) {
            nodes.add(source.object(new Object(), null));nodes.add(source.canonicalCell(new Object()));nodes.add(source.storage(new Object()));
            from.ownValue(nodes.get(0), "object");from.ownCell(nodes.get(1), "cell");from.ownValue(nodes.get(2), "storage");
        }
        try (var ignored = to.enter()) {
            for (var node : nodes) {
                if (node.kind() == PhpOwnership.Kind.CELL) rejected(IllegalArgumentException.class, () -> to.ownCell(node, "foreign"));
                else rejected(IllegalArgumentException.class, () -> to.ownValue(node, "foreign"));
                assertEquals(1, node.incoming());
            }
            assertEquals(0, target.graph().liveNodes());
        }
        from.close();to.close();
    }
    @Test public void scalarValueDoesNotInventAHeapNodeAndCellModeIsExplicit() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("env");
        try (var ignored = participant.enter()) {
            try (var scalar = participant.ownValue(null, "scalar")) {assertNull(scalar.node());assertEquals("scalar", scalar.rawValue());}
            assertEquals(0, domain.graph().liveNodes());
            var cell = domain.canonicalCell(new Object());var storage = domain.storage(new Object());
            rejected(IllegalArgumentException.class, () -> participant.ownValue(cell, "wrong mode"));
            rejected(IllegalArgumentException.class, () -> participant.ownCell(storage, "wrong mode"));
            rejected(IllegalArgumentException.class, () -> participant.ownCell(null, "wrong mode"));
            participant.ownCell(cell, "cell");participant.ownValue(storage, "storage");
        }
        participant.close();
    }
    private static final class VirtualCarrier extends ReferenceMemory {
        @Override public Memory getValue() {throw new AssertionError("No virtual value evaluation");}
        @Override public ReferenceMemory getReference() {throw new AssertionError("No virtual canonicalization");}
    }
    @Test public void explicitCanonicalIdentityAndRawMetadataNeverInvokeMemoryGetters() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("env");var raw = new VirtualCarrier();
        try (var ignored = participant.enter()) {
            Object canonical = new Object();var cell = domain.canonicalCell(canonical);
            var handle = participant.ownCell(cell, raw);assertSame(raw, handle.rawValue());assertSame(canonical, cell.identity());
            assertSame(cell, domain.canonicalCell(canonical));handle.close();
        }
        participant.close();
    }
    @Test public void hostDetachSurvivesClosingOriginalParticipantAndUsesNewCleanupContext() throws Throwable {
        var domain = new PhpOwnershipDomain();var original = domain.participant("allocation");var cleanup = domain.participant("release");
        List<String> events = new ArrayList<>();PhpOwnershipDomain.OwnedValue host;
        try (var ignored = original.enter()) {
            host = original.ownValue(domain.object(new Object(), log(events, "host")), "raw");assertSame(host, host.detachToHost());
            assertNull(host.participant());assertEquals(1, host.node().incoming());
        }
        original.close();assertFalse(host.isClosed());assertTrue(events.isEmpty());
        rejected(IllegalStateException.class, host::close);
        rejected(IllegalStateException.class, () -> host.close(original));assertFalse(host.isClosed());
        host.close(cleanup);assertNull(host.rawValue());assertNull(host.node());
        assertEquals(List.of("host:destruct:release", "host:destroy:release"), events);cleanup.close();
    }
    @Test public void transferMovesValueAndCellCustodyWithoutAnExtraOwner() throws Throwable {
        var domain = new PhpOwnershipDomain();var first = domain.participant("first");var second = domain.participant("second");
        PhpOwnershipDomain.OwnedValue value;PhpOwnershipDomain.OwnedCell cell;
        try (var ignored = first.enter()) {
            value = first.ownValue(domain.storage(new Object()), "view");cell = first.ownCell(domain.canonicalCell(new Object()), "cell");
            assertSame(value, value.transferTo(second));assertSame(cell, cell.transferTo(second));
            assertEquals(1, value.node().incoming());assertEquals(1, cell.node().incoming());
        }
        first.close();assertFalse(value.isClosed());assertFalse(cell.isClosed());second.close();assertTrue(value.isClosed());assertTrue(cell.isClosed());
    }
    @Test public void foreignCustodyTransferAndCleanupDoNotChangeOriginalHandle() throws Throwable {
        var domain = new PhpOwnershipDomain();var original = domain.participant("original");
        var foreign = new PhpOwnershipDomain().participant("foreign");
        try (var ignored = original.enter()) {
            var owned = original.ownValue(domain.object(new Object(), null), "raw");
            rejected(IllegalArgumentException.class, () -> owned.transferTo(foreign));
            rejected(IllegalArgumentException.class, () -> owned.close(foreign));
            assertSame(original, owned.participant());assertEquals("raw", owned.rawValue());assertEquals(1, owned.node().incoming());
        }
        original.close();foreign.close();
    }
    @Test public void executionScopesAreLifoAndClosedParticipantsCannotReenter() throws Throwable {
        var domain = new PhpOwnershipDomain();var a = domain.participant("A");var b = domain.participant("B");
        var outer = a.enter();var inner = b.enter();assertEquals("B", domain.currentContext());
        rejected(IllegalStateException.class, outer::close);assertFalse(outer.isClosed());assertEquals("B", domain.currentContext());
        inner.close();assertEquals("A", domain.currentContext());outer.close();
        rejected(IllegalStateException.class, domain::currentContext);a.close();rejected(IllegalStateException.class, a::enter);b.close();
    }
    @Test public void rawHandleMirrorIsClearedBeforeDestructorAndHostMayResurrect() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("env");
        var original = new AtomicReference<PhpOwnershipDomain.OwnedValue>();var resurrected = new AtomicReference<PhpOwnershipDomain.OwnedValue>();List<String> events = new ArrayList<>();
        try (var ignored = participant.enter()) {
            var node = domain.object(new Object(), new PhpOwnershipDomain.Lifecycle() {
                public void destruct(PhpOwnership.Node node, Object context) throws Throwable {
                    assertTrue(original.get().isClosed());assertNull(original.get().rawValue());assertNull(original.get().node());
                    events.add("destruct");resurrected.set(domain.hostValue(node, "resurrected"));
                }
                public void destroy(PhpOwnership.Node node, Object context) {events.add("destroy");}
            });
            original.set(participant.ownValue(node, "raw"));original.get().close();assertEquals(List.of("destruct"), events);assertEquals(1, node.incoming());
        }
        participant.close();var cleanup = domain.participant("cleanup");resurrected.get().close(cleanup);cleanup.close();
        assertEquals(List.of("destruct", "destroy"), events);
    }
    @Test public void participantUnwindReleasesEveryRootAndPreservesPrimaryException() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("env");var primary = new Exception("primary");
        var first = new Exception("first");var second = new Exception("second");List<String> events = new ArrayList<>();
        try (var ignored = participant.enter()) {
            participant.ownValue(domain.object(new Object(), new PhpOwnershipDomain.Lifecycle() {
                public void destruct(PhpOwnership.Node node, Object context) throws Throwable {events.add("first");throw first;}
            }), "first");
            participant.ownValue(domain.object(new Object(), new PhpOwnershipDomain.Lifecycle() {
                public void destruct(PhpOwnership.Node node, Object context) throws Throwable {events.add("second");throw second;}
            }), "second");
        }
        try {participant.unwind(primary);fail();} catch (Exception actual) {assertSame(primary, actual);assertArrayEquals(new Throwable[]{second, first}, actual.getSuppressed());}
        assertEquals(List.of("second", "first"), events);assertTrue(participant.isClosed());assertEquals(0, domain.graph().liveNodes());
        rejected(IllegalStateException.class, domain::currentContext);
    }
    @Test public void customFailureCompositionReceivesPendingAndEveryCleanup() throws Throwable {
        List<String> order = new ArrayList<>();var primary = new Exception("pending");var cleanup = new Exception("cleanup");
        var composed = new Exception("composed");var domain = new PhpOwnershipDomain((pending, next) -> {
            assertSame(primary, pending);assertSame(cleanup, next);composed.addSuppressed(pending);composed.addSuppressed(next);order.add("compose");return composed;
        });var participant = domain.participant("env");
        try (var ignored = participant.enter()) {
            participant.ownValue(domain.object(new Object(), new PhpOwnershipDomain.Lifecycle() {
                public void destruct(PhpOwnership.Node node, Object context) throws Throwable {throw cleanup;}
            }), "raw");
        }
        try {participant.unwind(primary);fail();} catch (Exception actual) {assertSame(composed, actual);}
        assertEquals(List.of("compose"), order);assertEquals(0, domain.graph().liveNodes());
    }
    @Test public void cyclesNeedExplicitCollectionAndUseReleasingContext() throws Throwable {
        var domain = new PhpOwnershipDomain();var allocation = domain.participant("allocation");var cleanup = domain.participant("collection");List<String> events = new ArrayList<>();
        try (var ignored = allocation.enter()) {
            var a = domain.object(new Object(), log(events, "a"));var b = domain.object(new Object(), log(events, "b"));
            domain.graph().edge(a).replace(b);domain.graph().edge(b).replace(a);allocation.ownValue(a, "a");
        }
        allocation.close();assertTrue(events.isEmpty());assertEquals(2, domain.graph().liveNodes());
        try (var ignored = cleanup.enter()) {assertEquals(2, domain.collectCycles());}
        assertEquals(List.of("a:destruct:collection", "b:destruct:collection", "a:destroy:collection", "b:destroy:collection"), events);cleanup.close();
    }
    @Test public void rawPublicationBarrierRejectsContextAndHandleChangesBeforeRawMutation() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("env");
        try (var ignored = participant.enter()) {
            var node = domain.object(new Object(), null);var owned = participant.ownValue(node, "raw");var slot = domain.graph().root();
            try (var publication = slot.preparePublication(node)) {
                rejected(IllegalStateException.class, participant::enter);rejected(IllegalStateException.class, owned::close);
                rejected(IllegalStateException.class, owned::detachToHost);assertEquals("raw", owned.rawValue());assertFalse(owned.isClosed());
            }
            slot.close();owned.close();
        }
        participant.close();
    }
    @Test public void executionHandoffSerializesContextsAndHostCleanupAcrossThreads() throws Throwable {
        var domain = new PhpOwnershipDomain();var participant = domain.participant("main");var workerParticipant = domain.participant("worker");List<String> events = new ArrayList<>();
        PhpOwnershipDomain.OwnedValue host;
        try (var ignored = participant.enter()) {
            host = domain.hostValue(domain.object(new Object(), log(events, "host")), "raw");rejected(IllegalStateException.class, domain::handoff);
        }
        var ticket = domain.handoff();rejected(IllegalStateException.class, participant::enter);
        var failure = new AtomicReference<Throwable>();var returned = new AtomicReference<PhpOwnership.Handoff>();
        Thread worker = new Thread(() -> {
            try {
                rejected(IllegalStateException.class, workerParticipant::enter);ticket.claim();host.close(workerParticipant);
                try (var ignored = workerParticipant.enter()) {assertEquals("worker", domain.currentContext());}
                returned.set(domain.handoff());
            } catch (Throwable error) {failure.set(error);}
        });worker.setDaemon(true);worker.start();worker.join(5000);assertFalse("Worker did not finish", worker.isAlive());
        if (failure.get() != null) throw failure.get();returned.get().claim();
        assertEquals(List.of("host:destruct:worker", "host:destroy:worker"), events);participant.close();workerParticipant.close();
        rejected(IllegalStateException.class, ticket::claim);assertEquals(0, domain.graph().liveNodes());
    }
}
