package org.develnext.jphp.core.syntax.generators;

import java.util.*;
import org.develnext.jphp.core.common.Separator;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.CommentToken;
import org.develnext.jphp.core.tokenizer.token.SemicolonToken;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.CommaToken;
import org.develnext.jphp.core.tokenizer.token.expr.DollarExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.MatchExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AmpersandRefToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AssignExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.common.Modifier;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.support.PhpTypeDeclaration;

/** Only declarations containing a real hook block enter this path; ordinary declarations remain unchanged. */
public final class PropertyHookGenerator extends Generator<ClassVarStmtToken> {
    public PropertyHookGenerator(SyntaxAnalyzer analyzer) {super(analyzer);}
    @Override public boolean isAutomatic() {return false;}
    public static boolean hasHooksAhead(ListIterator<Token> iterator) {
        return hookBlockAhead(iterator)>=0;
    }
    /** Locate the declaration block without parsing or mutating initializer expression state. */
    private static int hookBlockAhead(ListIterator<Token> iterator) {
        int start=iterator.nextIndex(),depth=0;
        boolean expressionBody=false;Token previous=null;
        try {
            while(iterator.hasNext()) {
                Token token=iterator.next();
                if(token instanceof CommentToken) continue;
                if(token instanceof BraceExprToken brace) {
                    if(depth==0 && brace.isBlockOpened()) {
                        // Dynamic names and closure/match/anonymous-class bodies own these braces.
                        if(!expressionBody && !(previous instanceof StaticAccessExprToken)
                                && !(previous instanceof DynamicAccessExprToken) && !(previous instanceof DollarExprToken))
                            return iterator.previousIndex();
                        expressionBody=false;
                    }
                    if(depth==0 && brace.isBlockClosed()) break;
                    depth+=brace.isOpened()?1:-1;
                } else if(depth==0) {
                    if(token instanceof SemicolonToken) break;
                    // Scoped keyword names (for example C::class) do not introduce an expression body.
                    if(!(previous instanceof StaticAccessExprToken) && !(previous instanceof DynamicAccessExprToken)
                            && (token instanceof FunctionStmtToken || token instanceof MatchExprToken
                                || token instanceof ClassStmtToken)) expressionBody=true;
                }
                previous=token;
            }
        } finally {while(iterator.nextIndex()>start) iterator.previous();}
        return -1;
    }
    @Override public ClassVarStmtToken getToken(Token token,ListIterator<Token> iterator) {
        throw new IllegalStateException("Property hook parser requires its exact declaring-property context");
    }
    public ClassVarStmtToken getToken(VariableExprToken variable,ListIterator<Token> iterator,
            ClassStmtToken clazz,List<Token> modifiers,PhpTypeDeclaration type) {
        ClassVarStmtToken property=new ClassVarStmtToken(variable.getMeta());
        property.setClazz(clazz);property.setVariable(variable);property.setDeclaredType(type);
        boolean abstractProperty=false;
        for(Token modifier : modifiers) {
            if(modifier instanceof PropertySetVisibilityStmtToken set)property.setPhpSetModifier(set.getModifier());
            if(modifier instanceof PrivateStmtToken) property.setModifier(Modifier.PRIVATE);
            if(modifier instanceof ProtectedStmtToken) property.setModifier(Modifier.PROTECTED);
            if(modifier instanceof PublicStmtToken) property.setModifier(Modifier.PUBLIC);
            if(modifier instanceof AbstractStmtToken) abstractProperty=true;
            if(modifier instanceof FinalStmtToken) property.setFinal(true);
            if(modifier.getWord().equalsIgnoreCase("static")) property.setStatic(true);
            if(modifier.getWord().equalsIgnoreCase("readonly")) property.setPhpReadonly(true);
        }
        if(property.getModifier()==null) property.setModifier(Modifier.PUBLIC);
        if(property.isStatic())
            analyzer.getEnvironment().error(variable.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Cannot declare hooks for static property");
        if(property.isPhpReadonly() || clazz.isPhpReadonly())
            analyzer.getEnvironment().error(variable.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Hooked properties cannot be readonly");
        Token token=nextToken(iterator);
        if(token instanceof AssignExprToken) {
            List<Token> expression=new ArrayList<>();int depth=0;
            int hookBlock=hookBlockAhead(iterator);
            while(iterator.hasNext()) {
                token=nextToken(iterator);
                if(iterator.previousIndex()==hookBlock) break;
                if(depth==0 && (token instanceof SemicolonToken || token instanceof CommaToken)) unexpectedToken(token);
                if(token instanceof BraceExprToken brace) depth+=brace.isOpened()?1:-1;
                expression.add(token);
            }
            expression.add(new SemicolonToken(TokenMeta.of(";",variable)));
            ListIterator<Token> bounded=expression.listIterator();
            property.setValue(analyzer.generator(SimpleExprGenerator.class).getToken(bounded.next(),bounded,Separator.SEMICOLON,null));
        }
        if(!isOpenedBrace(token,BraceExprToken.Kind.BLOCK)) unexpectedToken(token);
        List<PropertyHookStmtToken> hooks=new ArrayList<>();
        EnumSet<PropertyHookStmtToken.Kind> seen=EnumSet.noneOf(PropertyHookStmtToken.Kind.class);
        while(iterator.hasNext()) {
            token=nextToken(iterator);
            if(isClosedBrace(token,BraceExprToken.Kind.BLOCK)) break;
            List<AttributeStmtToken> attributes=List.of();
            if(token instanceof AttributeStartToken) {
                attributes=analyzer.generator(AttributeGenerator.class).parse(token,iterator);
                token=nextToken(iterator);
            }
            boolean isFinal=false,reference=false;
            if(token instanceof FinalStmtToken) {isFinal=true;token=nextToken(iterator);}
            if(token instanceof AmpersandRefToken) {reference=true;token=nextToken(iterator);}
            if(!(token instanceof NameToken) || !Set.of("get","set").contains(token.getWord().toLowerCase(Locale.ROOT)))
                unexpectedToken(token,"get or set hook");
            PropertyHookStmtToken.Kind kind=token.getWord().equalsIgnoreCase("get")?PropertyHookStmtToken.Kind.GET:PropertyHookStmtToken.Kind.SET;
            if(!seen.add(kind)) analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Cannot redeclare property hook \"%s\"",kind.name().toLowerCase(Locale.ROOT));
            if(reference && kind==PropertyHookStmtToken.Kind.SET) unexpectedToken(token,"Only get hooks may return by reference");
            MethodStmtToken method=new MethodStmtToken(token.getMeta());
            method.setPhpAttributes(attributes);
            method.setClazz(clazz);method.setName(NameToken.valueOf("$"+variable.getName()+"::"+kind.name().toLowerCase(Locale.ROOT)));
            method.setModifier(property.getModifier());method.setFinal(isFinal);method.setReturnReference(reference);
            Token first=nextToken(iterator);
            PropertyHookStmtToken hook=analyzer.generator(FunctionGenerator.class).parsePropertyHook(method,kind,first,iterator,type,List.of());
            // Direct backing reads are recorded in the actual executing method scope by SimpleExprGenerator.
            if(hook.getBodyKind()==PropertyHookStmtToken.BodyKind.ABSTRACT && !clazz.isInterface() && !abstractProperty)
                analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                        "Non-abstract property hook must have a body");
            if(clazz.isInterface() && hook.getBodyKind()!=PropertyHookStmtToken.BodyKind.ABSTRACT) unexpectedToken(token);
            hooks.add(hook);
        }
        if(hooks.isEmpty()) unexpectedToken(token,"A property hook block must not be empty");
        property.setPropertyHooks(hooks);
        boolean backed=hooks.stream().anyMatch(hook->!hook.getBackingAccesses().isEmpty());
        // An inherited backed slot is decided only after the exact declared parent is resolved.
        if(!backed && property.getValue()!=null && clazz.getExtend()==null)
            analyzer.getEnvironment().error(variable.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Cannot specify default value for virtual hooked property %s::$%s",clazz.getFulledName(),variable.getName());
        PropertyHookStmtToken get=property.getPropertyHook(PropertyHookStmtToken.Kind.GET);
        if(backed && get!=null && get.getMethod().isReturnReference()
                && property.getPropertyHook(PropertyHookStmtToken.Kind.SET)!=null)
            analyzer.getEnvironment().error(variable.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Get hook of backed property %s::%s with set hook may not return by reference",clazz.getFulledName(),variable.getName());
        return property;
    }
}
