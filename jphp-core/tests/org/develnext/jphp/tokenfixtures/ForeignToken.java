package org.develnext.jphp.tokenfixtures;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;

public class ForeignToken extends NameToken {
    public ForeignToken(TokenMeta meta) { super(meta); }
}
