package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

public class NullCoalesceAssignExprToken extends ValueExprToken implements CallableExprToken {
    private final ExprStmtToken target;
    private final ExprStmtToken value;
    public NullCoalesceAssignExprToken(TokenMeta meta, ExprStmtToken target, ExprStmtToken value) {
        super(meta, TokenType.T_J_CUSTOM);
        this.target = target;
        this.value = value;
    }
    public ExprStmtToken getTarget() { return target; }
    public ExprStmtToken getValue() { return value; }
}
