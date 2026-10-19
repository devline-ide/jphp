package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Native default-path lifetime expectations remain failing until ordinary activation is complete. */
public class PhpRuntimeComparisonDefaultTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void objectWarningKeepsOperandAliveAfterHandlerReplacesOriginal()throws Throwable {
        plainInclude("error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=new C;set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=null;return true;});$r=$a==1;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;N;}");
        plainInclude("declare(strict_types=1);error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=new C;set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=null;return true;});$r=$a==1;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;N;}");
    }
    @Test public void nestedArrayOperandRetainsObjectUntilTheComparisonReturns()throws Throwable {
        plainInclude("error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=[new C];$b=[1];set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=[];return true;});$r=$a==$b;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;a:0:{}}");
        plainInclude("declare(strict_types=1);error_reporting(0);class C {function __destruct(){echo 'd;';}}$a=[new C];$b=[1];set_error_handler(function($n,$s)use(&$a){echo 'w;';$a=[];return true;});$r=$a==$b;restore_error_handler();echo 'after;';echo serialize([$r,$a]);","w;d;after;a:2:{i:0;b:1;i:1;a:0:{}}");
    }
    private void plainInclude(String source,String expected)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            try {
                var module=compile(env,source,dump);assertNull(env.getRuntimeOwnership());module.include(env);env.getDefaultBuffer().flush();
                assertEquals("debug="+debug+" dump="+dump+" source="+source,expected,output.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }

}
