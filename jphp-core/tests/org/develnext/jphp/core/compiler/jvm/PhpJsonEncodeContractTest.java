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

/** Pinned native JSON encoding contracts; no production flags or managed-custody claims. */
public class PhpJsonEncodeContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs()throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.class.getField("COMPILER_ABI_VERSION").getInt(null));
        fixtures=PhpPortableTestInputs.fixture("qa.json.encode","json-encode");
    }
    @AfterClass public static void releaseInputs()throws Exception {PhpPortableTestInputs.release(fixtures);fixtures=null;}
    static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    static ModuleEntity compile(Environment env,String source,boolean phb,boolean debug,PhpLanguageProfile profile)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,context).compile(false);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY,env.getScope().getPhpLanguageProfile());
        assertEquals(profile,module.getPhpLanguageProfile());assertEquals(profile,env.getModuleManager().getPhpLanguageProfile(new TraceInfo(module.getContext())));
        if(phb) {
            var bytes=new ByteArrayOutputStream();var dump=new ModuleDumper(context,env,debug);dump.save(module,bytes);
            assertEquals(ModuleDumper.COMPILER_ABI_VERSION,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());
            module=dump.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());assertEquals(profile,module.getPhpLanguageProfile());
        }
        var scope=env.getScope();scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static byte[] execute(String source,boolean phb,boolean debug,PhpLanguageProfile profile)throws Throwable {
        var output=new ByteArrayOutputStream();var env=new Environment(scope(debug),output);Throwable primary=null;
        try {compile(env,source,phb,debug,profile).include(env);env.flushAll();return output.toByteArray();}
        catch(Throwable failure){primary=failure;throw failure;}
        finally {try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
    }
    private static String source(Path file)throws Exception {
        String raw=Files.readString(file,StandardCharsets.UTF_8);assertTrue(file.toString(),raw.startsWith("<?php"));return raw.substring(5);
    }
    private static void nativeGroup(String group,int expectedRows,int expectedPrograms)throws Throwable {
        int programs=0,rows=0,variants=0;
        for(String line:Files.readAllLines(fixtures.resolve("cases.tsv"),StandardCharsets.UTF_8)) {
            String[] row=line.split("\t");assertEquals(3,row.length);if(!row[0].equals(group))continue;
            String id=row[1];int count=Integer.parseInt(row[2]);byte[] expected=Files.readAllBytes(fixtures.resolve(id+".stdout"));
            assertEquals(id+" stderr",0,Files.size(fixtures.resolve(id+".stderr")));
            assertEquals(id+" native rows",count,new String(expected,StandardCharsets.UTF_8).lines().count());
            String php=source(fixtures.resolve(id+".php"));
            for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
                assertArrayEquals(id+" debug="+debug+" PHB="+phb,expected,execute(php,phb,debug,PhpLanguageProfile.PHP_8_5));
                variants++;System.out.println("CASEPASS "+id+" debug="+debug+" PHB="+phb);
            }
            programs++;rows+=count;
        }
        assertEquals("complete native rows "+group,expectedRows,rows);assertEquals(expectedPrograms,programs);assertEquals(expectedPrograms*4,variants);
    }
    @Test(timeout=180000) public void depthAndCoercionCaptureMatchNativeSourceAndRealPhb()throws Throwable {nativeGroup("depth",28,3);}
    @Test(timeout=180000) public void rawUtf8KeysValuesAndConsumptionMatchNativeSourceAndRealPhb()throws Throwable {nativeGroup("utf8",17,2);}
    @Test(timeout=180000) public void recursionCallbacksAndPropertyMutationMatchNativeSourceAndRealPhb()throws Throwable {nativeGroup("callbacks",38,6);}
    @Test(timeout=180000) public void valuesFlagsFractionsAndPrettyBytesMatchNativeSourceAndRealPhb()throws Throwable {nativeGroup("values",69,2);}
    @Test(timeout=60000) public void nativeMixedInputAndStringFalseSignatureMatchNativeSourceAndRealPhb()throws Throwable {nativeGroup("reflection",1,1);}
}
