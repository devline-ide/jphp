package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;

public class PhpOutputCapabilitiesTest {
    private static byte[] resource(String name) throws IOException {
        try (var input = PhpOutputCapabilitiesTest.class.getClassLoader().getResourceAsStream(
                "resources/native-oracle/output-capabilities/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    private static Environment environment(ByteArrayOutputStream output) {
        var scope = new CompileScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        return new Environment(scope, output);
    }
    private static void execute(Environment env, String source, boolean debug, boolean phb) throws Throwable {
        var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(debug);
        if (phb) {
            var bytes = new ByteArrayOutputStream(); var codec = new ModuleDumper(context, env, debug);
            codec.save(module, bytes); module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        module.include(env); env.flushAll();
    }
    @Test public void capabilitySemanticsKeepNativeValuesNoticesAndRetainedBuffersSourcePhb() throws Throwable {
        var cases = new String(resource("catalog.txt"), StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(192, cases.length);
        var failures = new ArrayList<String>(); int variants = 0;
        for (String name : cases) for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            variants++; var output = new ByteArrayOutputStream(); var env = environment(output);
            try {
                execute(env, new String(resource(name + ".php"), StandardCharsets.UTF_8).substring(5), debug, phb);
                byte[] expected = resource(name + ".stdout");
                if (!Arrays.equals(expected, output.toByteArray())) {
                    Files.write(Path.of(System.getProperty("java.io.tmpdir")).getParent().resolve(name + "-" + debug + "-" + phb + ".actual"), output.toByteArray());
                    failures.add(name + " debug=" + debug + " PHB=" + phb + " expected="
                            + new String(expected, StandardCharsets.UTF_8) + " actual=" + output.toString(StandardCharsets.UTF_8));
                }
            } catch (Throwable failure) { failures.add(name + " " + failure); }
            finally { env.doFinal(); }
        }
        assertEquals(768, variants);
        assertTrue("Differences=" + failures.size() + "\n" + String.join("\n", failures.subList(0, Math.min(12, failures.size()))), failures.isEmpty());
    }
    @Test public void standardOutputCapabilityConstantsAreAvailableToPhp() throws Throwable {
        var output = new ByteArrayOutputStream(); var env = environment(output);
        try {
            execute(env, "echo PHP_OUTPUT_HANDLER_CLEANABLE,':',PHP_OUTPUT_HANDLER_FLUSHABLE,':',PHP_OUTPUT_HANDLER_REMOVABLE,':',PHP_OUTPUT_HANDLER_STDFLAGS;", false, false);
            assertEquals("16:32:64:112", output.toString(StandardCharsets.UTF_8));
        } finally { env.doFinal(); }
    }
}
