package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.NamespaceStmtToken;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ContextClassImportsTest extends JvmCompilerCase {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Context context(String source, PhpLanguageProfile profile, String... names) {
        Context context = new Context(source);
        context.setPhpLanguageProfile(profile);
        context.setImplicitClassImports(Arrays.asList(names));
        return context;
    }

    private Memory runContext(Environment env, Context context, boolean dumped) throws Throwable {
        ModuleEntity module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ModuleDumper dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module);
        env.getScope().addUserModule(module);
        env.registerModule(module);
        return module.include(env);
    }

    private File source(String name, String code) throws Exception {
        File file = temporary.newFile(name);
        Files.writeString(file.toPath(), "<?php " + code, StandardCharsets.UTF_8);
        return file;
    }

    private String quoted(File file) { return "'" + file.getPath().replace("\\", "/").replace("'", "\\'") + "'"; }

    @Test public void testImmutableValidatedDeterministicMetadata() throws Throwable {
        List<String> input = new ArrayList<>(Arrays.asList("\\Vendor\\Widget", "Vendor\\Класс", "Vendor\\Éclair",
                "Vendor\\١Abc", "Vendor\\😀", "Vendor\\Widget"));
        Context first = new Context("return 1;");
        first.setImplicitClassImports(input);
        input.clear();
        assertEquals(5, first.getImplicitClassImports().size());
        assertEquals("Vendor\\Widget", first.getImplicitClassImports().get("widget"));
        try { first.getImplicitClassImports().clear(); fail("Mutable import map"); }
        catch (UnsupportedOperationException expected) { }
        Context second = new Context("return 1;");
        List<String> reversed = new ArrayList<>(first.getImplicitClassImports().values());
        Collections.reverse(reversed);
        second.setImplicitClassImports(reversed);
        assertEquals(first.getImplicitClassImportsFingerprint(), second.getImplicitClassImportsFingerprint());
        assertEquals(first.getModuleName(), second.getModuleName());
        assertNotEquals(new Context("return 1;").getModuleName(), first.getModuleName());
        first.freezeImplicitClassImports();
        first.setImplicitClassImports(reversed); // Identical configuration remains idempotent.
        try { first.setImplicitClassImports(Collections.singleton("Other\\Widget")); fail("Changed frozen metadata"); }
        catch (IllegalStateException expected) { }
        Context empty = new Context("return 1;");
        empty.freezeImplicitClassImports();
        try { empty.setImplicitClassImports(Collections.singleton("Vendor\\Widget")); fail("Changed frozen default"); }
        catch (IllegalStateException expected) { }
        for (String invalid : Arrays.asList("", "A\\", "\\\\A", "A\\\\B", "A\\123Bad", "A\\bad-name", "A\\bad name",
                "A\\B;echo 1", "A\\B\u007f", "A\\\ud800", "A\\self", "A\\STRING")) {
            Context candidate = new Context("return 1;");
            try { candidate.setImplicitClassImports(Collections.singleton(invalid)); fail("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
            assertTrue(candidate.getImplicitClassImports().isEmpty());
            candidate.setImplicitClassImports(Collections.singleton("Vendor\\Widget"));
        }
        String message = null;
        for (List<String> collision : Arrays.asList(Arrays.asList("Z\\Widget", "A\\widget"), Arrays.asList("A\\widget", "Z\\Widget"))) {
            try { new Context("").setImplicitClassImports(collision); fail("Accepted duplicate alias"); }
            catch (IllegalArgumentException expected) {
                if (message == null) message = expected.getMessage();
                else assertEquals(message, expected.getMessage());
            }
        }
        assertEquals(new ModuleCacheKey("same.php", true, PhpLanguageProfile.PHP_8_5, "one"),
                new ModuleCacheKey("same.php", true, PhpLanguageProfile.JPHP_LEGACY, "two"));
        assertNotEquals(new ModuleCacheKey("same.php", false, PhpLanguageProfile.PHP_8_5, "one"),
                new ModuleCacheKey("same.php", false, PhpLanguageProfile.PHP_8_5, "two"));
    }

    @Test public void testEveryNamespaceAndClassPrefixSourceAndDump() throws Throwable {
        String code = "namespace First { $a = Widget::class.'|'.Widget\\Child::class.'|'.namespace\\Widget::class; } " +
                "namespace { $b = Widget::class.'|'.\\Other\\Widget::class; } " +
                "namespace Last { return $a.'|'.$b.'|'.Widget::class; }";
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                assertEquals("Vendor\\Widget|Vendor\\Widget\\Child|First\\Widget|Vendor\\Widget|Other\\Widget|Vendor\\Widget",
                        runContext(env, context(code, profile, "Vendor\\Widget"), dump).toString());
                assertEquals("Vendor\\Widget", runContext(env, context("return Widget::class;", profile, "Vendor\\Widget"), dump).toString());
                assertEquals("Vendor\\Widget", runContext(env, context("namespace Semi; return Widget::class;", profile, "Vendor\\Widget"), dump).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testExplicitUseWinsAndOtherUseTypesRemainSeparate() throws Throwable {
        String code = "namespace { $a = Widget::class; } " +
                "namespace A { use Other\\{Widget}; $b = Widget::class.'|'.Widget\\Child::class; } " +
                "namespace B { use Different\\Thing as Widget; $c = Widget::class; } " +
                "namespace Vendor { function Widget() { return 8; } const Widget = 7; } " +
                "namespace C { use function Vendor\\Widget; use const Vendor\\Widget; " +
                "return $a.'|'.$b.'|'.$c.'|'.Widget::class.'|'.Widget().'|'.Widget; }";
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                assertEquals("Implicit\\Widget|Other\\Widget|Other\\Widget\\Child|Different\\Thing|Implicit\\Widget|8|7",
                        runContext(env, context(code, profile, "Implicit\\Widget"), dump).toString());
                assertEquals("User\\Widget", runContext(env, context("use User\\Widget; return Widget::class;", profile, "Implicit\\Widget"), dump).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testNamedDeclarationsBeatImportsBeforeAndAfterDeclaration() throws Throwable {
        String code = "namespace A { $before = Widget::class.'|'.Contract::class.'|'.Tools::class.'|'.Widget\\Nested::class; " +
                "class Widget {} interface Contract {} trait Tools {} " +
                "$after = Widget::class.'|'.Contract::class.'|'.Tools::class; } " +
                "namespace B { $other = Widget::class.'|'.Contract::class.'|'.Tools::class; } " +
                "namespace a { return $before.'|'.$after.'|'.$other.'|'.Widget::class.'|'.((new Widget) instanceof \\A\\Widget); }";
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                assertEquals("A\\Widget|A\\Contract|A\\Tools|A\\Widget\\Nested|A\\Widget|A\\Contract|A\\Tools|" +
                                "Vendor\\Widget|Vendor\\Contract|Vendor\\Tools|a\\Widget|1",
                        runContext(env, context(code, profile, "Vendor\\Widget", "Vendor\\Contract", "Vendor\\Tools"), dump).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testGlobalDeclarationsAndLexicalBraces() throws Throwable {
        String code = "$before = Widget::class.'|'.Contract::class.'|'.Tools::class; " +
                "class Widget {} interface Contract {} trait Tools {} " +
                "$text = 'namespace Wrong { class Decoy {} }'; " +
                "return $before.'|'.Widget::class.'|'.Other::class;";
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                assertEquals("Widget|Contract|Tools|Widget|Vendor\\Other",
                        runContext(env, context(code, profile, "Vendor\\Widget", "Vendor\\Contract", "Vendor\\Tools", "Vendor\\Other"), dump).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testInventoryIncludesCurrentlyUnsupportedConditionalDeclarations() throws Throwable {
        // Native PHP accepts conditional class declarations. BodyGenerator currently
        // rejects them; keep this limitation explicit while proving the prepass covers
        // their forward-reference names before the unsupported syntax is encountered.
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            Environment env = new Environment(newScope());
            try {
                SyntaxAnalyzer analyzer = new SyntaxAnalyzer(env,
                        new Tokenizer(context("return 0;", profile, "Vendor\\Widget", "Vendor\\Other")));
                try {
                    analyzer.reset(env, new Tokenizer(context("if (true) { class Widget {} } return Widget::class;", profile,
                            "Vendor\\Widget", "Vendor\\Other")));
                    fail("Conditional declaration support changed; replace this limitation assertion with native execution parity");
                } catch (php.runtime.exceptions.support.ErrorException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("class"));
                }
                analyzer.setNamespace(analyzer.newNamespace());
                assertEquals("Widget", analyzer.getRealName(NameToken.valueOf("Widget")).toName());
                assertEquals("Vendor\\Other", analyzer.getRealName(NameToken.valueOf("Other")).toName());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testClassAliasFoldingIsAsciiOnly() throws Throwable {
        String code = "return Éclair::class.'|'.éclair::class.'|'.ÉCLAIR::class.'|'.éCLAIR::class;";
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                Context options = context(code, profile, "One\\Éclair", "Two\\éclair");
                assertEquals(2, options.getImplicitClassImports().size());
                assertEquals("One\\Éclair", options.getImplicitClassImports().get("Éclair"));
                assertEquals("Two\\éclair", options.getImplicitClassImports().get("éclair"));
                assertEquals("One\\Éclair|Two\\éclair|One\\Éclair|Two\\éclair", runContext(env, options, dump).toString());
                assertEquals("User\\Éclair|Two\\éclair", runContext(env,
                        context("use User\\Éclair; return Éclair::class.'|'.éclair::class;", profile,
                                "One\\Éclair", "Two\\éclair"), dump).toString());

                assertEquals("Vendor\\nameſpace\\Child|Demo\\Child", runContext(env,
                        context("namespace Demo; return nameſpace\\Child::class.'|'.namespace\\Child::class;", profile,
                                "Vendor\\nameſpace"), dump).toString());

                // Inspect namespace/declaration precedence before runtime class caches.
                SyntaxAnalyzer analyzer = new SyntaxAnalyzer(env, new Tokenizer(context(
                        "namespace ÉSpace { class Éclair {} class Widget {} } namespace éSpace {}", profile,
                        "One\\Éclair", "Two\\éclair", "Vendor\\Widget")));
                NamespaceStmtToken upper = NamespaceStmtToken.getDefault().setName(FulledNameToken.valueOf(new String[]{"ÉSpace"}));
                analyzer.setNamespace(upper);
                assertEquals("ÉSpace\\ÉCLAIR", analyzer.getRealName(NameToken.valueOf("ÉCLAIR")).toName());
                assertEquals("Two\\éclair", analyzer.getRealName(NameToken.valueOf("éCLAIR")).toName());
                NamespaceStmtToken lower = NamespaceStmtToken.getDefault().setName(FulledNameToken.valueOf(new String[]{"éSpace"}));
                analyzer.setNamespace(lower);
                assertEquals("Vendor\\Widget", analyzer.getRealName(NameToken.valueOf("Widget")).toName());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testNativeExplicitAliasPrefixFixture() throws Throwable {
        File file = new File(getClass().getClassLoader().getResource("resources/namespaces/context_class_alias_prefix.php").toURI());
        String expected = Files.readString(file.toPath().resolveSibling("context_class_alias_prefix.expected"), StandardCharsets.UTF_8).trim();
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                Context context = new Context(file); context.setPhpLanguageProfile(profile);
                assertEquals(expected, runContext(env, context, dump).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testTypeInheritanceTraitAndCatchContexts() throws Throwable {
        for (boolean dump : new boolean[] {false, true}) {
            Environment env = new Environment(newScope());
            try {
                runContext(env, context("namespace Vendor; class Widget {} trait Tools { function tool() {return 4;} }",
                        PhpLanguageProfile.PHP_8_5), false);
                String code = "namespace Demo; class Child extends Widget { use Tools; public Widget $p; " +
                        "function take(Widget $x): Widget { return $x; } } " +
                        "$c = new Child; $c->p = new Widget; $a = $c->take($c->p); $caught = false; " +
                        "try { throw new Exception('x'); } catch (Exception $e) { $caught = true; } " +
                        "return $a instanceof Widget && $caught && $c->tool() === 4;";
                assertTrue(runContext(env, context(code, PhpLanguageProfile.PHP_8_5,
                        "Vendor\\Widget", "Vendor\\Tools", "Exception"), dump).toBoolean());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testContextCacheIsolationAndSourceBytes() throws Throwable {
        File file = source("contexts.php", "return Widget::class;");
        Environment env = new Environment(newScope());
        try {
            Context a = new Context(file); a.setImplicitClassImports(Collections.singleton("A\\Widget"));
            Context b = new Context(file); b.setImplicitClassImports(Collections.singleton("B\\Widget"));
            ModuleEntity first = env.importModule(a), second = env.importModule(b), plain = env.importModule(new Context(file));
            assertNotSame(first, second); assertNotSame(first, plain);
            assertEquals("A\\Widget", first.include(env).toString());
            assertEquals("B\\Widget", second.include(env).toString());
            assertEquals("Widget", plain.include(env).toString());
            Context repeat = new Context(file); repeat.setImplicitClassImports(Collections.singleton("A\\Widget"));
            assertSame(first, env.importModule(repeat));
            assertEquals(file.getPath(), a.getFileName());
            String code = "<?php\r\n// Кириллица\r\nreturn Widget::class;\r\n";
            File originalFile = temporary.newFile("original-utf8.php");
            Files.writeString(originalFile.toPath(), code, StandardCharsets.UTF_8);
            Context original = new Context(originalFile);
            original.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            original.setImplicitClassImports(Collections.singleton("A\\Widget"));
            assertArrayEquals(code.getBytes(StandardCharsets.UTF_8), original.getInputStream(StandardCharsets.UTF_8).readAllBytes());
            SyntaxAnalyzer analyzer = new SyntaxAnalyzer(env, new Tokenizer(original));
            NameToken name = new NameToken(new TokenMeta("Widget", 17, 17, 5, 11));
            assertSame(name.getMeta(), analyzer.getRealName(name).getMeta());
            assertEquals("A\\Widget", analyzer.getRealName(name).toName());
            analyzer.reset(env, new Tokenizer(context("return Widget::class;", PhpLanguageProfile.PHP_8_5, "B\\Widget")));
            assertEquals("B\\Widget", analyzer.getRealName(name).toName());
        } finally { env.doFinal(); }
    }

    @Test public void testModuleManagerIncludeAndEnvironmentIsolation() throws Throwable {
        File app = source("app.php", "return Widget::class;"), vendor = source("vendor.php", "return Widget::class;");
        CompileScope scope = newScope();
        Environment first = new Environment(scope), second = new Environment(scope);
        AtomicInteger calls = new AtomicInteger();
        try {
            first.getModuleManager().setImplicitClassImportsResolver(path -> {
                calls.incrementAndGet();
                return path.equals(ModuleCacheKey.normalize(app.getPath())) ? Collections.singleton("A\\Widget") : null;
            });
            second.getModuleManager().setImplicitClassImportsResolver(path -> Collections.singleton("B\\Widget"));
            assertEquals("A\\Widget", runContext(first, context("return include " + quoted(app) + ";", PhpLanguageProfile.PHP_8_5), false).toString());
            assertEquals(1, calls.get());
            assertEquals("A\\Widget", first.getModuleManager().fetchModule(app.getPath(), false, PhpLanguageProfile.PHP_8_5).include(first).toString());
            assertEquals(2, calls.get());
            assertEquals("B\\Widget", second.getModuleManager().fetchModule(app.getPath(), false, PhpLanguageProfile.PHP_8_5).include(second).toString());
            assertEquals("Widget", first.getModuleManager().fetchModule(vendor.getPath(), false, PhpLanguageProfile.PHP_8_5).include(first).toString());
            try { first.getModuleManager().setImplicitClassImportsResolver(path -> Collections.emptyList()); fail("Changed frozen resolver"); }
            catch (IllegalStateException expected) { }
        } finally { first.doFinal(); second.doFinal(); }
    }

    @Test public void testResourcePolicySeesNormalizedApplicationPath() throws Throwable {
        String app = "res://resources/include/context_imports_leaf.php";
        String alias = "res://resources/include/../include/./context_imports_leaf.php";
        String outside = "res://resources/namespaces/context_class_alias_prefix.php";
        Environment env = new Environment(newScope(), new ByteArrayOutputStream());
        List<String> paths = new ArrayList<>();
        try {
            env.getModuleManager().setImplicitClassImportsResolver(path -> {
                paths.add(path);
                return path.equals(app) ? Collections.singleton("App\\Widget") : Collections.emptyList();
            });
            assertEquals("App\\Widget", env.getModuleManager().fetchModule(alias, false, PhpLanguageProfile.PHP_8_5).include(env).toString());
            assertEquals(Collections.singletonList(app), paths);
            ModuleEntity external = env.getModuleManager().fetchModule(outside, false, PhpLanguageProfile.PHP_8_5);
            assertTrue(external.getContext().getImplicitClassImports().isEmpty());
            assertEquals(Arrays.asList(app, outside), paths);
        } finally { env.doFinal(); }
    }

    @Test public void testAutoloadSourceReceivesPolicy() throws Throwable {
        File file = source("autoload.php", "namespace Auto; class Loaded extends Widget {}");
        Environment env = new Environment(newScope());
        AtomicInteger calls = new AtomicInteger();
        try {
            env.getModuleManager().setImplicitClassImportsResolver(path -> {
                calls.incrementAndGet(); return Collections.singleton("Vendor\\Widget");
            });
            runContext(env, context("namespace Vendor; class Widget {}", PhpLanguageProfile.PHP_8_5), false);
            assertTrue(runContext(env, context("spl_autoload_register(function($name) { include " + quoted(file) + "; }); " +
                    "return (new \\Auto\\Loaded) instanceof \\Vendor\\Widget;", PhpLanguageProfile.PHP_8_5), false).toBoolean());
            assertEquals(1, calls.get());
        } finally { env.doFinal(); }
    }

    @Test public void testCompiledModuleKeepsResolvedImportsAndIgnoresPolicy() throws Throwable {
        File file = temporary.newFile("imports.phb");
        Environment env = new Environment(newScope());
        try {
            Context source = context("return Widget::class;", PhpLanguageProfile.PHP_8_5, "Original\\Widget");
            ModuleEntity template = new JvmCompiler(env, source).compile(false);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new ModuleDumper(source, env, true).save(template, bytes);
            Files.write(file.toPath(), bytes.toByteArray());
            env.getModuleManager().setImplicitClassImportsResolver(path -> { throw new AssertionError("Resolver called for dump"); });
            ModuleEntity loaded = env.getModuleManager().fetchModule(file.getPath(), true, PhpLanguageProfile.JPHP_LEGACY);
            assertEquals("Original\\Widget", loaded.include(env).toString());
            Context other = new Context(file); other.setImplicitClassImports(Collections.singleton("Other\\Widget"));
            assertSame(loaded, env.importCompiledModule(other, true));
            assertEquals("Original\\Widget", env.importCompiledModule(other, true).include(env).toString());
        } finally { env.doFinal(); }
    }
}
