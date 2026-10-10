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
import php.runtime.memory.ArrayMemory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class JsonDuplicateWireOracleTest extends JsonJvmTestCase {
    @Test public void nativeDuplicateWireThroughBothJsonEntryPoints() throws Throwable {
        String source = resource("php85_json_duplicate_wire.php").substring(5);
        String[] expected = resource("php85_json_duplicate_wire.expected").split("\n");
        assertEquals(12, expected.length);
        List<String> differences = new ArrayList<>();
        for (boolean processor : new boolean[] {false, true}) {
            String code = processor ? source.replace("return json_encode($value);",
                    "return (new \\php\\format\\JsonProcessor(\\php\\format\\JsonProcessor::SERIALIZE_NULLS))->format($value);") : source;
            for (boolean strict : new boolean[] {false, true}) {
                for (boolean debug : new boolean[] {false, true}) {
                    for (boolean dumped : new boolean[] {false, true}) {
                        String mode = "processor=" + processor + " strict=" + strict + " debug=" + debug + " dumped=" + dumped;
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        Environment env = new Environment(newScope(), output);
                        try {
                            env.getScope().setDebugMode(debug);
                            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                            Context context = new Context((strict ? "declare(strict_types=1);\n" : "") + code);
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
                            assertEquals(mode + " ASM", "", diagnostics.toString());
                            ArrayMemory values = module.include(env).toValue(ArrayMemory.class);
                            assertEquals(mode + " rows", expected.length, values.size());
                            for (int i = 0; i < expected.length; i++) {
                                String actual = values.valueOfIndex(i).toString();
                                if (!expected[i].equals(actual)) differences.add(mode + " row=" + (i + 1)
                                        + " expected=" + expected[i] + " actual=" + actual);
                            }
                            if (output.size() != 0) differences.add(mode + " uncaptured output=" + output.toString(StandardCharsets.UTF_8));
                        } finally { env.doFinal(); }
                    }
                }
            }
        }
        assertTrue(differences.size() + " differences\n" + String.join("\n", differences.subList(0, Math.min(20, differences.size()))), differences.isEmpty());
    }
    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/json/" + name)) {
            assertNotNull(name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
