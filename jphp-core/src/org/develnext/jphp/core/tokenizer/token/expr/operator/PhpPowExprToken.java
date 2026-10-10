package org.develnext.jphp.core.tokenizer.token.expr.operator;

import org.develnext.jphp.core.tokenizer.TokenMeta;

public class PhpPowExprToken extends PowExprToken {
    public PhpPowExprToken(TokenMeta meta) { super(meta); }
    @Override public int getPriority() { return 20; }
    @Override public boolean isRightSide() { return true; }
}
