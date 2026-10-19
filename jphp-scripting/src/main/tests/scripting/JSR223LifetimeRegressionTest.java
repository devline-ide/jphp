package scripting;

import org.develnext.jphp.scripting.JPHPScriptEngine;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.DieException;
import php.runtime.env.Environment;
import php.runtime.env.handler.ShutdownHandler;
import php.runtime.lang.exception.BaseBaseException;
import javax.script.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real retained Environment lifecycle controls; child-JVM execution required for PHP exit. */
public class JSR223LifetimeRegressionTest {
    @FunctionalInterface private interface Action {
        void run(JPHPScriptEngine engine, Environment environment) throws Throwable;
    }
    private static void close(JPHPScriptEngine engine) throws Exception {
        ((AutoCloseable) engine).close();
    }
    private void fixture(Action action) throws Throwable {
        String previous = System.getProperty("jphp.config");
        Path bootstrap = Files.createTempFile("jphp-lifetime-bootstrap-", ".php");
        Path config = Files.createTempFile("jphp-lifetime-config-", ".properties");
        JPHPScriptEngine engine = null;
        Throwable primary = null;
        try {
            Files.writeString(bootstrap, "<?php return 0;", StandardCharsets.UTF_8);
            Properties properties = new Properties();
            properties.setProperty("bootstrap.file", bootstrap.toString());
            properties.setProperty("env.doFinal", "0");
            properties.setProperty("env.extensions", "spl");
            try (OutputStream out = Files.newOutputStream(config)) {
                properties.store(out, "owned JSR223 lifetime fixture");
            }
            System.setProperty("jphp.config", config.toString());
            engine = new JPHPScriptEngine();
            var field = JPHPScriptEngine.class.getDeclaredField("environment");
            field.setAccessible(true);
            action.run(engine, (Environment) field.get(engine));
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                if (engine != null) close(engine);
            } catch (Throwable cleanup) {
                if (primary == null) throw cleanup;
                if (primary != cleanup) primary.addSuppressed(cleanup);
            } finally {
                if (previous == null) System.clearProperty("jphp.config");
                else System.setProperty("jphp.config", previous);
                Files.deleteIfExists(config);
                Files.deleteIfExists(bootstrap);
            }
        }
    }

    @Test public void closeFinalizesOnceAndRejectsPreviouslyCompiledCode() throws Throwable {
        fixture((engine, environment) -> {
            AtomicInteger shutdowns = new AtomicInteger();
            environment.registerShutdownFunction(new ShutdownHandler(null, new Memory[0]) {
                @Override public void call() { shutdowns.incrementAndGet(); }
            });
            CompiledScript script = engine.compile("<?php return 41;");
            assertEquals("41", script.eval().toString());
            assertEquals("7", engine.eval("<?php return 7;").toString());
            assertEquals(0, shutdowns.get());
            close(engine);
            assertEquals(1, shutdowns.get());
            close(engine);
            assertEquals(1, shutdowns.get());
            ScriptException compile = assertThrows(ScriptException.class,
                    () -> engine.compile("<?php return 9;"));
            assertEquals("JPHP script engine is closed", compile.getMessage());
            ScriptException eval = assertThrows(ScriptException.class, script::eval);
            assertEquals("JPHP script engine is closed", eval.getMessage());
        });
    }

    @Test public void phpExitRetainsDieExceptionAndNextEvalWorks() throws Throwable {
        fixture((engine, environment) -> {
            ScriptException failure = assertThrows(ScriptException.class,
                    () -> engine.eval("<?php exit(37);"));
            assertEquals(DieException.class, failure.getCause().getClass());
            assertEquals(37, ((DieException) failure.getCause()).getExitCode());
            assertEquals("7", engine.eval("<?php return 7;").toString());
        });
    }

    @Test public void evalRestoresDifferentCallerAndUncaughtHandler() throws Throwable {
        fixture((engine, environment) -> {
            Environment caller = new Environment(environment.scope, new ByteArrayOutputStream());
            try {
                try (Environment.ThreadBinding ignored = caller.bindCurrentThread()) {
                    Thread.UncaughtExceptionHandler handler = Thread.currentThread().getUncaughtExceptionHandler();
                    assertSame(caller, Environment.current());
                    assertEquals("7", engine.eval("<?php return 7;").toString());
                    assertSame(caller, Environment.current());
                    assertSame(handler, Thread.currentThread().getUncaughtExceptionHandler());
                    ScriptException failure = assertThrows(ScriptException.class,
                            () -> engine.eval("<?php throw new \\RuntimeException('caller-primary');"));
                    assertTrue(failure.getCause() instanceof BaseBaseException);
                    assertEquals("caller-primary", ((BaseBaseException) failure.getCause()).getMessage(environment).toString());
                    assertSame(caller, Environment.current());
                    assertSame(handler, Thread.currentThread().getUncaughtExceptionHandler());
                }
            } finally {
                try { caller.doFinal(); } finally { caller.releaseThreadSupport(); }
            }
        });
    }

    @Test public void closeRetainsPhpAndJavaShutdownPrimaryAndRestoresCaller() throws Throwable {
        for (boolean php : new boolean[] {false, true}) {
            fixture((engine, environment) -> {
                Throwable primary;
                if (php) {
                    ScriptException created = assertThrows(ScriptException.class,
                            () -> engine.eval("<?php throw new \\RuntimeException('shutdown-primary');"));
                    assertTrue(created.getCause() instanceof BaseBaseException);
                    primary = created.getCause();
                } else primary = new IOException("java-shutdown-primary");
                AtomicInteger shutdowns = new AtomicInteger();
                environment.registerShutdownFunction(new ShutdownHandler(null, new Memory[0]) {
                    @Override public void call() throws Throwable {
                        shutdowns.incrementAndGet();
                        throw primary;
                    }
                });
                Environment caller = new Environment(environment.scope, new ByteArrayOutputStream());
                try {
                    try (Environment.ThreadBinding ignored = caller.bindCurrentThread()) {
                        Thread.UncaughtExceptionHandler handler = Thread.currentThread().getUncaughtExceptionHandler();
                        ScriptException failure = assertThrows(ScriptException.class, () -> close(engine));
                        assertSame(primary, failure.getCause());
                        assertSame(caller, Environment.current());
                        assertSame(handler, Thread.currentThread().getUncaughtExceptionHandler());
                        assertEquals(1, shutdowns.get());
                        close(engine);
                        assertEquals(1, shutdowns.get());
                    }
                } finally {
                    try { caller.doFinal(); } finally { caller.releaseThreadSupport(); }
                }
            });
        }
    }

    @Test public void closeCapturesActualPhpShutdownExitWithoutTerminatingHost() throws Throwable {
        fixture((engine, environment) -> {
            engine.eval("<?php register_shutdown_function(function(){exit(37);}); return 1;");
            Environment caller=new Environment(environment.scope,new ByteArrayOutputStream());
            try {
                try(Environment.ThreadBinding ignored=caller.bindCurrentThread()) {
                    Thread.UncaughtExceptionHandler handler=Thread.currentThread().getUncaughtExceptionHandler();
                    ScriptException failure=assertThrows(ScriptException.class,()->close(engine));
                    assertEquals(DieException.class,failure.getCause().getClass());
                    assertEquals(37,((DieException)failure.getCause()).getExitCode());
                    assertSame(caller,Environment.current());
                    assertSame(handler,Thread.currentThread().getUncaughtExceptionHandler());
                    close(engine);
                    assertSame(caller,Environment.current());
                }
            }finally {try{caller.doFinal();}finally{caller.releaseThreadSupport();}}
        });
    }

    private static final class CleanupEnvironment extends Environment {
        final IOException finalizerFailure=new IOException("actual-finalizer-cleanup");
        final IOException handlerFailure=new IOException("actual-handler-cleanup");
        final IOException flushFailure=new IOException("actual-flush-cleanup");
        int dispatches,finalizers,releases,flushes;
        CleanupEnvironment(Environment original,OutputStream output){super(original.scope,output);}
        @Override public void doFinal() throws Throwable {dispatches++;super.doFinal();}
        @Override public void finalizeObjects() throws Throwable {finalizers++;super.finalizeObjects();throw finalizerFailure;}
        @Override public void releaseOwnedErrorHandlers() throws Throwable {
            releases++;super.releaseOwnedErrorHandlers();if(releases==1)throw handlerFailure;
        }
        @Override public void flushAll() throws Throwable {flushes++;super.flushAll();throw flushFailure;}
    }

    @Test public void closeKeepsVirtualFinalizationPrimarySuppressionFlushAndCaller() throws Throwable {
        for(boolean shutdownFailure:new boolean[]{false,true})fixture((engine,original)->{
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            CleanupEnvironment replacement=new CleanupEnvironment(original,output);
            try{original.doFinalForEmbedding();}finally{original.releaseThreadSupport();}
            var field=JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);field.set(engine,replacement);
            IOException shutdown=new IOException("actual-shutdown-primary");
            AtomicInteger shutdowns=new AtomicInteger();
            replacement.registerShutdownFunction(new ShutdownHandler(null,new Memory[0]) {
                @Override public void call() throws Throwable {shutdowns.incrementAndGet();if(shutdownFailure)throw shutdown;}
            });
            replacement.echo("close-buffer");
            Environment caller=new Environment(replacement.scope,new ByteArrayOutputStream());
            try{
                try(Environment.ThreadBinding ignored=caller.bindCurrentThread()) {
                    Thread.UncaughtExceptionHandler handler=Thread.currentThread().getUncaughtExceptionHandler();
                    ScriptException failure=assertThrows(ScriptException.class,()->close(engine));
                    Throwable primary=shutdownFailure ? shutdown : replacement.finalizerFailure;
                    assertSame(primary,failure.getCause());
                    Throwable[] expected=shutdownFailure ? new Throwable[]{replacement.finalizerFailure,replacement.handlerFailure,replacement.flushFailure}
                            : new Throwable[]{replacement.handlerFailure,replacement.flushFailure};
                    assertArrayEquals(expected,primary.getSuppressed());
                    assertEquals(1,replacement.dispatches);assertEquals(1,replacement.finalizers);
                    assertEquals(2,replacement.releases);assertEquals(1,replacement.flushes);assertEquals(1,shutdowns.get());
                    assertEquals("close-buffer",output.toString(StandardCharsets.UTF_8));
                    assertSame(caller,Environment.current());assertSame(handler,Thread.currentThread().getUncaughtExceptionHandler());
                    close(engine);assertEquals(1,replacement.dispatches);assertEquals(1,shutdowns.get());
                    assertSame(caller,Environment.current());assertSame(handler,Thread.currentThread().getUncaughtExceptionHandler());
                }
            }finally{try{caller.doFinal();}finally{caller.releaseThreadSupport();}}
        });
    }

    @Test public void failedBootstrapKeepsPhpPrimaryAndSuppressedShutdownExit() throws Throwable {
        String previous=System.getProperty("jphp.config");
        Path bootstrap=Files.createTempFile("jphp-failed-bootstrap-exit-",".php");
        Path config=Files.createTempFile("jphp-failed-bootstrap-exit-",".properties");
        Environment caller=new Environment(new php.runtime.env.CompileScope(),new ByteArrayOutputStream());
        try {
            Files.writeString(bootstrap,"<?php register_shutdown_function(function(){exit(37);}); throw new \\RuntimeException('bootstrap-primary');",StandardCharsets.UTF_8);
            Properties properties=new Properties();properties.setProperty("bootstrap.file",bootstrap.toString());
            properties.setProperty("env.doFinal","0");properties.setProperty("env.extensions","spl");
            try(OutputStream out=Files.newOutputStream(config)){properties.store(out,"owned failed-bootstrap shutdown-exit fixture");}
            System.setProperty("jphp.config",config.toString());
            try(Environment.ThreadBinding ignored=caller.bindCurrentThread()) {
                Thread.UncaughtExceptionHandler handler=Thread.currentThread().getUncaughtExceptionHandler();
                IllegalStateException failure=assertThrows(IllegalStateException.class,JPHPScriptEngine::new);
                assertEquals("Unable to initialize the JPHP script engine",failure.getMessage());
                assertTrue(failure.getCause() instanceof BaseBaseException);
                BaseBaseException primary=(BaseBaseException)failure.getCause();
                assertEquals("RuntimeException",primary.getReflection().getName());
                assertEquals("bootstrap-primary",primary.getMessage(null).toString());
                assertEquals(1,primary.getSuppressed().length);
                assertEquals(DieException.class,primary.getSuppressed()[0].getClass());
                assertEquals(37,((DieException)primary.getSuppressed()[0]).getExitCode());
                assertSame(caller,Environment.current());assertSame(handler,Thread.currentThread().getUncaughtExceptionHandler());
            }
        }finally {
            try{caller.doFinal();}finally{caller.releaseThreadSupport();
                if(previous==null)System.clearProperty("jphp.config");else System.setProperty("jphp.config",previous);
                Files.deleteIfExists(config);Files.deleteIfExists(bootstrap);}
        }
    }

    @Test public void idleExplicitSessionUsesItsActualFailureCompositionPolicy() throws Throwable {
        fixture((engine,original)->{
            CleanupEnvironment replacement=new CleanupEnvironment(original,new ByteArrayOutputStream());
            try{original.doFinalForEmbedding();}finally{original.releaseThreadSupport();}
            var field=JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);field.set(engine,replacement);
            IOException shutdown=new IOException("session-shutdown-primary");
            IOException composed=new IOException("chosen-by-actual-session-policy",shutdown);
            java.util.List<Throwable> pending=new java.util.ArrayList<>(),cleanups=new java.util.ArrayList<>();
            var domain=new php.runtime.PhpOwnershipDomain((primary,cleanup)->{
                pending.add(primary);cleanups.add(cleanup);composed.addSuppressed(cleanup);return composed;
            });
            var participant=domain.participant(replacement);
            var session=new php.runtime.PhpOwnershipExecution.Session(participant,replacement);
            replacement.registerShutdownFunction(new ShutdownHandler(null,new Memory[0]) {
                @Override public void call() throws Throwable{throw shutdown;}
            });
            try(var execution=session.enter()) {
                assertSame(session,php.runtime.PhpOwnershipExecution.current(replacement));
                assertNull(php.runtime.PhpOwnershipExecution.currentEntry(replacement));assertNull(replacement.getRuntimeOwnership());
                ScriptException failure=assertThrows(ScriptException.class,()->close(engine));
                assertSame(composed,failure.getCause());assertSame(shutdown,composed.getCause());
                assertArrayEquals(new Throwable[]{shutdown,composed,composed},pending.toArray(new Throwable[0]));
                assertArrayEquals(new Throwable[]{replacement.finalizerFailure,replacement.handlerFailure,replacement.flushFailure},cleanups.toArray(new Throwable[0]));
                assertArrayEquals(cleanups.toArray(new Throwable[0]),composed.getSuppressed());
                assertEquals(1,replacement.dispatches);assertEquals(1,replacement.finalizers);assertEquals(1,replacement.flushes);
            }finally{participant.close();}
            assertNull(php.runtime.PhpOwnershipExecution.current(replacement));
        });
    }
}
