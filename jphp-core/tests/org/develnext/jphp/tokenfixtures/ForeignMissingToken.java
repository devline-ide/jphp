package org.develnext.jphp.tokenfixtures;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;

public class ForeignMissingToken extends NameToken {
    public ForeignMissingToken(String word) { super(TokenMeta.of(word)); }
}
