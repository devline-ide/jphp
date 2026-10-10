package org.develnext.jphp.core.tokenizer.token.expr.operator;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;

/** Parsing marker; lowered to a value with a lazy RHS and a captured lvalue. */
public class AssignNullCoalesceExprToken extends OperatorExprToken implements AssignableOperatorToken {
    public AssignNullCoalesceExprToken(TokenMeta meta) { super(meta, TokenType.T_J_CUSTOM); }
    @Override public int getPriority() { return 150; }
    @Override public boolean isRightSide() { return true; }
}
