package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.support.Entity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Native PHP 8.5.11 oracle: each row asserts its value/error and all emitted diagnostic type/message pairs. */
public class PhpPropertyArrayReferenceArgumentsTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        String name="resources/type_hinting/php85_property_array_reference_arguments."+suffix;
        try (var input=getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name,input);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    private void verify(boolean debug) throws Throwable {
        for (boolean strict:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                var context=new Context((strict?"declare(strict_types=1);":"")+resource("php").substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var entities=new ArrayList<Entity>();entities.add(module);
                entities.addAll(module.getClasses());entities.addAll(module.getFunctions());entities.addAll(module.getClosures());
                var diagnostics=new StringWriter();var writer=new PrintWriter(diagnostics);
                for (Entity entity:entities) if (entity.getData()!=null && entity.getData().length!=0)
                    CheckClassAdapter.verify(new ClassReader(entity.getData()),scope.getClassLoader(),false,writer);
                assertEquals("ASM debug="+debug+" dump="+dumped,"",diagnostics.toString());
                var actual=module.include(env);
                var trace=php.runtime.env.TraceInfo.valueOf(context.getFileName(),0,0,module.getInternalName());
                var expected=php.runtime.memory.output.serialization.PhpSerialization.unserialize(env,trace,
                        php.runtime.memory.StringMemory.valueOf(resource("expected")));
                var differences=new ArrayList<String>();
                assertEquals(63,actual.toValue(php.runtime.memory.ArrayMemory.class).size());
                for (int i=0;i<63;i++) {
                    String wanted=php.runtime.memory.output.serialization.PhpSerialization.serialize(env,trace,expected.valueOfIndex(i)).toString();
                    String got=php.runtime.memory.output.serialization.PhpSerialization.serialize(env,trace,actual.valueOfIndex(i)).toString();
                    if (!wanted.equals(got)) differences.add("row="+i+" expected="+wanted+" actual="+got);
                }
                assertEquals("debug="+debug+" strict="+strict+" dump="+dumped+"\n"+String.join("\n",differences),0,differences.size());
            } finally {env.doFinal();}
        }
    }

    @Test public void testArrayReferenceArgumentsWithDynamicLocals() throws Throwable {verify(true);}
    @Test public void testArrayReferenceArgumentsWithOptimizedLocals() throws Throwable {verify(false);}
}
