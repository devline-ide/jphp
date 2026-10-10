package org.develnext.jphp.core.tokenizer.token.expr.operator;

import org.develnext.jphp.core.tokenizer.TokenMeta;

/** PHP numeric unary signs bind below exponentiation. */
public class UnaryNumericExprToken extends UnarMinusExprToken {
    private final boolean negative;
    public UnaryNumericExprToken(TokenMeta meta, boolean negative) { super(meta); this.negative = negative; }
    public boolean isNegative() { return negative; }
    @Override public int getPriority() { return 21; }
    @Override public String getCode() { return negative ? "negative" : "positive"; }
}
