package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Native PHP 8.5.11 terminal property SEND_REF/SEND_VAL oracle, source and dump. */
public class PhpPropertyReferenceArgumentsTest extends JvmCompilerCase {
    @Test public void testWeakPropertyArgumentFetch() throws Throwable { check(false); }
    @Test public void testStrictPropertyArgumentFetch() throws Throwable { check(true); }

    private void check(boolean strict) throws Throwable {
        String fixture=resource("php85_property_reference_arguments.php").substring(5);
        var differences=new java.util.ArrayList<String>();
        for (boolean dumped : new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context=new Context((strict ? "declare(strict_types=1);\n" : "")+fixture);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true); var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                var result=module.include(env);
                TraceInfo trace=TraceInfo.valueOf(context.getFileName(),0,0,module.getInternalName());
                var expected=PhpSerialization.unserialize(env,trace,StringMemory.valueOf(resource("php85_property_reference_arguments.expected")));
                assertEquals(61,expected.toValue(ArrayMemory.class).size());
                assertEquals(61,result.toValue(ArrayMemory.class).size());
                for (int i=0;i<61;i++) {
                    String wanted=PhpSerialization.serialize(env,trace,expected.valueOfIndex(i)).toString();
                    String actual=PhpSerialization.serialize(env,trace,result.valueOfIndex(i)).toString();
                    if (!wanted.equals(actual)) differences.add("strict="+strict+" dump="+dumped+" row="+i+" expected="+wanted+" actual="+actual);
                }
                var diagnostics=new StringWriter();var writer=new PrintWriter(diagnostics);
                CheckClassAdapter.verify(new ClassReader(module.getData()),env.getScope().getClassLoader(),false,writer);
                for (var clazz : module.getClasses()) if (clazz.getData() != null && clazz.getData().length != 0)
                    CheckClassAdapter.verify(new ClassReader(clazz.getData()),env.getScope().getClassLoader(),false,writer);
                for (var closure : module.getClosures()) if (closure.getData() != null && closure.getData().length != 0)
                    CheckClassAdapter.verify(new ClassReader(closure.getData()),env.getScope().getClassLoader(),false,writer);
                assertEquals("",diagnostics.toString());
            } finally {env.doFinal();}
        }
        assertEquals(String.join("\n",differences),0,differences.size());
    }

    private String resource(String name) throws IOException {
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/"+name)) {
            assertNotNull(name,input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
