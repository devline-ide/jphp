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

public class PhpOutputLifecycleTest {
    private static byte[] resource(String name) throws IOException {
        try (var input = PhpOutputLifecycleTest.class.getClassLoader().getResourceAsStream(
                "resources/native-oracle/output-lifecycle/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    private static Environment environment(ByteArrayOutputStream output) {
        var scope = new CompileScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope, output);
    }
    private static void include(Environment env, String source, boolean debug, boolean phb) throws Throwable {
        var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(debug);
        if (phb) {
            var bytes = new ByteArrayOutputStream(); var codec = new ModuleDumper(context, env, debug);
            codec.save(module, bytes); module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        module.include(env);
    }
    @Test public void automaticChunksAndFinalCallbacksMatchNativeSourcePhb() throws Throwable {
        var cases = new String(resource("catalog.txt"), StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(24, cases.length); int variants = 0; var failures = new ArrayList<String>();
        for (String name : cases) for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            variants++; var output = new ByteArrayOutputStream(); var env = environment(output);
            try {
                include(env, new String(resource(name + ".php"), StandardCharsets.UTF_8).substring(5), debug, phb);
                env.flushAll();
                if (!Arrays.equals(resource(name + ".stdout"), output.toByteArray())) {
                    Files.write(Path.of(System.getProperty("java.io.tmpdir")).getParent().resolve(name + "-" + debug + "-" + phb + ".actual"), output.toByteArray());
                    failures.add(name + " debug=" + debug + " PHB=" + phb + " expected="
                            + new String(resource(name + ".stdout"), StandardCharsets.UTF_8) + " actual=" + output.toString(StandardCharsets.UTF_8));
                }
            } catch (Throwable failure) { failures.add(name + " " + failure); }
            finally { env.doFinal(); }
        }
        assertEquals(96, variants);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }
    @Test public void failedFinalCallbackRetiresOnceAndParentCanFinishOnHostRetry() throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean phb : new boolean[]{false,true}) {
            var output = new ByteArrayOutputStream(); var env = environment(output);
            try {
                include(env, "$GLOBALS['calls']=0;ob_start(function($s,$p){return 'top['.$s.']';});"
                        + "ob_start(function($s,$p){$GLOBALS['calls']++;throw new Exception('final-failed');});echo 'payload';", debug, phb);
                var failed = env.peekOutputBuffer();
                try { env.flushAll(); fail("Expected original final callback exception"); }
                catch (php.runtime.lang.exception.BaseBaseException failure) {
                    assertEquals("final-failed", failure.getMessage(env).toString());
                }
                assertEquals(1, env.getGlobals().valueOfIndex("calls").toInteger());
                assertEquals(2, env.getOutputBuffers().size());
                failed.close();
                assertEquals(1, env.getGlobals().valueOfIndex("calls").toInteger());
                env.flushAll();
                assertEquals("top[payload]", output.toString(StandardCharsets.UTF_8));
            } finally { env.doFinal(); }
        }
    }
}
