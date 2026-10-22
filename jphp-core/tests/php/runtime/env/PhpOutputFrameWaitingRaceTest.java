package php.runtime.env;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Two genuine waiting carriers may not admit the same caller-held frame twice. */
public class PhpOutputFrameWaitingRaceTest {
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private static void waiting(Thread thread) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < until) Thread.sleep(1);
        assertEquals("Both carriers must really wait behind the original foreign owner", Thread.State.WAITING, thread.getState());
    }
    @Test(timeout = 15000) public void twoSharedLineageWaitersCannotOverwriteOneActiveFrameOrLeakAdmission() throws Throwable {
        PhpOperationGate gate = new PhpOperationGate(); Object sharedLineage = new Object();
        var frame = gate.newOutputFrame();
        var original = gate.enter(new Object(), PhpOperationGate.Reentry.REJECT);
        CountDownLatch started = new CountDownLatch(2), entered = new CountDownLatch(1), rejected = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger(), refusals = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[2];
        try {
            for (int index = 0; index < workers.length; index++) {
                workers[index] = new Thread(() -> {
                    started.countDown();
                    try {
                        long generation;
                        try { generation = gate.enterOutputFrame(frame, sharedLineage, PhpOperationGate.Reentry.SAME_LINEAGE); }
                        catch (IllegalStateException expected) { refusals.incrementAndGet(); rejected.countDown(); return; }
                        try { admitted.incrementAndGet(); entered.countDown(); await(release); }
                        finally { gate.closeOutputFrame(frame, generation); }
                    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                }, "shared-frame-waiter-" + index);
                workers[index].setDaemon(true); workers[index].start();
            }
            await(started); waiting(workers[0]); waiting(workers[1]);
            assertEquals(0, admitted.get()); assertEquals(0, refusals.get());
            original.close(); await(entered); await(rejected);
            assertEquals("Exactly one real holder may own the frame", 1, admitted.get());
            assertEquals("The second carrier must reject while the first holder remains active", 1, refusals.get());
            try (var contender = gate.tryEnter(new Object(), PhpOperationGate.Reentry.REJECT)) {
                assertNull("Duplicate refusal must not release the winner's actual admission", contender);
            }
        } finally {
            original.close(); release.countDown();
            for (Thread worker : workers) if (worker != null) { worker.join(4000); assertFalse("Owned waiter remains live", worker.isAlive()); }
        }
        if (failure.get() != null) throw failure.get();
        try (var after = gate.tryEnter(new Object(), PhpOperationGate.Reentry.REJECT)) {
            assertNotNull("Both waiters completed; no hidden admission count may remain", after);
        }
    }
}
