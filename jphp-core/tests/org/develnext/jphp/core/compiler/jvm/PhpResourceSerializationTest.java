package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhpResourceSerializationTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "resources/type_hinting/php85_resource_serialization." + suffix)) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test public void testNativeResourceValuesAndReferenceIdentity() throws Throwable {
        String[] expected = resource("expected").trim().split("\\r?\\n");
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new StandardExtension());
            try {
                Context context = new Context(resource("php").substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);
                env.getScope().addUserModule(module);
                env.registerModule(module);
                ArrayMemory actual = module.include(env).toValue(ArrayMemory.class);
                assertEquals(expected.length, actual.size());
                for (int i = 0; i < expected.length; i++)
                    assertEquals("dump=" + dumped + " row=" + i, expected[i], actual.valueOfIndex(null, i).toString());
            } finally { env.doFinal(); }
        }
    }
}
