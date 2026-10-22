package org.develnext.jphp.core.compiler.jvm;

import org.junit.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Native20 rows plus explicit legacy shape/zero-argument override continuity. No interface-order claim. */
public class PhpReflectionInterfaceConstantContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs() throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        fixtures=PhpPortableTestInputs.fixture("qa.reflection.interface.constant","reflection-interface-constant");
    }
    @AfterClass public static void releaseInputs() throws Exception {PhpPortableTestInputs.release(fixtures);fixtures=null;}
    private static void include(Environment env,Path file,PhpLanguageProfile profile,boolean phb,boolean debug)throws Throwable {
        var context=new Context(Files.readString(file,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY,env.scope.getPhpLanguageProfile());
    }
    private static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    @Test(timeout=120000) public void nativeParentSingularConstantsInterfaceClosureAndNullableFiltersSourceAndDump()throws Throwable {
        byte[] expected=Files.readAllBytes(fixtures.resolve("contract.stdout"));assertEquals(20,new String(expected,StandardCharsets.UTF_8).lines().count());assertEquals(0,Files.size(fixtures.resolve("contract.stderr")));
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            var output=new ByteArrayOutputStream();var env=new Environment(scope(debug),output);Throwable primary=null;
            try{include(env,fixtures.resolve("contract.php"),PhpLanguageProfile.PHP_8_5,phb,debug);env.flushAll();byte[] actual=output.toByteArray();System.out.println("REFLECTION_NATIVE20_BYTES debug="+debug+" PHB="+phb+" "+java.util.Base64.getEncoder().encodeToString(actual));assertArrayEquals("native20 debug="+debug+" PHB="+phb,expected,actual);}
            catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }
    @Test(timeout=120000) public void legacyModernLegacyPreservesShapesFiltersAndZeroArgumentOverridesSourceAndDump()throws Throwable {
        byte[] modern=Files.readAllBytes(fixtures.resolve("profile.stdout"));assertEquals(0,Files.size(fixtures.resolve("profile.stderr")));
        String legacy="null|assoc|2|list\n";
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            var output=new ByteArrayOutputStream();var env=new Environment(scope(debug),output);Throwable primary=null;
            try{
                include(env,fixtures.resolve("profile-definitions.php"),PhpLanguageProfile.JPHP_LEGACY,phb,debug);
                include(env,fixtures.resolve("legacy-overrides.php"),PhpLanguageProfile.JPHP_LEGACY,phb,debug);env.flushAll();assertEquals("1:2\n",output.toString(StandardCharsets.UTF_8));output.reset();
                for(var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5,PhpLanguageProfile.JPHP_LEGACY})include(env,fixtures.resolve("profile-statements.php"),profile,phb,debug);
                env.flushAll();String actual=output.toString(StandardCharsets.UTF_8);assertEquals(legacy+new String(modern,StandardCharsets.UTF_8)+legacy,actual);
                System.out.println("REFLECTION_PROFILE_ISOLATION_AND_LEGACY_ZERO_ARG_OVERRIDES debug="+debug+" PHB="+phb+" PASS");
            }catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }
}
