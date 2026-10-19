package org.develnext.jphp.core.syntax;

import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken;
import org.junit.Test;
import php.runtime.common.LangMode;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;

import java.util.List;

import static org.junit.Assert.*;

/** AST-only prospective controls. Runtime composition is a distinct parent-owned gate. */
public class PhpDeferredTraitRelationParserTest {
    private Environment environment() {
        var env = new Environment(); env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        env.getScope().setLangMode(LangMode.DEFAULT); return env;
    }

    private SyntaxAnalyzer parse(Environment env, String source, PhpLanguageProfile profile) throws Exception {
        var context = new Context(source); context.setPhpLanguageProfile(profile);
        return new SyntaxAnalyzer(env, new Tokenizer(context));
    }

    @Test public void orderedGroupsPreserveResolvedNamesSourceTokensAndUnqualifiedOrigin() throws Throwable {
        var env = environment();
        try {
            var source = "namespace N;use Vendor\\First as One;function install(){class C{\n"
                    + "use One,B,Cc{B::MiXeD insteadof Cc,One;m as KeepCase;B::MiXeD as protected AliasCase;}\n"
                    + "use D{}\nuse E;}}";
            var c = parse(env, source, PhpLanguageProfile.PHP_8_5).getDeferredClasses().get(0);
            var groups = c.getTraitUseGroups(); assertEquals(3, groups.size());
            assertEquals(List.of("Vendor\\First", "N\\B", "N\\Cc"), groups.get(0).traits().stream().map(t -> t.getName()).toList());
            assertEquals(List.of("One", "B", "Cc"), groups.get(0).traits().stream().map(t -> t.getWord()).toList());
            assertEquals("use", groups.get(0).traceToken().getWord());
            assertEquals(1, groups.get(0).traceToken().getMeta().getStartLine());
            assertEquals(2, groups.get(1).traceToken().getMeta().getStartLine());
            assertEquals(3, groups.get(2).traceToken().getMeta().getStartLine());
            assertTrue(groups.get(1).adaptations().isEmpty()); assertTrue(groups.get(2).adaptations().isEmpty());
            var precedence = (ClassStmtToken.TraitPrecedence) groups.get(0).adaptations().get(0);
            assertEquals("N\\B", precedence.preferredTrait().getName()); assertEquals("B", precedence.preferredTrait().getWord());
            assertEquals("MiXeD", precedence.method().getName());
            assertEquals(List.of("N\\Cc", "Vendor\\First"), precedence.excluded().stream().map(t -> t.getName()).toList());
            var alias = (ClassStmtToken.TraitAlias) groups.get(0).adaptations().get(1);
            assertNull("An unqualified origin must not become the first trait", alias.trait());
            assertEquals("m", alias.method().getName()); assertEquals("KeepCase", alias.alias().getName());
            assertNull(alias.visibility()); assertFalse(alias.makeFinal());
            assertEquals("m", alias.traceToken().getWord());
            var qualified = (ClassStmtToken.TraitAlias) groups.get(0).adaptations().get(2);
            assertEquals("N\\B", qualified.trait().getName()); assertEquals(Modifier.PROTECTED, qualified.visibility());
            assertEquals("AliasCase", qualified.alias().getName()); assertEquals("B", qualified.traceToken().getWord());
            // Old maps remain available, including their documented first-trait fallback.
            assertEquals(5, c.getUses().size()); assertEquals("Vendor\\First", c.findAliases("m").get(0).getTrait());
            assertEquals("N\\B", c.findReplacement("mixed").getOrigin());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void modernFinalModifierIsDistinctFromAliasNamedFinalAndPropagatesToOldMap() throws Throwable {
        var env = environment();
        try {
            var syntax = parse(env, "trait T{function f(){}}function install(){class C{use T{"
                    + "f as final alias;f as private hidden;f as public final;}}class D{use T{f as final;}}}", PhpLanguageProfile.PHP_8_5);
            var c = syntax.getDeferredClasses().get(0); var a = c.getTraitUseGroups().get(0).adaptations();
            var finalAlias = (ClassStmtToken.TraitAlias) a.get(0);
            assertTrue(finalAlias.makeFinal()); assertNull(finalAlias.visibility()); assertEquals("alias", finalAlias.alias().getName());
            var privateAlias = (ClassStmtToken.TraitAlias) a.get(1);
            assertFalse(privateAlias.makeFinal()); assertEquals(Modifier.PRIVATE, privateAlias.visibility());
            var aliasNamedFinal = (ClassStmtToken.TraitAlias) a.get(2);
            assertFalse(aliasNamedFinal.makeFinal()); assertEquals(Modifier.PUBLIC, aliasNamedFinal.visibility());
            assertEquals("final", aliasNamedFinal.alias().getName());
            assertTrue(c.findAliases("f").get(0).isMakeFinal()); assertFalse(c.findAliases("f").get(2).isMakeFinal());
            var d = syntax.getDeferredClasses().get(1);
            assertTrue(((ClassStmtToken.TraitAlias) d.getTraitUseGroups().get(0).adaptations().get(0)).makeFinal());
            assertNull(d.findAliases("f").get(0).getName()); assertTrue(d.findAliases("f").get(0).isMakeFinal());
            try { parse(env, "trait T{}class C{use T{f as final alias;}}", PhpLanguageProfile.JPHP_LEGACY); fail("Old grammar"); }
            catch (RuntimeException expected) {}
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void immutableRecipeSnapshotsDoNotExposeLegacyTokenMetaMutation() throws Throwable {
        var env = environment();
        try {
            var c = parse(env, "function install(){class C{use A,B{A::f insteadof B;f as alias;}}}", PhpLanguageProfile.PHP_8_5)
                    .getDeferredClasses().get(0);
            var group = c.getTraitUseGroups().get(0);
            try { c.getTraitUseGroups().clear(); fail("groups"); } catch (UnsupportedOperationException expected) {}
            try { group.traits().clear(); fail("traits"); } catch (UnsupportedOperationException expected) {}
            try { group.adaptations().clear(); fail("adaptations"); } catch (UnsupportedOperationException expected) {}
            var precedence = (ClassStmtToken.TraitPrecedence) group.adaptations().get(0);
            try { precedence.excluded().clear(); fail("excluded"); } catch (UnsupportedOperationException expected) {}
            var originalWord = group.traits().get(0).getWord();
            group.traits().get(0).getMeta().setWord("forged"); group.traceToken().getMeta().setWord("forged");
            c.getUses().get(0).getMeta().setWord("legacy mutation");
            assertEquals(originalWord, group.traits().get(0).getWord()); assertEquals("use", group.traceToken().getWord());
            assertEquals("A", group.traits().get(0).getName());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }

    @Test public void modernPrecedenceRequiresQualifiedOriginAndLegacyMapsRemainAvailable() throws Throwable {
        var env = environment();
        try {
            try { parse(env, "function install(){class C{use A,B{f insteadof B;}}}", PhpLanguageProfile.PHP_8_5); fail("Explicit Trait::method required"); }
            catch (RuntimeException expected) {}
            var old = parse(env, "class C{use A,B{f insteadof B;f as private alias;}}", PhpLanguageProfile.JPHP_LEGACY).findClass("C");
            assertEquals("A", old.findReplacement("f").getOrigin()); assertEquals("A", old.findAliases("f").get(0).getTrait());
            assertFalse(old.findAliases("f").get(0).isMakeFinal());
        } finally { try { env.doFinal(); } finally { env.getScope().getClassLoader().close(); } }
    }
}
