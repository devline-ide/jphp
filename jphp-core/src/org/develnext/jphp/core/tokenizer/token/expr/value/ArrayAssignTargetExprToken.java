package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

/** A captured write path; the ordinary assignment operator still owns the RHS. */
public class ArrayAssignTargetExprToken extends ValueExprToken implements CallableExprToken {
    private final ExprStmtToken target;
    public ArrayAssignTargetExprToken(TokenMeta meta, ExprStmtToken target) {
        super(meta, TokenType.T_J_CUSTOM);
        this.target = target;
    }
    public ExprStmtToken getTarget() { return target; }
}
