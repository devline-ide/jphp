package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.ClassDumper;
import php.runtime.memory.ObjectMemory;
import static org.junit.Assert.*;

/** Actual native inheritance/default facts, with a fresh consumer Environment for every PHB lane. */
public class PhpPropertyHookInheritedDefaultTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var stream=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,stream);return stream.readAllBytes();}
    }
    private CompileScope scope(boolean debug) {
        var scope=newScope();scope.setDebugMode(debug);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());return scope;
    }
    private void runNativeRows(boolean negative)throws Throwable {
        JsonArray rows=new Gson().fromJson(new String(resource("resources/property_hooks/inherited_defaults/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(18,rows.size());int selected=0;List<Throwable> failures=new ArrayList<>();
        for(JsonElement element:rows) {
            JsonObject row=element.getAsJsonObject();if((row.get("exit").getAsInt()!=0)!=negative)continue;selected++;
            for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
                String name=row.get("resource").getAsString(),mode=row.get("id").getAsString()+" debug="+debug+" PHB="+phb;
                byte[] code=resource(name+".php");Path directory=Files.createTempDirectory("hook-inherited-default-"),file=directory.resolve("fixture.php");Files.write(file,code);
                var output=new ByteArrayOutputStream();Environment producer=new Environment(scope(debug),output),consumer=null;Throwable executionFailure=null;
                try {
                    Context context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module=new JvmCompiler(producer,context).compile(false);
                    Environment active=producer;
                    if(phb) {
                        var bytes=new ByteArrayOutputStream();new ModuleDumper(context,producer,debug).save(module,bytes);
                        consumer=new Environment(scope(debug),output);Context reader=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());reader.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                        module=new ModuleDumper(reader,consumer,debug).load(new ByteArrayInputStream(bytes.toByteArray()));active=consumer;
                    }
                    assertEquals(mode,module.getPhpLanguageProfile(),PhpLanguageProfile.PHP_8_5);
                    active.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);active.scope.loadModule(module);active.scope.addUserModule(module);active.registerModule(module);
                    module.include(active);active.flushAll();
                    if(!negative) {
                        // Also encode the actual resolved class, rather than accepting only its source template.
                        var actual=active.getGlobals().valueOfIndex("c").toValue(ObjectMemory.class).getReflection();
                        var actualProperty=actual.findProperty("x");assertFalse(mode,actualProperty.getPhpHooks().isVirtual());
                        assertTrue(actualProperty.isDefault());assertEquals(3L,actualProperty.getDefaultValue(active).toLong());
                        var classBytes=new ByteArrayOutputStream();var classes=new ClassDumper(context,module,active,debug);classes.save(actual,classBytes);
                        var decoded=classes.load(new ByteArrayInputStream(classBytes.toByteArray()));
                        assertTrue(mode+" raw source virtual fact retained",decoded.findProperty("x").getPhpHooks().isDeclaredVirtual()==actualProperty.getPhpHooks().isDeclaredVirtual());
                        if(decoded.isUnresolvedDeclaration()) {
                            assertEquals(actual.getParent().getName(),decoded.getDeferredParentName());
                            decoded.attachDeferredParent(actual.getParent(),active);
                        }else {
                            assertNotNull(mode+" encoded linked parent retained",decoded.getParent());
                            assertEquals(actual.getParent().getName(),decoded.getParent().getName());
                            php.runtime.reflection.support.PhpPropertyTypes.validateClass(decoded,active);
                        }
                        var decodedProperty=decoded.findProperty("x");assertFalse(mode,decodedProperty.getPhpHooks().isVirtual());
                        assertEquals(3L,decodedProperty.getDefaultValue(active).toLong());
                    }
                }catch(Throwable failed){executionFailure=failed;}
                try {
                    if(negative) {
                        assertNotNull("Invalid virtual/backed default accepted "+mode,executionFailure);
                        String expected=row.get("diagnostic").getAsString();boolean matched=false;
                        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
                        for(Throwable failed=executionFailure;failed!=null && seen.add(failed);failed=failed.getCause())
                            if(failed.getMessage()!=null && failed.getMessage().contains(expected)){matched=true;break;}
                        assertTrue(mode+" requires exact native diagnostic: "+expected+"; actual "+executionFailure,matched);
                        assertEquals(mode+" output before invalid storage rejection",0,output.size());
                        assertEquals(0,resource(name+".stdout").length);assertTrue(resource(name+".stderr").length>0);
                    }else {
                        if(executionFailure!=null)throw executionFailure;
                        assertArrayEquals(mode,resource(name+".stdout"),output.toByteArray());assertEquals(mode,0,resource(name+".stderr").length);
                    }
                }catch(Throwable failed){failures.add(new AssertionError(mode,failed));}
                finally {try{if(consumer!=null)consumer.doFinal();}finally{try{producer.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}}
            }
        }
        assertEquals(negative?8:10,selected);
        if(!failures.isEmpty()){var all=new AssertionError(failures.size()+" inherited default native variants failed");failures.forEach(all::addSuppressed);throw all;}
    }
    @Test public void inheritedBackedDefaultsMatchNativeWeakStrictSourcePhbBothDebugModes()throws Throwable {runNativeRows(false);}
    @Test public void virtualAndPrivateAncestorsAndBackedReferenceStillRejectBeforeOutputSourcePhb()throws Throwable {runNativeRows(true);}
}
