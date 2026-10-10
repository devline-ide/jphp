package org.develnext.jphp.core.compiler.common;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import java.util.ArrayList;
import java.util.List;

/** Only the receiver spine short-circuits; arguments, names and keys are independent expressions. */
public final class NullsafeChain {
    private NullsafeChain() {}
    public static DynamicAccessExprToken access(Token token) {
        if (token instanceof CallExprToken) token=((CallExprToken)token).getName();
        return token instanceof DynamicAccessExprToken ? (DynamicAccessExprToken)token : null;
    }
    public static boolean step(Token token) {
        if (token instanceof CallExprToken) token=((CallExprToken)token).getName();
        return token instanceof DynamicAccessExprToken || token instanceof ArrayGetExprToken || token instanceof ArrayPushExprToken
                || token instanceof StaticAccessOperatorExprToken;
    }
    public static boolean isResult(ExprStmtToken expression) {
        if (expression == null || expression.getAsmExpr() == null) return false;
        List<Boolean> stack=new ArrayList<>();
        for (Token token:expression.getAsmExpr().getTokens()) {
            if (token == null) continue;
            Token operation=token instanceof CallExprToken ? ((CallExprToken)token).getName() : token;
            if (operation instanceof OperatorExprToken) {
                int count=((OperatorExprToken)operation).isBinary() ? 2 : 1;
                if (stack.size()<count) return false;
                boolean receiver=stack.remove(stack.size()-1);
                if (count==2) stack.remove(stack.size()-1);
                DynamicAccessExprToken access=access(token);
                stack.add(count==1 && step(token) && (receiver || access!=null && access.isNullsafe()));
            } else stack.add(false);
        }
        return stack.size()==1 && stack.get(0);
    }
}
