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

public class PhpOutputArgumentsTest {
    private static byte[] resource(String name) throws IOException {
        try (var input = PhpOutputArgumentsTest.class.getClassLoader().getResourceAsStream(
                "resources/native-oracle/output-arguments/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    @Test public void modernStartArgumentsMatchNativeTypesCallbacksOrderAndNamedDefaults() throws Throwable {
        var cases = new String(resource("catalog.txt"), StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(134, cases.length);
        var failures = new ArrayList<String>(); int variants = 0;
        for (String name : cases) for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            variants++;
            var scope = new CompileScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new php.runtime.ext.CoreExtension());
            scope.registerExtension(new php.runtime.ext.SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                String source = new String(resource(name + ".php"), StandardCharsets.UTF_8).substring(5);
                // Native reports bypass PHP buffers through STDOUT. Return the same JSON before host finalization.
                assertTrue(source.endsWith("));"));
                assertEquals(1, source.split("fwrite\\(STDOUT,", -1).length - 1);
                source = source.replace("fwrite(STDOUT,", "return ");
                source = source.substring(0, source.length() - 3) + ");";
                var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(debug);
                if (phb) {
                    var bytes = new ByteArrayOutputStream(); var codec = new ModuleDumper(context, env, debug);
                    codec.save(module, bytes); module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                byte[] actual = module.include(env).getBinaryBytes(StandardCharsets.UTF_8);
                byte[] expected = resource(name + ".stdout");
                if (!Arrays.equals(expected, actual)) {
                    Files.write(Path.of(System.getProperty("java.io.tmpdir")).getParent()
                            .resolve(name + "-" + debug + "-" + phb + ".actual"), actual);
                    failures.add(name + " debug=" + debug + " PHB=" + phb + " expected="
                            + new String(expected, StandardCharsets.UTF_8) + " actual=" + new String(actual, StandardCharsets.UTF_8));
                }
            } catch (Throwable failure) { failures.add(name + " debug=" + debug + " PHB=" + phb + " " + failure); }
            finally { env.doFinal(); }
        }
        assertEquals(536, variants);
        assertTrue("Differences=" + failures.size() + "\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))), failures.isEmpty());
    }
    @Test public void legacyStartKeepsItsOriginalStringArgumentWarnings() throws Throwable {
        for (int argument : new int[]{2,3}) for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            var scope = new CompileScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                String parameters = argument == 2 ? "null,'1',true" : "null,0,'112'";
                var context = new Context("$warnings=[];set_error_handler(function($n,$m)use(&$warnings){$warnings[]=[$n,$m];return true;});"
                        + "$r=ob_start(" + parameters + ");return [$r,$warnings,ob_get_level()];");
                context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module = new JvmCompiler(env, context).compile(debug);
                if (phb) {
                    var bytes = new ByteArrayOutputStream(); var codec = new ModuleDumper(context, env, debug);
                    codec.save(module, bytes); module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                var result = module.include(env);
                assertTrue(result.valueOfIndex(0).isNull());
                assertEquals(0, result.valueOfIndex(2).toLong());
                assertEquals(1, result.valueOfIndex(1).toValue(php.runtime.memory.ArrayMemory.class).size());
                assertEquals(2, result.valueOfIndex(1).valueOfIndex(0).valueOfIndex(0).toLong());
                assertEquals("ob_start() expects parameter " + argument + " to be long, string given",
                        result.valueOfIndex(1).valueOfIndex(0).valueOfIndex(1).toString());
            } finally { env.doFinal(); }
        }
    }
}
