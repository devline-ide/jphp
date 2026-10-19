package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

/** Actual PHP 8.5.11 hex2bin byte results, scalar routes and exact diagnostics. */
public class PhpHex2binNativeContractTest {
    @Test public void debugSourceAndFreshDump() throws Throwable { check(true); }
    @Test public void optimizedSourceAndFreshDump() throws Throwable { check(false); }
    private void check(boolean debug) throws Throwable {
        String fixtures;
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/hex2bin/native-routes.tsv")) {
            assertNotNull(input);fixtures = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String row : fixtures.split("\\R")) {
            if (row.isEmpty()) continue;
            var cells = row.split("\\t");
            String source = new String(Base64.getDecoder().decode(cells[1]), StandardCharsets.UTF_8);
            String expected = new String(Base64.getDecoder().decode(cells[2]), StandardCharsets.UTF_8);
            for (boolean dumped : new boolean[]{false, true}) {
                var scope = new CompileScope();scope.registerExtension(new CoreExtension());
                scope.registerExtension(new SPLExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
                scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var context = new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    var module = new JvmCompiler(env, context).compile(false);
                    if (dumped) {
                        var dumper = new ModuleDumper(context, env, true);var bytes = new ByteArrayOutputStream();
                        dumper.save(module, bytes);module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    }
                    scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                    assertEquals(cells[0] + " debug=" + debug + " dump=" + dumped,
                            expected, module.include(env).toString());
                } finally {try {env.doFinal();} finally {scope.getClassLoader().close();}}
            }
        }
    }

    @Test public void historicalJavaDescriptorAndLegacyBehaviorRemain() throws Throwable {
        var historical = org.develnext.jphp.zend.ext.standard.StringFunctions.class.getDeclaredMethod("hex2bin", php.runtime.Memory.class);
        assertEquals(String.class, historical.getReturnType());
        assertTrue(historical.isAnnotationPresent(php.runtime.annotation.Reflection.Ignore.class));
        assertEquals("\u00ff\u0000\u0080\u00c3\u00a9", historical.invoke(null, php.runtime.memory.StringMemory.valueOf("ff0080c3a9")));
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = new CompileScope();scope.registerExtension(new CoreExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context("return json_encode([bin2hex(hex2bin('ff0080c3a9')),bin2hex(hex2bin('fg1'))]);");
                context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertEquals("[\"ff0080c3a9\",\"f0\"]", module.include(env).toString());
            } finally {try {env.doFinal();} finally {scope.getClassLoader().close();}}
        }
    }
}
