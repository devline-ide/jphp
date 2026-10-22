package php.runtime.env;

import org.junit.Test;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.memory.StringMemory;
import php.runtime.output.OutputBuffer;
import java.io.ByteArrayOutputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Focused admission/cache regressions; root performs compilation and allocation measurements. */
public class PhpGateAllocationCacheTest {
    private static Object field(Object object, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
    }
    private static Thread holder(PhpOperationGate gate, Object token, CountDownLatch entered,
                                 CountDownLatch release, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try (var lease = gate.enter(token, PhpOperationGate.Reentry.SAME_LINEAGE)) { entered.countDown();await(release); }
            catch (Throwable caught) { failure.set(caught);entered.countDown(); }
        });
        thread.setDaemon(true);thread.start();return thread;
    }
    private static void finish(Thread thread, CountDownLatch release, AtomicReference<Throwable> failure) throws Exception {
        release.countDown();thread.join(5000);assertFalse(thread.isAlive());assertNull(failure.get());
    }

    @Test(timeout=15000) public void dormantCarrierCannotAuthorizeAnotherCarriersSameFamilyLease() throws Exception {
        var gate = new PhpOperationGate();Object token = new Object();
        try (var initial = gate.enter(token, PhpOperationGate.Reentry.SAME_LINEAGE)) {}
        var entered = new CountDownLatch(1);var release = new CountDownLatch(1);var failure = new AtomicReference<Throwable>();
        Thread owner = holder(gate, token, entered, release, failure);await(entered);
        try {
            assertFalse(gate.isOwnedByCarrier(token));
            try (var forbidden = gate.tryEnter(token, PhpOperationGate.Reentry.SAME_CARRIER)) { fail("Idle carrier admitted"); }
            catch (PhpOperationGate.OverlapException expected) {}
            try (var forbidden = gate.enter(token, PhpOperationGate.Reentry.SAME_CARRIER)) { fail("Idle carrier admitted"); }
            catch (PhpOperationGate.OverlapException expected) {}
            assertNull(gate.tryEnter(new Object(), PhpOperationGate.Reentry.SAME_LINEAGE));
        } finally { finish(owner, release, failure); }
        try (var after = gate.enter(new Object(), PhpOperationGate.Reentry.SAME_CARRIER)) {}
    }

    @Test(timeout=15000) public void hotCarrierRecordsAreReusedAndIdleRetentionIsBounded() throws Exception {
        var gate = new PhpOperationGate();Object token = new Object();
        try (var initial = gate.enter(token, PhpOperationGate.Reentry.SAME_LINEAGE)) {}
        List<?> carriers = (List<?>) field(gate, PhpOperationGate.class, "carriers");
        assertEquals(1, carriers.size());Object first = carriers.get(0);
        for (int index = 0; index < 1000; index++) try (var next = gate.enter(token, PhpOperationGate.Reentry.SAME_CARRIER)) {}
        assertEquals(1, carriers.size());assertSame(first, carriers.get(0));
        List<Thread> retained = new ArrayList<>();var failure = new AtomicReference<Throwable>();
        for (int index = 0; index < 32; index++) {
            var entered = new CountDownLatch(1);var release = new CountDownLatch(0);
            Thread thread = holder(gate, token, entered, release, failure);retained.add(thread);await(entered);thread.join(5000);
            assertFalse(thread.isAlive());assertNull(failure.get());
        }
        assertTrue("Idle weak carrier metadata must stay bounded", carriers.size() <= 8);
        assertFalse(gate.isOwnedBy(token));Reference.reachabilityFence(retained);
    }

    @Test(timeout=15000) public void weakDomainCacheIsReusedButZeroClaimsAllowAnotherDomain() throws Exception {
        var token = PhpExecutionLineage.current();var first = new PhpCallbackDomain();var other = new PhpCallbackDomain();
        try (var lease = first.enter()) {}
        Object cached = field(token, PhpExecutionLineage.Token.class, "domain");assertNotNull(cached);
        for (int index = 0; index < 1000; index++) try (var lease = first.enter()) {}
        assertSame(cached, field(token, PhpExecutionLineage.Token.class, "domain"));
        try (var active = first.enter()) {
            try (var forbidden = other.enter()) { fail("Active cross-domain claim admitted"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("cross runtime domains")); }
        }
        other.validateEntry();try (var allowed = other.enter()) {}
        first.validateEntry();try (var allowed = first.enterForRetirement()) {}
    }

    private static WeakReference<PhpCallbackDomain> releasedDomain() {
        var domain = new PhpCallbackDomain();try (var lease = domain.enter()) {}return new WeakReference<>(domain);
    }
    @Test(timeout=15000) public void zeroClaimCacheDoesNotRootReleasedDomain() throws Exception {
        var token = PhpExecutionLineage.current();WeakReference<PhpCallbackDomain> weak = releasedDomain();
        for (int index = 0; index < 120 && weak.get() != null; index++) { System.gc();Thread.sleep(25); }
        assertNull("Dormant root TLS cache retained domain", weak.get());
        try (var lease = new PhpCallbackDomain().enter()) {}Reference.reachabilityFence(token);
    }

    @Test(timeout=15000) public void nestedRootWriteRetainsOneRealLeaseAcrossProtectedHook() throws Throwable {
        var scope = new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        var env = new Environment(scope, new ByteArrayOutputStream());
        try {
            class Probe extends OutputBuffer {
                int hooks;
                Probe() { super(env, null, null, 0, true); }
                @Override protected void _write(byte[] bytes, int length) throws Throwable {
                    var gate = (PhpOperationGate) field(this, OutputBuffer.class, "operationGate");
                    assertTrue(gate.isOwnedByCarrier(env.executionLineage()));
                    assertEquals("Internal dispatch duplicated root admission", 1, ((Integer) field(gate, PhpOperationGate.class, "leases")).intValue());
                    assertFalse(Thread.holdsLock(gate));hooks++;super._write(bytes, length);
                }
            }
            var output = new Probe();output.write(StringMemory.valueOf("a"));output.write("b");output.write(new byte[]{'c'});
            assertEquals(3, output.hooks);assertEquals("abc", output.getContents().toString());
        } finally { env.doFinal(); }
    }
}
