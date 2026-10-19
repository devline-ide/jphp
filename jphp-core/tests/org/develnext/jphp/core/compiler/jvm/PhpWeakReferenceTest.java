package org.develnext.jphp.core.compiler.jvm;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;
public class PhpWeakReferenceTest extends JvmCompilerCase {
 private php.runtime.reflection.ModuleEntity compile(Environment env,String source,PhpLanguageProfile profile,boolean dumped)throws Throwable {
  var c=new Context(source);c.setPhpLanguageProfile(profile);var m=new JvmCompiler(env,c).compile(false);
  if(dumped){var bytes=new ByteArrayOutputStream();var d=new ModuleDumper(c,env,true);d.save(m,bytes);m=d.load(new ByteArrayInputStream(bytes.toByteArray()));}
  env.scope.loadModule(m);env.scope.addUserModule(m);env.registerModule(m);return m;
 }
 @Test public void nativeIdentityConstructionCloneSerializationPropertiesDebugAndReflectionSourceAndDump()throws Throwable {
  JsonArray rows;try(var f=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_weak_reference_controls.json")){assertNotNull(f);rows=new Gson().fromJson(new String(f.readAllBytes(),StandardCharsets.UTF_8),JsonObject.class).getAsJsonArray("cases");}
  for(var row:rows)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}) {
   var r=row.getAsJsonObject();var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());var out=new ByteArrayOutputStream();var env=new Environment(scope,out);env.getDefaultBuffer().setChunkSize(0);
   try{compile(env,(strict?"declare(strict_types=1);":"")+r.get("code").getAsString(),PhpLanguageProfile.PHP_8_5,dump).include(env);env.getDefaultBuffer().flush();assertEquals(r.get("id").getAsString()+" debug="+debug+" dump="+dump+" strict="+strict,r.get("stdout").getAsString().replaceAll("#\\d+","#ID"),out.toString(StandardCharsets.UTF_8).replaceAll("#\\d+","#ID"));assertNull(env.getRuntimeOwnership());}finally{env.doFinal();}
  }
 }
 @Test public void legacyCallerCanUseJvmWeakIdentityWithoutChangingProfile()throws Throwable {
  for(boolean dump:new boolean[]{false,true}){var scope=newScope();var out=new ByteArrayOutputStream();var env=new Environment(scope,out);try{compile(env,"$o=new stdClass;$w=WeakReference::create($o);echo $w->get()===$o?'yes':'no';",PhpLanguageProfile.JPHP_LEGACY,dump).include(env);env.getDefaultBuffer().flush();assertEquals("yes",out.toString(StandardCharsets.UTF_8));assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());}finally{env.doFinal();}}
 }
 @Test public void unsupportedExplicitOwnershipRejectsBeforeModuleEffects()throws Throwable {
  var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var out=new ByteArrayOutputStream();var env=new Environment(scope,out);var domain=new PhpOwnershipDomain();var m=compile(env,"echo 'effect';$o=new stdClass;$w=WeakReference::create($o);",PhpLanguageProfile.PHP_8_5,false);var authority=new PhpRuntimeOwnership.Authority(env,domain);
  try(var execution=authority.enter()){try{var e=assertThrows(Throwable.class,()->authority.registerModule(m,m.getData(),(p,b,c)->{}));assertTrue(e.toString(),e instanceof IOException||e instanceof IllegalArgumentException);assertEquals(0,out.size());}finally{env.doFinal();authority.close();}}
 }
}
