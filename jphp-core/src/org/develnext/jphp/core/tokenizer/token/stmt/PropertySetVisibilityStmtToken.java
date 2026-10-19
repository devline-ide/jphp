package org.develnext.jphp.core.tokenizer.token.stmt;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import php.runtime.common.Modifier;
/** Parser-owned modifier for an exact visibility(set) token sequence. */
public final class PropertySetVisibilityStmtToken extends StmtToken {
    private final Modifier modifier;
    public PropertySetVisibilityStmtToken(TokenMeta meta,Modifier modifier){super(meta,TokenType.T_J_CUSTOM);this.modifier=modifier;}
    public Modifier getModifier(){return modifier;}
}
