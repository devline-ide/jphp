package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;import php.runtime.common.PhpLanguageProfile;import php.runtime.env.*;import php.runtime.ext.*;import php.runtime.loader.dump.*;import php.runtime.loader.dump.io.DumpException;import php.runtime.reflection.*;import java.io.*;import java.nio.charset.StandardCharsets;import static org.junit.Assert.*;
public class GeneratorLegacyAliasTest {
 private Environment environment(boolean debug){var s=new CompileScope();s.registerExtension(new CoreExtension());s.registerExtension(new SPLExtension());s.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());s.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);s.setDebugMode(debug);return new Environment(s,new ByteArrayOutputStream());}
 private ModuleEntity compile(Environment env,String source)throws Throwable{Context c=new Context(source);c.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);return new JvmCompiler(env,c).compile(false);}
 private byte[] save(Environment env,ModuleEntity m)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();new ModuleDumper(m.getContext(),env,true).save(m,bytes);return bytes.toByteArray();}
 private void publish(Environment env,ModuleEntity m){env.getScope().loadModule(m);env.getScope().addUserModule(m);env.registerModule(m);}
 private String resource(String name)throws IOException{try(InputStream in=getClass().getClassLoader().getResourceAsStream("resources/expressions/"+name)){assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
 @Test public void exactLegacyConstructorAliasSourceAndPhbExecuteWithCompleteLinkage()throws Throwable{
  for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}){
   Environment env=environment(debug);try{ModuleEntity source=compile(env,resource("legacy_named_constructor.php").substring(5));ClassEntity owner=source.findClass("legacynamedconstructor");assertSame(owner.findMethod("legacynamedconstructor"),owner.findMethod("__construct"));
    byte[] wire=save(env,source);ModuleEntity m=dumped?new ModuleDumper(source.getContext(),env,true).load(new ByteArrayInputStream(wire)):source;publish(env,m);
    assertTrue(m.hasValidatedGeneratorLinkage());assertNull(m.findClass("legacynamedconstructor").findMethod("__construct").getGeneratorEntity());
    var result=m.include(env).toValue(php.runtime.memory.ArrayMemory.class);assertEquals(3,result.valueOfIndex(0).valueOfIndex(0).toLong());assertEquals(9,result.valueOfIndex(0).valueOfIndex(1).toLong());assertEquals(5,result.valueOfIndex(1).valueOfIndex(0).toLong());assertEquals(9,result.valueOfIndex(1).valueOfIndex(1).toLong());
   }finally{env.doFinal();}}
 }
 @Test public void generatorConstructorAliasesRetainExactFactoryAcrossSourceAndPhb()throws Throwable{
  String fixture="class LegacyGenAlias{function LegacyGenAlias($seed=17){$sent=yield $seed;yield $seed+1;return $sent+2;}}class LegacyGenChild extends LegacyGenAlias{}return 0;";
  String query="$o=new LegacyGenChild();return [(new ReflectionMethod('LegacyGenAlias','LegacyGenAlias'))->isGenerator(),(new ReflectionMethod('LegacyGenAlias','__construct'))->isGenerator(),(new ReflectionMethod('LegacyGenChild','LegacyGenAlias'))->isGenerator(),(new ReflectionMethod('LegacyGenChild','__construct'))->isGenerator(),$o->LegacyGenAlias(17)];";
  for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}){
   Environment env=environment(debug);try{ModuleEntity source=compile(env,fixture);ClassEntity owner=source.findClass("legacygenalias");assertSame(owner.findMethod("legacygenalias"),owner.findMethod("__construct"));
    byte[] wire=save(env,source);ModuleEntity m=dumped?new ModuleDumper(source.getContext(),env,true).load(new ByteArrayInputStream(wire)):source;assertTrue(m.hasValidatedGeneratorLinkage());publish(env,m);m.include(env);
    MethodEntity alias=env.fetchClass("LegacyGenAlias",false).findMethod("__construct");assertSame(m.findGenerator(0),alias.getGeneratorEntity());assertSame(alias,env.fetchClass("LegacyGenChild",false).findMethod("__construct"));
    ModuleEntity probe=compile(env,query);publish(env,probe);var rows=probe.include(env).toValue(php.runtime.memory.ArrayMemory.class);for(int at=0;at<4;at++)assertTrue(rows.valueOfIndex(at).toBoolean());
    var gen=rows.valueOfIndex(4).toObject(php.runtime.lang.Generator.class);assertSame(m.findGenerator(0),gen.getReflection());assertEquals(17,gen.current(env).toLong());assertEquals(18,gen.send(env,php.runtime.memory.LongMemory.valueOf(7)).toLong());gen.next(env);assertEquals(9,gen.getReturn(env).toLong());
   }finally{env.doFinal();}}
 }
 @Test public void distinctMethodPointersWithSameIdentityStillReject()throws Throwable{
  Environment env=environment(false);try{ModuleEntity m=compile(env,"class LegacyConflict{function LegacyConflict(){yield 1;}function ordinary(){return 2;}}return 0;");ClassEntity clazz=m.findClass("legacyconflict");MethodEntity original=clazz.findMethod("legacyconflict"),duplicate=original.duplicateForInject();duplicate.setClazz(clazz);assertNotSame(original,duplicate);assertSame(clazz,duplicate.getClazz());clazz.getMethods().put("forged_alias",duplicate);
   try{PhpGeneratorLinkageDumper.save(m,name->null);fail("Different declarations with identical physical identity accepted");}catch(DumpException expected){assertTrue(expected.getMessage().contains("duplicate declaration identity"));}
   assertSame(original,clazz.findMethod("legacyconflict"));assertSame(original,clazz.findMethod("__construct"));assertSame(m.findGenerator(0),original.getGeneratorEntity());
  }finally{env.doFinal();}
 }
}
