package org.develnext.jphp.core.syntax.generators;


import org.develnext.jphp.core.common.Separator;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.ClassExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.CommaToken;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AssignExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.ImportExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;

import java.util.ListIterator;

public class ConstGenerator extends Generator<ConstStmtToken> {

    @SuppressWarnings("unchecked")
    public final static Class<? extends Token>[] valueTokens = new Class[]{
            ValueExprToken.class, OperatorExprToken.class
    };

    public ConstGenerator(SyntaxAnalyzer analyzer) {
        super(analyzer);
    }

    /*@SuppressWarnings("unchecked")
    protected void processBody(ConstStmtToken result, ListIterator<Token> iterator){
        Token current = nextToken(iterator);
        if (!(current instanceof AssignExprToken))
            unexpectedToken(current, "=");

        ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class).getToken(nextToken(iterator), iterator);
        if (value == null)
            unexpectedToken(nextToken(iterator));

        result.setValue(value);
    }*/

    @Override
    @SuppressWarnings("unchecked")
    public ConstStmtToken getToken(Token current, ListIterator<Token> iterator) {
        if (current instanceof ConstStmtToken){
            ConstStmtToken result = (ConstStmtToken)current;

            Token prev = null;
            if (analyzer.getClazz() == null)
                result.setNamespace(analyzer.getNamespace());

            while (true) {
                Token next = analyzer.getClazz() == null ? nextToken(iterator) : nextTokenSensitive(iterator, ClassStmtToken.class);

                boolean modernHead = analyzer.getClazz() != null && result.items.isEmpty()
                        && analyzer.getPhpLanguageProfile()
                            == php.runtime.common.PhpLanguageProfile.PHP_8_5;
                if (next instanceof NameToken || modernHead && !isBreak(next) && !(next instanceof CommaToken) && !(next instanceof ClassStmtToken)) {
                    Token token = nextToken(iterator);
                    if (modernHead && !(token instanceof AssignExprToken)) {
                        java.util.List<Token> type = new java.util.ArrayList<>(); type.add(next);
                        Token name = token;
                        while (true) {
                            token = nextTokenSensitive(iterator, ClassStmtToken.class);
                            if (token instanceof AssignExprToken) break;
                            if (isBreak(token) || token instanceof CommaToken) unexpectedToken(token, "=");
                            type.add(name); name = token;
                        }
                        name = makeSensitive(name);
                        if (!(name instanceof NameToken)) unexpectedToken(name, TokenType.T_STRING);
                        next = name;
                        result.setDeclaredType(PhpTypeDeclarationParser.parse(analyzer, type,
                                analyzer.getClazz().getFulledName() + "::" + ((NameToken) name).getName()));
                    }
                    if (!(next instanceof NameToken)) unexpectedToken(next, TokenType.T_STRING);
                    if (next instanceof FulledNameToken && !((FulledNameToken) next).isProcessed(NamespaceUseStmtToken.UseType.CONSTANT))
                        unexpectedToken(next, TokenType.T_STRING);
                    if (!(token instanceof AssignExprToken))
                        unexpectedToken(token, "=");

                    ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class)
                        .getToken(nextToken(iterator), iterator, Separator.COMMA_OR_SEMICOLON, null);
                    if (!isBreak(iterator.previous())){
                        iterator.next();
                    }
                    if (value == null)
                        unexpectedToken(iterator.previous());

                    result.add((NameToken)next, value);
                } else if (next instanceof CommaToken){
                    if (prev instanceof CommaToken)
                        unexpectedToken(next);
                    prev = next;
                } else if (isBreak(next)){
                    break;
                } else
                    unexpectedToken(next, TokenType.T_STRING);
            }
            return result;
        }
        return null;
    }
}
