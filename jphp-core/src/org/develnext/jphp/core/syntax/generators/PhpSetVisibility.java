package org.develnext.jphp.core.syntax.generators;
import java.util.ListIterator;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.CommentToken;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.exceptions.support.ErrorType;
/** Lookahead leaves DNF type parentheses and unrelated expressions untouched. */
public final class PhpSetVisibility {
    private PhpSetVisibility(){}
    public static ExprStmtToken reference(SyntaxAnalyzer analyzer,ExprStmtToken expression) {
        if(expression==null || analyzer.getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)return expression;
        var path=new java.util.ArrayList<Token>(expression.getTokens());
        for(Token part:path)if(part instanceof org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken
                && !(part instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetExprToken)
                && !(part instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken)
                && !(part instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.StaticAccessOperatorExprToken))return expression;
        boolean address=true,changed=false;
        for(int i=path.size()-1;i>=0 && address;i--) {
            Token step=path.get(i);
            if(step instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetExprToken array) {
                if(step.getClass()==org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetExprToken.class) {
                    var ref=new org.develnext.jphp.core.tokenizer.token.expr.operator.ArrayGetRefExprToken(array);ref.setShortcut(true);path.set(i,ref);changed=true;
                }
            } else if(step instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken property) {
                if(step.getClass()==org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken.class) {
                    path.set(i,new org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessGetRefExprToken(property));changed=true;
                }
                address=false; // An object container is read as a VALUE, never granted an unnecessary mutable CELL.
            } else if(step instanceof org.develnext.jphp.core.tokenizer.token.expr.value.StaticAccessExprToken property && property.isGetStaticField()) {
                property.setWriteAccess(true);property.setDirectAssignment(false);changed=true;address=false;
            } else if(step instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.StaticAccessOperatorExprToken access
                    && access.getOrigin().isGetStaticField()) {
                access.getOrigin().setWriteAccess(true);access.getOrigin().setDirectAssignment(false);changed=true;address=false;
            } else if(i==0 && step instanceof org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken variable) {
                if(analyzer.getFunction()!=null)analyzer.getFunction().variable(variable).setReference(true).setUnstable(true).setMutable(true);
                address=false;
            } else break;
        }
        return changed?new ExprStmtToken(analyzer.getEnvironment(),analyzer.getContext(),path):expression;
    }
    public static boolean visibility(Token token){return token instanceof PublicStmtToken||token instanceof ProtectedStmtToken||token instanceof PrivateStmtToken;}
    private static Token next(ListIterator<Token> iterator){while(iterator.hasNext()){Token token=iterator.next();if(!(token instanceof CommentToken))return token;}return null;}
    public static Token parse(SyntaxAnalyzer analyzer,Token token,ListIterator<Token> iterator){
        if(!visibility(token)||analyzer.getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)return token;
        int saved=iterator.nextIndex();Token open=next(iterator),name=next(iterator);
        if(!(open instanceof BraceExprToken brace)||!brace.isOpened()||brace.getKind()!=BraceExprToken.Kind.SIMPLE
                || name==null||!name.getWord().equalsIgnoreCase("set")){
            while(iterator.nextIndex()>saved)iterator.previous();return token;
        }
        Token close=next(iterator);
        if(!(close instanceof BraceExprToken closeBrace)||!closeBrace.isClosed()||closeBrace.getKind()!=BraceExprToken.Kind.SIMPLE)
            analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()),ErrorType.E_PARSE,"syntax error, expecting \")\"" );
        return new PropertySetVisibilityStmtToken(token.getMeta(),token instanceof PrivateStmtToken?Modifier.PRIVATE:
                token instanceof ProtectedStmtToken?Modifier.PROTECTED:Modifier.PUBLIC);
    }
}
