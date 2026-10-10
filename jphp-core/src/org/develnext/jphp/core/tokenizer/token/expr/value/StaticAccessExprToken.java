package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

public class StaticAccessExprToken extends ValueExprToken {
    protected ValueExprToken clazz;
    protected ValueExprToken field;
    protected ExprStmtToken fieldExpr;
    protected boolean dynamicConstant;
    private boolean writeAccess, quietRead;
    public boolean isWriteAccess() {return writeAccess;}
    public void setWriteAccess(boolean value) {writeAccess=value;}
    public boolean isQuietRead() {return quietRead;}
    public void setQuietRead(boolean value) {quietRead=value;}

    public StaticAccessExprToken(TokenMeta meta) {
        super(meta, TokenType.T_DOUBLE_COLON);
    }

    public ValueExprToken getClazz() {
        return clazz;
    }

    public void setClazz(ValueExprToken clazz) {
        this.clazz = clazz;
    }

    public ValueExprToken getField() {
        return field;
    }

    public void setField(ValueExprToken field) {
        this.field = field;
    }

    public ExprStmtToken getFieldExpr() {
        return fieldExpr;
    }

    public void setFieldExpr(ExprStmtToken fieldExpr) {
        this.fieldExpr = fieldExpr;
    }

    public boolean isDynamicConstant() { return dynamicConstant; }

    public void setDynamicConstant(boolean dynamicConstant) { this.dynamicConstant = dynamicConstant; }

    public boolean isGetStaticField(){
        return field instanceof VariableExprToken;
    }

    public boolean isGetMethod(){
        return field instanceof NameToken;
    }

    public boolean isAsParent(){
        return clazz.getLast() instanceof ParentExprToken;
    }
}
