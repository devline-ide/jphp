package org.develnext.jphp.core.syntax;

import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.PhpPowExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.PowExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.IntegerExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassVarStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.FunctionStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ReturnStmtToken;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class PhpLanguageProfileSyntaxTest {
    private static Context modernContext(String source) {
        Context context = new Context(source);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        return context;
    }

    private static ExprStmtToken expression(SyntaxAnalyzer analyzer) {
        assertEquals(1, analyzer.getTree().size());
        return (ExprStmtToken) analyzer.getTree().getFirst();
    }

    private static void assertPower(ExprStmtToken expression, long base, boolean modern) {
        assertEquals(3, expression.getTokens().size());
        assertEquals(base, ((IntegerExprToken) expression.getTokens().getFirst()).getValue());
        assertEquals(modern ? PhpPowExprToken.class : PowExprToken.class,
                expression.getTokens().get(1).getClass());
        assertEquals(3L, ((IntegerExprToken) expression.getTokens().getLast()).getValue());
    }

    @Test
    public void implicitLegacyAstNeedsNoEnvironmentOrAsm() throws IOException {
        SyntaxAnalyzer analyzer = new SyntaxAnalyzer(null, new Tokenizer(new Context("2 ** 3;")));
        assertNull(analyzer.getEnvironment());
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, analyzer.getPhpLanguageProfile());
        ExprStmtToken expression = expression(analyzer);
        assertNull(expression.getAsmExpr());
        assertPower(expression, 2, false);
    }

    @Test
    public void explicitModernAstKeepsTypedDeclarationsAndArrayCallOperands() throws IOException {
        Context context = modernContext("class AstLeft { public int|float $value = 0o17; } "
                + "class AstRight {} function consume(AstLeft|AstRight $a): int|float { "
                + "sink($a[0]); return 0o17 ** 3; }");
        SyntaxAnalyzer analyzer = new SyntaxAnalyzer(null, new Tokenizer(context));
        assertNull(analyzer.getEnvironment());
        assertEquals(PhpLanguageProfile.PHP_8_5, analyzer.getPhpLanguageProfile());
        assertEquals(2, analyzer.getClasses().size());
        ClassVarStmtToken property = analyzer.findClass("AstLeft").getProperties().getFirst();
        assertEquals("int|float", property.getDeclaredType().getSignature());
        assertNull(property.getValue().getAsmExpr());
        assertEquals(15L, ((IntegerExprToken) property.getValue().getSingle()).getValue());

        assertEquals(1, analyzer.getFunctions().size());
        FunctionStmtToken function = analyzer.getFunctions().iterator().next();
        assertEquals("AstLeft|AstRight", function.getArguments().getFirst().getDeclaredType().getSignature());
        assertEquals("int|float", function.getDeclaredReturnType().getSignature());
        List<ExprStmtToken> body = function.getBody().getInstructions();
        assertEquals(2, body.size());
        assertNull(body.getFirst().getAsmExpr());
        CallExprToken call = (CallExprToken) body.getFirst().getSingle();
        assertEquals("sink", ((NameToken) call.getName()).getName());
        assertEquals(1, call.getParameters().size());
        ExprStmtToken argument = call.getParameters().getFirst();
        assertNull(argument.getAsmExpr());
        assertEquals(2, argument.getTokens().size());
        assertEquals("a", ((VariableExprToken) argument.getTokens().getFirst()).getName());
        ArrayGetExprToken offset = (ArrayGetExprToken) argument.getTokens().getLast();
        assertEquals(1, offset.getParameters().size());
        ExprStmtToken key = offset.getParameters().getFirst();
        assertNull(key.getAsmExpr());
        assertEquals(0L, ((IntegerExprToken) key.getSingle()).getValue());
        assertNull(body.getLast().getAsmExpr());
        ExprStmtToken returned = ((ReturnStmtToken) body.getLast().getSingle()).getValue();
        assertNull(returned.getAsmExpr());
        assertPower(returned, 15, true);
    }

    @Test
    public void resetSwitchesProfileAndClearsPreviousAstWithoutEnvironment() throws IOException {
        SyntaxAnalyzer analyzer = new SyntaxAnalyzer(null, new Tokenizer(modernContext("class OldAst {} 0o17 ** 3;")));
        assertEquals(1, analyzer.getClasses().size());
        Tokenizer tokenizer = new Tokenizer(new Context("2 ** 3;"));
        // A reused tokenizer must also receive the implicit legacy profile.
        tokenizer.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        analyzer.reset(null, tokenizer);
        assertNull(analyzer.getEnvironment());
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, analyzer.getPhpLanguageProfile());
        assertTrue(analyzer.getClasses().isEmpty());
        assertTrue(analyzer.getFunctions().isEmpty());
        assertNull(expression(analyzer).getAsmExpr());
        assertPower(expression(analyzer), 2, false);
        analyzer.reset(null, new Tokenizer(modernContext("0o17 ** 3;")));
        assertEquals(PhpLanguageProfile.PHP_8_5, analyzer.getPhpLanguageProfile());
        assertNull(expression(analyzer).getAsmExpr());
        assertPower(expression(analyzer), 15, true);
    }

    @Test
    public void emptyFacadeWithoutTokenizerPreservesLookupAndScopeFallback() {
        SyntaxAnalyzer empty = new SyntaxAnalyzer(null, null);
        assertNull(empty.getEnvironment());
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, empty.getPhpLanguageProfile());
        assertNull(empty.findClass("AbsentClass"));
        assertTrue(empty.getTree().isEmpty());
        assertTrue(empty.getClasses().isEmpty());

        CompileScope scope = new CompileScope();
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        Environment environment = new Environment(scope);
        SyntaxAnalyzer scoped = new SyntaxAnalyzer(environment, null);
        assertSame(environment, scoped.getEnvironment());
        assertEquals(PhpLanguageProfile.PHP_8_5, scoped.getPhpLanguageProfile());
        assertNull(scoped.findClass("AbsentClass"));
        assertTrue(scoped.getTree().isEmpty());
        scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, scoped.getPhpLanguageProfile());
    }

    @Test
    public void scopeFallbackAndExplicitContextOverrideSelectParserAndLexerTogether() throws IOException {
        CompileScope scope = new CompileScope();
        scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        Environment environment = new Environment(scope);
        SyntaxAnalyzer analyzer = new SyntaxAnalyzer(environment, new Tokenizer(new Context("0o17 ** 3;")));
        assertSame(environment, analyzer.getEnvironment());
        assertEquals(PhpLanguageProfile.PHP_8_5, analyzer.getPhpLanguageProfile());
        assertNotNull(expression(analyzer).getAsmExpr());
        assertPower(expression(analyzer), 15, true);

        Context legacy = new Context("2 ** 3;");
        legacy.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        analyzer.reset(environment, new Tokenizer(legacy));
        assertEquals(PhpLanguageProfile.JPHP_LEGACY, analyzer.getPhpLanguageProfile());
        assertPower(expression(analyzer), 2, false);
        scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        analyzer.reset(environment, new Tokenizer(modernContext("0o17 ** 3;")));
        assertEquals(PhpLanguageProfile.PHP_8_5, analyzer.getPhpLanguageProfile());
        assertPower(expression(analyzer), 15, true);
    }
}
