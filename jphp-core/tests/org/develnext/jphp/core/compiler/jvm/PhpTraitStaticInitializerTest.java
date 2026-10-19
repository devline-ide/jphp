package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;
public class PhpTraitStaticInitializerTest extends JvmCompilerCase {
 @Test(timeout=120000) public void nativePinnedArbitraryInitializersAndTransitiveDonors() throws Throwable {
  try(var reader=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/resources/functions/php85_trait_static_initializers.tsv"),StandardCharsets.UTF_8))) {
   int count=0;
   for(String line;(line=reader.readLine())!=null;) {
    count++;var cells=line.split("\t",-1);assertEquals(4,cells.length);
    String source=new String(Base64.getDecoder().decode(cells[2]),StandardCharsets.UTF_8);
    String expected=new String(Base64.getDecoder().decode(cells[3]),StandardCharsets.UTF_8);
    for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
     var scope=super.newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
     scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
     var out=new ByteArrayOutputStream();var env=new Environment(scope,out);
     try {
      var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
      var module=new JvmCompiler(env,context).compile(false);
      if(phb){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
      scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.doFinal();
      assertEquals(cells[0]+" strict="+cells[1]+" debug="+debug+" PHB="+phb,expected,out.toString(StandardCharsets.UTF_8));
     }finally{try{env.doFinal();}finally{scope.getClassLoader().close();}}
    }
   }
   assertEquals(12,count);
  }
 }
}
