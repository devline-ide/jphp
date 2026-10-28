package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.invoke.cache.CallCache;
import php.runtime.invoke.cache.ClassCallCache;
import php.runtime.reflection.ClassEntity;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Weak row reuse must preserve embedding hooks, environment isolation and finalization. */
public class CallCacheWeakRowMemoTest {
    @FunctionalInterface private interface Work { void run() throws Throwable; }
    private void isolated(Work work) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { work.run(); } catch (Throwable error) { failure.set(error); } }, "jphp-weak-row-test");
        worker.setDaemon(true); worker.start(); worker.join(15000);
        assertFalse("isolated test timed out", worker.isAlive());
        if (failure.get() != null) throw failure.get();
    }
    private ClassEntity entity(String name) { ClassEntity result = new ClassEntity(new Context("")); result.setName(name); return result; }
    private Environment environment() { return new Environment(new CompileScope(), new ByteArrayOutputStream()); }

    @Test public void overriddenEnvironmentLookupStillRunsAfterEveryWarmCall() throws Throwable {
        isolated(() -> {
            class HookedEnvironment extends Environment {
                int lookups; boolean hidden;
                HookedEnvironment() { super(new CompileScope(), new ByteArrayOutputStream()); }
                @Override public CallCache.Row getCallCacheRow(CallCache<?> cache, boolean create) {
                    lookups++; return hidden ? null : super.getCallCacheRow(cache, create);
                }
            }
            HookedEnvironment env = new HookedEnvironment();
            try {
                ClassCallCache cache = new ClassCallCache(); ClassEntity value = entity("Hooked");
                cache.put(env, 0, value);
                for (int i = 0; i < 50; i++) assertSame(value, cache.get(env, 0));
                assertEquals(51, env.lookups);
                env.hidden = true;
                assertNull(cache.get(env, 0)); cache.put(env, 0, entity("Hidden"));
                assertEquals(53, env.lookups);
                env.hidden = false;
                assertSame(value, cache.get(env, 0));
            } finally { env.doFinal(); }
        });
    }

    @Test public void cacheSubclassHooksSurviveWarmReuse() throws Throwable {
        isolated(() -> {
            class HookedCache extends ClassCallCache {
                int gets, puts;
                @Override protected Item getItem(Environment env, int index) { gets++; return super.getItem(env, index); }
                @Override protected void putItem(Environment env, int index, Item item) { puts++; super.putItem(env, index, item); }
            }
            Environment env = environment();
            try {
                HookedCache cache = new HookedCache(); ClassEntity value = entity("Value");
                cache.put(env, 3, value);
                for (int i = 0; i < 50; i++) assertSame(value, cache.get(env, 3));
                assertEquals(50, cache.gets); assertEquals(1, cache.puts);
            } finally { env.doFinal(); }
        });
    }

    @Test public void retiringFirstEnvironmentDoesNotRetireTheOtherRows() throws Throwable {
        isolated(() -> {
            Environment first = environment(), second = environment();
            try {
                ClassCallCache cache = new ClassCallCache();
                ClassEntity a = entity("First"), b = entity("Second"), replacement = entity("Replacement");
                cache.put(first, 0, a); cache.put(second, 0, b);
                for (int i = 0; i < 50; i++) { assertSame(a, cache.get(first, 0)); assertSame(b, cache.get(second, 0)); }
                first.doFinal();
                assertFalse(first.isCallCacheActive()); assertNull(cache.get(first, 0));
                cache.put(first, 0, replacement); assertNull(first.getCallCacheRow(cache, false));
                assertTrue(second.isCallCacheActive()); assertSame(b, cache.get(second, 0));
                cache.put(second, 0, replacement); assertSame(replacement, cache.get(second, 0));
            } finally { second.doFinal(); first.doFinal(); }
        });
    }

    @Test public void finalizationStartBlocksWarmCacheBeforeFailureAndCompletion() throws Throwable {
        isolated(() -> {
            ClassCallCache cache = new ClassCallCache(); RuntimeException pending = new IllegalStateException("finalize");
            AtomicBoolean entered = new AtomicBoolean();
            Environment env = new Environment(new CompileScope(), new ByteArrayOutputStream()) {
                @Override public void finalizeObjects() {
                    entered.set(true); assertFalse(isCallCacheActive());
                    assertNull(cache.get(this, 0)); cache.put(this, 0, entity("Late"));
                    assertNull(getCallCacheRow(cache, false)); throw pending;
                }
            };
            try {
                ClassEntity value = entity("Cached"); cache.put(env, 0, value);
                assertSame(value, cache.get(env, 0));
                try { env.doFinal(); fail("finalization failure expected"); }
                catch (Throwable failure) { assertSame(pending, failure); }
                assertTrue(entered.get()); assertFalse(env.isCallCacheActive());
                assertNull(cache.get(env, 0)); cache.put(env, 0, value);
                assertNull(env.getCallCacheRow(cache, false));
            } finally { env.doFinal(); }
        });
    }

    @Test public void simultaneousEnvironmentsKeepTheirOwnEntitiesAtTheSameSite() throws Throwable {
        ClassCallCache cache = new ClassCallCache(); CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>(); Thread[] workers = new Thread[2];
        for (int index = 0; index < workers.length; index++) {
            final int label = index;
            workers[index] = new Thread(() -> {
                Environment env = environment();
                try {
                    ClassEntity value = entity("Concurrent" + label); cache.put(env, 0, value);
                    start.await();
                    for (int i = 0; i < 2000; i++) { cache.put(env, 0, value); assertSame(value, cache.get(env, 0)); }
                } catch (Throwable error) { failed.compareAndSet(null, error); }
                finally { try { env.doFinal(); } catch (Throwable error) { failed.compareAndSet(null, error); } }
            }, "jphp-weak-row-concurrent-" + index);
            workers[index].setDaemon(true); workers[index].start();
        }
        start.countDown();
        for (Thread worker : workers) { worker.join(10000); assertFalse("concurrent test timed out", worker.isAlive()); }
        if (failed.get() != null) throw failed.get();
    }
}
