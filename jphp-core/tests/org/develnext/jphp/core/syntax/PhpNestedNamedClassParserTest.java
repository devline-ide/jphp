package org.develnext.jphp.core.syntax;

import org.develnext.jphp.core.syntax.generators.ClassGenerator;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import org.junit.Test;
import php.runtime.common.LangMode;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

/** Parser/catalog proof only; declaring/binding/loading/executing templates is a separate compiler/runtime gate. */
public class PhpNestedNamedClassParserTest {
    private Tokenizer tokenizer(String source, PhpLanguageProfile profile) throws IOException {
        var context = new Context(source); context.setPhpLanguageProfile(profile); return new Tokenizer(context);
    }

    private SyntaxAnalyzer parse(Environment env, String source) throws IOException {
        return new SyntaxAnalyzer(env, tokenizer(source, PhpLanguageProfile.PHP_8_5));
    }

    private Environment environment() {
        var env = new Environment(); env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        env.getScope().setLangMode(LangMode.DEFAULT); return env;
    }

    private List<String> names(List<ClassStmtToken> classes) {
        var names = new ArrayList<String>(); for (var clazz : classes) names.add(clazz.getName().getName()); return names;
    }

    private MethodStmtToken method(ClassStmtToken clazz, String name) {
        return clazz.getMethods().stream().filter(m -> m.getName().getName().equals(name)).findFirst().orElseThrow();
    }

    private Set<String> locals(FunctionStmtToken function) {
        var names = new HashSet<String>(); for (var local : function.getLocal()) names.add(local.getName()); return names;
    }

    @Test public void orderedCatalogPreservesRepeatedNamesAndActualBodyDeclarations() throws Throwable {
        var env = environment();
        try {
            var syntax = parse(env, "class StaticTop{}function outer(){if(true){class Repeated{}}else{class Repeated{}}"
                    + "interface InsideI{function f();}trait InsideT{function f(){}}final class Closed{}"
                    + "abstract class Abstracted{abstract function f();}}");
            assertEquals(List.of("Repeated", "Repeated", "InsideI", "InsideT", "Closed", "Abstracted"), names(syntax.getDeferredClasses()));
            assertEquals(1, syntax.getClasses().size()); assertEquals(-1, syntax.findClass("StaticTop").getDeclarationId());
            assertFalse(syntax.findClass("StaticTop").isDeferredDeclaration()); assertNull(syntax.findClass("Repeated"));
            for (int i = 0; i < syntax.getDeferredClasses().size(); i++) {
                assertEquals(i, syntax.getDeferredClasses().get(i).getDeclarationId());
                assertTrue(syntax.getDeferredClasses().get(i).isDeferredDeclaration());
            }
            var declarations = syntax.getDeferredClasses();
            assertTrue(declarations.get(2).isInterface()); assertTrue(declarations.get(3).isTrait());
            assertTrue(declarations.get(4).isFinal()); assertTrue(declarations.get(5).isAbstract());
            var function = syntax.getFunctions().iterator().next(); var body = function.getBody().getInstructions();
            var condition = (IfStmtToken) body.get(0).getSingle();
            assertSame(declarations.get(0), condition.getBody().getInstructions().get(0).getSingle());
            assertSame(declarations.get(1), condition.getElseBody().getInstructions().get(0).getSingle());
            for (int i = 1; i < body.size(); i++) {
                assertTrue(body.get(i).isStmtList()); assertSame(declarations.get(i + 1), body.get(i).getSingle());
            }
            try { declarations.clear(); fail("Catalog must be immutable to consumers"); }
            catch (UnsupportedOperationException expected) {}
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void methodsOfFunctionDeclaredClassHaveIsolatedContextsAndOuterClosureMayDeclareClass() throws Throwable {
        var env = environment();
        try {
            var syntax = parse(env, "function outer($outer){while($outer){class Inner{"
                    + "function m($inner){inner:$own=$inner;$this;return self::class;}"
                    + "function make(){return function(){return $this;};}}}"
                    + "$after=$outer;return function()use($outer){class ClosureInner{function m($only){$local=$only;}}return $outer;};}"
                    + "class Host{function outer(){return $this;}}");
            assertEquals(List.of("Inner", "ClosureInner"), names(syntax.getDeferredClasses()));
            var inner = syntax.getDeferredClasses().get(0); var member = method(inner, "m");
            assertSame(inner, member.getClazz()); assertEquals(0, member.getLabels().get("inner").getLevel());
            assertTrue(locals(member).containsAll(Set.of("inner", "own", "this")));
            var outer = syntax.getFunctions().iterator().next();
            assertFalse(locals(outer).contains("inner")); assertFalse(locals(outer).contains("own"));
            assertFalse(locals(outer).contains("this")); assertTrue(locals(outer).containsAll(Set.of("outer", "after")));
            var closureInner = syntax.getDeferredClasses().get(1);
            assertSame(closureInner, method(closureInner, "m").getClazz());
            assertFalse(locals(method(closureInner, "m")).contains("outer"));
            var host = syntax.findClass("Host"); assertSame(host, method(host, "outer").getClazz());
            assertTrue(locals(method(host, "outer")).contains("this"));
            assertEquals(2, syntax.getClosures().size());
            assertSame(inner, new ArrayList<>(syntax.getClosures()).get(0).getOwnerClass());
            assertNull(new ArrayList<>(syntax.getClosures()).get(1).getOwnerClass());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void namedClassWithinInstanceOrStaticMethodIsRejectedBeforeCatalogInsertion() throws Throwable {
        for (String source : List.of(
                "class Host{function outer(){class MethodInner{function m(){return self::class;}}return $this;}}",
                "class Host{static function outer(){class MethodInner{}}}",
                "function outer(){class Inner{function make(){class Deeper{}return function(){return $this;};}}}")) {
            var env = environment();
            try {
                try { parse(env, source); fail("Native rejects class declarations nested within class methods"); }
                catch (php.runtime.exceptions.CompileException expected) {
                    assertEquals("Class declarations may not be nested", expected.getMessage());
                }
            } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
        }
        var env = environment();
        try {
            var syntax = parse(env, ";"); var outerClass = new ClassStmtToken(TokenMeta.empty());
            syntax.setClazz(outerClass);
            var tokens = tokenizer("class Forbidden{}", PhpLanguageProfile.PHP_8_5).fetchAll();
            var iterator = tokens.listIterator();
            try { syntax.generator(ClassGenerator.class).getToken(iterator.next(), iterator, true); fail("Nested named class"); }
            catch (php.runtime.exceptions.CompileException expected) {
                assertEquals("Class declarations may not be nested", expected.getMessage());
            }
            assertSame(outerClass, syntax.getClazz()); assertTrue(syntax.getDeferredClasses().isEmpty());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void malformedDefinitionRestoresEveryEnclosingContextInFinally() throws Throwable {
        var env = environment();
        try {
            var syntax = parse(env, ";"); var scope = syntax.getScope(); scope.setLevelForGoto(true);
            var absolute = new FunctionStmtToken(TokenMeta.empty());
            var first = new FunctionStmtToken(TokenMeta.empty()); var last = new FunctionStmtToken(TokenMeta.empty());
            syntax.setClazz(null); syntax.setFunction(absolute); syntax.pushClosure(first); syntax.pushClosure(last);
            var local = new VariableExprToken(TokenMeta.of("$kept")); scope.addVariable(local);
            var tokens = tokenizer("class Broken{function m($argument){$bad=;}}", PhpLanguageProfile.PHP_8_5).fetchAll();
            var iterator = tokens.listIterator(); Token token = iterator.next();
            try { syntax.generator(ClassGenerator.class).getToken(token, iterator, true); fail("Malformed body must fail"); }
            catch (RuntimeException expected) {}
            assertNull(syntax.getClazz()); assertSame(absolute, syntax.getFunction(true));
            assertSame(last, syntax.getFunction()); assertSame(scope, syntax.getScope());
            assertEquals(Set.of(local), scope.getVariables()); assertEquals(1, scope.getGotoLevel());
            assertSame(last, syntax.popClosure()); assertSame(first, syntax.popClosure()); assertNull(syntax.peekClosure());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void staticAnonymousNullEnvironmentAndResetCatalogRemainDistinct() throws Throwable {
        var env = environment();
        try {
            var syntax = parse(env, "class Top{}$x=new class{function f(){}};function f(){class Deferred{function g(){yield 1;}}}");
            assertEquals(1, syntax.getClasses().size()); assertEquals(1, syntax.getAnonymousClasses().size());
            assertEquals(List.of("Deferred"), names(syntax.getDeferredClasses()));
            assertFalse(syntax.getAnonymousClasses().get(0).isDeferredDeclaration());
            assertTrue(method(syntax.getDeferredClasses().get(0), "g").isGenerator());
            assertTrue(method(syntax.getDeferredClasses().get(0), "g").getGeneratorId() >= 0);
            syntax.reset(env, tokenizer("class Reused{}function f(){class NewDeferred{}}", PhpLanguageProfile.PHP_8_5));
            assertEquals(List.of("NewDeferred"), names(syntax.getDeferredClasses()));
            assertEquals(0, syntax.getDeferredClasses().get(0).getDeclarationId());
            assertEquals(0, syntax.getAnonymousClasses().size()); assertNotNull(syntax.findClass("Reused"));
            var noEnv = parse(null, "function f(){class NoEnv{function m(){}}}");
            assertEquals(List.of("NoEnv"), names(noEnv.getDeferredClasses()));
            var legacy = new SyntaxAnalyzer(env, tokenizer("class Legacy{}", PhpLanguageProfile.JPHP_LEGACY));
            assertNotNull(legacy.findClass("Legacy")); assertTrue(legacy.getDeferredClasses().isEmpty());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void unchangedOriginalTwentyClassSourcesParseIntoRealCatalogs() throws Throwable {
        var input = getClass().getResourceAsStream("/resources/syntax/php85_nested_named_classes_original.tsv"); assertNotNull(input);
        int rows = 0;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var c = line.split("\\t", -1); assertEquals(4, c.length); var env = environment();
                try {
                    var source = new String(Base64.getDecoder().decode(c[2]), StandardCharsets.UTF_8);
                    var syntax = parse(env, source);
                    assertTrue(c[0], syntax.getDeferredClasses().size() + syntax.getClasses().size() + syntax.getAnonymousClasses().size() > 0);
                    var identities = Collections.newSetFromMap(new IdentityHashMap<ClassStmtToken, Boolean>());
                    for (var clazz : syntax.getDeferredClasses()) assertTrue(identities.add(clazz));
                    rows++;
                } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
            }
        }
        assertEquals(20, rows);
    }
}
