package php.runtime.env;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.output.OutputBuffer;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Black-box callback safety: a real escaped public permit cannot release private output admission. */
public class PhpPrivateOutputScopeSafetyTest {
    private interface Action { void run() throws Throwable; }
    private static Thread start(String label, Action action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } }, label);
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private static void join(Thread thread) throws Exception { thread.join(4000); assertFalse("Owned contender remains live", thread.isAlive()); }
    private static void waiting(Thread thread) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < until) Thread.sleep(1);
        assertEquals("Real unrelated entry must wait for the private output admission", Thread.State.WAITING, thread.getState());
    }
    private static final class CallbackSink extends OutputStream {
        Action callback;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            try { if (callback != null) callback.run(); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (IOException failure) { throw failure; }
            catch (Throwable failure) { throw new IOException(failure); }
            bytes.write(value, offset, length);
        }
    }
    private static void scenario(boolean throwing) throws Throwable {
        CompileScope scope = new CompileScope(); CallbackSink sink = new CallbackSink();
        Environment a = new Environment(scope, sink); ByteArrayOutputStream otherOutput = new ByteArrayOutputStream();
        Environment b = new Environment(scope, otherOutput);
        a.getDefaultBuffer().setImplicitFlush(true); b.getDefaultBuffer().setImplicitFlush(true);
        AtomicInteger retireEffects = new AtomicInteger();
        a.registerShutdownFunction(new php.runtime.env.handler.ShutdownHandler(null, new Memory[0]) {
            @Override public void call() { retireEffects.incrementAndGet(); }
        });
        PhpCallbackDomain.Lease escaped = a.enterCallbackDomain(); escaped.includeBody();
        IOException sentinel = new IOException("physical-output-sentinel");
        List<Thread> contenders = new ArrayList<>(); List<AtomicReference<Throwable>> targetResults = new ArrayList<>(), otherResults = new ArrayList<>();
        AtomicInteger callbacks = new AtomicInteger();
        sink.callback = () -> {
            callbacks.incrementAndGet();
            // On the second successful round this is an already closed, genuinely escaped public permit.
            escaped.close();
            CountDownLatch targetDone = new CountDownLatch(1); AtomicReference<Throwable> target = new AtomicReference<>();
            targetResults.add(target);
            contenders.add(start("retire-current-private-output-env", () -> {
                try { a.doFinal(); target.set(new AssertionError("Foreign retirement passed while native sink was active")); }
                catch (IllegalStateException expected) { target.set(expected); }
                finally { targetDone.countDown(); }
            }, target));
            await(targetDone); assertTrue(String.valueOf(target.get()), target.get() instanceof IllegalStateException);
            assertTrue(target.get().getMessage().contains("Foreign-family")); assertEquals(0, retireEffects.get());
            CountDownLatch otherStarted = new CountDownLatch(1); AtomicReference<Throwable> other = new AtomicReference<>();
            otherResults.add(other);
            Thread worker = start("unrelated-entry-during-private-output", () -> { otherStarted.countDown(); b.echo("u"); }, other);
            contenders.add(worker); await(otherStarted); waiting(worker);
            try { a.echo("bad-reentry"); fail("Native sink borrowed private root mutation permission"); }
            catch (IllegalStateException expected) { assertEquals("Root output mutation inside its active host callback", expected.getMessage()); }
            try (var park = scope.getClassLoader().getCallbackDomain().parkForHostLifecycle()) {
                fail("Private output entry must remain NONparkable after the escaped include-body permit closes");
            } catch (PhpOperationGate.OverlapException expected) { }
            if (throwing) throw sentinel;
        };
        try {
        try {
            if (throwing) {
                try { a.echo("x"); fail("Physical sink must throw"); }
                catch (RuntimeException failure) { assertSame(sentinel, failure.getCause()); }
            } else { a.echo("a"); a.echo("b"); }
        } finally {
            sink.callback = null;
            for (Thread thread : contenders) join(thread);
            escaped.close();
        }
            assertEquals(throwing ? 1 : 2, callbacks.get());
            for (AtomicReference<Throwable> result : targetResults) assertTrue(result.get() instanceof IllegalStateException);
            for (AtomicReference<Throwable> result : otherResults) assertNull(result.get());
            assertEquals(throwing ? "u" : "uu", otherOutput.toString(StandardCharsets.UTF_8));
            assertEquals(0, retireEffects.get());
            a.echo("z");
            assertEquals(throwing ? "xz" : "abz", sink.bytes.toString(StandardCharsets.UTF_8));
            try (var restored = scope.getClassLoader().getCallbackDomain().parkForHostLifecycle()) { }
        } finally { try { a.doFinal(); } finally { b.doFinal(); } }
        assertEquals(1, retireEffects.get());
    }
    @Test(timeout = 20000) public void escapedPublicLeaseCannotReleaseCurrentOrLaterPrivateOutputScopes() throws Throwable { scenario(false); }
    @Test(timeout = 20000) public void escapedPublicLeaseAndThrowingPhysicalSinkUnwindOnlyTheirActualPrivateScope() throws Throwable { scenario(true); }

    private static WeakReference<?>[] retiredOutputFixture() throws Throwable {
        Environment env = new Environment(new CompileScope(), new ByteArrayOutputStream());
        OutputBuffer buffer = env.getDefaultBuffer(); env.echo("idle-private-scope"); env.doFinal();
        return new WeakReference<?>[] { new WeakReference<>(env), new WeakReference<>(buffer), new WeakReference<>(env.scope.getClassLoader()) };
    }
    @Test(timeout = 15000) public void actualOutputIdleStateDoesNotRootEnvironmentBufferOrRuntimeLoaderOnLiveCarrier() throws Throwable {
        AtomicReference<WeakReference<?>[]> weak = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread carrier = start("live-idle-output-carrier", () -> {
            try { weak.set(retiredOutputFixture()); ready.countDown(); assertTrue(finish.await(8, TimeUnit.SECONDS)); }
            finally { ready.countDown(); }
        }, failure);
        try {
            await(ready); assertNull(failure.get()); assertNotNull(weak.get());
            for (WeakReference<?> reference : weak.get()) {
                for (int round = 0; round < 100 && reference.get() != null; round++) { System.gc(); Thread.sleep(20); }
                assertNull("Idle carrier state retained a retired output fixture", reference.get());
            }
            assertTrue("Carrier must remain alive while retention is checked", carrier.isAlive());
        } finally { finish.countDown(); join(carrier); }
        assertNull(failure.get());
    }
}
