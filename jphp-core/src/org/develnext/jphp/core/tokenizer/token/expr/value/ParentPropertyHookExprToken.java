package org.develnext.jphp.core.tokenizer.token.expr.value;

/** Literal parent::$property::get/set access; ordinary static access is unchanged. */
public final class ParentPropertyHookExprToken extends StaticAccessExprToken {
    private final int hookKind;
    public ParentPropertyHookExprToken(StaticAccessExprToken source,int kind) {
        super(source.getMeta());
        if(kind<1 || kind>2 || !(source.getClazz() instanceof ParentExprToken)
                || !(source.getField() instanceof VariableExprToken))throw new IllegalArgumentException("Invalid parent hook syntax");
        setClazz(source.getClazz());setField(source.getField());hookKind=kind;
    }
    public int getHookKind(){return hookKind;}
    public String getProperty(){return ((VariableExprToken)getField()).getName();}
}
