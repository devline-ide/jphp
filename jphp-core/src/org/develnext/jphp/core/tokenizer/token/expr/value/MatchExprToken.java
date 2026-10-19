package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import java.util.ArrayList;
import java.util.List;

public class MatchExprToken extends ValueExprToken {
    public record Arm(List<ExprStmtToken> conditions, ExprStmtToken result) {
        public boolean isDefault() { return conditions.isEmpty(); }
    }
    private ExprStmtToken subject;
    private final List<Arm> arms = new ArrayList<>();
    public MatchExprToken(TokenMeta meta) { super(meta, TokenType.T_J_MATCH); }
    public ExprStmtToken getSubject() { return subject; }
    public void setSubject(ExprStmtToken subject) { this.subject = subject; }
    public List<Arm> getArms() { return arms; }
}
