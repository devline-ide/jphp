package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class PhpStringOffsetReferenceTest extends JvmCompilerCase {
    @Test public void testNativeWeakStringOffsetReferences() throws Throwable {
        assertNative("php85_string_offset_references");
    }

    @Test public void testNativeStrictStringOffsetReferences() throws Throwable {
        assertNative("php85_string_offset_references_strict");
    }

    private void assertNative(String fixture) throws Throwable {
        var differences=new ArrayList<String>();
        for (boolean dumped : new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context=new Context(resource(fixture+".php").substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                var trace=php.runtime.env.TraceInfo.valueOf(context.getFileName(),0,0,module.getInternalName());
                var actual=php.runtime.memory.output.serialization.PhpSerialization.unserialize(env,trace,module.include(env));
                var expected=php.runtime.memory.output.serialization.PhpSerialization.unserialize(env,trace,
                        php.runtime.memory.StringMemory.valueOf(resource(fixture+".expected")));
                int count=expected.toValue(php.runtime.memory.ArrayMemory.class).size();
                assertEquals(fixture,count,actual.toValue(php.runtime.memory.ArrayMemory.class).size());
                for (int i=0;i<count;i++) {
                    String wanted=php.runtime.memory.output.serialization.PhpSerialization.serialize(env,trace,expected.valueOfIndex(i)).toString();
                    String got=php.runtime.memory.output.serialization.PhpSerialization.serialize(env,trace,actual.valueOfIndex(i)).toString();
                    if (!wanted.equals(got)) differences.add("dump="+dumped+" row="+i+" expected="+wanted+" actual="+got);
                }
            } finally {env.doFinal();}
        }
        assertEquals(fixture+"\n"+String.join("\n",differences),0,differences.size());
    }

    private String resource(String name) throws java.io.IOException {
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/"+name)) {
            assertNotNull(name,input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
