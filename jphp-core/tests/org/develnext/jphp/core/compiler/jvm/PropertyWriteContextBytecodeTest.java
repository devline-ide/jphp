package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.junit.Assume;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;

public class PropertyWriteContextBytecodeTest extends JvmCompilerCase {
    private void verify(String code, PhpLanguageProfile profile) throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var context=new Context(code);context.setPhpLanguageProfile(profile);
            var module=new JvmCompiler(env,context).compile(false);
            env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
            var diagnostics=new StringWriter();var output=new PrintWriter(diagnostics);
            CheckClassAdapter.verify(new ClassReader(module.getData()),env.getScope().getClassLoader(),false,output);
            for (var clazz : module.getClasses()) if (clazz.getData() != null && clazz.getData().length != 0)
                CheckClassAdapter.verify(new ClassReader(clazz.getData()),env.getScope().getClassLoader(),false,output);
            assertEquals("",diagnostics.toString());module.include(env);
        } finally {env.doFinal();}
    }
    @Test public void nestedSwitchCatchAndFinallyKeepContextSlotIndependent() throws Throwable {
        String code="function slots($items){$result=[];foreach($items as $item){switch($item){case 1:try {switch($item+1){case 2:$result[]=2;break;default:throw new Exception;} }catch(Exception $e){$result[]=3;}finally{$result[]=4;}break;default:$result[]=5;}}return $result;} return slots([1,2]);";
        for (PhpLanguageProfile profile:PhpLanguageProfile.values()) verify(code,profile);
    }
    @Test public void verifyExternalFrameworkLoaderWithoutCopyingItsImplementation() throws Throwable {
        String path=System.getenv("JPHP_QA_FRAMEWORK_SOURCE");Assume.assumeNotNull(path);
        String source=Files.readString(Path.of(path),StandardCharsets.UTF_8);
        verify(source.startsWith("<?php") ? source.substring(5) : source,PhpLanguageProfile.JPHP_LEGACY);
    }
}
