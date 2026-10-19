package org.develnext.jphp.core.syntax.generators;

import org.develnext.jphp.core.common.Separator;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.CommaToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.KeyValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.MatchExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.DefaultStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import php.runtime.exceptions.support.ErrorType;
import java.util.ArrayList;
import java.util.ListIterator;

public class MatchGenerator extends Generator<MatchExprToken> {
    public MatchGenerator(SyntaxAnalyzer analyzer) { super(analyzer); }
    @Override public boolean isAutomatic() { return false; }
    @Override public MatchExprToken getToken(Token token, ListIterator<Token> iterator) {
        var match=(MatchExprToken)token;
        match.setSubject(analyzer.generator(ExprGenerator.class).getInBraces(BraceExprToken.Kind.SIMPLE,iterator));
        if(match.getSubject()==null) unexpectedToken(token);
        Token current=nextToken(iterator);
        if(!isOpenedBrace(current,BraceExprToken.Kind.BLOCK)) unexpectedToken(current,"{");
        current=nextToken(iterator);boolean hasDefault=false;
        while(!isClosedBrace(current,BraceExprToken.Kind.BLOCK)) {
            var conditions=new ArrayList<ExprStmtToken>();
            if(current instanceof DefaultStmtToken) {
                if(hasDefault) analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Match expressions may only contain one default arm");
                hasDefault=true;current=nextToken(iterator);
                if(current instanceof CommaToken) current=nextToken(iterator);
                if(!(current instanceof KeyValueExprToken)) unexpectedToken(current,"=>");
            } else {
                while(true) {
                    ExprStmtToken condition=analyzer.generator(SimpleExprGenerator.class).getToken(current,iterator,Separator.MATCH_CONDITION,BraceExprToken.Kind.BLOCK);
                    if(condition==null) unexpectedToken(current);
                    conditions.add(condition);Token delimiter=iterator.previous();iterator.next();
                    if(delimiter instanceof KeyValueExprToken) break;
                    if(!(delimiter instanceof CommaToken)) unexpectedToken(delimiter,"=>");
                    current=nextToken(iterator);
                    if(current instanceof KeyValueExprToken) break;
                }
            }
            current=nextToken(iterator);
            ExprStmtToken result=analyzer.generator(SimpleExprGenerator.class).getToken(current,iterator,Separator.COMMA,BraceExprToken.Kind.BLOCK);
            if(result==null) unexpectedToken(current);
            match.getArms().add(new MatchExprToken.Arm(conditions,result));
            current=nextToken(iterator);
        }
        return match;
    }
}
