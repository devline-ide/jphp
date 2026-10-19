package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

import java.util.List;

public class NewExprToken extends ValueExprToken implements CallableExprToken {
    private Token name;
    private org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken anonymousClass;
    public org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken getAnonymousClass() { return anonymousClass; }
    public void setAnonymousClass(org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken value) { anonymousClass=value; }
    private ExprStmtToken exprName;
    private List<ExprStmtToken> parameters;
    private List<CallArgument> arguments;

    public NewExprToken(TokenMeta meta) {
        super(meta, TokenType.T_NEW);
    }

    public Token getName() {
        return name;
    }

    public void setName(FulledNameToken name) {
        this.name = name;
    }

    public void setName(SelfExprToken name) {
        this.name = name;
    }

    public void setName(ExprStmtToken name){
        this.exprName = name;
    }

    public void setName(StaticExprToken name){
        this.name = name;
    }

    public boolean isDynamic(){
        return exprName != null;
    }

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

    @Override
    public boolean isNamedToken() {
        return true;
    }

    public ExprStmtToken getExprName() {
        return exprName;
    }
}
