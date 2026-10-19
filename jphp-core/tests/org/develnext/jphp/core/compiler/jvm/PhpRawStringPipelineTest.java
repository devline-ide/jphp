package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.PhpByteStringMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhpRawStringPipelineTest {
    private void modes(String source,byte[] expected) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}){
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                Memory result=module.include(env);
                assertArrayEquals("debug="+debug+",dumped="+dumped+",source="+source.substring(0,Math.min(100,source.length())),expected,result.getBinaryBytes(StandardCharsets.UTF_8));
            }finally{env.doFinal();}
        }
    }
    @Test public void rawLiteralAndFoldedConcatenationKeepExactBytes() throws Throwable {
        modes("return \"\\xFF\\0\\u{D800}\\x80A\";",new byte[]{(byte)0xff,0,(byte)0xed,(byte)0xa0,(byte)0x80,(byte)0x80,65});
        modes("return \"\\xFF\".'A'.\"\\x80\";",new byte[]{(byte)0xff,65,(byte)0x80});
        modes("$x=\"\\xFF\\x80\";return $x;",new byte[]{(byte)0xff,(byte)0x80});
    }
    @Test public void rawDefaultsAndClassConstantsSurviveNestedDumperRoutes() throws Throwable {
        byte[] raw={(byte)0xff,(byte)0x80};
        modes("function rawDefault($x=\"\\xFF\\x80\"){return $x;}return rawDefault();",raw);
        modes("class RawDefault {public $value=\"\\xFF\\x80\";}return(new RawDefault)->value;",raw);
        modes("class RawConstant {const VALUE=\"\\xFF\\x80\";}return RawConstant::VALUE;",raw);
        modes("class RawMethod {static function get($x=\"\\xFF\\x80\"){return $x;}}return RawMethod::get();",raw);
    }
    @Test public void largeNulAndHighByteConstantsUseBoundedModifiedUtf8Chunks() throws Throwable {
        byte[] bytes=new byte[40000];java.util.Arrays.fill(bytes,(byte)0xff);
        modes("$value=\""+"\\xFF".repeat(40000)+"\";return $value;",bytes);
        bytes=new byte[40001];bytes[40000]=(byte)0xff;
        modes("$value=\""+"\\0".repeat(40000)+"\\xFF\";return $value;",bytes);
    }
}
