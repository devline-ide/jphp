package php.runtime.env;

import org.junit.Test;
import php.runtime.Memory;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Negative generation/carrier controls use real contention and retirement as the authority witness. */
public class PhpOutputLexicalFrameProtocolTest {
    private interface Action { void run() throws Throwable; }
    private static void rejects(Action action) throws Throwable {
        try { action.run(); fail("Invalid private frame transition passed"); }
        catch (IllegalStateException | IllegalArgumentException expected) { }
    }
    private static void foreign(Action action) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } }, "foreign-lexical-frame-control");
        thread.setDaemon(true); thread.start(); thread.join(TimeUnit.SECONDS.toMillis(3));
        assertFalse("Foreign frame control must reject without blocking", thread.isAlive());
        if (failure.get() != null) throw failure.get();
    }
    private static void actuallyContended(PhpOperationGate gate) throws Throwable {
        foreign(() -> {
            try (var probe = gate.tryEnter(PhpExecutionLineage.current(), PhpOperationGate.Reentry.REJECT)) {
                assertNull("Invalid close released the actual protected operation", probe);
            }
        });
    }
    private static void targetRetirementRejected(Environment env, AtomicInteger effects) throws Throwable {
        foreign(() -> {
            try { env.doFinal(); fail("Invalid frame close released an active target Environment"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Foreign-family")); }
        });
        assertEquals("Rejection must precede shutdown effects", 0, effects.get());
    }
    @Test(timeout = 15000) public void gateFramesRejectStaleForeignOtherGateAndDuplicateEntryWithoutReleasingCurrentEpoch() throws Throwable {
        PhpOperationGate gate = new PhpOperationGate(), wrongGate = new PhpOperationGate();
        Object lineage = PhpExecutionLineage.current(); var frame = gate.newOutputFrame();
        long previous = gate.enterOutputFrame(frame, lineage, PhpOperationGate.Reentry.SAME_CARRIER);
        gate.closeOutputFrame(frame, previous);
        long current = gate.enterOutputFrame(frame, lineage, PhpOperationGate.Reentry.SAME_CARRIER);
        try {
            assertNotEquals(previous, current);
            rejects(() -> gate.closeOutputFrame(frame, previous)); actuallyContended(gate);
            foreign(() -> rejects(() -> gate.closeOutputFrame(frame, current))); actuallyContended(gate);
            rejects(() -> wrongGate.closeOutputFrame(frame, current)); actuallyContended(gate);
            rejects(() -> gate.enterOutputFrame(frame, lineage, PhpOperationGate.Reentry.SAME_CARRIER)); actuallyContended(gate);
            rejects(() -> { try (var unexpected = gate.parkForHostLifecycle(lineage)) { } }); actuallyContended(gate);
        } finally { gate.closeOutputFrame(frame, current); }
        foreign(() -> { try (var probe = gate.tryEnter(PhpExecutionLineage.current(), PhpOperationGate.Reentry.REJECT)) { assertNotNull(probe); } });
    }
    @Test(timeout = 20000) public void domainFramesRejectStaleForeignAndOtherDomainBeforeTargetEffectsAndUnwindFailedEntry() throws Throwable {
        Environment env = new Environment(new CompileScope(), new ByteArrayOutputStream());
        Environment other = new Environment(new CompileScope(), new ByteArrayOutputStream());
        PhpCallbackDomain domain = env.scope.getClassLoader().getCallbackDomain(), wrongDomain = other.scope.getClassLoader().getCallbackDomain();
        var frame = domain.newOutputFrame(); AtomicInteger effects = new AtomicInteger();
        env.registerShutdownFunction(new php.runtime.env.handler.ShutdownHandler(null, new Memory[0]) {
            @Override public void call() { effects.incrementAndGet(); }
        });
        try {
            rejects(() -> domain.enterOutputFrame(frame, other));
            // A rejected foreign-Env producer must not leave any callback-domain claim behind.
            try (var validOther = other.enterCallbackDomain()) { }
            long previous = domain.enterOutputFrame(frame, env); domain.closeOutputFrame(frame, previous);
            long current = domain.enterOutputFrame(frame, env);
            try {
                assertNotEquals(previous, current);
                rejects(() -> domain.closeOutputFrame(frame, previous)); targetRetirementRejected(env, effects);
                foreign(() -> rejects(() -> domain.closeOutputFrame(frame, current))); targetRetirementRejected(env, effects);
                rejects(() -> wrongDomain.closeOutputFrame(frame, current)); targetRetirementRejected(env, effects);
                rejects(() -> domain.enterOutputFrame(frame, env)); targetRetirementRejected(env, effects);
                rejects(() -> { try (var unexpected = domain.parkForHostLifecycle()) { } }); targetRetirementRejected(env, effects);
            } finally { domain.closeOutputFrame(frame, current); }
            try (var validOther = other.enterCallbackDomain()) { }
        } finally { try { env.doFinal(); } finally { other.doFinal(); } }
        assertEquals(1, effects.get());
    }
}
