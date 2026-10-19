package org.develnext.jphp.core.tokenizer.token.stmt;

import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.TokenMeta;

abstract public class StmtToken extends Token {
    private java.util.List<AttributeStmtToken> phpAttributes;
    public java.util.List<AttributeStmtToken> getPhpAttributes() {
        return phpAttributes == null ? java.util.List.of() : phpAttributes;
    }
    public void setPhpAttributes(java.util.List<AttributeStmtToken> attributes) {
        phpAttributes = attributes.isEmpty() ? null : java.util.List.copyOf(attributes);
    }
    public StmtToken(TokenMeta meta, TokenType type) {
        super(meta, type);
    }

    @Override
    public boolean isNamedToken() {
        return true;
    }
}
