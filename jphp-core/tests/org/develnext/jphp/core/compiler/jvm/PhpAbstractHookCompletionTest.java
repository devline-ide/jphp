package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual PHP interface property contracts; cold validation remains separate from hidden method dispatch. */
public class PhpAbstractHookCompletionTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var stream=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,stream);return stream.readAllBytes();}
    }
    private void cases(boolean positive,boolean deferred)throws Throwable {
        var rows=new Gson().fromJson(new String(resource("resources/property_hooks/completion/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        var failures=new ArrayList<Throwable>();int compared=0;
        for(var element:rows) {
            var row=element.getAsJsonObject();boolean accepted=row.get("nativeExit").getAsInt()==0;
            if(accepted!=positive||row.get("phase").getAsString().equals("deferred")!=deferred)continue;
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
                if(!positive&&!deferred&&dump)continue; // Invalid top-level declarations cannot produce a native PHB.
                compared++;String path=row.get("resource").getAsString(),label=row.get("id").getAsString()+" debug="+debug+" PHB="+dump;
                var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                Path directory=Files.createTempDirectory("jphp-hook-interface-"),file=directory.resolve("fixture.php");boolean compiled=false,loadedDump=false;
                try {
                    byte[] code=resource(path+".php");Files.write(file,code);var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(env,context).compile(false);compiled=true;
                    if(dump){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,true);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));loadedDump=true;}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);module.include(env);env.flushAll();
                    if(!positive)fail("Required interface operation admitted: "+label);
                    assertArrayEquals(label,resource(path+".stdout"),output.toByteArray());assertEquals(label,0,resource(path+".stderr").length);
                }catch(Throwable failure) {
                    if(positive||failure instanceof AssertionError)failures.add(new AssertionError(label,failure));
                    else {
                        try {
                            assertEquals(label,row.get("message").getAsString(),failure.getMessage());
                            if(deferred){assertTrue(label+" declaration must remain deferred through compile",compiled);if(dump)assertTrue(label+" actual PHB load must finish before deferred declaration",loadedDump);}
                            assertArrayEquals(label+" no body effects before rejection",resource(path+".stdout"),output.toByteArray());
                        }catch(Throwable mismatch){failures.add(new AssertionError(label,mismatch));}
                    }
                }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
            }
        }
        assertTrue("Native rows must execute",compared>0);
        if(!failures.isEmpty()){var error=new AssertionError(failures.size()+" interface contract variants failed");failures.forEach(error::addSuppressed);throw error;}
    }
    @Test public void ordinaryAndPropertyObligationsMayBeDeferredAndFulfilled()throws Throwable{cases(true,false);cases(true,true);}
    @Test public void modernTopLevelAbstractCompletionUsesNativeAggregate()throws Throwable{cases(false,false);}
    @Test public void deferredAbstractCompletionWaitsForAllSourceAndPhbContracts()throws Throwable{cases(false,true);}
}
