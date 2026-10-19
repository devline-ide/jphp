package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;
/** Exact original native weak/strict hook bytes, not a parser-only smoke test. Full hook acceptance is still gated. */
public class PhpPropertyHookConsumersTest extends JvmCompilerCase {
 private byte[] resource(String name)throws IOException {
  try(var in=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,in);return in.readAllBytes();}
 }
 @Test public void pinnedNativeConsumerCasesUseActualGetSetRefTestsUnsetArithmeticAndBackingSourcePhb()throws Throwable {
  JsonArray rows=new Gson().fromJson(new String(resource("resources/property_hooks/consumers/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
  assertEquals(42,rows.size());List<Throwable> failures=new ArrayList<>();
  for(JsonElement element:rows)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
   JsonObject row=element.getAsJsonObject();String name=row.get("resource").getAsString(),mode=row.get("id").getAsString()+" debug="+debug+" PHB="+dump;
   Path directory=Files.createTempDirectory("jphp-hook-owned-fixture-"),file=directory.resolve(row.get("id").getAsString().substring(row.get("id").getAsString().indexOf('/')+1)+".php");
   byte[] code=resource(name+".php");Files.write(file,code);var output=new ByteArrayOutputStream();var scope=newScope();scope.setDebugMode(debug);
   scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
   var env=new Environment(scope,output);
   try {
    var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
    var module=new JvmCompiler(env,context).compile(false);
    if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
    env.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
    // Only this exact owned diagnostic filename is remapped to the original native semantic context identity.
    String actual=output.toString(StandardCharsets.UTF_8).replace(file.toString().replace("\\","\\\\"),row.get("nativeContextName").getAsString().replace("\\","\\\\"));
    assertArrayEquals(mode,resource(name+".stdout"),actual.getBytes(StandardCharsets.UTF_8));assertEquals("original oracle has no hidden stderr "+mode,0,resource(name+".stderr").length);
   }catch(Throwable failure){failures.add(new AssertionError(mode,failure));}
   finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
  }
  if(!failures.isEmpty()){AssertionError all=new AssertionError(failures.size()+" hook consumer variants failed");failures.forEach(all::addSuppressed);throw all;}
 }
}
