package org.develnext.jphp.core.tokenizer.token.stmt;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;

import java.util.List;

public class MethodStmtToken extends FunctionStmtToken {
    private boolean isAbstract;
    private String propertyHookName;
    // Compiler-only lexical origin of the generated _run body, never a receiver/ownership certificate.
    private int generatorPropertyHookKind;
    public int getGeneratorPropertyHookKind() {return generatorPropertyHookKind;}
    public void setGeneratorPropertyHookKind(int kind) {
        if(kind<1 || kind>2)throw new IllegalArgumentException("Invalid generator hook kind");
        generatorPropertyHookKind=kind;
    }
    private final java.util.List<PropertyHookStmtToken.BackingAccess> propertyHookBackingAccesses=new java.util.ArrayList<>();
    public String getPropertyHookName() {return propertyHookName;}
    public void setPropertyHookName(String name) {propertyHookName=name;}
    public void notePropertyHookBackingAccess(PropertyHookStmtToken.BackingAccess access) {
        if(propertyHookName==null || !access.member().getWord().equals(propertyHookName))
            throw new IllegalArgumentException("Not the executing hook's exact backing property");
        propertyHookBackingAccesses.add(access);
    }
    public java.util.List<PropertyHookStmtToken.BackingAccess> getPropertyHookBackingAccesses() {
        return java.util.List.copyOf(propertyHookBackingAccesses);
    }
    private boolean isFinal;
    private boolean isStatic;
    private boolean moduleChunk;
    public boolean isModuleChunk() {return moduleChunk;}
    public void setModuleChunk(boolean value) {moduleChunk=value;}

    private ClassStmtToken clazz;

    public MethodStmtToken(TokenMeta meta) {
        super(meta);
    }

    public MethodStmtToken(FunctionStmtToken token){
        this(token.getMeta());
        setPhpAttributes(token.getPhpAttributes());
        setArguments(token.arguments);
        setBody(token.body);
        setModifier(token.modifier);
        setName(token.name);
        setReturnReference(token.returnReference);
        setLocal(token.local);
        setDynamicLocal(token.dynamicLocal);
        setCallsExist(token.callsExist);
        setVarsExists(token.varsExists);
        setUses(token.uses);
        setDocComment(token.docComment);
        setGenerator(token.isGenerator);
        setId(token.id);
        setTypeInfo(token.getTypeInfo());
        setGeneratorId(token.generatorId);
        setReturnHintType(token.returnHintType);
        setDeclaredReturnType(token.declaredReturnType);
        setReturnHintTypeClass(token.returnHintTypeClass);
        setReturnOptional(token.returnOptional);

        labels = token.labels;
        variables = token.variables;
        namespace = token.namespace;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean anAbstract) {
        isAbstract = anAbstract;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public void setFinal(boolean aFinal) {
        isFinal = aFinal;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public List<ArgumentStmtToken> getArguments() {
        return arguments;
    }

    public void setArguments(List<ArgumentStmtToken> arguments) {
        this.arguments = arguments;
    }

    public ClassStmtToken getClazz() {
        return clazz;
    }

    public void setClazz(ClassStmtToken clazz) {
        this.clazz = clazz;
    }

    public boolean isInterfacable(){
        return body == null;
    }

    public static MethodStmtToken of(String name, ClassStmtToken clazz){
        MethodStmtToken mToken = new MethodStmtToken(clazz.getMeta());
        mToken.setClazz(clazz);
        mToken.setName(new NameToken(TokenMeta.of(name, clazz)));
        mToken.setNamespace(null);
        return mToken;
    }
}
