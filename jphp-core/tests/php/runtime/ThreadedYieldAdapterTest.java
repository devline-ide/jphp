package php.runtime;

import org.junit.Test;
import php.runtime.util.generator.CollectionAbortedException;
import php.runtime.util.generator.Collector;
import php.runtime.util.generator.ResultHandler;
import php.runtime.util.generator.ThreadedYieldAdapter;
import php.runtime.util.generator.YieldAdapterIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ThreadedYieldAdapterTest {
    private interface Body<T> {
        void collect(ResultHandler<T> handler) throws CollectionAbortedException;
    }

    private static <T> YieldAdapterIterator<T> adapt(Body<T> body) {
        return new ThreadedYieldAdapter<T>().adapt(new Collector<T>() {
            @Override public void collect(ResultHandler<T> handler) throws CollectionAbortedException {
                body.collect(handler);
            }
            @Override public void setIterator(YieldAdapterIterator iterator) { }
        }).iterator();
    }

    private static void await(CountDownLatch latch) throws CollectionAbortedException {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("worker gate timed out");
        } catch (InterruptedException e) {
            throw new CollectionAbortedException(e);
        }
    }

    private static <T> FutureTask<T> start(java.util.concurrent.Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        Thread thread = new Thread(future, "jphp-yield-adapter-qa");
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    private static void pending(FutureTask<?> task) throws Exception {
        try {
            task.get(100, TimeUnit.MILLISECONDS);
            fail("close returned before collector cleanup completed");
        } catch (TimeoutException expected) {
            // The collector is deliberately held inside finally until the test releases it.
        }
    }

    @Test(timeout = 6000)
    public void closeWaitsForSuspendedFinallyAndRetiresPayload() throws Exception {
        CountDownLatch cleanupEntered = new CountDownLatch(1), cleanupAllowed = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Object payload = new Object();
        YieldAdapterIterator<Object> iterator = ThreadedYieldAdapterTest.<Object>adapt(handler -> {
            worker.set(Thread.currentThread());
            try { handler.handleResult(payload); }
            finally {
                cleanupEntered.countDown();
                await(cleanupAllowed);
                cleaned.set(true);
            }
        });
        assertSame(payload, iterator.next());
        iterator.setThrowable(new IllegalStateException("pending input"));
        FutureTask<Void> closing = start(() -> { iterator.close(); return null; });
        try {
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            pending(closing);
        } finally { cleanupAllowed.countDown(); }
        closing.get(3, TimeUnit.SECONDS);
        assertTrue(cleaned.get());
        assertFalse(worker.get().isAlive());
        assertNull(iterator.getCurrentValue());
        assertNull(iterator.getThrowable());
        assertFalse(iterator.hasNext());
        iterator.close();
    }

    @Test(timeout = 6000)
    public void closeBeforeFirstReadDoesNotStartBody() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            entered.set(true); handler.handleResult(1);
        });
        iterator.close();
        iterator.close();
        assertFalse(entered.get());
        assertFalse(iterator.hasNext());
        try { iterator.next(); fail("closed iterator yielded"); }
        catch (NoSuchElementException expected) { }
    }

    @Test(timeout = 6000)
    public void closeUnblocksAReaderWhileCollectorUnwinds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), blocked = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean cleaned = new AtomicBoolean();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            worker.set(Thread.currentThread()); entered.countDown();
            try { await(blocked); handler.handleResult(1); }
            finally { cleaned.set(true); }
        });
        FutureTask<Boolean> reading = start(() -> {
            try { iterator.next(); return false; }
            catch (NoSuchElementException expected) { return true; }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        iterator.close();
        assertTrue(reading.get(500, TimeUnit.MILLISECONDS));
        assertTrue(cleaned.get());
        assertFalse(worker.get().isAlive());
        assertNull(iterator.getCurrentValue());
    }

    @Test(timeout = 6000)
    public void workerFailureReachesReaderWithIdentityAndCompletedFinally() throws Exception {
        IllegalStateException failure = new IllegalStateException("collector failed");
        AtomicBoolean cleaned = new AtomicBoolean();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            try { throw failure; } finally { cleaned.set(true); }
        });
        FutureTask<Throwable> reading = start(() -> {
            try { iterator.next(); return null; } catch (Throwable e) { return e; }
        });
        try {
            assertSame(failure, reading.get(500, TimeUnit.MILLISECONDS));
            assertTrue(cleaned.get());
            assertFalse(iterator.hasNext());
        } finally { iterator.close(); }
    }

    @Test(timeout = 6000)
    public void selfCloseDoesNotWaitForItselfOrPublishAnotherValue() throws Exception {
        AtomicReference<YieldAdapterIterator<Integer>> reference = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean aborted = new AtomicBoolean();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            worker.set(Thread.currentThread());
            try {
                reference.get().close();
                handler.handleResult(2);
                fail("closed worker published another value");
            } catch (CollectionAbortedException expected) { aborted.set(true); }
            catch (java.io.IOException unexpected) { throw new AssertionError(unexpected); }
        });
        reference.set(iterator);
        assertFalse(iterator.hasNext());
        iterator.close();
        assertTrue(aborted.get());
        assertFalse(worker.get().isAlive());
    }

    @Test(timeout = 6000)
    public void interruptedCloserWaitsForCleanupAndRestoresItsInterrupt() throws Exception {
        CountDownLatch cleanupEntered = new CountDownLatch(1), cleanupAllowed = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            try { handler.handleResult(1); }
            finally { cleanupEntered.countDown(); await(cleanupAllowed); cleaned.set(true); }
        });
        assertEquals(Integer.valueOf(1), iterator.next());
        FutureTask<Boolean> closing = start(() -> {
            Thread.currentThread().interrupt();
            iterator.close();
            return Thread.currentThread().isInterrupted();
        });
        try {
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            pending(closing);
        } finally { cleanupAllowed.countDown(); }
        assertTrue(closing.get(3, TimeUnit.SECONDS));
        assertTrue(cleaned.get());
    }

    @Test(timeout = 6000)
    public void naturalYieldResumeOrderAndCurrentValueArePreserved() throws Exception {
        List<String> events = new ArrayList<>();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            for (int i = 0; i < 3; i++) {
                events.add("before:" + i); handler.handleResult(i); events.add("after:" + i);
            }
        });
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(iterator.hasNext()); assertTrue(iterator.hasNext());
                assertEquals(Integer.valueOf(i), iterator.next());
                assertEquals("before:" + i, events.get(events.size() - 1));
            }
            assertFalse(iterator.hasNext());
            assertEquals(Integer.valueOf(2), iterator.getCurrentValue());
            assertEquals(Arrays.asList("before:0", "after:0", "before:1", "after:1", "before:2", "after:2"), events);
        } finally { iterator.close(); }
    }

    @Test(timeout = 6000)
    public void checkedAbortRemainsAnOrdinaryTerminalEvent() throws Exception {
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            handler.handleResult(3); throw new CollectionAbortedException("done");
        });
        try {
            assertEquals(Integer.valueOf(3), iterator.next());
            assertFalse(iterator.hasNext());
        } finally { iterator.close(); }
    }

    @Test(timeout = 6000)
    public void interruptedReadRetiresWorkerAndPreservesCallerInterrupt() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        YieldAdapterIterator<Integer> iterator = ThreadedYieldAdapterTest.<Integer>adapt(handler -> {
            worker.set(Thread.currentThread()); handler.handleResult(1);
        });
        FutureTask<Boolean> reading = start(() -> {
            Thread.currentThread().interrupt();
            assertFalse(iterator.hasNext());
            return Thread.currentThread().isInterrupted();
        });
        assertTrue(reading.get(3, TimeUnit.SECONDS));
        if (worker.get() != null) assertFalse(worker.get().isAlive());
        iterator.close();
    }
}
