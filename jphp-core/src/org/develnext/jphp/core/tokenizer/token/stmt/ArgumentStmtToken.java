package org.develnext.jphp.core.tokenizer.token.stmt;

import php.runtime.common.HintType;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;

public class ArgumentStmtToken extends StmtToken {
    private boolean reference;
    private java.util.List<PropertyHookStmtToken> promotedPropertyHooks=java.util.List.of();
    public java.util.List<PropertyHookStmtToken> getPromotedPropertyHooks() {return promotedPropertyHooks;}
    public void setPromotedPropertyHooks(java.util.List<PropertyHookStmtToken> hooks) {
        promotedPropertyHooks=java.util.List.copyOf(hooks);
    }
    private boolean variadic;
    private HintType hintType;
    private php.runtime.reflection.support.PhpTypeDeclaration declaredType;
    private NameToken hintTypeClass;
    private VariableExprToken name;
    private ExprStmtToken value;
    private boolean optional;
    private php.runtime.common.Modifier promotionModifier;
    private php.runtime.common.Modifier phpSetModifier;
    public php.runtime.common.Modifier getPhpSetModifier() {return phpSetModifier;}
    public void setPhpSetModifier(php.runtime.common.Modifier value) {phpSetModifier=value;}
    private boolean phpReadonly;
    public boolean isPhpReadonly() {return phpReadonly;}
    public void setPhpReadonly(boolean value) {phpReadonly=value;}
    private php.runtime.reflection.support.PhpTypeDeclaration promotedType;
    public php.runtime.common.Modifier getPromotionModifier() {return promotionModifier;}
    public void setPromotionModifier(php.runtime.common.Modifier value) {promotionModifier=value;}
    public php.runtime.reflection.support.PhpTypeDeclaration getPromotedType() {return promotedType;}
    public void setPromotedType(php.runtime.reflection.support.PhpTypeDeclaration value) {promotedType=value;}

    public ArgumentStmtToken(TokenMeta meta) {
        super(meta, TokenType.T_J_CUSTOM);
    }

    public php.runtime.reflection.support.PhpTypeDeclaration getDeclaredType() {return declaredType;}
    public void setDeclaredType(php.runtime.reflection.support.PhpTypeDeclaration type) {declaredType = type;}

    public NameToken getHintTypeClass() {
        return hintTypeClass;
    }

    public HintType getHintType() {
        return hintType;
    }

    public void setHintType(HintType hintType) {
        this.hintType = hintType;
    }

    public void setHintTypeClass(NameToken typeHint) {
        this.hintTypeClass = typeHint;
    }

    public VariableExprToken getName() {
        return name;
    }

    public void setName(VariableExprToken name) {
        this.name = name;
    }

    public ExprStmtToken getValue() {
        return value;
    }

    public void setValue(ExprStmtToken value) {
        this.value = value;
    }

    public boolean isReference() {
        return reference;
    }

    public void setReference(boolean reference) {
        this.reference = reference;
    }

    public boolean isVariadic() {
        return variadic;
    }

    public void setVariadic(boolean variadic) {
        this.variadic = variadic;
    }

    public boolean isOptional() {
        return optional;
    }

    public void setOptional(boolean optional) {
        this.optional = optional;
    }
}
