package org.develnext.jphp.core.compiler.jvm;
import org.junit.*;
import org.develnext.jphp.pcre.PcreExtension;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Native49 raw proof:48 semantic rows +a truthful engine envelope; supplemental9 exact rows. */
public class PhpPregMatchContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs()throws Exception {
        for(Class<?> type:new Class<?>[]{php.runtime.Memory.class,JvmCompiler.class,PcreExtension.class})
            System.out.println("PREG_PROVIDER_ORIGIN "+type.getName()+" "+Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        fixtures=PhpPortableTestInputs.fixture("qa.preg.match","preg-match");
    }
    @AfterClass public static void releaseInputs()throws Exception{PhpPortableTestInputs.release(fixtures);fixtures=null;}
    private static PcreExtension extension(){
        String path=System.getProperty("qa.pcre.native.path"),hash=System.getProperty("qa.pcre.native.sha256");
        assertEquals("Both native QA overrides must be present together",path==null,hash==null);
        return path==null?new PcreExtension():new PcreExtension(Path.of(path),hash);
    }
    private static CompileScope scope(boolean debug,PcreExtension pcre){
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());scope.registerExtension(pcre);return scope;
    }
    private static void include(Environment env,String php,String name,boolean phb,boolean debug)throws Throwable{
        var context=new Context(php,fixtures.resolve(name).toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb){var out=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,debug);dump.save(module,out);module=dump.load(new ByteArrayInputStream(out.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY,env.scope.getPhpLanguageProfile());
    }
    private static void finish(Environment env,Throwable primary)throws Throwable{
        try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;if(primary!=cleanup)primary.addSuppressed(cleanup);}
    }
    @Test(timeout=180000) public void native48BehaviorRowsAndTruthfulEngineEnvelopeSourceAndDump()throws Throwable{
        byte[] raw=Files.readAllBytes(fixtures.resolve("native49.stdout"));String nativeText=new String(raw,StandardCharsets.UTF_8);assertEquals(49,nativeText.lines().count());assertEquals(0,Files.size(fixtures.resolve("native49.stderr")));
        assertTrue(nativeText.startsWith("engine:[\"8.5.11\",\"10.44 2024-06-07\",true,\"1000000\",\"100000\"]\n"));
        byte[] expected=nativeText.substring(nativeText.indexOf('\n')+1).getBytes(StandardCharsets.UTF_8);
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            var pcre=extension();var out=new ByteArrayOutputStream();var env=new Environment(scope(debug,pcre),out);Throwable primary=null;
            try{
                include(env,Files.readString(fixtures.resolve("native49.php"),StandardCharsets.UTF_8),"native49.php",phb,debug);env.flushAll();String actual=out.toString(StandardCharsets.UTF_8);int newline=actual.indexOf('\n');assertTrue(newline>0);
                String engine=actual.substring(0,newline);String suffix="\",\""+pcre.nativeVersion()+"\","+pcre.nativeJitSupported()+",\"1000000\",\"100000\"]";
                assertTrue("Truthful actual engine envelope: "+engine,engine.startsWith("engine:[\"")&&engine.endsWith(suffix));
                assertArrayEquals("native48 behavior debug="+debug+" PHB="+phb,expected,actual.substring(newline+1).getBytes(StandardCharsets.UTF_8));
                System.out.println("PREG_ENGINE_ACTUAL debug="+debug+" PHB="+phb+" "+engine);
                System.out.println("PREG_MATCH_NATIVE48_BYTES debug="+debug+" PHB="+phb+" "+java.util.Base64.getEncoder().encodeToString(out.toByteArray()));
            }catch(Throwable failure){primary=failure;throw failure;}finally{finish(env,primary);}
        }
    }
    @Test(timeout=180000) public void nativeLimitsWarningReentryTypedMatchesMarkCaptureCowAndEnvironmentIsolationSourceAndDump()throws Throwable{
        byte[] expected=Files.readAllBytes(fixtures.resolve("state-limits.stdout"));assertEquals(9,new String(expected,StandardCharsets.UTF_8).lines().count());assertEquals(0,Files.size(fixtures.resolve("state-limits.stderr")));
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            var pcre=extension();var shared=scope(debug,pcre);var out=new ByteArrayOutputStream();var env=new Environment(shared,out);Throwable primary=null;
            try{
                include(env,Files.readString(fixtures.resolve("state-limits.php"),StandardCharsets.UTF_8),"state-limits.php",phb,debug);env.flushAll();assertArrayEquals("native9 debug="+debug+" PHB="+phb,expected,out.toByteArray());
                System.out.println("PREG_MATCH_NATIVE9_BYTES debug="+debug+" PHB="+phb+" "+java.util.Base64.getEncoder().encodeToString(out.toByteArray()));out.reset();
                include(env,"<?php @preg_match('~(~','a');echo preg_last_error();","error-isolation-a.php",phb,debug);env.flushAll();assertEquals("1",out.toString(StandardCharsets.UTF_8));out.reset();
                var otherOut=new ByteArrayOutputStream();var other=new Environment(shared,otherOut);Throwable secondary=null;
                try{
                    other.setConfigValue("pcre.backtrack_limit",LongMemory.valueOf(17));
                    include(other,"<?php echo preg_last_error();","error-isolation-b.php",phb,debug);other.flushAll();assertEquals("0",otherOut.toString(StandardCharsets.UTF_8));
                    assertEquals(1000000,env.getConfigValue("pcre.backtrack_limit",LongMemory.valueOf(1000000)).toLong());
                    include(env,"<?php echo preg_last_error();","error-isolation-c.php",phb,debug);env.flushAll();assertEquals("1",out.toString(StandardCharsets.UTF_8));
                }catch(Throwable failure){secondary=failure;throw failure;}finally{finish(other,secondary);}
                System.out.println("PREG_ENV_LOCAL_ERROR_AND_LIMIT_ISOLATION debug="+debug+" PHB="+phb+" PASS");
            }catch(Throwable failure){primary=failure;throw failure;}finally{finish(env,primary);}
        }
    }
}
