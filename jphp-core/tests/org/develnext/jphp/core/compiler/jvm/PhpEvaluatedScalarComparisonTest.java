package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;
public class PhpEvaluatedScalarComparisonTest extends JvmCompilerCase {
 private void check(String source,String expected) throws Throwable {
  for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}){
   var scope=newScope();scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);
   var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
   try{var context=new Context(source);context.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,context).compile(false);
    if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
    assertEquals(profile+",debug="+debug+",PHB="+dumped,expected,output.toString(java.nio.charset.StandardCharsets.UTF_8));
   }finally{env.doFinal();}
  }
 }
 @Test public void strings()throws Throwable{check("function value($x){return $x;}echo ((string)value('7') == (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') != (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') === (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') !== (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') < (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') <= (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') > (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') >= (string)value('8')) ? '1':'0';echo ','; echo ((string)value('7') <=> (string)value('8'));echo ',';","0,1,0,1,1,1,0,0,-1,");}
 @Test public void category2()throws Throwable{check("function value($x){return $x;}echo ((int)value(7) == (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) != (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) === (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) !== (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) < (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) <= (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) > (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) >= (float)value(7)) ? '1':'0';echo ','; echo ((int)value(7) <=> (float)value(7));echo ',';","1,0,0,1,0,1,0,1,0,");}
 @Test public void methods()throws Throwable{check("class ValueProbe{function get($x){return $x;}}$a=new ValueProbe;$b=new ValueProbe;echo ((string)$a->get('7') == (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') != (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') === (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') !== (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') < (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') <= (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') > (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') >= (string)$b->get('8')) ? '1':'0';echo ','; echo ((string)$a->get('7') <=> (string)$b->get('8'));echo ',';","0,1,0,1,1,1,0,0,-1,");}
}
