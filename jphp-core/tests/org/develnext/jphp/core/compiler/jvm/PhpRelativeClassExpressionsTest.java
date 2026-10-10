package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

public class PhpRelativeClassExpressionsTest extends JvmCompilerCase {
    @Test public void testNativeCompileBoundaries() throws Throwable {
        String rows;
        try (InputStream input=getClass().getClassLoader().getResourceAsStream("resources/expressions/php85_relative_class_expressions.tsv")) {
            assertNotNull(input);
            rows=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        for (String row : rows.split("\\R")) {
            String[] fields=row.split("\t",-1);
            for (boolean dumped : new boolean[]{false,true}) {
                Environment env=new Environment(newScope(),new ByteArrayOutputStream());
                try {
                    Context context=new Context(new String(Base64.getDecoder().decode(fields[2]),StandardCharsets.UTF_8));
                    context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    php.runtime.reflection.ModuleEntity module;
                    try { module=new JvmCompiler(env,context).compile(false); }
                    catch (ErrorException error) {
                        if (!fields[1].equals("reject")) throw new AssertionError(fields[0],error);
                        assertEquals(fields[0],fields[3],error.getMessage());
                        assertEquals(ErrorType.E_COMPILE_ERROR,error.getType());
                        continue;
                    }
                    assertEquals(fields[0]+": unexpectedly compiled","accept",fields[1]);
                    if (dumped) {
                        var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                        dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    }
                    env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                    var diagnostics=new StringWriter();var writer=new PrintWriter(diagnostics);var loader=env.getScope().getClassLoader();
                    CheckClassAdapter.verify(new ClassReader(module.getData()),loader,false,writer);
                    for (var clazz:module.getClasses()) if (!clazz.isInterface()) CheckClassAdapter.verify(new ClassReader(clazz.getData()),loader,false,writer);
                    for (var closure:module.getClosures()) CheckClassAdapter.verify(new ClassReader(closure.getData()),loader,false,writer);
                    assertEquals(fields[0],"",diagnostics.toString());
                    module.include(env);
                } finally {env.doFinal();}
            }
        }
    }

    @Test public void testLegacyCompileBoundariesRetained() throws Throwable {
        for (String code:new String[]{"function f(){parent::m();}","class C {function f(){parent::m();}}"}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            try { new JvmCompiler(env,new Context(code)).compile(false); }
            finally {env.doFinal();}
        }
    }
}
