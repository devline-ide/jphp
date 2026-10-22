package org.develnext.jphp.core.compiler.jvm;

import org.junit.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Actual PHP8.5 golden rows. ArrayObject remains a distinct native aggregate, not an iterator alias. */
public class PhpArrayObjectContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs()throws Exception {
        assertEquals(PhpPortableTestInputs.runtimeOrigin(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(PhpPortableTestInputs.coreOrigin(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        fixtures=PhpPortableTestInputs.fixture("qa.spl.arrayobject","arrayobject");
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
            if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
            assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());return output.toByteArray();
        }catch(Throwable failure){primary=failure;throw failure;}
        finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
    }
    private static void nativeCase(String id,int rows)throws Throwable {
        byte[] expected=Files.readAllBytes(fixtures.resolve(id+".stdout"));assertEquals(rows,new String(expected,StandardCharsets.UTF_8).lines().count());
        assertEquals(0,Files.size(fixtures.resolve(id+".stderr")));
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}){
            byte[] actual=execute(fixtures.resolve(id+".php"),phb,debug);
            System.out.println("ARRAYOBJECT_NATIVE_BYTES "+id+" debug="+debug+" PHB="+phb+" "+java.util.Base64.getEncoder().encodeToString(actual));
            assertArrayEquals(id+" debug="+debug+" PHB="+phb,expected,actual);
        }
    }
    private static void profileIsolation()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                for(var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5,PhpLanguageProfile.JPHP_LEGACY}) {
                    var context=new Context("$a=new ArrayObject(['x'=>1]);$a['x']=2;echo get_class($a).':'.count($a).':'.$a['x'].'|';");context.setPhpLanguageProfile(profile);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(phb){var wire=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,wire);module=dumper.load(new ByteArrayInputStream(wire.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
                }
                env.flushAll();assertEquals("ArrayObject:1:2|ArrayObject:1:2|ArrayObject:1:2|",output.toString(StandardCharsets.UTF_8));
                System.out.println("ARRAYOBJECT_PROFILE_ISOLATION debug="+debug+" PHB="+phb+" PASS");
            }catch(Throwable failure){primary=failure;throw failure;}
            finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }
    @Test(timeout=180000)public void nativeConstructorOffsetsReferencesAndExchange()throws Throwable{nativeCase("storage",10);profileIsolation();}
    @Test(timeout=180000)public void nativeFlagsCustomIteratorAndSortCallbacks()throws Throwable{nativeCase("iterators-flags",11);}
    @Test(timeout=180000)public void nativeCloneSerializationAndReflectionSurface()throws Throwable{nativeCase("clone-serialization",8);nativeCase("reflection",3);nativeCase("backing-serialization",3);}
    @Test(timeout=180000)public void nativeExplicitCellsSelfStorageRawRestoreAndOffsetOverrides()throws Throwable{nativeCase("review-intersections",10);nativeCase("raw-own-reference-publish",7);}
}
