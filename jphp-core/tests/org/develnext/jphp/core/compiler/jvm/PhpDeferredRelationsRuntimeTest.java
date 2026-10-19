package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

/** Parent-pinned runtime controls; fatal declaration rows are deliberately separate from positives. */
public class PhpDeferredRelationsRuntimeTest extends JvmCompilerCase {
    @Override protected CompileScope newScope() {
        var scope = super.newScope();
        scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return scope;
    }

    private record Row(String name, boolean strict, String source, String output) {}

    private List<Row> rows(String resource, int count) throws IOException {
        var input = getClass().getResourceAsStream("/resources/functions/" + resource);
        // Some existing QA classpaths place resources/functions at the classpath root.
        if (input == null) input = getClass().getResourceAsStream("/functions/" + resource);
        assertNotNull("Parent must pin and install this resource: " + resource, input);
        var result = new ArrayList<Row>();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var c = line.split("\\t", -1); assertEquals(resource, 4, c.length);
                result.add(new Row(c[0], Boolean.parseBoolean(c[1]), decode(c[2]), decode(c[3])));
            }
        }
        assertEquals(resource, count, result.size()); return result;
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private ModuleEntity compile(Environment env, String source, boolean phb) throws Throwable {
        var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (phb) {
            var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module);
        return module;
    }

    private void check(Row row, boolean debug, boolean phb) throws Throwable {
        var scope = newScope(); scope.setDebugMode(debug);
        var output = new ByteArrayOutputStream(); var env = new Environment(scope, output);
        try {
            var module = compile(env, row.source(), phb); env.registerModule(module);
            module.include(env); env.doFinal();
            assertEquals(row.name() + " strict=" + row.strict() + " debug=" + debug + " PHB=" + phb,
                    row.output(), output.toString(StandardCharsets.UTF_8));
        } finally {
            try { env.doFinal(); } finally { scope.getClassLoader().close(); }
        }
    }

    private void corpus(String resource, int count) throws Throwable {
        var failures = new ArrayList<String>();
        for (var row : rows(resource, count)) for (boolean debug : new boolean[]{false, true})
            for (boolean phb : new boolean[]{false, true}) {
                try { check(row, debug, phb); }
                catch (Throwable failure) { failures.add(row.name() + " strict=" + row.strict()
                        + " debug=" + debug + " PHB=" + phb + " " + failure); }
            }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test(timeout = 120000) public void nativeTraitCompositionContextsAliasesAndInitializersSourceAndDump() throws Throwable {
        corpus("php85_deferred_relations_positive.tsv", 38);
    }

    @Test(timeout = 60000) public void ambiguousAliasAndFinalOverrideEscapePhpCatchWithNativeDiagnostic() throws Throwable {
        var failures = new ArrayList<String>();
        for (var row : rows("php85_deferred_relations_fatal.tsv", 4))
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var scope = newScope(); scope.setDebugMode(debug);
                var output = new ByteArrayOutputStream(); var env = new Environment(scope, output);
                try {
                    var module = compile(env, row.source(), phb); env.registerModule(module);
                    Throwable failure = null;
                    try { module.include(env); } catch (Throwable actual) { failure = actual; }
                    assertNotNull(row.name() + " must escape PHP catch(Throwable)", failure);
                    assertTrue(row.name() + " fatal transport: " + failure,
                            failure instanceof php.runtime.exceptions.support.ErrorException);
                    assertEquals(row.name(), row.output(), failure.getMessage());
                    assertEquals("No fabricated caught-Error result", "", output.toString(StandardCharsets.UTF_8));
                } catch (Throwable failure) {
                    failures.add(row.name() + " strict=" + row.strict() + " debug=" + debug + " PHB=" + phb + " " + failure);
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test(timeout = 120000) public void conditionalTraitsBindActualDonorsPerEnvironmentWithSharedScopeSourceAndDump() throws Throwable {
        var all = rows("php85_deferred_relations_shared4.tsv", 4);
        for (boolean strict : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true})
            for (boolean phb : new boolean[]{false, true}) for (boolean firstLeft : new boolean[]{false, true}) {
                var left = all.stream().filter(r -> r.strict() == strict && r.name().equals("shared_trait_left")).findFirst().orElseThrow();
                var right = all.stream().filter(r -> r.strict() == strict && r.name().equals("shared_trait_right")).findFirst().orElseThrow();
                // Native pin rows inject $choose. The executable shared source has no such assignment.
                var source = left.source().replace("$chooseA=true;\n", "");
                assertEquals(source, right.source().replace("$chooseA=false;\n", ""));
                var scope = newScope(); scope.setDebugMode(debug);
                var out1 = new ByteArrayOutputStream(); var out2 = new ByteArrayOutputStream();
                var env1 = new Environment(scope, out1); var env2 = new Environment(scope, out2);
                try {
                    var module = compile(env1, source, phb);
                    assertNull(env1.fetchClass("VariantTrait", false));
                    assertNull(env2.fetchClass("VariantTrait", false));
                    env1.getGlobals().refOfIndex("chooseA").assign(firstLeft ? Memory.TRUE : Memory.FALSE);
                    env2.getGlobals().refOfIndex("chooseA").assign(firstLeft ? Memory.FALSE : Memory.TRUE);
                    env1.registerModule(module); env2.registerModule(module);
                    // Same ModuleEntity/compiled recipe, independently selected actual trait Class identities.
                    module.include(env1); module.include(env2); env1.doFinal(); env2.doFinal();
                    assertEquals("first env strict=" + strict + " debug=" + debug + " PHB=" + phb,
                            (firstLeft ? left : right).output(), out1.toString(StandardCharsets.UTF_8));
                    assertEquals("second env strict=" + strict + " debug=" + debug + " PHB=" + phb,
                            (firstLeft ? right : left).output(), out2.toString(StandardCharsets.UTF_8));
                    var parent1 = env1.fetchClass("VariantTrait", false);
                    var parent2 = env2.fetchClass("VariantTrait", false);
                    var child1 = env1.fetchClass("VariantConsumer", false);
                    var child2 = env2.fetchClass("VariantConsumer", false);
                    assertNotNull(parent1); assertNotNull(parent2); assertNotNull(child1); assertNotNull(child2);
                    assertNotSame("different branches must select different parent templates", parent1, parent2);
                    assertSame(parent1, child1.getTraits().values().iterator().next()); assertSame(parent2, child2.getTraits().values().iterator().next());
                    assertNotSame("effective child entities cannot leak first binding", child1, child2);
                    assertNotSame(child1.getNativeClass(), child2.getNativeClass());
                    assertNotSame(parent1.getNativeClass(), parent2.getNativeClass());
                    assertSame(php.runtime.lang.BaseObject.class, child1.getNativeClass().getSuperclass());
                    assertSame(php.runtime.lang.BaseObject.class, child2.getNativeClass().getSuperclass());
                    assertTrue(scope.getClassLoader().matchesDefinedBytecode(child1.getNativeClass(), child1.getData()));
                    assertTrue(scope.getClassLoader().matchesDefinedBytecode(child2.getNativeClass(), child2.getData()));
                } finally {
                    try { env1.doFinal(); } finally {
                        try { env2.doFinal(); } finally { scope.getClassLoader().close(); }
                    }
                }
            }
    }
}
