package org.develnext.jphp.core.tokenizer.token.stmt;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
/** Modern #[ delimiter. The legacy lexer still reads # as a comment. */
public final class AttributeStartToken extends StmtToken {
    public AttributeStartToken(TokenMeta meta) { super(meta, TokenType.T_J_CUSTOM); }
}
