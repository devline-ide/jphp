package php.runtime.output;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.exceptions.CriticalException;
import php.runtime.ext.support.Extension;
import php.runtime.lang.BaseObject;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual registered native PHP magic, with distinct output and callback Environments. */
public class PhpOutputEnvironmentProvenanceTest {
    @Reflection.Name("CrossEnvironmentOutputMagic") @Reflection.NativePhpSignature
    public static class CrossEnvironmentOutputMagic extends BaseObject {
        private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        private final CriticalException sentinel = new CriticalException("native-magic-sentinel");
        private boolean throwing;
        private int calls;
        public CrossEnvironmentOutputMagic(Environment env, ClassEntity entity) { super(env, entity); }
        @Reflection.Signature(result = @Reflection.Arg(type = HintType.STRING))
        public Memory __toString(Environment env, Memory... arguments) throws Throwable {
            assertSame(getEnvironment(), env); calls++; entered.countDown();
            assertTrue("Owned test did not release native magic", release.await(5, TimeUnit.SECONDS));
            env.echo("i");
            if (throwing) throw sentinel;
            return StringMemory.valueOf("v");
        }
    }
    private static class NativeProvider extends Extension {
        @Override public String getName() { return "output-environment-provenance-native-control"; }
        @Override public Status getStatus() { return Status.EXPERIMENTAL; }
        @Override public void onRegister(CompileScope scope) { registerClass(scope, CrossEnvironmentOutputMagic.class); }
    }
    private interface Action { void run() throws Throwable; }
    private static Thread start(String name, Action action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } }, name);
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
    private static void join(Thread thread) throws Exception {
        if (thread == null) return;
        thread.join(5000); assertFalse("Owned fixture thread remains live", thread.isAlive());
    }
    private static boolean flag(Environment env, String name) throws Exception {
        Field field = Environment.class.getDeclaredField(name); field.setAccessible(true); return field.getBoolean(env);
    }
    private static void waiting(Thread thread) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < limit) Thread.sleep(1);
        assertEquals("Unrelated retirement waits at real domain admission", Thread.State.WAITING, thread.getState());
    }
    private static void exercise(boolean throwing) throws Throwable {
        CompileScope scope = new CompileScope(); scope.registerExtension(new NativeProvider());
        ByteArrayOutputStream outA = new ByteArrayOutputStream(), outB = new ByteArrayOutputStream();
        Environment a = new Environment(scope, outA), b = new Environment(scope, outB);
        Environment c = new Environment(scope, new ByteArrayOutputStream());
        a.getDefaultBuffer().setImplicitFlush(true); b.getDefaultBuffer().setImplicitFlush(true);
        ClassEntity entity = b.fetchClass(CrossEnvironmentOutputMagic.class);
        assertNotNull(entity); assertNotNull(entity.methodMagicToString);
        CrossEnvironmentOutputMagic object = new CrossEnvironmentOutputMagic(b, entity); object.throwing = throwing;
        AtomicInteger shutdownB = new AtomicInteger(), shutdownC = new AtomicInteger();
        b.registerShutdownFunction(new php.runtime.env.handler.ShutdownHandler(null, new Memory[0]) {
            @Override public void call() { shutdownB.incrementAndGet(); }
        });
        c.registerShutdownFunction(new php.runtime.env.handler.ShutdownHandler(null, new Memory[0]) {
            @Override public void call() { shutdownC.incrementAndGet(); }
        });
        AtomicReference<Throwable> echoFailure = new AtomicReference<>(), targetFailure = new AtomicReference<>(), otherFailure = new AtomicReference<>();
        CountDownLatch targetStarted = new CountDownLatch(1), targetDone = new CountDownLatch(1);
        Thread echo = null, target = null, unrelated = null;
        boolean targetFinishedBeforeRelease = false;
        try {
        try {
            echo = start("cross-env-magic", () -> a.getDefaultBuffer().write(new ObjectMemory(object)), echoFailure);
            await(object.entered);
            target = start("retire-active-magic-env-b", () -> {
                targetStarted.countDown();
                try { b.doFinal(); } finally { targetDone.countDown(); }
            }, targetFailure);
            await(targetStarted);
            targetFinishedBeforeRelease = targetDone.await(1, TimeUnit.SECONDS);
            // Run this control even on the rejecting predecessor: never leave its waiting finalizer stuck.
            unrelated = start("retire-unrelated-env-c", c::doFinal, otherFailure);
            waiting(unrelated);
            assertFalse(flag(b, "finalizationStarted")); assertFalse(flag(c, "finalizationStarted"));
            assertEquals(0, shutdownB.get()); assertEquals(0, shutdownC.get());
        } finally {
            object.release.countDown(); join(echo); join(target); join(unrelated);
        }
            assertTrue("Actual Env B native magic must reject foreign B retirement before body release", targetFinishedBeforeRelease);
            assertTrue(String.valueOf(targetFailure.get()), targetFailure.get() instanceof IllegalStateException);
            assertTrue(targetFailure.get().getMessage().contains("Foreign-family"));
            assertEquals(0, shutdownB.get()); assertNull(otherFailure.get()); assertEquals(1, shutdownC.get());
            if (throwing) assertSame(object.sentinel, echoFailure.get()); else assertNull(echoFailure.get());
            assertEquals(1, object.calls); assertEquals(0, a.getCallStackTop()); assertEquals(0, b.getCallStackTop());
            assertEquals(throwing ? "" : "v", outA.toString(StandardCharsets.UTF_8));
            assertEquals("i", outB.toString(StandardCharsets.UTF_8));
            a.echo("a"); b.echo("b");
            assertEquals(throwing ? "a" : "va", outA.toString(StandardCharsets.UTF_8));
            assertEquals("ib", outB.toString(StandardCharsets.UTF_8));
            try (var restored = scope.getClassLoader().getCallbackDomain().parkForHostLifecycle()) { }
        } finally { a.doFinal(); b.doFinal(); c.doFinal(); }
        assertEquals(1, shutdownB.get()); assertEquals(1, shutdownC.get());
    }
    @Test(timeout = 25000) public void crossEnvironmentNativeMagicRetainsActualTargetAndLetsUnrelatedRetirementFinish() throws Throwable { exercise(false); }
    @Test(timeout = 25000) public void throwingCrossEnvironmentNativeMagicReleasesActualTargetAndRestoresBothOutputs() throws Throwable { exercise(true); }
}
