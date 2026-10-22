package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.Callback;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.PhpFiber;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Source-only fail-first controls. Run one method per root-supervised JVM; no native parallel-host claim. */
public class PhpFiberOutputEmbeddingProtocolTest {
    private static Path ROOT;
    @BeforeClass public static void origins() throws Exception {
        ROOT = PhpPortableTestInputs.fixture("qa.output.protocol", "fiber-output-protocol");
        Path expected = PhpPortableTestInputs.runtimeOrigin();
        for (Class<?> type : List.of(PhpFiber.class, Environment.class, ConcurrentEnvironment.class, php.runtime.output.OutputBuffer.class))
            assertEquals(type.getName(), expected, Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug) {
        var scope = new CompileScope();
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());return scope;
    }
    private static Environment environment(boolean concurrent, boolean debug, OutputStream sink) {
        Environment env = concurrent ? new ConcurrentEnvironment(scope(debug), sink) : new Environment(scope(debug), sink);
        env.getDefaultBuffer().setImplicitFlush(true);return env;
    }
    private static php.runtime.reflection.ModuleEntity compile(Environment env, String source, boolean phb) throws Throwable {
        var context = new Context(source.startsWith("<?php") ? source.substring(5) : source);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (phb) {
            var bytes = new ByteArrayOutputStream();var codec = new ModuleDumper(context, env, true);
            codec.save(module, bytes);assertTrue(bytes.size() > 100);
            module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static PhpFiber fiber(Environment env, boolean phb, String body) throws Throwable {
        return (PhpFiber) compile(env, "return new Fiber(function(){" + body + "});", phb).include(env).toValue(ObjectMemory.class).value;
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void raise(Throwable failure) throws T { throw (T) failure; }

    /** Both monitors are checked BEFORE park, so r5 fails promptly rather than leaving a blocked writer. */
    private static final class ParkingSink extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Environment env;boolean first = true;
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}, 0, 1); }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            try {
                if (first && PhpFiber.current(env) != null) {
                    first = false;
                    assertFalse("sink callback retained Environment monitor", Thread.holdsLock(env));
                    assertFalse("sink callback retained root buffer monitor", Thread.holdsLock(env.getDefaultBuffer()));
                    PhpFiber.suspendFiber(env, new StringMemory("sink-park"));
                }
                bytes.write(data, offset, length);
            } catch (Throwable failure) { PhpFiberOutputEmbeddingProtocolTest.<RuntimeException>raise(failure); }
        }
    }
    @Test(timeout=60000) public void callbackCapableSinkMayParkWithoutRootOrHostMonitor() throws Throwable {
        for (boolean concurrent : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var sink = new ParkingSink();var env = environment(concurrent, debug, sink);sink.env = env;
            try {
                var fiber = fiber(env, phb, "echo 'A';return 17;");
                var stack = env.getCallStack();var buffers = env.getOutputBuffers();int flags = env.getErrorFlags();
                assertEquals("sink-park", fiber.start(env).toString());assertEquals(0, sink.bytes.size());
                fiber.resume(env, Memory.NULL);
                assertEquals("A", sink.bytes.toString(StandardCharsets.UTF_8));assertEquals(17, fiber.getReturn(env).toInteger());
                assertSame(stack, env.getCallStack());assertSame(buffers, env.getOutputBuffers());assertEquals(flags, env.getErrorFlags());
                assertTrue(env.getDefaultBuffer().isImplicitFlush());
            } finally { env.doFinal(); }
        }
    }
    /** Explicit external-host sink policy approved by root: reject the overlapping write before any B mutation. */
    @Test(timeout=60000) public void parkedHostSinkRejectsSameFamilyOverlapBeforeMutationAndRecovers() throws Throwable {
        for (boolean concurrent : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var sink = new ParkingSink();var env = environment(concurrent, debug, sink);sink.env = env;
            try {
                var fiber = fiber(env, phb, "echo 'A';return 17;");
                assertEquals("sink-park", fiber.start(env).toString());
                try { env.echo("B"); fail("Overlapping host-sink operation must fail before mutation"); }
                catch (IllegalStateException expected) { /* Host contract; not attributed to native PHP ob_start. */ }
                assertEquals(0, sink.bytes.size());
                fiber.resume(env, Memory.NULL);assertEquals("A", sink.bytes.toString(StandardCharsets.UTF_8));
                env.echo("B");assertEquals("AB", sink.bytes.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
    }
    @Test(timeout=30000) public void unrelatedConcurrentRootHandlersAreSerializedWithoutHoldingMonitors() throws Throwable {
        var out = new ByteArrayOutputStream();var env = environment(true, false, out);
        var entered = new CountDownLatch(1);var release = new CountDownLatch(1);var second = new CountDownLatch(1);var attempted = new CountDownLatch(1);
        var active = new AtomicInteger();var maximum = new AtomicInteger();var calls = new AtomicInteger();var failure = new AtomicReference<Throwable>();
        var payloads = Collections.synchronizedList(new ArrayList<String>());
        Memory callback = php.runtime.invoke.RunnableInvoker.make(env, new Callback<Memory, Memory[]>() {
            @Override public Memory call(Memory[] args) {
                int now = active.incrementAndGet();maximum.accumulateAndGet(now, Math::max);
                try {
                    assertFalse(Thread.holdsLock(env));assertFalse(Thread.holdsLock(env.getDefaultBuffer()));
                    payloads.add(args[0].toString());
                    if (calls.incrementAndGet() == 1) { entered.countDown();assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    else second.countDown();
                    return args[0];
                } catch (Throwable problem) { PhpFiberOutputEmbeddingProtocolTest.<RuntimeException>raise(problem);return Memory.NULL; }
                finally { active.decrementAndGet(); }
            }
        });
        env.getDefaultBuffer().setCallback(callback);
        Thread first = new Thread(() -> {try (var binding = env.bindCurrentThread()) {env.echo("A");} catch (Throwable problem) {failure.compareAndSet(null, problem);}}, "jphp-root-output-A");
        Thread other = new Thread(() -> {try (var binding = env.bindCurrentThread()) {attempted.countDown();env.echo("B");} catch (Throwable problem) {failure.compareAndSet(null, problem);}}, "jphp-root-output-B");
        first.setDaemon(true);other.setDaemon(true);
        try {
            first.start();assertTrue(entered.await(5, TimeUnit.SECONDS));other.start();assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertFalse("Second root callback overlapped the first", second.await(1, TimeUnit.SECONDS));
            release.countDown();first.join(5000);other.join(5000);
            assertFalse(first.isAlive());assertFalse(other.isAlive());assertNull(failure.get());
            assertEquals(1, maximum.get());assertEquals(List.of("A", "B"), payloads);assertEquals("AB", out.toString(StandardCharsets.UTF_8));
        } finally {
            release.countDown();first.join(5000);if (other.getState() != Thread.State.NEW) other.join(5000);
            env.getDefaultBuffer().setCallback(null);env.doFinal();
        }
    }
    private static void assertNoEnvironmentMonitorOwner(Environment env) {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        for (var info : bean.getThreadInfo(bean.getAllThreadIds(), true, false)) {
            if (info == null) continue;
            for (var monitor : info.getLockedMonitors())
                assertFalse("embedded finalizer retained Environment monitor across Fiber finally",
                        monitor.getIdentityHashCode() == System.identityHashCode(env)
                                && monitor.getClassName().equals(env.getClass().getName()));
        }
    }
    /** Direct supplied adapter, not a claim about native PHP parallel stream execution. */
    @Test(timeout=60000) public void mappedStreamOutputAdapterMayParkWithoutRootOrHostMonitor() throws Throwable {
        for (boolean concurrent : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var sink = new ParkingSink();var env = environment(concurrent, debug, sink);sink.env = env;
            var stream = new php.runtime.ext.core.classes.stream.MiscStream(env, (OutputStream) sink);
            env.getDefaultBuffer().setOutput(php.runtime.ext.core.classes.stream.Stream.getOutputStream(env, stream));
            try {
                var fiber = fiber(env, phb, "echo 'A';return 17;");
                assertEquals("sink-park", fiber.start(env).toString());assertEquals(0, sink.bytes.size());
                fiber.resume(env, Memory.NULL);assertEquals("A", sink.bytes.toString(StandardCharsets.UTF_8));
                assertEquals(17, fiber.getReturn(env).toInteger());
            } finally {env.doFinal();}
        }
    }
    @Test(timeout=60000) public void embeddingRetirementRunsFiberFinallyWithoutEnvironmentMonitor() throws Throwable {
        assertFalse("embedding entry must not hold the Environment monitor", java.lang.reflect.Modifier.isSynchronized(Environment.class.getMethod("doFinalForEmbedding").getModifiers()));
        for (boolean concurrent : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var env = environment(concurrent, debug, new ByteArrayOutputStream());var called = new AtomicInteger();
            env.getGlobals().refOfIndex("embedding_finally_probe").assign(php.runtime.invoke.RunnableInvoker.make(env, new Callback<Memory, Memory[]>() {
                @Override public Memory call(Memory[] args) {
                    assertNoEnvironmentMonitorOwner(env);
                    assertTrue(env.defineConstant("EMBEDDING_FINALLY_DONE", LongMemory.valueOf(1), true));called.incrementAndGet();return Memory.NULL;
                }
            }));
            try {
                var fiber = fiber(env, phb, "try{Fiber::suspend('park');}finally{($GLOBALS['embedding_finally_probe'])();}");
                assertEquals("park", fiber.start(env).toString());
                env.doFinalForEmbedding();assertEquals(1, called.get());assertEquals(1, env.findConstant("EMBEDDING_FINALLY_DONE").toInteger());
                assertEquals(Memory.TRUE, fiber.isTerminated(env));assertNull(PhpFiber.current(env));
            } finally { env.doFinal(); }
        }
    }
    @Test(timeout=60000) public void foreignEmbeddingRetirementPropagatesAdmissionLineage() throws Throwable {
        assertFalse("embedding entry must not hold the Environment monitor", java.lang.reflect.Modifier.isSynchronized(Environment.class.getMethod("doFinalForEmbedding").getModifiers()));
        for (boolean concurrent : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var env = environment(concurrent, debug, new ByteArrayOutputStream());var called = new AtomicInteger();var failure = new AtomicReference<Throwable>();
            env.getGlobals().refOfIndex("foreign_finally_probe").assign(php.runtime.invoke.RunnableInvoker.make(env, new Callback<Memory, Memory[]>() {
                @Override public Memory call(Memory[] args) {
                    assertNoEnvironmentMonitorOwner(env);assertTrue(env.defineConstant("FOREIGN_FINALLY_DONE", LongMemory.valueOf(1), true));
                    called.incrementAndGet();return Memory.NULL;
                }
            }));
            Thread finalizer = new Thread(() -> {
                try (var binding = env.bindCurrentThread()) {env.doFinalForEmbedding();}
                catch (Throwable problem) {failure.set(problem);}
            }, "jphp-foreign-embedding-finalizer");
            finalizer.setDaemon(true);
            try {
                var fiber = fiber(env, phb, "try{Fiber::suspend('park');}finally{($GLOBALS['foreign_finally_probe'])();}");
                assertEquals("park", fiber.start(env).toString());finalizer.start();finalizer.join(5000);
                assertFalse("retirement admission token was not inherited by the Fiber finally", finalizer.isAlive());assertNull(failure.get());
                assertEquals(1, called.get());assertEquals(Memory.TRUE, fiber.isTerminated(env));
            } finally {
                // The root runs each method in an isolated bounded JVM. Do not enter a second finalizer after a timeout.
                if (!finalizer.isAlive()) env.doFinal();
            }
        }
    }
    @Test public void sameCarrierHostSinkReentryIsRejectedBeforeRootMutation() throws Throwable {
        for (boolean concurrent : new boolean[]{false, true}) {
            var out = new ByteArrayOutputStream();var holder = new Environment[1];var first = new AtomicBoolean(true);var rejected = new AtomicBoolean();
            OutputStream sink = new OutputStream() {
                @Override public void write(int value) throws IOException {write(new byte[]{(byte)value}, 0, 1);}
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (first.getAndSet(false)) try {holder[0].echo("B");}
                    catch (IllegalStateException expected) {rejected.set(true);}
                    catch (Throwable problem) {PhpFiberOutputEmbeddingProtocolTest.<RuntimeException>raise(problem);}
                    out.write(bytes, offset, length);
                }
            };
            var env = environment(concurrent, false, sink);holder[0] = env;
            try {
                env.echo("A");assertTrue("callback write must reject before B changes pending bytes", rejected.get());
                assertEquals("A", out.toString(StandardCharsets.UTF_8));env.echo("B");assertEquals("AB", out.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();}
        }
    }
    @Test public void sinkFailureIdentityAndPendingBytesSurviveRetry() throws Throwable {
        var marker = new IOException("sink-primary");var failed = new AtomicBoolean();var out = new ByteArrayOutputStream();
        OutputStream sink = new OutputStream() {
            @Override public void write(int value) throws IOException {write(new byte[]{(byte)value}, 0, 1);}
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (!failed.getAndSet(true)) throw marker;out.write(bytes, offset, length);
            }
        };
        var env = environment(true, false, sink);
        try {
            try { env.echo("A");fail("sink must fail"); } catch (RuntimeException failure) {assertSame(marker, failure.getCause());}
            env.getDefaultBuffer().flush();env.echo("B");assertEquals("AB", out.toString(StandardCharsets.UTF_8));
        } finally {env.doFinal();}
    }
    @Test(timeout=60000) public void ordinaryPhpOutputControlsRetainNativeBehaviorSourceAndRealPhb() throws Throwable {
        for (String name : List.of("ordinary-fiber-ordered-output", "ordinary-output-handler", "ordinary-handler-error-identity", "ordinary-handler-fiber-boundary", "ordinary-handler-parent-output"))
            for (boolean concurrent : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var out = new ByteArrayOutputStream();var env = environment(concurrent, debug, out);
                try {
                    compile(env, Files.readString(ROOT.resolve("native/" + name + ".php"), StandardCharsets.UTF_8), phb).include(env);env.flushAll();
                    assertEquals(name, Files.readString(ROOT.resolve("native/" + name + ".stdout"), StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
                } finally {env.doFinal();}
            }
    }


    @org.junit.AfterClass public static void releasePortableFixtures() throws Exception {
        Path owned = ROOT;ROOT = null;PhpPortableTestInputs.release(owned);
    }
}
