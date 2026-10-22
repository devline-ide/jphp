package org.develnext.jphp.core.compiler.jvm;

import org.junit.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Exact native class-constant and modifier-mask regressions; Reflection enumeration order is outside this fixture. */
public class PhpDefinedReflectionFilterContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs()throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        fixtures=PhpPortableTestInputs.fixture("qa.defined.reflection","defined-reflection");
    }
    @AfterClass public static void releaseInputs()throws Exception {PhpPortableTestInputs.release(fixtures);fixtures=null;}
    private static byte[] execute(Path file,boolean phb,boolean debug)throws Throwable {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
        try {
            var context=new Context(Files.readString(file,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
            if(phb) {
                var wire=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,wire);
                assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(wire.toByteArray())).getCompilerAbiVersion());
                module=dumper.load(new ByteArrayInputStream(wire.toByteArray()));assertTrue(module.isCompiledArtifact());
            }
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            module.include(env);env.flushAll();return output.toByteArray();
        } catch(Throwable failure){primary=failure;System.out.println("LAZY_PARTIAL_BYTES "+file.getFileName()+" debug="+debug+" PHB="+phb+" "+Base64.getEncoder().encodeToString(output.toByteArray()));throw failure;}
        finally {try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
    }
    private static void nativeCase(String id,int rows)throws Throwable {
        byte[] expected=Files.readAllBytes(fixtures.resolve(id+".stdout"));assertEquals(rows,new String(expected,StandardCharsets.UTF_8).lines().count());
        assertEquals(0,Files.size(fixtures.resolve(id+".stderr")));int variants=0,mismatches=0;Throwable first=null;
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            String key=id+" debug="+debug+" PHB="+phb;
            try {
                byte[] actual=execute(fixtures.resolve(id+".php"),phb,debug);
                boolean same=Arrays.equals(expected,actual);if(!same)mismatches++;
                System.out.println("LAZY_NATIVE_BYTES "+key+" "+(same?"MATCH":"MISMATCH")+" "+Base64.getEncoder().encodeToString(actual));
            } catch(Throwable failure) {
                mismatches++;if(first==null)first=failure;else if(first!=failure)first.addSuppressed(failure);
                System.out.println("LAZY_NATIVE_THROW "+key+" "+failure.getClass().getName()+" "+failure.getMessage());
            }
            variants++;
        }
        assertEquals(4,variants);
        if(mismatches!=0) {AssertionError failure=new AssertionError(id+" native variants mismatched="+mismatches);if(first!=null)failure.initCause(first);throw failure;}
    }
    @Test(timeout=180000) public void classConstantDefinedMatchesNativeLookupScopeAccessAutoloadAndDeferredErrors()throws Throwable {
        nativeCase("defined-main",22);nativeCase("defined-direct",2);nativeCase("defined-supplement",8);nativeCase("defined-active-scope",3);
    }
    @Test(timeout=180000) public void modernReflectionMethodFiltersUsePublicModifierValuesAndAnyBitMask()throws Throwable {nativeCase("reflection-filter",8);}
    @Test(timeout=180000) public void classConstantDefinedUsesExecutingModuleProfileInOneLegacyScope()throws Throwable {
        String php="echo defined('PHP_VERSION')?'1':'0';echo defined('ReflectionMethod::IS_PUBLIC')?'1':'0';echo '|';";
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                for(var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5,PhpLanguageProfile.JPHP_LEGACY}) {
                    var context=new Context(php);context.setPhpLanguageProfile(profile);ModuleEntity module=new JvmCompiler(env,context).compile(false);
                    if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                    assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
                }
                env.flushAll();assertEquals("10|11|10|",output.toString(StandardCharsets.UTF_8));
            } catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }
}
