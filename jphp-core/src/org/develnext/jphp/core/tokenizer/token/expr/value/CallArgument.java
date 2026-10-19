package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

/** Source-order call argument; the expression remains in the existing AST view. */
public final class CallArgument {
    public enum Kind { POSITIONAL, NAMED, UNPACK }
    private final ExprStmtToken expression;
    private final String name;
    private final Kind kind;
    private final TokenMeta meta;

    public CallArgument(ExprStmtToken expression, String name, Kind kind, TokenMeta meta) {
        this.expression = java.util.Objects.requireNonNull(expression);
        this.kind = java.util.Objects.requireNonNull(kind);
        if ((kind == Kind.NAMED) != (name != null)) throw new IllegalArgumentException("Named argument label mismatch");
        this.name = name;
        this.meta = java.util.Objects.requireNonNull(meta);
    }
    public ExprStmtToken getExpression() { return expression; }
    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public TokenMeta getMeta() { return meta; }
}
