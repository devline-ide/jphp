package org.develnext.jphp.core.tokenizer.token.stmt;

import java.util.List;
import java.util.Objects;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;

/** Complete parsed hook body and source evidence; hooks are not public class methods. */
public final class PropertyHookStmtToken extends StmtToken {
    public enum Kind { GET, SET }
    public enum BodyKind { BLOCK, EXPRESSION, ABSTRACT }
    public record BackingAccess(VariableExprToken receiver, DynamicAccessExprToken access, Token member) {
        public BackingAccess {
            Objects.requireNonNull(receiver);Objects.requireNonNull(access);Objects.requireNonNull(member);
            if (!receiver.getName().equals("this") || access.isNullsafe())
                throw new IllegalArgumentException("Not a direct hook backing access");
        }
    }
    private final Kind kind;
    private final MethodStmtToken method;
    private final BodyKind bodyKind;
    private final ExprStmtToken arrowExpression;
    private final List<BackingAccess> backingAccesses;

    public PropertyHookStmtToken(TokenMeta meta, Kind kind, MethodStmtToken method,
            BodyKind bodyKind, ExprStmtToken arrowExpression, List<BackingAccess> backingAccesses) {
        super(meta,TokenType.T_J_CUSTOM);
        this.kind=Objects.requireNonNull(kind);this.method=Objects.requireNonNull(method);
        this.bodyKind=Objects.requireNonNull(bodyKind);this.backingAccesses=List.copyOf(backingAccesses);
        this.arrowExpression=arrowExpression;
        if ((bodyKind==BodyKind.EXPRESSION)!=(arrowExpression!=null)
                || (bodyKind==BodyKind.ABSTRACT)!=(method.getBody()==null))
            throw new IllegalArgumentException("Inconsistent hook body shape");
        if (method.isStatic() || kind==Kind.SET && method.isReturnReference())
            throw new IllegalArgumentException("Invalid hook calling convention");
    }
    public Kind getKind() {return kind;}
    public MethodStmtToken getMethod() {return method;}
    public BodyKind getBodyKind() {return bodyKind;}
    public ExprStmtToken getArrowExpression() {return arrowExpression;}
    public List<BackingAccess> getBackingAccesses() {return backingAccesses;}
}
