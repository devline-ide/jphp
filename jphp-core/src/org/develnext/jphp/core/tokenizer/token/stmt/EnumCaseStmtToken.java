package org.develnext.jphp.core.tokenizer.token.stmt;
import org.develnext.jphp.core.tokenizer.TokenMeta;
/** A declaration case, distinct from switch cases and ordinary class constants. */
public final class EnumCaseStmtToken extends ConstStmtToken {
    public EnumCaseStmtToken(TokenMeta meta) {super(meta);}
}
