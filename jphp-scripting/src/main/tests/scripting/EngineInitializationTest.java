package scripting;

import org.develnext.jphp.scripting.JPHPScriptEngine;
import org.develnext.jphp.scripting.JPHPScriptEngineFactory;
import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.ext.support.Extension;
import php.runtime.launcher.Launcher;
import php.runtime.launcher.LaunchException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import static org.junit.Assert.*;

/** Real Launcher initialization, not a provider/context bypass. Global config is restored per control. */
public class EngineInitializationTest {
    @FunctionalInterface private interface Action {void run() throws Throwable;}
    private void external(String path, Action body) throws Throwable {
        String prior=System.getProperty("jphp.config");
        try {if(path==null)System.clearProperty("jphp.config");else System.setProperty("jphp.config",path);body.run();}
        finally {if(prior==null)System.clearProperty("jphp.config");else System.setProperty("jphp.config",prior);}
    }
    private void configured(String name,String value,Action body)throws Throwable {
        Path file=Files.createTempFile("jphp-init-", ".properties");
        try {var p=new Properties();p.setProperty(name,value);p.setProperty("env.doFinal","0");
            try(var out=Files.newOutputStream(file)){p.store(out,"owned init fixture");}external(file.toString(),body);
        } finally {Files.deleteIfExists(file);}
    }
    private void clearFactory()throws Exception {
        var field=JPHPScriptEngineFactory.class.getDeclaredField("engines");field.setAccessible(true);
        ((ThreadLocal<?>)field.get(null)).remove();
    }
    private IllegalStateException engineFailure() {
        var failure=assertThrows(IllegalStateException.class,JPHPScriptEngine::new);
        assertEquals("Unable to initialize the JPHP script engine",failure.getMessage());assertNotNull(failure.getCause());return failure;
    }
    private static final class ResourceLauncher extends Launcher {
        final InputStream resource;
        ResourceLauncher(InputStream input){super("owned-init.conf",new String[0]);resource=input;}
        @Override public InputStream getResource(String name){return resource;}
        void read(){readConfig();}
    }
    public static final class BrokenExtension extends Extension {
        static Error failure;
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){throw failure;}
    }

    @Test public void defaultOptionalLauncherAndNormalEngineStillInitialize()throws Throwable {
        external(null,()->{var launcher=new Launcher();launcher.run(false);assertNotNull(launcher.getEnvironment());
            assertEquals("7",new JPHPScriptEngine().compile("<?php return 7;").eval().toString());});
    }
    @Test public void mandatoryDefaultBootstrapStillFails()throws Throwable {
        external(null,()->{var failure=assertThrows(LaunchException.class,()->new Launcher().run(true));
            assertTrue(failure.getMessage(),failure.getMessage().contains("res://JPHP-INF/.bootstrap.php"));});
    }
    @Test public void missingExternalConfigIsVisibleFromEngineAndFactory()throws Throwable {
        Path missing=Files.createTempFile("jphp-missing-config-", ".properties");Files.delete(missing);
        external(missing.toString(),()->{
            var failure=engineFailure();assertTrue(failure.getCause() instanceof LaunchException);
            assertTrue(failure.getCause().getCause() instanceof FileNotFoundException);
            assertEquals("Unable to load the config -Djphp.config="+missing,failure.getCause().getMessage());
            clearFactory();try {var factory=new JPHPScriptEngineFactory();
                assertThrows(IllegalStateException.class,factory::getScriptEngine);
                assertThrows(IllegalStateException.class,factory::getEngineName);
            } finally {clearFactory();}
        });
    }
    @Test public void missingExtensionAndActualExtensionErrorAreNotIgnored()throws Throwable {
        configured("env.extensions","scripting.AbsentInitializationExtension",()->{
            Throwable cause=engineFailure().getCause();boolean found=false;
            for(Throwable p=cause;p!=null;p=p.getCause())if(p instanceof ClassNotFoundException)found=true;
            assertTrue("Missing extension cause was hidden",found);
        });
        var original=new AssertionError("actual extension initialization Error");BrokenExtension.failure=original;
        try {configured("env.extensions",BrokenExtension.class.getName(),()->assertSame(original,assertThrows(AssertionError.class,JPHPScriptEngine::new)));}
        finally {BrokenExtension.failure=null;}
    }
    @Test public void invalidConfiguredClassLoaderIsVisible()throws Throwable {
        configured("env.classLoader","AbsentInitializationClassLoader",()->{
            Throwable cause=engineFailure().getCause();assertTrue(cause instanceof LaunchException);
            assertEquals("Class loader class is not found: AbsentInitializationClassLoader",cause.getMessage());
        });
    }
    @Test public void explicitMissingBootstrapIsNotOptional()throws Throwable {
        Path missing=Files.createTempFile("jphp-missing-bootstrap-", ".php");Files.delete(missing);
        configured("bootstrap.file",missing.toString(),()->{
            Throwable cause=engineFailure().getCause();assertTrue(cause instanceof LaunchException);
            assertTrue(cause.getMessage(),cause.getMessage().contains("bootstrap.file"));
        });
    }
    @Test public void configuredBootstrapStillExecutesActualPhp()throws Throwable {
        Path bootstrap=Files.createTempFile("jphp-valid-bootstrap-", ".php");
        try {Files.writeString(bootstrap,"<?php function initialization_probe(){return 37;}",StandardCharsets.UTF_8);
            configured("bootstrap.file",bootstrap.toString(),()->
                assertEquals("37",new JPHPScriptEngine().compile("<?php return initialization_probe();").eval().toString()));
        } finally {Files.deleteIfExists(bootstrap);}
    }
    @Test public void bootstrapGlobalsAndConfiguredEnvironmentRemainAttachedToEngine()throws Throwable {
        Path bootstrap=Files.createTempFile("jphp-bootstrap-environment-", ".php");
        try {
            Files.writeString(bootstrap,"<?php $initialization_global=41; function initialization_global_probe(){global $initialization_global;return $initialization_global;}",StandardCharsets.UTF_8);
            for(String concurrent:new String[]{"0","1"}) {
                Path config=Files.createTempFile("jphp-bootstrap-environment-", ".properties");
                try {
                    var properties=new Properties();properties.setProperty("bootstrap.file",bootstrap.toString());
                    properties.setProperty("env.concurrent",concurrent);properties.setProperty("env.doFinal","0");
                    try(var output=Files.newOutputStream(config)){properties.store(output,"owned bootstrap environment fixture");}
                    external(config.toString(),()->{
                        var engine=new JPHPScriptEngine();
                        var field=JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);
                        var actual=(php.runtime.env.Environment)field.get(engine);
                        assertEquals(concurrent.equals("1"),actual instanceof php.runtime.env.ConcurrentEnvironment);
                        var bindings=(JPHPScriptEngine.JPHPBindings)engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
                        var globalsField=bindings.getClass().getDeclaredField("globals");globalsField.setAccessible(true);
                        assertSame(actual.getGlobals(),globalsField.get(bindings));
                        assertEquals("41",engine.get("initialization_global").toString());
                        assertEquals("41",engine.compile("<?php return initialization_global_probe();").eval().toString());
                        engine.put("initialization_global",43);
                        assertEquals("43",engine.compile("<?php return initialization_global_probe();").eval().toString());
                    });
                } finally {Files.deleteIfExists(config);}
            }
        } finally {Files.deleteIfExists(bootstrap);}
    }
    @Test public void invalidExplicitPhpBootstrapFailsInitialization()throws Throwable {
        Path bootstrap=Files.createTempFile("jphp-invalid-bootstrap-", ".php");
        try {
            Files.writeString(bootstrap,"<?php function broken_bootstrap( {",StandardCharsets.UTF_8);
            configured("bootstrap.file",bootstrap.toString(),()->{
                Throwable cause=engineFailure().getCause();
                assertEquals(php.runtime.lang.exception.BaseParseError.class,cause.getClass());
                var actual=(php.runtime.lang.exception.BaseParseError)cause;
                assertEquals("ParseError",actual.getReflection().getName());
                assertEquals("Syntax error, unexpected '{'",actual.getMessage(null).toString());
                assertEquals(bootstrap.toAbsolutePath().normalize(),Path.of(actual.getTrace().getFileName()).toAbsolutePath().normalize());
                assertEquals(0,actual.getTrace().getStartLine());
                assertEquals(1L,actual.getLine(null).toLong());
            });
        } finally {Files.deleteIfExists(bootstrap);}
    }
    @Test public void explicitPhpRuntimeExceptionBootstrapFailsInitialization()throws Throwable {
        Path bootstrap=Files.createTempFile("jphp-throwing-bootstrap-", ".php");
        try {
            Files.writeString(bootstrap,"<?php throw new \\RuntimeException('actual bootstrap failure');",StandardCharsets.UTF_8);
            configured("bootstrap.file",bootstrap.toString(),()->{
                Throwable cause=engineFailure().getCause();
                assertTrue("Actual PHP failure was not propagated: "+cause,cause instanceof php.runtime.lang.exception.BaseBaseException);
                var actual=(php.runtime.lang.exception.BaseBaseException)cause;
                assertEquals("RuntimeException",actual.getReflection().getName());
                assertEquals("actual bootstrap failure",actual.getMessage(null).toString());
            });
        } finally {Files.deleteIfExists(bootstrap);}
    }
    @Test public void configResourceIsClosedOnSuccess()throws Throwable {
        external(null,()->{int[] closes={0};var input=new ByteArrayInputStream("env.phpProfile=PHP_8_5\n".getBytes(StandardCharsets.ISO_8859_1)){
            public void close()throws IOException{closes[0]++;super.close();}};
            var launcher=new ResourceLauncher(input);launcher.read();assertEquals(1,closes[0]);
            assertEquals("PHP_8_5",launcher.getCompileScope().getPhpLanguageProfile().name());});
    }
    @Test public void configResourceReadAndCloseFailuresPreserveExactCauseAndSuppression()throws Throwable {
        external(null,()->{
            for(boolean unchecked:new boolean[]{false,true}) {
                IOException primary=new IOException("actual config read");Throwable cleanup=unchecked?new IllegalStateException("actual config close"):new IOException("actual config close");int[] closes={0};
                var input=new InputStream(){public int read()throws IOException{throw primary;}
                    public void close()throws IOException{closes[0]++;if(cleanup instanceof IOException)throw (IOException)cleanup;throw (RuntimeException)cleanup;}};
                var failure=assertThrows(LaunchException.class,()->new ResourceLauncher(input).read());
                assertEquals(primary.getMessage(),failure.getMessage());assertSame(primary,failure.getCause());
                assertArrayEquals(new Throwable[]{cleanup},primary.getSuppressed());assertEquals(1,closes[0]);
            }
            IOException cleanup=new IOException("actual config close only");int[] closes={0};
            var input=new ByteArrayInputStream(new byte[0]){public void close()throws IOException{closes[0]++;throw cleanup;}};
            var failure=assertThrows(LaunchException.class,()->new ResourceLauncher(input).read());
            assertEquals(cleanup.getMessage(),failure.getMessage());assertSame(cleanup,failure.getCause());assertEquals(1,closes[0]);
        });
    }
}
