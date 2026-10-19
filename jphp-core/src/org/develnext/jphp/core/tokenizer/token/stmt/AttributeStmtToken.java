package org.develnext.jphp.core.tokenizer.token.stmt;
import java.util.List;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallArgument;
/** An ordered declaration occurrence; expressions stay unevaluated until Reflection requests them. */
public final class AttributeStmtToken extends StmtToken {
    private final FulledNameToken name;
    private final List<CallArgument> arguments;
    public AttributeStmtToken(TokenMeta meta, FulledNameToken name, List<CallArgument> arguments) {
        super(meta, TokenType.T_J_CUSTOM); this.name=name;this.arguments=List.copyOf(arguments);
    }
    public FulledNameToken getName() {return name;}
    public List<CallArgument> getArguments() {return arguments;}
}
