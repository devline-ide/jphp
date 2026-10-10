package org.develnext.jphp.json.gson;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.json.JsonJvmTestCase;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class JsonRepeatedIdentityOracleTest extends JsonJvmTestCase {
    @Test public void nativeRepeatedIdentityThroughBothJsonEntryPoints() throws Throwable {
        String source = resource("json_repeated_identity.php").substring(5);
        String expected = resource("json_repeated_identity.expected");
        for (boolean processor : new boolean[] {false, true}) {
            String code = processor ? source.replace("return json_encode($value);",
                    "return (new \\php\\format\\JsonProcessor())->format($value);") : source;
            for (boolean debug : new boolean[] {false, true}) {
                for (boolean dumped : new boolean[] {false, true}) {
                    Environment env = new Environment(newScope(), new ByteArrayOutputStream());
                    try {
                        env.getScope().setDebugMode(debug);
                        env.getScope().registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                        Context context = new Context(code);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context, env, true);
                            var bytes = new ByteArrayOutputStream();
                            dumper.save(module, bytes);
                            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                        env.getScope().loadModule(module);
                        env.getScope().addUserModule(module);
                        env.registerModule(module);
                        StringWriter diagnostics = new StringWriter();
                        PrintWriter writer = new PrintWriter(diagnostics);
                        var loader = env.getScope().getClassLoader();
                        CheckClassAdapter.verify(new ClassReader(module.getData()), loader, false, writer);
                        for (var clazz : module.getClasses()) {
                            if (clazz.getData() != null && clazz.getData().length != 0)
                                CheckClassAdapter.verify(new ClassReader(clazz.getData()), loader, false, writer);
                        }
                        for (var closure : module.getClosures())
                            CheckClassAdapter.verify(new ClassReader(closure.getData()), loader, false, writer);
                        assertEquals("", diagnostics.toString());
                        var values = module.include(env);
                        StringBuilder actual = new StringBuilder();
                        for (int i = 0; i < values.toValue(php.runtime.memory.ArrayMemory.class).size(); i++)
                            actual.append(values.valueOfIndex(i)).append('\n');
                        assertEquals("processor=" + processor + " debug=" + debug + " dumped=" + dumped,
                                expected, actual.toString());
                    } finally { env.doFinal(); }
                }
            }
        }
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/json/" + name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
