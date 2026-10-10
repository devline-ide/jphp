package org.develnext.jphp.core.tokenizer.token.expr.operator;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;

/** A left-associated pipe retains its RHS as an expression for ordered evaluation. */
public final class PipeExprToken extends LogicOperatorExprToken {
    public PipeExprToken(TokenMeta meta) { super(meta, TokenType.T_J_PIPE); }
    @Override public int getPriority() { return 67; }
}
