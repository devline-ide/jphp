package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

public class LargeModuleTest extends JvmCompilerCase {
    @Test public void testSharedReferencesAndFinallySurviveLargeModule() throws Throwable {
        String code="$n=0;$r=&$n;$values=[];"+"$r=$r+1;$values[]=$r;".repeat(2048)
                +"try {return [&$n,count($values),$values[0],$values[2047]];}finally {$r=$r+1;}";
        for (PhpLanguageProfile profile:PhpLanguageProfile.values()) for (boolean dump:new boolean[]{false,true}) {
            Memory result=run(code,profile,dump,new ByteArrayOutputStream());
            assertEquals(2049,result.valueOfIndex(0).toLong());assertEquals(2048,result.valueOfIndex(1).toLong());
            assertEquals(1,result.valueOfIndex(2).toLong());assertEquals(2048,result.valueOfIndex(3).toLong());
        }
    }
    @Test public void testNullAndUndefinedReturnsDoNotContinueIntoLaterParts() throws Throwable {
        for (String tail:new String[]{"return null;","return $undefined;"}) {
            String code="$n=0;"+"$n=$n+1;".repeat(384)+tail+"echo 'SHOULD_NOT_RUN';"+"$n=$n+1;".repeat(512);
            for (PhpLanguageProfile profile:PhpLanguageProfile.values()) for (boolean dump:new boolean[]{false,true}) {
                var output=new ByteArrayOutputStream();Memory result=run(code,profile,dump,output);
                assertTrue(result.isNull());assertFalse(output.toString().contains("SHOULD_NOT_RUN"));
            }
        }
    }
    private Memory run(String code,PhpLanguageProfile profile,boolean dump,ByteArrayOutputStream output) throws Throwable {
        var env=new Environment(newScope(),output);
        env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        try {
            var context=new Context(code);context.setPhpLanguageProfile(profile);
            var module=new JvmCompiler(env,context).compile(false);
            if (dump) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
            var diagnostics=new StringWriter();CheckClassAdapter.verify(new ClassReader(module.getData()),env.getScope().getClassLoader(),false,new PrintWriter(diagnostics));
            assertEquals("",diagnostics.toString());return module.include(env);
        } finally {env.doFinal();}
    }
}
