package org.develnext.jphp.core.compiler.jvm;
import com.google.gson.*;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.*;
import php.runtime.loader.dump.io.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
/** Authoritative PHP8.5 operations, including negative accesses inside accepted programs. */
public class PhpAsymmetricPropertyVisibilityTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var stream=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,stream);return stream.readAllBytes();}
    }
    private void cases(boolean accepted)throws Throwable {
        var rows=new Gson().fromJson(new String(resource("resources/asymmetric_visibility/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        var failures=new ArrayList<Throwable>();int compared=0;
        for(var element:rows) {
            var row=element.getAsJsonObject();if((row.get("nativeExit").getAsInt()==0)!=accepted)continue;
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
                if(!accepted&&dump)continue;
                compared++;String path=row.get("resource").getAsString(),label=row.get("id").getAsString()+" debug="+debug+" PHB="+dump;
                var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                Path directory=Files.createTempDirectory("jphp-asymmetric-"),file=directory.resolve("fixture.php");
                try {
                    byte[] code=resource(path+".php");Files.write(file,code);var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(!accepted)fail("Required native declaration rejection admitted: "+label);
                    if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,true);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    module.include(env);env.flushAll();assertArrayEquals(label,resource(path+".stdout"),output.toByteArray());assertEquals(label,0,resource(path+".stderr").length);
                }catch(Throwable failure) {
                    if(accepted||failure instanceof AssertionError)failures.add(new AssertionError(label,failure));
                    else try{assertEquals(label,row.get("message").getAsString(),failure.getMessage());assertArrayEquals(label+" no effects before rejection",resource(path+".stdout"),output.toByteArray());}
                    catch(Throwable mismatch){failures.add(new AssertionError(label,mismatch));}
                }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
            }
        }
        assertEquals(accepted?200:40,compared);
        if(!failures.isEmpty()){var error=new AssertionError(failures.size()+" asymmetric variants failed");failures.forEach(error::addSuppressed);throw error;}
    }
    private void supplementalNativeOperations()throws Throwable {
        for(String mode:new String[]{"weak","strict"})for(String name:new String[]{"reflection_methods","quiet_array_unset","json_array_shapes","json_nested_array_shapes"})
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
                String path="resources/asymmetric_visibility/supplement/"+mode+"/"+name,label=path+" debug="+debug+" PHB="+dump;
                var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var directory=Files.createTempDirectory("jphp-asymmetric-supplement-");var file=directory.resolve(name+".php");
                try {
                    byte[] code=resource(path+".php");Files.write(file,code);var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(env,context).compile(false);
                    if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    module.include(env);env.flushAll();assertArrayEquals(label,resource(path+".stdout"),output.toByteArray());assertEquals(label,0,resource(path+".stderr").length);
                    assertEquals(label,PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());assertEquals(label,PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
                }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
            }
        // Preserve the actual historical flag contract for an ordinary legacy caller.
        var scope=newScope();var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
        try {
            var context=new Context("class LegacyMethods {public static function pub(){}} $r=new ReflectionMethod('LegacyMethods','pub'); echo ReflectionMethod::IS_PUBLIC,',',ReflectionMethod::IS_STATIC,':',$r->getModifiers(),':',(int)$r->isStatic();");context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
            assertEquals("Legacy method flags/constants must remain unchanged","256,1:257:1",output.toString(StandardCharsets.UTF_8));
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());output.reset();
            var jsonContext=new Context("$a=['x'=>1]; unset($a['x']); echo json_encode($a),':'; $b=['x'=>1]; $b[]= 'a'; $b[]= 'b'; unset($b['x']); echo json_encode($b);");jsonContext.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var jsonModule=new JvmCompiler(env,jsonContext).compile(false);scope.loadModule(jsonModule);scope.addUserModule(jsonModule);env.registerModule(jsonModule);jsonModule.include(env);env.flushAll();
            assertEquals("Legacy JSON storage-shape semantics must remain unchanged","{}:{\"0\":\"a\",\"1\":\"b\"}",output.toString(StandardCharsets.UTF_8));
            assertEquals(PhpLanguageProfile.JPHP_LEGACY,jsonModule.getPhpLanguageProfile());
        }finally{env.doFinal();}
    }
    @Test public void everyNativeWriteReferenceScopeHookStaticAndReflectionCase()throws Throwable{cases(true);supplementalNativeOperations();}
    @Test public void invalidVisibilityDeclarationsKeepNativeCompileRejections()throws Throwable{cases(false);}
    @Test public void malformedWriterMetadataAndAbi22RejectBeforePublishingDeclarations()throws Throwable {
        for(int mutation=0;mutation<3;mutation++) {
            var scope=newScope();var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context("class H{public private(set) int $x=2;}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);var property=module.findClass("H").findProperty("x");
                var codec=new PropertyDumper(context,env,true);codec.setAllowPhpSet(true);
                var bytes=new ByteArrayOutputStream();codec.save(property,bytes);byte[] saved=bytes.toByteArray();
                byte[] prefix={0,0,0,5,1,0,0,3};int at=-1;
                for(int i=0;i<=saved.length-prefix.length;i++){boolean match=true;for(int j=0;j<prefix.length;j++)if(saved[i+j]!=prefix[j]){match=false;break;}if(match){assertEquals(-1,at);at=i;}}
                assertTrue("Actual writer metadata envelope",at>=0);
                if(mutation==0)saved[at+7]=0;else if(mutation==1)saved[at+4]=0;else codec.setAllowPhpSet(false);
                try{codec.load(new ByteArrayInputStream(saved));fail("Malformed/ABI22 writer admitted");}
                catch(DumpException expected){assertEquals(mutation==0?"Invalid asymmetric writer mode":mutation==1?"Invalid asymmetric writer contract":"Asymmetric property visibility requires compiler ABI23",expected.getMessage());}
                assertEquals(0,output.size());assertNull(scope.fetchUserClass("H"));
            }finally{env.doFinal();}
        }
    }
}
