package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import java.io.*;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.exceptions.CustomErrorException;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;
public class PhpPropertyHookDeclarationRulesTest extends JvmCompilerCase {
 @Test public void scalarSetterHintCannotBeOverwrittenByWiderPropertyType()throws Throwable {
  for(boolean debug:new boolean[]{false,true}) {
   var output=new ByteArrayOutputStream();var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,output);
   try {
    var context=new Context("echo 'before';class Narrow {public int|string $x {set(int $v){echo 'setter';}}}echo 'after';");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
    try {new JvmCompiler(env,context).compile(false);fail("Narrow setter accepted");}
    catch(CustomErrorException expected){assertEquals(ErrorType.E_COMPILE_ERROR,expected.getType());assertEquals("Type of parameter $v of hook Narrow::$x::set must be compatible with property type",expected.getMessage());}
    assertEquals(0,output.size());
   }finally{env.doFinal();}
  }
 }
 @Test public void explicitScalarAndWiderTypesKeepOwnParameterContractThroughColdDump()throws Throwable {
  for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
   var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope);
   try {
    var context=new Context("class Scalar {public int $x {set(int $v){}}}class Wider {public int $x {set(int|string $v){}}}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
    var module=new JvmCompiler(env,context).compile(false);
    if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
    for(var owner:module.getDeferredClasses()) {
     var checker=owner.findProperty("x").getPhpHooks().set().method().getParameters()[0].getTypeChecker();
     var type=php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(checker,false);assertTrue(type.has("int"));assertEquals(owner.getName().equals("Wider"),type.has("string"));
    }
   }finally{env.doFinal();}
  }
 }
}
