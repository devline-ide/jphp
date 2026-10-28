package php.runtime.env;

import org.junit.Test;
import php.runtime.ext.support.Extension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ExtensionRegistrationConcurrencyTest {
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("concurrent registration did not reach its checkpoint", latch.await(3, TimeUnit.SECONDS));
    }

    private static Thread start(String name, AtomicReference<Throwable> failure, Runnable action) {
        Thread thread = new Thread(() -> {
            try { action.run(); } catch (Throwable problem) { failure.compareAndSet(null, problem); }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void waitForContender(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
            Thread.sleep(1);
        assertEquals("registration must wait for the active class-lookup domain", Thread.State.WAITING, thread.getState());
    }

    @Test public void classLookupAndExtensionRegistrationCannotInvertTheirLocks() throws Exception {
        var scope = new CompileScope();
        var lookupEntered = new CountDownLatch(1);
        var registrationStarted = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var contender = new AtomicReference<Thread>();
        var registrations = new AtomicInteger();
        Extension extension = new Extension() {
            @Override public String getName() { return "Concurrent-Registration-Probe"; }
            @Override public Status getStatus() { return Status.EXPERIMENTAL; }
            @Override public void onRegister(CompileScope target) {
                registrations.incrementAndGet();
                assertNotNull(target.fetchUserClass("stdClass"));
            }
        };
        Thread lookup = start("jphp-registration-lookup", failure, () -> {
            try (var admission = scope.getClassLoader().getCallbackDomain().enter()) {
                lookupEntered.countDown();
                await(registrationStarted);
                waitForContender(contender.get());
                assertNull(scope.findCompileFunction("__registration_missing_function"));
            } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        await(lookupEntered);
        Thread registration = new Thread(() -> {
            try {
                registrationStarted.countDown();
                scope.registerExtension(extension);
            } catch (Throwable problem) { failure.compareAndSet(null, problem); }
        }, "jphp-registration-contender");
        registration.setDaemon(true);
        contender.set(registration);
        registration.start();
        lookup.join(4000);
        registration.join(4000);
        assertFalse("class lookup is deadlocked", lookup.isAlive());
        assertFalse("extension registration is deadlocked", registration.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        scope.registerExtension(extension);
        assertEquals("duplicate registration must remain idempotent", 1, registrations.get());
        assertSame(extension, scope.getExtension(extension.getName()));
        scope.getClassLoader().close();
    }
}
