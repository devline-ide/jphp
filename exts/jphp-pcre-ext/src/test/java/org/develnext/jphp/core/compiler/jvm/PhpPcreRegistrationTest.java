package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.JarFile;
import org.develnext.jphp.pcre.PcreExtension;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Run normal methods without native access; optional boundary cases use a separate JVM fixture. */
public class PhpPcreRegistrationTest {
    private static Properties manifest() throws Exception {
        Path jar=Path.of(PcreExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue("Tests require the selected provider JAR, not class-output directories",Files.isRegularFile(jar));
        Properties result=new Properties();
        try(JarFile selected=new JarFile(jar.toFile())){
            var entry=selected.getJarEntry("META-INF/jphp-pcre/native.properties");
            assertNotNull("Provider build metadata",entry);
            try(var input=selected.getInputStream(entry)){result.load(input);}
        }
        return result;
    }
    private static void assertUninitialized(PcreExtension extension) throws Exception {
        Field initialized=PcreExtension.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        assertNull("Registration/compilation must not initialize FFM",initialized.get(extension));
    }
    private static CompileScope scope(boolean debug,PcreExtension pcre){
        CompileScope result=new CompileScope();
        result.setDebugMode(debug);result.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        result.registerExtension(new php.runtime.ext.CoreExtension());
        result.registerExtension(new php.runtime.ext.SPLExtension());
        result.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        result.registerExtension(pcre);return result;
    }
    private static void assertModernOnly(CompileScope scope){
        for(String name:new String[]{"preg_match","preg_last_error","preg_last_error_msg"}){
            assertNull(name+" in legacy",scope.findCompileFunction(name,PhpLanguageProfile.JPHP_LEGACY));
            assertNotNull(name+" in PHP85",scope.findCompileFunction(name,PhpLanguageProfile.PHP_8_5));
            assertNull(name+" after modern resolution",scope.findCompileFunction(name,PhpLanguageProfile.JPHP_LEGACY));
        }
    }
    private static ModuleEntity compile(Environment env,String php,boolean debug,boolean phb) throws Throwable {
        Context context=new Context(php,new File("pcre-registration-"+debug+"-"+phb+".php"));
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(phb){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            ModuleDumper dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);
            module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static void finish(Environment env,CompileScope scope,Throwable primary) throws Throwable {
        Throwable failure=primary;
        try{env.doFinal();}catch(Throwable cleanup){if(failure==null)failure=cleanup;else if(failure!=cleanup)failure.addSuppressed(cleanup);}
        try{scope.getClassLoader().close();}catch(Throwable cleanup){if(failure==null)failure=cleanup;else if(failure!=cleanup)failure.addSuppressed(cleanup);}
        if(primary==null&&failure!=null)throw failure;
    }
    @Test(timeout=60000) public void registrationReflectionAndSourceDumpExecutionNeedNoNativeEngine() throws Throwable {
        Properties declared=manifest();String classifier=declared.getProperty("native.classifier");
        assertNotNull(classifier);String version=declared.getProperty(classifier+".version");
        assertNotNull(version);String jit=declared.getProperty(classifier+".jit");
        assertTrue("Recorded JIT capability", "true".equals(jit)||"false".equals(jit));
        String numeric=version.split(" ",2)[0];String[] parts=numeric.split("\\.");
        String expected="preg_match|0|No error|"+version+"|"+Integer.parseInt(parts[0])+"|"+Integer.parseInt(parts[1])+"|"+(Boolean.parseBoolean(jit)?"1":"0");
        String php="<?php echo (new \\ReflectionFunction('preg_match'))->getName(),'|',preg_last_error(),'|',preg_last_error_msg(),'|',PCRE_VERSION,'|',PCRE_VERSION_MAJOR,'|',PCRE_VERSION_MINOR,'|',(int)PCRE_JIT_SUPPORT;";
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            PcreExtension pcre=new PcreExtension();assertUninitialized(pcre);
            CompileScope scope=scope(debug,pcre);assertUninitialized(pcre);assertModernOnly(scope);
            ByteArrayOutputStream output=new ByteArrayOutputStream();Environment env=new Environment(scope,output);Throwable primary=null;
            try{
                assertUninitialized(pcre);ModuleEntity module=compile(env,php,debug,phb);assertUninitialized(pcre);
                module.include(env);env.flushAll();assertEquals("debug="+debug+" PHB="+phb,expected,output.toString(StandardCharsets.UTF_8));
                assertUninitialized(pcre);assertModernOnly(scope);
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
            }catch(Throwable failure){primary=failure;throw failure;}finally{finish(env,scope,primary);}
        }
    }
    @Test public void explicitPathAndDigestAreAnAtomicLazyPair() throws Exception {
        Path absent=Path.of("unopened-pcre-registration-payload.dll");
        assertThrows(IllegalArgumentException.class,()->new PcreExtension(absent,null));
        assertThrows(IllegalArgumentException.class,()->new PcreExtension(null,"0".repeat(64)));
        Properties declared=manifest();String hash=declared.getProperty(declared.getProperty("native.classifier")+".sha256");
        assertNotNull(hash);assertUninitialized(new PcreExtension(absent,hash));
    }
    /** Separate JVM invocation: this test class's main with missing-companion or wrong-host. */
    public static void main(String[] args) throws Throwable {
        if(args.length!=1)throw new IllegalArgumentException("Expected missing-companion or wrong-host fixture argument");
        new PhpPcreRegistrationTest().unavailableRuntimeFailsAtCompiledPregMatchInsteadOfRegistration(args[0]);
        System.out.println("PCRE_REGISTRATION_BOUNDARY "+args[0]+" PASS");
    }
    private void unavailableRuntimeFailsAtCompiledPregMatchInsteadOfRegistration(String boundary) throws Throwable {
        assertTrue("Unknown boundary fixture",boundary.equals("missing-companion")||boundary.equals("wrong-host"));
        Properties declared=manifest();String classifier=declared.getProperty("native.classifier");
        Path jar=Path.of(PcreExtension.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String os=System.getProperty("os.name", "").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform=os.startsWith("windows")?"windows":os.startsWith("linux")?"linux":os.startsWith("mac")||os.startsWith("darwin")?"macos":"unsupported";
        String architecture=arch.equals("amd64")||arch.equals("x86_64")?"x86_64":arch.equals("aarch64")||arch.equals("arm64")?"aarch64":"unsupported";
        if(boundary.equals("missing-companion")){
            assertEquals("Missing-file fixture must otherwise match the runtime",classifier,platform+"-"+architecture);
            assertFalse("Root must supply JAR-only fixture",Files.exists(jar.getParent().resolve(declared.getProperty(classifier+".path")),LinkOption.NOFOLLOW_LINKS));
        }else assertNotEquals("Root must supply a genuinely different target fixture",classifier,platform+"-"+architecture);
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            PcreExtension pcre=new PcreExtension();CompileScope scope=scope(debug,pcre);assertUninitialized(pcre);
            Environment env=new Environment(scope,new ByteArrayOutputStream());Throwable primary=null;
            try{
                ModuleEntity module=compile(env,"<?php preg_match('~a~','a');",debug,phb);assertUninitialized(pcre);
                Throwable first=failedInclude(module,env),second=failedInclude(module,env);
                Throwable firstInit=initializationFailure(first),secondInit=initializationFailure(second);
                assertNotNull("First preg_match must report unavailable runtime: "+first,firstInit);
                assertSame("Failure must be cached rather than retrying native initialization",firstInit,secondInit);
                if(boundary.equals("wrong-host"))assertTrue(firstInit.getCause() instanceof UnsupportedOperationException);
                else assertTrue(firstInit.getCause() instanceof NoSuchFileException);
            }catch(Throwable failure){primary=failure;throw failure;}finally{finish(env,scope,primary);}
        }
    }
    private static Throwable failedInclude(ModuleEntity module,Environment env) throws Throwable {
        try{module.include(env);}catch(Throwable expected){return expected;}
        fail("preg_match unexpectedly executed on unavailable native fixture");return null;
    }
    private static Throwable initializationFailure(Throwable failure){
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        for(int depth=0;failure!=null&&depth<20&&seen.add(failure);depth++){
            if(failure instanceof IllegalStateException&&failure.getMessage()!=null&&failure.getMessage().startsWith("PCRE2 runtime unavailable: "))return failure;
            Throwable wrapped=failure instanceof php.runtime.ext.java.JavaException java?java.getThrowable():null;
            failure=wrapped!=null?wrapped:failure.getCause();
        }
        return null;
    }
}
