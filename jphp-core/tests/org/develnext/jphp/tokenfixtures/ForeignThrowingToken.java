package org.develnext.jphp.tokenfixtures;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;

public class ForeignThrowingToken extends NameToken {
    public ForeignThrowingToken(TokenMeta meta) {
        super(meta);
        throw new IllegalStateException("constructor failure");
    }
}
