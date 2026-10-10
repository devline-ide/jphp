package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.*;
import php.runtime.env.handler.ShutdownHandler;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.handler.ExceptionHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.CustomErrorException;
import php.runtime.exceptions.FinallyException;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.ext.core.classes.*;
import php.runtime.ext.support.Extension;
import php.runtime.invoke.Invoker;
import php.runtime.lang.UncaughtException;
import php.runtime.lang.BaseException;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ParameterEntity;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Isolated host threads: no global handlers, live GUI, or JUnit-thread context changes. */
public class EnvironmentThreadLifecycleTest {
    @FunctionalInterface private interface Work { void run() throws Throwable; }
    @FunctionalInterface private interface Call { Memory run() throws Throwable; }
    private CompileScope scope() {
        CompileScope scope = new CompileScope();
        scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
        return scope;
    }
    private Environment env() { return new Environment(scope(), new ByteArrayOutputStream()); }
    private void isolated(Work work) throws Throwable {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { work.run(); } catch (Throwable e) { failed.set(e); } }, "jphp-lifecycle-qa");
        thread.setDaemon(true); thread.start(); thread.join(10000);
        assertFalse("isolated thread did not finish", thread.isAlive());
        if (failed.get() != null) throw failed.get();
    }
    private Invoker invoker(Environment env, Call call) {
        return new Invoker(env, TraceInfo.UNKNOWN) {
            public ParameterEntity[] getParameters() { return new ParameterEntity[0]; }
            public String getName() { return "lifecycle"; }
            public int getArgumentCount() { return 0; }
            protected void pushCall(TraceInfo trace, Memory[] args) { }
            protected Memory invoke(Memory... args) throws Throwable { return call.run(); }
            public int canAccess(Environment target) { return 0; }
        };
    }
    private Memory callable(Environment env, Call call) { return new ObjectMemory(new WrapInvoker(env, invoker(env, call))); }
    private Memory wrapped(Environment caller, Environment target) { return new ObjectMemory(new WrapEnvironment(caller, target)); }
    private Memory result(Environment caller, Memory future) throws Throwable {
        return future.toObject(WrapFuture.class).get(caller, LongMemory.valueOf(5000));
    }
    private void futureFailure(Environment caller, Memory future, Throwable expected) throws Throwable {
        try { result(caller, future); fail("Future must preserve the host failure"); }
        catch (ExecutionException e) { assertSame(expected, e.getCause()); }
    }

    @Test public void testConstructorFinalizationAndHostFailures() throws Throwable {
        isolated(() -> {
            Thread thread = Thread.currentThread(); AtomicReference<Throwable> seen = new AtomicReference<>();
            Thread.UncaughtExceptionHandler host = (t, e) -> seen.set(e); thread.setUncaughtExceptionHandler(host);
            Environment env = env(); assertSame(env, Environment.current());
            env.doFinal(); assertNull(Environment.current()); assertSame(host, thread.getUncaughtExceptionHandler());
            for (Throwable failure : new Throwable[] {new Exception("host-exception"), new AssertionError("host-error")}) {
                Environment.catchThrowable(failure, env); assertSame(failure, seen.get());
            }
            try { env.bindCurrentThread(); fail("finalized environment cannot be rebound"); }
            catch (IllegalStateException expected) { }
            env.doFinal(); assertSame(host, thread.getUncaughtExceptionHandler());
        });
    }

    @Test public void testNestingOutOfOrderAndExternalHandlers() throws Throwable {
        isolated(() -> {
            Thread thread = Thread.currentThread(); Thread.UncaughtExceptionHandler host = (t, e) -> { };
            thread.setUncaughtExceptionHandler(host);
            Environment a = env(); Environment b = env();
            b.releaseThreadSupport(); assertSame(a, Environment.current());
            try (Environment.ThreadBinding first = a.bindCurrentThread()) {
                try (Environment.ThreadBinding second = b.bindCurrentThread()) { assertSame(b, Environment.current()); }
                assertSame(a, Environment.current());
            }
            // CURRENT's retired predecessor must not erase an independently installed handler.
            Thread.UncaughtExceptionHandler external = (t, e) -> { }; thread.setUncaughtExceptionHandler(external);
            Environment.ThreadBinding second = b.bindCurrentThread();
            a.doFinal(); assertSame(b, Environment.current()); second.close(); second.close();
            assertNull(Environment.current()); assertSame(external, thread.getUncaughtExceptionHandler());
            b.doFinal(); assertSame(external, thread.getUncaughtExceptionHandler());
        });
    }

    @Test public void testConstructorAndCopyInitializationFailures() throws Throwable {
        isolated(() -> {
            Environment parentEnv = env(); Thread thread = Thread.currentThread();
            Thread.UncaughtExceptionHandler before = thread.getUncaughtExceptionHandler();
            RuntimeException failed = new IllegalStateException("extension-load"); CompileScope scope = scope();
            scope.registerExtension(new Extension() {
                public Status getStatus() { return Status.STABLE; }
                public void onRegister(CompileScope s) { }
                public void onLoad(Environment e) { assertSame(parentEnv, Environment.current()); Environment.addThreadSupport(e); throw failed; }
            });
            try { new Environment(scope, new ByteArrayOutputStream()); fail("onLoad failure"); }
            catch (RuntimeException e) { assertSame(failed, e); }
            assertSame(parentEnv, Environment.current()); assertSame(before, thread.getUncaughtExceptionHandler());
            RuntimeException copyFailure = new IllegalStateException("copy-init");
            Thread.UncaughtExceptionHandler external = (t, e) -> { };
            ClassEntity failing = new ClassEntity(new Context("")) {
                @Override public void initEnvironment(Environment e) {
                    if (e != parentEnv) { thread.setUncaughtExceptionHandler(external); throw copyFailure; }
                }
            };
            failing.setName("LifecycleCopyFailure"); parentEnv.registerClass(failing);
            try { new Environment(parentEnv); fail("copy initialization failure"); }
            catch (RuntimeException e) { assertSame(copyFailure, e); }
            assertSame(parentEnv, Environment.current()); assertSame(external, thread.getUncaughtExceptionHandler());
            parentEnv.doFinal(); assertSame(external, thread.getUncaughtExceptionHandler());
        });
    }

    @Test public void testFinalizationFailureAndForeignRetirement() throws Throwable {
        isolated(() -> {
            Thread thread = Thread.currentThread(); Thread.UncaughtExceptionHandler host = (t, e) -> { };
            thread.setUncaughtExceptionHandler(host); Environment parent = env();
            RuntimeException failure = new IllegalStateException("shutdown"); AtomicInteger calls = new AtomicInteger();
            Environment child = new Environment(parent);
            child.registerShutdownFunction(new ShutdownHandler(invoker(child, () -> {
                assertSame(child, Environment.current()); calls.incrementAndGet(); throw failure;
            }), new Memory[0]));
            try { child.doFinal(); fail("shutdown failure"); } catch (Throwable e) { assertSame(failure, e); }
            assertSame(parent, Environment.current()); child.doFinal(); assertEquals(1, calls.get());
            Environment wrappedFailure = new Environment(parent);
            RuntimeException reporter = new IllegalStateException("shutdown-reporter");
            wrappedFailure.setExceptionHandler(new ExceptionHandler(null, null) {
                @Override public boolean onException(Environment e, BaseBaseException exception) { throw reporter; }
            });
            Throwable primary = new UncaughtException(new BaseException(wrappedFailure));
            wrappedFailure.registerShutdownFunction(new ShutdownHandler(invoker(wrappedFailure, () -> { throw primary; }), new Memory[0]));
            try { wrappedFailure.doFinal(); fail("wrapped shutdown failure"); }
            catch (Throwable e) { assertSame(primary, e); assertSame(reporter, e.getSuppressed()[0].getCause()); }
            assertSame(parent, Environment.current());
            IOException flushFailure = new IOException("shutdown-flush");
            Environment failedFlush = new Environment(scope(), new OutputStream() {
                @Override public void write(int value) throws IOException { throw flushFailure; }
            });
            failedFlush.echo("pending-shutdown-output");
            try { failedFlush.doFinal(); fail("output flush failure"); }
            catch (Throwable e) { assertSame(flushFailure, e); }
            assertSame(parent, Environment.current());
            Environment shared = env(); shared.releaseThreadSupport();
            Thread registered = new Thread(); registered.setUncaughtExceptionHandler(host);
            Environment.addThreadSupport(registered, shared);
            Thread.UncaughtExceptionHandler installed = registered.getUncaughtExceptionHandler();
            Environment.addThreadSupport(registered, shared); assertSame(installed, registered.getUncaughtExceptionHandler());
            AtomicReference<Environment.ThreadBinding> workerBinding = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), retired = new CountDownLatch(1);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                Thread.UncaughtExceptionHandler workerHost = (t, e) -> { }; Thread.currentThread().setUncaughtExceptionHandler(workerHost);
                try (Environment.ThreadBinding binding = shared.bindCurrentThread()) {
                    assertSame(shared, Environment.current()); workerBinding.set(binding); entered.countDown();
                    assertTrue(retired.await(5, TimeUnit.SECONDS));
                    assertNull(Environment.current()); assertSame(workerHost, Thread.currentThread().getUncaughtExceptionHandler());
                } catch (Throwable e) { workerFailure.set(e); }
            });
            worker.setDaemon(true); worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { workerBinding.get().close(); fail("only owner may close execution binding"); } catch (IllegalStateException expected) { }
            shared.doFinal(); assertSame(host, registered.getUncaughtExceptionHandler()); retired.countDown();
            worker.join(5000); assertFalse(worker.isAlive()); assertNull(workerFailure.get());
            assertSame(parent, Environment.current()); parent.doFinal(); assertSame(host, thread.getUncaughtExceptionHandler());
        });
    }

    @Test public void testPhpReportFlushAndReporterFailureIdentity() throws Throwable {
        isolated(() -> {
            Thread thread = Thread.currentThread(); AtomicReference<Throwable> seen = new AtomicReference<>();
            Thread.UncaughtExceptionHandler host = (t, e) -> seen.set(e); thread.setUncaughtExceptionHandler(host);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); Environment env = new Environment(scope(), bytes);
            AtomicInteger reports = new AtomicInteger(); RuntimeException reporterFailure = new IllegalStateException("reporter");
            env.setErrorReportHandler(new ErrorReportHandler() {
                public boolean onError(SystemMessage message) { return false; }
                public boolean onFatal(ErrorException error) {
                    reports.incrementAndGet(); env.echo("reported");
                    if (error.getMessage().equals("fail-report")) throw reporterFailure;
                    return true;
                }
            });
            Environment.catchThrowable(new CustomErrorException(ErrorType.E_ERROR, "php-report", TraceInfo.UNKNOWN), env);
            assertTrue(bytes.toString("UTF-8").contains("reported")); assertEquals(1, reports.get()); assertNull(seen.get());
            Environment.catchThrowable(new FinallyException(), env); assertEquals(1, reports.get()); assertNull(seen.get());
            Environment.catchThrowable(new UncaughtException(new FinallyException()), env); assertEquals(1, reports.get()); assertNull(seen.get());
            Throwable primary = new CustomErrorException(ErrorType.E_ERROR, "fail-report", TraceInfo.UNKNOWN);
            int beforeFailedReport = bytes.size();
            thread.getUncaughtExceptionHandler().uncaughtException(thread, primary);
            assertTrue("reporter output must flush even when reporting fails", bytes.size() > beforeFailedReport);
            assertSame(primary, seen.get()); assertSame(reporterFailure, primary.getSuppressed()[0]);
            assertNull(Environment.current()); assertSame(host, thread.getUncaughtExceptionHandler()); env.doFinal();
            IOException flushFailure = new IOException("report-flush");
            Environment brokenOutput = new Environment(scope(), new OutputStream() {
                @Override public void write(int value) throws IOException { throw flushFailure; }
            });
            brokenOutput.setErrorReportHandler(new ErrorReportHandler() {
                public boolean onError(SystemMessage message) { return false; }
                public boolean onFatal(ErrorException error) { brokenOutput.echo("prefix"); throw reporterFailure; }
            });
            Throwable withFlushFailure = new CustomErrorException(ErrorType.E_ERROR, "fail-report", TraceInfo.UNKNOWN);
            Environment.catchThrowable(withFlushFailure, brokenOutput);
            assertSame(withFlushFailure, seen.get()); assertSame(reporterFailure, withFlushFailure.getSuppressed()[0]);
            assertSame(flushFailure, reporterFailure.getSuppressed()[0]);
            try { brokenOutput.doFinal(); fail("final output failure"); } catch (Throwable e) { assertSame(flushFailure, e); }
            assertNull(Environment.current()); assertSame(host, thread.getUncaughtExceptionHandler());
        });
    }

    @Test public void testWrapEnvironmentExecutionAndMessagesRestoreCaller() throws Throwable {
        isolated(() -> {
            Environment caller = env(); Thread.UncaughtExceptionHandler before = Thread.currentThread().getUncaughtExceptionHandler();
            WrapEnvironment wrapper = new WrapEnvironment(caller, caller.fetchClass("php\\lang\\Environment"));
            wrapper.__construct(caller, Memory.NULL, LongMemory.valueOf(0));
            Environment target = wrapper.getWrapEnvironment(); assertSame(caller, Environment.current());
            assertSame(before, Thread.currentThread().getUncaughtExceptionHandler());
            Memory call = callable(target, () -> { assertSame(target, Environment.current()); return LongMemory.valueOf(42); });
            assertEquals(42, wrapper.execute(caller, call).toLong()); assertSame(caller, Environment.current());
            wrapper.onMessage(caller, call); assertEquals(42, wrapper.sendMessage(caller, Memory.NULL).toLong());
            RuntimeException failure = new IllegalStateException("execute");
            try { wrapper.execute(caller, callable(target, () -> { throw failure; })); fail("execute failure"); }
            catch (Throwable e) { assertSame(failure, e); }
            assertSame(caller, Environment.current()); assertSame(before, Thread.currentThread().getUncaughtExceptionHandler());
            target.doFinal(); caller.doFinal();
        });
    }

    @Test public void testWrapThreadRestoresHostAndPreservesJavaFailures() throws Throwable {
        isolated(() -> {
            Environment caller = env(), target = env(); target.releaseThreadSupport();
            Thread.UncaughtExceptionHandler before = Thread.currentThread().getUncaughtExceptionHandler();
            new WrapThread(caller, Thread.currentThread()); assertSame(before, Thread.currentThread().getUncaughtExceptionHandler());
            AtomicInteger calls = new AtomicInteger();
            WrapThread success = new WrapThread(caller, caller.fetchClass("php\\lang\\Thread"));
            success.__construct(caller, callable(target, () -> { assertSame(target, Environment.current()); calls.incrementAndGet(); return Memory.NULL; }), wrapped(caller, target), Memory.NULL);
            success.getThread().run(); assertSame(caller, Environment.current());
            success.getThread().setDaemon(true); success.getThread().start(); success.getThread().join(5000); assertFalse(success.getThread().isAlive()); assertEquals(2, calls.get());
            for (Throwable failure : new Throwable[] {new Exception("thread-exception"), new AssertionError("thread-error")}) {
                AtomicReference<Throwable> seen = new AtomicReference<>(); WrapThread failed = new WrapThread(caller, caller.fetchClass("php\\lang\\Thread"));
                failed.__construct(caller, callable(target, () -> { throw failure; }), wrapped(caller, target), Memory.NULL);
                // Keep the host sentinel outside the environment binding for actual JVM uncaught dispatch.
                failed.getThread().setUncaughtExceptionHandler((t, e) -> seen.set(e));
                failed.getThread().setDaemon(true); failed.getThread().start(); failed.getThread().join(5000); assertSame(failure, seen.get());
            }
            assertSame(before, Thread.currentThread().getUncaughtExceptionHandler()); target.doFinal(); caller.doFinal();
        });
    }

    @Test public void testThreadPoolExecuteSubmitScheduleAndWorkerReuse() throws Throwable {
        isolated(() -> {
            Environment caller = env(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Environment target = new Environment(scope(), bytes); target.releaseThreadSupport();
            Thread.UncaughtExceptionHandler host = (t, e) -> { }; ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jphp-lifecycle-pool"); t.setUncaughtExceptionHandler(host); t.setDaemon(true); return t;
            });
            WrapThreadPool pool = new WrapThreadPool(caller, service);
            try {
                Memory custom = wrapped(caller, target); CountDownLatch executed = new CountDownLatch(1);
                pool.execute(caller, callable(target, () -> { assertSame(target, Environment.current()); executed.countDown(); return Memory.NULL; }), custom);
                assertTrue(executed.await(5, TimeUnit.SECONDS));
                Call check = () -> { assertSame(target, Environment.current()); return LongMemory.valueOf(7); };
                assertEquals(7, result(caller, pool.submit(caller, callable(target, check), custom)).toLong());
                assertEquals(7, result(caller, pool.schedule(caller, callable(target, check), LongMemory.valueOf(0), custom)).toLong());
                assertTrue(service.submit(() -> Environment.current() == null && Thread.currentThread().getUncaughtExceptionHandler() == host).get(5, TimeUnit.SECONDS));
                for (Throwable failure : new Throwable[] {new Exception("future-exception"), new AssertionError("future-error")}) {
                    futureFailure(caller, pool.submit(caller, callable(target, () -> { throw failure; }), custom), failure);
                }
                target.setErrorReportHandler(new ErrorReportHandler() {
                    public boolean onError(SystemMessage message) { return false; }
                    public boolean onFatal(ErrorException error) { target.echo("pool-php"); return true; }
                });
                assertTrue(result(caller, pool.submit(caller, callable(target, () -> {
                    throw new CustomErrorException(ErrorType.E_ERROR, "pool-php", TraceInfo.UNKNOWN);
                }), custom)).isNull());
                assertTrue(bytes.toString("UTF-8").contains("pool-php"));
                RuntimeException reporter = new IllegalStateException("pool-reporter");
                target.setErrorReportHandler(new ErrorReportHandler() {
                    public boolean onError(SystemMessage message) { return false; }
                    public boolean onFatal(ErrorException error) { throw reporter; }
                });
                Throwable primary = new CustomErrorException(ErrorType.E_ERROR, "primary", TraceInfo.UNKNOWN);
                futureFailure(caller, pool.submit(caller, callable(target, () -> { throw primary; }), custom), primary);
                assertSame(reporter, primary.getSuppressed()[0]);
                assertTrue(service.submit(() -> Environment.current() == null && Thread.currentThread().getUncaughtExceptionHandler() == host).get(5, TimeUnit.SECONDS));
            } finally {
                pool.shutdown(caller); assertTrue(service.awaitTermination(5, TimeUnit.SECONDS)); target.doFinal(); caller.doFinal();
            }
        });
    }
}
