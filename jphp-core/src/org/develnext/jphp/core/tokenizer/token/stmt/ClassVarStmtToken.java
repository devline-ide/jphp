package org.develnext.jphp.core.tokenizer.token.stmt;

import org.develnext.jphp.core.tokenizer.token.CommentToken;
import php.runtime.common.Modifier;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;

public class ClassVarStmtToken extends StmtToken {
    private boolean isStatic;
    private java.util.List<PropertyHookStmtToken> propertyHooks = java.util.List.of();
    public java.util.List<PropertyHookStmtToken> getPropertyHooks() {return propertyHooks;}
    public boolean hasPropertyHooks() {return !propertyHooks.isEmpty();}
    public boolean isVirtualProperty() {
        return hasPropertyHooks() && propertyHooks.stream().allMatch(hook->hook.getBackingAccesses().isEmpty());
    }
    public PropertyHookStmtToken getPropertyHook(PropertyHookStmtToken.Kind kind) {
        for (PropertyHookStmtToken hook : propertyHooks) if (hook.getKind()==kind) return hook;
        return null;
    }
    public void setPropertyHooks(java.util.List<PropertyHookStmtToken> hooks) {
        java.util.List<PropertyHookStmtToken> copy=java.util.List.copyOf(hooks);
        java.util.EnumSet<PropertyHookStmtToken.Kind> seen=java.util.EnumSet.noneOf(PropertyHookStmtToken.Kind.class);
        for (PropertyHookStmtToken hook : copy) if (!seen.add(hook.getKind()))
            throw new IllegalArgumentException("Duplicate property hook");
        propertyHooks=copy;
    }
    private boolean isFinal;
    private php.runtime.common.Modifier phpSetModifier;
    public php.runtime.common.Modifier getPhpSetModifier() {return phpSetModifier;}
    public void setPhpSetModifier(php.runtime.common.Modifier value) {phpSetModifier=value;}
    private boolean phpReadonly;
    public boolean isPhpReadonly() {return phpReadonly;}
    public void setPhpReadonly(boolean value) {phpReadonly=value;}
    private boolean promoted;
    public boolean isPromoted() {return promoted;}
    public void setPromoted(boolean value) {promoted=value;}
    private php.runtime.reflection.support.PhpTypeDeclaration declaredType;
    public boolean isFinal() {return isFinal;}
    public void setFinal(boolean value) {isFinal = value;}
    public php.runtime.reflection.support.PhpTypeDeclaration getDeclaredType() {return declaredType;}
    public void setDeclaredType(php.runtime.reflection.support.PhpTypeDeclaration type) {declaredType = type;}
    private Modifier modifier;

    private VariableExprToken variable;
    private ExprStmtToken value;
    private CommentToken docComment;
    private ClassStmtToken clazz;

    public ClassVarStmtToken(TokenMeta meta) {
        super(meta, TokenType.T_VAR);
    }

    public Modifier getModifier() {
        return modifier;
    }

    public void setModifier(Modifier modifier) {
        this.modifier = modifier;
    }

    public VariableExprToken getVariable() {
        return variable;
    }

    public void setVariable(VariableExprToken variable) {
        this.variable = variable;
    }

    public ExprStmtToken getValue() {
        return value;
    }

    public void setValue(ExprStmtToken value) {
        this.value = value;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public CommentToken getDocComment() {
        return docComment;
    }

    public void setDocComment(CommentToken docComment) {
        this.docComment = docComment;
    }

    public ClassStmtToken getClazz() {
        return clazz;
    }

    public void setClazz(ClassStmtToken clazz) {
        this.clazz = clazz;
    }
}
