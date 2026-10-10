package org.develnext.jphp.core.tokenizer.token.expr.operator;

public class DynamicAccessGetRefExprToken extends DynamicAccessExprToken {
    public DynamicAccessGetRefExprToken(DynamicAccessExprToken token){
        super(token.getMeta());
        setCoalescingRead(token.isCoalescingRead());
        setField(token.getField());
        setFieldExpr(token.getFieldExpr());
    }
}
