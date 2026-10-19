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
public class PhpNestedNamedClassRuntimeTest extends JvmCompilerCase {
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

    @Test(timeout = 120000) public void unchangedTwentyNamedClassRowsSourceAndDump() throws Throwable {
        corpus("php85_nested_named_original20.tsv", 20);
    }

    @Test(timeout = 120000) public void validDeclarationTimingAndLexicalScopeSourceAndDump() throws Throwable {
        corpus("php85_nested_named_runtime16.tsv", 16);
    }

    @Test(timeout = 60000) public void nativeCompileFatalMethodNestedDeclarationsAreRejectedBeforeExecution() throws Throwable {
        for (var row : rows("php85_nested_named_compile_fatal4.tsv", 4))
            for (boolean debug : new boolean[]{false, true}) {
                var scope = newScope(); scope.setDebugMode(debug);
                var output = new ByteArrayOutputStream(); var env = new Environment(scope, output);
                try {
                    try { compile(env, row.source(), false); fail(row.name() + " must fail during compilation"); }
                    catch (php.runtime.exceptions.CompileException expected) {
                        assertEquals(row.name(), row.output(), expected.getMessage());
                    }
                    assertEquals("No module body may execute", "", output.toString(StandardCharsets.UTF_8));
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
    }

    @Test(timeout = 60000) public void nativeRepeatedDeclarationIsFatalAndNotCaughtByPhpThrowable() throws Throwable {
        for (var row : rows("php85_nested_named_execution_fatal4.tsv", 4))
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var scope = newScope(); scope.setDebugMode(debug);
                var output = new ByteArrayOutputStream(); var env = new Environment(scope, output);
                try {
                    var module = compile(env, row.source(), phb); env.registerModule(module);
                    Throwable failure = null;
                    try { module.include(env); } catch (Throwable actual) { failure = actual; }
                    assertNotNull(row.name() + " must escape the fixture's PHP catch(Throwable)", failure);
                    assertTrue(row.name() + " fatal host transport: " + failure,
                            failure instanceof php.runtime.exceptions.support.ErrorException);
                    // Native path/line/stack formatting belongs to the process runner. Only strip the
                    // exact previous-declaration location suffix; compare the genuine core diagnostic.
                    var message = failure.getMessage();
                    int previous = message.indexOf(" (previously declared in ");
                    if (previous >= 0) message = message.substring(0, previous);
                    assertEquals(row.name(), row.output(), message);
                    assertEquals("No fabricated caught-Error JSON result", "", output.toString(StandardCharsets.UTF_8));
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
    }

    @Test(timeout = 120000) public void deferredTraitsAndInterfacesRemainExplicitRuntimeRequirements() throws Throwable {
        // These are real PHP contracts, not omitted/ignored cases if the staged runtime is incomplete.
        corpus("php85_nested_named_frontiers8.tsv", 8);
    }

    @Test(timeout = 120000) public void conditionalParentsBindPerEnvironmentWithSharedScopeSourceAndDump() throws Throwable {
        var all = rows("php85_nested_named_shared4.tsv", 4);
        for (boolean strict : new boolean[]{false, true}) for (boolean debug : new boolean[]{false, true})
            for (boolean phb : new boolean[]{false, true}) for (boolean firstLeft : new boolean[]{false, true}) {
                var left = all.stream().filter(r -> r.strict() == strict && r.name().equals("shared_parent_left")).findFirst().orElseThrow();
                var right = all.stream().filter(r -> r.strict() == strict && r.name().equals("shared_parent_right")).findFirst().orElseThrow();
                // Native pin rows inject $choose. The executable shared source has no such assignment.
                var source = left.source().replace("$choose=true;\n", "");
                assertEquals(source, right.source().replace("$choose=false;\n", ""));
                var scope = newScope(); scope.setDebugMode(debug);
                var out1 = new ByteArrayOutputStream(); var out2 = new ByteArrayOutputStream();
                var env1 = new Environment(scope, out1); var env2 = new Environment(scope, out2);
                try {
                    var module = compile(env1, source, phb);
                    assertNull(env1.fetchClass("VariantParent", false));
                    assertNull(env2.fetchClass("VariantParent", false));
                    env1.getGlobals().refOfIndex("choose").assign(firstLeft ? Memory.TRUE : Memory.FALSE);
                    env2.getGlobals().refOfIndex("choose").assign(firstLeft ? Memory.FALSE : Memory.TRUE);
                    env1.registerModule(module); env2.registerModule(module);
                    // Same ModuleEntity/defined bytes, independently selected deferred parents.
                    module.include(env1); module.include(env2); env1.doFinal(); env2.doFinal();
                    assertEquals("first env strict=" + strict + " debug=" + debug + " PHB=" + phb,
                            (firstLeft ? left : right).output(), out1.toString(StandardCharsets.UTF_8));
                    assertEquals("second env strict=" + strict + " debug=" + debug + " PHB=" + phb,
                            (firstLeft ? right : left).output(), out2.toString(StandardCharsets.UTF_8));
                    var parent1 = env1.fetchClass("VariantParent", false);
                    var parent2 = env2.fetchClass("VariantParent", false);
                    var child1 = env1.fetchClass("VariantChild", false);
                    var child2 = env2.fetchClass("VariantChild", false);
                    assertNotNull(parent1); assertNotNull(parent2); assertNotNull(child1); assertNotNull(child2);
                    assertNotSame("different branches must select different parent templates", parent1, parent2);
                    assertSame(parent1, child1.getParent()); assertSame(parent2, child2.getParent());
                    assertNotSame("effective child entities cannot leak first binding", child1, child2);
                    assertNotSame(child1.getNativeClass(), child2.getNativeClass());
                    assertSame(parent1.getNativeClass(), child1.getNativeClass().getSuperclass());
                    assertSame(parent2.getNativeClass(), child2.getNativeClass().getSuperclass());
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
