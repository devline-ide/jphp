package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

public class ThrowExprToken extends ValueExprToken {
    private final ExprStmtToken exception;
    public ThrowExprToken(TokenMeta meta, ExprStmtToken exception) { super(meta, TokenType.T_THROW); this.exception=exception; }
    public ExprStmtToken getException() { return exception; }
}
