package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class PhpSplIteratorCountArrayTest extends JvmCompilerCase {
 private void output(PhpLanguageProfile profile,PhpLanguageProfile defaultProfile,String source,String expected,boolean debug,boolean phb)throws Throwable {
  var scope=super.newScope();scope.setPhpLanguageProfile(defaultProfile);scope.setDebugMode(debug);
  var out=new ByteArrayOutputStream();var env=new Environment(scope,out);
  try {
   var context=new Context(source);context.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,context).compile(false);
   if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
   scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.doFinal();
   assertEquals(profile+" default="+defaultProfile+" debug="+debug+" PHB="+phb,expected,out.toString(StandardCharsets.UTF_8));
  }finally{try{env.doFinal();}finally{scope.getClassLoader().close();}}
 }
 @Test public void modernEmptySparseNestedReferenceAndObjectArraysUseCallerProfileSourceAndDump()throws Throwable {
  for(var defaultProfile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5})
   for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})
    output(PhpLanguageProfile.PHP_8_5,defaultProfile,(strict?"declare(strict_types=1);":"")+"$a=[3=>1,9=>2];$x=5;$b=[&$x,[1,2]];$c=[1,2,3];unset($c[1]);echo iterator_count([]),':',iterator_count($a),':',iterator_count($b),':',iterator_count($c),':',$x,':',iterator_count([new stdClass]);","0:2:2:2:5:1",debug,phb);
 }
 @Test public void legacyArrayWarningAndZeroRemainSourceAndDump()throws Throwable {
  for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})
   output(PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.JPHP_LEGACY,"set_error_handler(function($n,$s){echo 'w';return true;});echo iterator_count([1,2]);","w0",debug,phb);
 }
}
