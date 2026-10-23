package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;

/** Pending real native stdout binding; no Java-produced expected behavior. */
public class PhpDeprecatedRouteContractTest {
    private static Path fixtures;
    @BeforeClass public static void inputs() throws Exception {
        fixtures = PhpPortableTestInputs.fixture("qa.php.deprecated", "php-deprecated");
    }
    @AfterClass public static void releaseInputs() throws Exception {
        PhpPortableTestInputs.release(fixtures);
        fixtures = null;
    }
    private static java.util.List<String> deterministic(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("destructor-unset-native-lifetime:")).toList();
    }
    private static void compare(String name, int rows) throws Throwable {
        byte[] expected = Files.readAllBytes(fixtures.resolve(name + ".stdout"));
        assertEquals("Native row count", rows, new String(expected, StandardCharsets.UTF_8).lines().count());
        assertEquals("Native stderr", 0, Files.size(fixtures.resolve(name + ".stderr")));
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
            var scope = new CompileScope();
            scope.setDebugMode(debug);
            scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());
            scope.registerExtension(new php.runtime.ext.SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var output = new ByteArrayOutputStream();
            var env = new Environment(scope, output);
            Throwable primary = null;
            try {
                Path source = fixtures.resolve(name + ".php");
                var context = new Context(Files.readString(source, StandardCharsets.UTF_8), source.toFile());
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (phb) {
                    var bytes = new ByteArrayOutputStream();
                    var dumper = new ModuleDumper(context, env, debug);
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    assertTrue(module.isCompiledArtifact());
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                module.include(env); env.flushAll();
                String evidence = System.getProperty("qa.php.deprecated.evidence");
                if (evidence != null) Files.write(Path.of(evidence).resolve(name + "-" + debug + "-" + phb + ".stdout"),
                        output.toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW);
                if (name.equals("lifecycle")) {
                    // Retain raw output; native immediate unset destruction is a separately recorded timing observation.
                    assertEquals("lifecycle debug=" + debug + " PHB=" + phb, deterministic(expected), deterministic(output.toByteArray()));
                } else assertArrayEquals(name + " debug=" + debug + " PHB=" + phb, expected, output.toByteArray());
                assertEquals("Declaration profile must survive legacy scope", PhpLanguageProfile.JPHP_LEGACY, scope.getPhpLanguageProfile());
            } catch (Throwable failure) { primary = failure; throw failure; }
            finally {
                try { env.doFinal(); }
                catch (Throwable cleanup) { if (primary == null) throw cleanup; if (cleanup != primary) primary.addSuppressed(cleanup); }
            }
        }
    }
    @Test(timeout=120000) public void route0() throws Throwable { compare("provider-dynamic", 1); }
    @Test(timeout=120000) public void route1() throws Throwable { compare("trait-dependent-visibility", 6); }
    @Test(timeout=120000) public void route2() throws Throwable { compare("magic-accessors", 6); }
    @Test(timeout=120000) public void route3() throws Throwable { compare("lifecycle", 5); }
    @Test(timeout=120000) public void route4() throws Throwable { compare("property-hooks", 4); }
    @Test(timeout=120000) public void route5() throws Throwable { compare("namespace-trait-alias", 4); }
    @Test(timeout=120000) public void route6() throws Throwable { compare("anonymous-trait", 3); }
}
