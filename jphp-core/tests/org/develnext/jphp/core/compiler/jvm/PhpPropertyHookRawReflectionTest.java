package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;
public class PhpPropertyHookRawReflectionTest extends JvmCompilerCase {
 private byte[] resource(String name)throws IOException {
  try(var in=getClass().getClassLoader().getResourceAsStream(name)){assertNotNull(name,in);return in.readAllBytes();}
 }
 @Test public void rawReadWriteBypassOnlyHooksAndKeepNativeReflectionTypeRulesSourcePhb()throws Throwable {
  List<Throwable> failures=new ArrayList<>();
  for(String mode:List.of("weak","strict"))for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
   String name="resources/property_hooks/raw_reflection/"+mode;
   Path directory=Files.createTempDirectory("hook-reflection-owned-"),file=directory.resolve("probe.php");
   byte[] code=resource(name+".php");Files.write(file,code);var output=new ByteArrayOutputStream();var scope=newScope();scope.setDebugMode(debug);
   scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
   var env=new Environment(scope,output);
   try {
    var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
    var module=new JvmCompiler(env,context).compile(false);
    if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
    env.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
    assertArrayEquals(mode+" debug="+debug+" PHB="+dump,resource(name+".stdout"),output.toByteArray());assertEquals(0,resource(name+".stderr").length);
   }catch(Throwable failure){failures.add(new AssertionError(mode+" debug="+debug+" PHB="+dump,failure));}
   finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
  }
  if(!failures.isEmpty()){AssertionError all=new AssertionError(failures.size()+" raw reflection variants failed");failures.forEach(all::addSuppressed);throw all;}
 }
}
