package org.develnext.jphp.core.compiler.jvm;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;
/** Real public ordinary date subset. Full timelib/zone-data/managed producers are not claimed. */
public class PhpDateProviderContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs()throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        fixtures=PhpPortableTestInputs.fixture("qa.date.provider","date-provider");
    }
    @AfterClass public static void release()throws Exception{PhpPortableTestInputs.release(fixtures);}
    private static byte[] execute(String id,boolean debug,boolean phb)throws Throwable {
        Path file=fixtures.resolve(id+".php");var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.DateExtension());
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.setErrorFlags(22527);Throwable primary=null;
        try {
            assertNull(php.runtime.PhpOwnershipExecution.currentEntry(env));
            assertNull(env.fetchClass("DateTime",false,PhpLanguageProfile.JPHP_LEGACY));
            var context=new Context(Files.readString(file,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
            if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);
                assertEquals(28,ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray())).getCompilerAbiVersion());module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();assertNull(env.fetchClass("DateTime",false,PhpLanguageProfile.JPHP_LEGACY));return output.toByteArray();
        }catch(Throwable failure){primary=failure;System.out.println("DATE_PARTIAL_BYTES "+id+" "+debug+" "+phb+" "+java.util.Base64.getEncoder().encodeToString(output.toByteArray()));throw failure;}
        finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
    }
    private static void nativeCase(String id,int rows)throws Throwable {
        byte[] expected=Files.readAllBytes(fixtures.resolve(id+".stdout"));assertEquals(rows,new String(expected,StandardCharsets.UTF_8).lines().count());
        assertEquals(0,Files.size(fixtures.resolve(id+".stderr")));
        var failures=new java.util.ArrayList<Throwable>();
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try {
            byte[] actual=execute(id,debug,phb);
            System.out.println("DATE_VARIANT_BYTES "+id+" "+debug+" "+phb+" "+java.util.Base64.getEncoder().encodeToString(actual));
            assertArrayEquals(id+" debug="+debug+" PHB="+phb,expected,actual);
        }catch(Throwable failure){failures.add(failure);}
        org.junit.runners.model.MultipleFailureException.assertEmpty(failures);
    }
    @Test public void actualNativeCoreDateContractsSourceAndRealPhb()throws Throwable{nativeCase("core",19);}
    @Test public void actualNativeOperationsCloneViewsAndWireSourceAndRealPhb()throws Throwable{var failures=new java.util.ArrayList<Throwable>();
        try{nativeCase("operations",20);}catch(Throwable failure){failures.add(failure);}
        try{nativeCase("clone-diff",8);}catch(Throwable failure){failures.add(failure);}
        try{nativeCase("subclass-export",2);}catch(Throwable failure){failures.add(failure);}
        try{nativeCase("subclass-iterator-export",2);}catch(Throwable failure){failures.add(failure);}
        try{nativeCase("backing-warning",2);}catch(Throwable failure){failures.add(failure);}
        try{nativeCase("uninitialized-view",12);}catch(Throwable failure){failures.add(failure);}
        org.junit.runners.model.MultipleFailureException.assertEmpty(failures);}
    @Test public void actualNativeEnvDefaultsAndFormatErrorsSourceAndRealPhb()throws Throwable{var failures=new java.util.ArrayList<Throwable>();
        try{nativeCase("profile",9);}catch(Throwable failure){failures.add(failure);}
        try{nativeCase("format-recovery",4);}catch(Throwable failure){failures.add(failure);}
        org.junit.runners.model.MultipleFailureException.assertEmpty(failures);}
}
