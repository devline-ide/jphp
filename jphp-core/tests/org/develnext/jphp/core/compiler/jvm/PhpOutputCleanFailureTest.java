package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;

public class PhpOutputCleanFailureTest {
    private static byte[] resource(String name) throws IOException {
        try (var input = PhpOutputCleanFailureTest.class.getClassLoader().getResourceAsStream("resources/native-oracle/output-clean-failures/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    private static Environment environment(PhpLanguageProfile profile, ByteArrayOutputStream output) {
        var scope = new CompileScope(); scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new php.runtime.ext.CoreExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        return new Environment(scope, output);
    }
    private static void include(Environment env, String code, PhpLanguageProfile profile, boolean debug, boolean phb) throws Throwable {
        var context = new Context(code); context.setPhpLanguageProfile(profile);
        var module = new JvmCompiler(env, context).compile(debug);
        if (phb) {
            var bytes = new ByteArrayOutputStream(); var codec = new ModuleDumper(context, env, debug);
            codec.save(module, bytes); module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        module.include(env); env.flushAll();
    }
    @Test public void failedNonFinalHandlerStaysDisabledAndPassesBytesThroughSourcePhb() throws Throwable {
        for (String mode : new String[]{"weak", "strict"})
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var output = new ByteArrayOutputStream(); var env = environment(PhpLanguageProfile.PHP_8_5, output);
                try {
                    include(env, new String(resource(mode + ".php"), StandardCharsets.UTF_8).substring(5), PhpLanguageProfile.PHP_8_5, debug, phb);
                    java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).getParent()
                            .resolve(mode + "-" + debug + "-" + phb + ".actual"), output.toByteArray());
                    assertArrayEquals(mode + " debug=" + debug + " PHB=" + phb, resource(mode + ".stdout"), output.toByteArray());
                } finally { env.doFinal(); }
            }
    }
}
