package scripting;

import org.develnext.jphp.scripting.JPHPScriptEngine;
import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.env.handler.ShutdownHandler;
import php.runtime.ext.support.Extension;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.launcher.Launcher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real Launcher/Extension/Environment cleanup; no GC or substituted engine/provider. */
public class EngineInitializationLifecycleTest {
    @FunctionalInterface private interface Action {void run() throws Throwable;}
    private static final ThreadLocal<Probe> CURRENT=new ThreadLocal<>();
    private static final class Probe {
        Environment loaded; int cleanups; Error cleanupFailure;
    }
    public static final class LoadExtension extends Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){}
        public void onLoad(Environment env){
            Probe probe=Objects.requireNonNull(CURRENT.get());probe.loaded=env;
            env.registerShutdownFunction(new ShutdownHandler(null,new php.runtime.Memory[0]){
                public void call(){assertSame(env,Environment.current());probe.cleanups++;
                    if(probe.cleanupFailure!=null)throw probe.cleanupFailure;}
            });
        }
    }
    public static final class RegisterFailureExtension extends Extension {
        static Error primary;
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){throw primary;}
    }
    private void external(String config,Action action)throws Throwable {
        String previous=System.getProperty("jphp.config");
        try{System.setProperty("jphp.config",config);action.run();}
        finally{if(previous==null)System.clearProperty("jphp.config");else System.setProperty("jphp.config",previous);}
    }
    private void configured(String source,String extension,Action action)throws Throwable {
        Path php=Files.createTempFile("jphp-lifecycle-bootstrap-",".php"),config=Files.createTempFile("jphp-lifecycle-",".properties");
        try{
            Files.writeString(php,source,StandardCharsets.UTF_8);
            Properties settings=new Properties();settings.setProperty("bootstrap.file",php.toString());settings.setProperty("env.doFinal","0");
            if(extension!=null)settings.setProperty("env.extensions","spl,"+extension);
            try(var out=Files.newOutputStream(config)){settings.store(out,"owned lifecycle fixture");}
            external(config.toString(),action);
        }finally{Files.deleteIfExists(php);Files.deleteIfExists(config);}
    }
    private void priorBinding(Action action)throws Throwable {
        var prior=new Environment(new CompileScope(),new ByteArrayOutputStream());
        var handler=Thread.currentThread().getUncaughtExceptionHandler();
        try{action.run();assertSame(prior,Environment.current());assertSame(handler,Thread.currentThread().getUncaughtExceptionHandler());}
        finally{prior.doFinal();}
    }
    private BaseBaseException failure() {
        var exception=assertThrows(IllegalStateException.class,JPHPScriptEngine::new);
        assertEquals("Unable to initialize the JPHP script engine",exception.getMessage());
        assertTrue(exception.getCause() instanceof BaseBaseException);
        var primary=(BaseBaseException)exception.getCause();
        assertEquals("RuntimeException",primary.getReflection().getName());
        assertEquals("actual failed initialization",primary.getMessage(null).toString());return primary;
    }
    private Set<Thread> hooks()throws Exception {
        Class<?> type=Class.forName("java.lang.ApplicationShutdownHooks");
        var field=type.getDeclaredField("hooks");field.setAccessible(true);
        synchronized(type){return new HashSet<>(((Map<Thread,Thread>)field.get(null)).keySet());}
    }
    private Environment environment(JPHPScriptEngine engine)throws Exception {
        var field=JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);return (Environment)field.get(engine);
    }
    @Test public void failedBootstrapFinalizesActualEnvironmentOnceAndRestoresBinding()throws Throwable {
        Probe probe=new Probe();CURRENT.set(probe);
        try{priorBinding(()->configured("<?php throw new \\RuntimeException('actual failed initialization');",LoadExtension.class.getName(),()->{
            BaseBaseException primary=failure();assertNotNull(probe.loaded);assertSame(probe.loaded,primary.getEnvironment());
            assertEquals(1,probe.cleanups);assertEquals(0,primary.getSuppressed().length);
            assertThrows(IllegalStateException.class,probe.loaded::bindCurrentThread);
            probe.loaded.doFinal();assertEquals(1,probe.cleanups);
        }));}finally{CURRENT.remove();if(probe.loaded!=null)probe.loaded.doFinal();}
    }
    @Test public void cleanupErrorIsSuppressedOnExactPrimaryAndBindingIsRestored()throws Throwable {
        Probe probe=new Probe();probe.cleanupFailure=new AssertionError("actual cleanup Error");CURRENT.set(probe);
        try{priorBinding(()->configured("<?php throw new \\RuntimeException('actual failed initialization');",LoadExtension.class.getName(),()->{
            BaseBaseException primary=failure();assertSame(probe.loaded,primary.getEnvironment());
            assertArrayEquals(new Throwable[]{probe.cleanupFailure},primary.getSuppressed());assertEquals(1,probe.cleanups);
            assertThrows(IllegalStateException.class,probe.loaded::bindCurrentThread);
            probe.loaded.doFinal();assertEquals(1,probe.cleanups);
        }));}finally{CURRENT.remove();if(probe.loaded!=null)probe.loaded.doFinal();}
    }
    @Test public void failuresBeforeEnvironmentCreationPreserveErrorAndCallerBinding()throws Throwable {
        priorBinding(()->{
            Path missing=Files.createTempFile("jphp-lifecycle-missing-",".properties");Files.delete(missing);
            external(missing.toString(),()->assertTrue(assertThrows(IllegalStateException.class,JPHPScriptEngine::new).getCause().getCause() instanceof FileNotFoundException));
            Error primary=new AssertionError("actual extension Error before Environment");RegisterFailureExtension.primary=primary;
            try{configured("<?php return 0;",RegisterFailureExtension.class.getName(),()->assertSame(primary,assertThrows(AssertionError.class,JPHPScriptEngine::new)));}
            finally{RegisterFailureExtension.primary=null;}
        });
    }
    @Test public void validEmbeddingPreservesBootstrapAndRegistersNoCliShutdownHook()throws Throwable {
        priorBinding(()->configured("<?php function lifecycle_success(){return 43;}",null,()->{
            Set<Thread> before=hooks();JPHPScriptEngine engine=new JPHPScriptEngine();Environment actual=environment(engine);
            try{assertEquals(before,hooks());assertEquals("43",engine.compile("<?php return lifecycle_success();").eval().toString());}
            finally{actual.doFinal();}
            assertEquals(before,hooks());
        }));
    }
    @Test public void mandatoryLauncherRetainsItsActualOwnedCliShutdownHook()throws Throwable {
        priorBinding(()->configured("<?php return 0;",null,()->{
            Set<Thread> before=hooks();Launcher launcher=new Launcher();Set<Thread> owned=new HashSet<>();
            try{launcher.run(true);owned.addAll(hooks());owned.removeAll(before);assertEquals(1,owned.size());}
            finally{for(Thread hook:owned)assertTrue(Runtime.getRuntime().removeShutdownHook(hook));
                if(launcher.getEnvironment()!=null)launcher.getEnvironment().doFinal();}
            assertEquals(before,hooks());
        }));
    }
}
