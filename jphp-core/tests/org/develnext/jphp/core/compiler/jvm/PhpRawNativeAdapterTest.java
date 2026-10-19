package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

public class PhpRawNativeAdapterTest {
    private void modes(String source,String expected) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}){
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertEquals("debug="+debug+",dumped="+dumped+",source="+source,expected,module.include(env).toString());
            }finally{env.doFinal();}
        }
    }
    @Test public void bin2hexUsesUtf8BytesAndExactRawEscapes() throws Throwable {
        modes("return bin2hex('Привет');","d09fd180d0b8d0b2d0b5d182");
        modes("return bin2hex(\"\\xFF\\0\\u{D800}\\x80\");","ff00eda08080");
        modes("return bin2hex(string: \"\\xFF\");","ff");
    }
    @Test public void hex2binReturnAndImmutableFoldSurviveTheBytecodeDump() throws Throwable {
        modes("return bin2hex(hex2bin('ff0080'));","ff0080");
        modes("$value=hex2bin('ff0080');return bin2hex($value);","ff0080");
    }
    @Test public void strlenCountsRawDirectReferencesAndStringableResults() throws Throwable {
        modes("return strlen(\"\\xFF\\0\\u{D800}\\x80\");","6");
        modes("$s=\"\\xFF\\0\\u{D800}\\x80\";$r=&$s;return strlen($r);","6");
        modes("class BytesAsText {function __toString(){return \"\\xFF\\x80\";}}return strlen(new BytesAsText);","2");
    }
}
