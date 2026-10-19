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

/** Actual PHP 8.5.11 strlen scalar, byte-length and diagnostic routes. */
public class PhpStrlenNativeContractTest {
    @Test public void debugSourceAndFreshDump() throws Throwable { check(true); }
    @Test public void optimizedSourceAndFreshDump() throws Throwable { check(false); }
    private void check(boolean debug) throws Throwable {
        String fixtures;
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/strlen/native-routes.tsv")) {
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

    @Test public void legacyBehaviorRemainsIndependentOfModernByteContract() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = new CompileScope();scope.registerExtension(new CoreExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context("$warnings=[];set_error_handler(function($n,$s)use(&$warnings){$warnings[]=$s;return true;});$bad=strlen([]);restore_error_handler();return json_encode([strlen('abc'),strlen('é🙂'),$bad,count($warnings)]);");
                context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertEquals("[3,3,null,1]", module.include(env).toString());
            } finally {try {env.doFinal();} finally {scope.getClassLoader().close();}}
        }
    }
}
