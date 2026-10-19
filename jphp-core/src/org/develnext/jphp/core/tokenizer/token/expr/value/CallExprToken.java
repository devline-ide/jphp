package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.ExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

import java.util.List;

public class CallExprToken extends ValueExprToken implements CallableExprToken {
    private ExprToken name;
    private List<ExprStmtToken> parameters;
    private List<CallArgument> arguments;
    private boolean firstClassCallable;

    protected CallExprToken(TokenMeta meta, TokenType type) {
        super(meta, type);
    }

    public CallExprToken(TokenMeta meta) {
        super(meta, TokenType.T_J_CUSTOM);
    }

    public boolean isFirstClassCallable() {return firstClassCallable;}
    public void setFirstClassCallable(boolean value) {firstClassCallable=value;}

    public List<ExprStmtToken> getParameters() {
        return parameters;
    }

    public void setParameters(List<ExprStmtToken> parameters) {
        this.parameters = parameters;
        this.arguments = null;
    }

    public List<CallArgument> getArguments() { return arguments; }

    public void setArguments(List<CallArgument> arguments) {
        this.arguments = List.copyOf(arguments);
        this.parameters = new java.util.ArrayList<>(arguments.size());
        for (CallArgument argument : arguments) this.parameters.add(argument.getExpression());
    }

    public boolean hasNamedArguments() {
        return arguments != null && arguments.stream().anyMatch(argument -> argument.getKind() == CallArgument.Kind.NAMED);
    }

    public void setName(ExprToken name) {
        this.name = name;
    }

    public Token getName() {
        return name;
    }
}
