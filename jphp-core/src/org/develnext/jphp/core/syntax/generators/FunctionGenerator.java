package org.develnext.jphp.core.syntax.generators;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.ColonToken;
import org.develnext.jphp.core.tokenizer.token.CommentToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.ArgumentUnpackExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.ValueIfElseToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.exceptions.support.ErrorType;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.manually.BodyGenerator;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.token.SemicolonToken;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.CommaToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AmpersandRefToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AssignExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;

import java.util.*;

public class FunctionGenerator extends Generator<FunctionStmtToken> {

    public FunctionGenerator(SyntaxAnalyzer analyzer) {
        super(analyzer);
    }

    private boolean isPhp8() {
        return analyzer.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
    }

    private HintType legacyHint(String word) {
        if (word.equals("never") || word.equals("parent") || word.equals("static")) return null;
        return jphp_scalarTypeHints.contains(word) ? null : HintType.of(word);
    }

    private NameToken parentType(Token token) {
        if (analyzer.getClazz() == null || analyzer.getClazz().getExtend() == null) {
            unexpectedToken(token, "parent requires a class with a parent");
        }
        return analyzer.getClazz().getExtend().getName();
    }

    protected final static Set<String> scalarTypeHints = new HashSet<String>(){{
        add("array");
        add("callable");
        add("iterable");
        add("string");
        add("int");
        add("float");
        add("bool");
        add("object");
        add("self");
    }};

    protected final static Set<String> disallowScalarTypeHints = new HashSet<String>(){{
        add("void");
    }};

    protected final static Set<String> jphp_scalarTypeHints = new HashSet<String>(){{
        add("scalar");
        add("number");
        add("string");
        add("int");
        add("integer");
        add("double");
        add("float");
        add("bool");
        add("boolean");
    }};

    @SuppressWarnings("unchecked")
    protected ArgumentStmtToken processArgument(ListIterator<Token> iterator){
        boolean isReference = false;
        boolean isVariadic  = false;

        VariableExprToken variable = null;
        ExprStmtToken value = null;

        Token next = nextToken(iterator);
        if (next instanceof CommaToken || isClosedBrace(next, BraceExprToken.Kind.SIMPLE))
            return null;

        List<AttributeStmtToken> attributes=List.of();
        if (next instanceof AttributeStartToken) {
            attributes=analyzer.generator(AttributeGenerator.class).parse(next,iterator);
            next=nextToken(iterator);
        }
        boolean phpReadonly=false;
        php.runtime.common.Modifier promotion=null,setModifier=null;
        while(isPhp8() && (PhpSetVisibility.visibility(next) || next instanceof NameToken && next.getWord().equalsIgnoreCase("readonly"))) {
            if(next.getWord().equalsIgnoreCase("readonly")) {
                if(phpReadonly)unexpectedToken(next);phpReadonly=true;
            } else {
                Token modifier=PhpSetVisibility.parse(analyzer,next,iterator);
                if(modifier instanceof PropertySetVisibilityStmtToken set) {
                    if(setModifier!=null)analyzer.getEnvironment().error(next.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Multiple access type modifiers are not allowed");
                    setModifier=set.getModifier();
                } else {
                    if(promotion!=null)analyzer.getEnvironment().error(next.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Multiple access type modifiers are not allowed");
                    promotion=next instanceof PrivateStmtToken?php.runtime.common.Modifier.PRIVATE:next instanceof ProtectedStmtToken?php.runtime.common.Modifier.PROTECTED:php.runtime.common.Modifier.PUBLIC;
                }
            }
            next=nextToken(iterator);
        }
        if(promotion!=null || setModifier!=null || phpReadonly) {
            if(promotion==null)promotion=php.runtime.common.Modifier.PUBLIC;
            var function=analyzer.getFunction();
            if(analyzer.getClazz()==null || analyzer.peekClosure()!=null || function==null || function.getName()==null
                    || !function.getName().getName().equalsIgnoreCase("__construct"))
                analyzer.getEnvironment().error(next.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Cannot declare promoted property outside a constructor");
        }
        php.runtime.reflection.support.PhpTypeDeclaration promotedType = null;
        NameToken hintTypeClass = null;
        HintType hintType = null;
        boolean optional = false;
        php.runtime.reflection.support.PhpTypeDeclaration declaredType = null;
        if (isPhp8() && !(next instanceof VariableExprToken) && !(next instanceof AmpersandRefToken)
                && !(next instanceof ArgumentUnpackExprToken)) {
            List<Token> prefix = new ArrayList<>();
            while (!(next instanceof VariableExprToken) && !(next instanceof ArgumentUnpackExprToken)) {
                if (next instanceof AmpersandRefToken) {
                    Token following = nextTokenAndPrev(iterator);
                    if (following instanceof VariableExprToken || following instanceof ArgumentUnpackExprToken) break;
                }
                prefix.add(next); next = nextToken(iterator);
            }
            declaredType = PhpTypeDeclarationParser.parseFunction(analyzer, prefix, false);
            if (promotion != null) promotedType = PhpTypeDeclarationParser.parseProperty(analyzer, prefix,
                    analyzer.getClazz().getFulledName() + "::$" + next.getWord().replace("$", ""));
            optional = declaredType.allowsNull();
            String named = declaredType.getNamedType();
            if (named != null && !Set.of("null", "true", "false").contains(named)) {
                hintType = HintType.of(named);
                if (hintType == null || !php.runtime.reflection.support.PhpTypeDeclaration.BUILTINS.contains(named)
                        && !Set.of("self", "parent").contains(named)) {
                    hintType = null; hintTypeClass = NameToken.valueOf(named);
                }
                declaredType = null;
            }
        }

        if (next instanceof ValueIfElseToken) {
            optional = true;
            next = nextToken(iterator);
        }

        if (next instanceof SelfExprToken) {
            if (analyzer.getClazz() == null && !(isPhp8() && analyzer.peekClosure() != null)) {
                unexpectedToken(next);
            }

            if (analyzer.getClazz() != null && !analyzer.getClazz().isTrait() && !analyzer.getClazz().isAnonymous() && !(isPhp8() && analyzer.peekClosure() != null)) {
                next = analyzer.getClazz().getName();
                hintTypeClass = analyzer.getRealName((NameToken) next);
            } else {
                hintType = HintType.SELF;
            }

            next = nextToken(iterator);
        } else if (isPhp8() && next instanceof ParentExprToken) {
            if (analyzer.peekClosure() != null || analyzer.getClazz() != null && analyzer.getClazz().isTrait()) {
                hintType = HintType.PARENT;
            }
            else hintTypeClass = parentType(next);
            next = nextToken(iterator);
        } else if (next instanceof NameToken){
            String word = ((NameToken) next).getName().toLowerCase(Locale.ROOT);
            if (isPhp8() && word.equals("never")) unexpectedToken(next, "never is a return-only type");
            if (word.equals("mixed")) {
                if (isPhp8()) {
                    if (optional) unexpectedToken(next, "mixed cannot be nullable");
                    hintType = HintType.MIXED;
                    optional = true;
                } else {
                    hintTypeClass = analyzer.getRealName((NameToken) next);
                }
            } else if (scalarTypeHints.contains(word)) {
                hintType = HintType.of(word);
            } else if (disallowScalarTypeHints.contains(word)) {
                unexpectedToken(next, "valid type");
            } else {
                hintType = isPhp8() ? null : legacyHint(word);

                if (hintType == null)
                    hintTypeClass = analyzer.getRealName((NameToken)next);
            }

            next = nextToken(iterator);
        }

        if (next instanceof AmpersandRefToken){
            isReference = true;
            next = nextToken(iterator);
        }

        if (next instanceof ArgumentUnpackExprToken) {
            isVariadic = true;
            next = nextToken(iterator);
        }

        if (next instanceof VariableExprToken){
            variable = (VariableExprToken)next;
        } else
            unexpectedToken(next);

        next = nextToken(iterator);
        java.util.List<PropertyHookStmtToken> promotedHooks=java.util.List.of();
        if(isPhp8() && promotion!=null && isOpenedBrace(next,BraceExprToken.Kind.BLOCK)) {
            iterator.previous();
            Token visibility=promotion==php.runtime.common.Modifier.PRIVATE
                    ?new PrivateStmtToken(TokenMeta.of("private",variable))
                    :promotion==php.runtime.common.Modifier.PROTECTED
                    ?new ProtectedStmtToken(TokenMeta.of("protected",variable))
                    :new PublicStmtToken(TokenMeta.of("public",variable));
            java.util.List<Token> modifiers=new java.util.ArrayList<>();modifiers.add(visibility);
            if(setModifier!=null)modifiers.add(new PropertySetVisibilityStmtToken(variable.getMeta(),setModifier));
            if(phpReadonly) modifiers.add(new NameToken(TokenMeta.of("readonly",variable)));
            ClassVarStmtToken promotedProperty=analyzer.generator(PropertyHookGenerator.class).getToken(
                    variable,iterator,analyzer.getClazz(),modifiers,promotedType);
            promotedHooks=promotedProperty.getPropertyHooks();
            next=nextToken(iterator);
        }
        if (next instanceof AssignExprToken) {
            if (isVariadic) {
                unexpectedToken(next);
            }

            value = analyzer.generator(SimpleExprGenerator.class).getToken(
                    nextToken(iterator), iterator, true, BraceExprToken.Kind.SIMPLE
            );
        } else {
            if (next instanceof CommaToken || isClosedBrace(next, BraceExprToken.Kind.SIMPLE)){
                if (next instanceof BraceExprToken) {
                    iterator.previous();
                } else {
                    if (isVariadic) {
                        unexpectedToken(next);
                    }
                }
            } else
                unexpectedToken(next);
        }

        ArgumentStmtToken argument = new ArgumentStmtToken(variable.getMeta());
        argument.setPhpAttributes(attributes);
        argument.setName(variable);
        argument.setDeclaredType(declaredType);
        argument.setHintType(hintType);
        argument.setHintTypeClass(hintTypeClass);
        argument.setReference(isReference);
        argument.setVariadic(isVariadic);
        argument.setValue(value);
        argument.setOptional(optional);
        argument.setPhpReadonly(phpReadonly);
        argument.setPromotionModifier(promotion);
        argument.setPhpSetModifier(setModifier);
        argument.setPromotedType(promotedType);
        argument.setPromotedPropertyHooks(promotedHooks);
        if (promotion != null) {
            if (isVariadic) analyzer.getEnvironment().error(argument.toTraceInfo(analyzer.getContext()),
                    php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR, "Cannot declare variadic promoted property");
            analyzer.getFunction().variable(argument.getName()).setUsed(true);
            analyzer.getFunction().setThisExists(true);
        }

        if (argument.isReference() && argument.getValue() != null)
            analyzer.getFunction().variable(argument.getName()).setUsed(true);

        return argument;
    }

    protected void processArguments(FunctionStmtToken result, ListIterator<Token> iterator) {
        checkUnexpectedEnd(iterator);
        List<ArgumentStmtToken> arguments = new ArrayList<ArgumentStmtToken>();
        while (iterator.hasNext()){
            ArgumentStmtToken argument = processArgument(iterator);
            if (argument == null)
                break;

            arguments.add(argument);
        }

        if (isPhp8()) {
            var names = new java.util.HashSet<String>();
            for (ArgumentStmtToken argument : arguments) if (!names.add(argument.getName().getName()))
                analyzer.getEnvironment().error(argument.toTraceInfo(analyzer.getContext()), php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                        "Redefinition of parameter $%s",argument.getName().getName());
        }
        result.setArguments(arguments);

        Token token = nextTokenAndPrev(iterator);

        if (token instanceof ColonToken) {
            nextToken(iterator);
            Token next = nextToken(iterator);
            if (isPhp8()) {
                List<Token> prefix = new ArrayList<>();
                while (!isOpenedBrace(next, BraceExprToken.Kind.BLOCK) && !(next instanceof SemicolonToken)
                        && !next.getWord().equals("=>")) {
                    prefix.add(next); next = nextToken(iterator);
                }
                iterator.previous();
                var declaration = PhpTypeDeclarationParser.parseFunction(analyzer, prefix, true);
                result.setReturnOptional(declaration.allowsNull());
                String named = declaration.getNamedType();
                if (named != null && !Set.of("null", "true", "false").contains(named)) {
                    HintType hint = HintType.of(named);
                    if (hint != null && (php.runtime.reflection.support.PhpTypeDeclaration.BUILTINS.contains(named)
                            || Set.of("self", "parent", "static").contains(named))) result.setReturnHintType(hint);
                    else result.setReturnHintTypeClass(NameToken.valueOf(named));
                } else result.setDeclaredReturnType(declaration);
                return;
            }

            if (next instanceof ValueIfElseToken) {
                result.setReturnOptional(true);
                next = nextToken(iterator);
            }

            HintType hintType;
            NameToken hintTypeClass = null;

            if (next instanceof SelfExprToken) {
                if (analyzer.getClazz() == null) {
                    if (isPhp8() && analyzer.peekClosure() == null) unexpectedToken(next, "self requires class scope");
                    result.setReturnHintType(HintType.SELF);
                } else {
                    if (analyzer.getClazz().isTrait() || analyzer.getClazz().isAnonymous() || (isPhp8() && analyzer.peekClosure() != null)) {
                        result.setReturnHintType(HintType.SELF);
                    } else {
                        result.setReturnHintTypeClass(new FulledNameToken(next.getMeta(), new ArrayList<Token>() {{
                            if (analyzer.getClazz().getNamespace().getName() != null) {
                                addAll(analyzer.getClazz().getNamespace().getName().getNames());
                            }

                            add(analyzer.getClazz().getName());
                        }}));
                    }
                }
            } else if (isPhp8() && next instanceof ParentExprToken) {
                if (analyzer.peekClosure() != null || analyzer.getClazz() != null && analyzer.getClazz().isTrait()) {
                    result.setReturnHintType(HintType.PARENT);
                }
                else result.setReturnHintTypeClass(parentType(next));
            } else if (isPhp8() && next instanceof StaticExprToken) {
                if (analyzer.getClazz() == null && analyzer.peekClosure() == null) unexpectedToken(next, "static requires class scope");
                result.setReturnHintType(HintType.STATIC);
            } else if (next instanceof NameToken){
                String word = ((NameToken) next).getName().toLowerCase(Locale.ROOT);
                if (word.equals("mixed")) {
                    if (isPhp8()) {
                        if (result.isReturnOptional()) unexpectedToken(next, "mixed cannot be nullable");
                        hintType = HintType.MIXED;
                        result.setReturnOptional(true);
                    } else {
                        hintType = null;
                        hintTypeClass = analyzer.getRealName((NameToken) next);
                    }
                } else if (isPhp8() && word.equals("never")) {
                    if (result.isReturnOptional()) unexpectedToken(next, "never cannot be nullable");
                    hintType = HintType.NEVER;
                } else if (scalarTypeHints.contains(word) || word.equals("void")) {
                    hintType = HintType.of(word);
                } else {
                    hintType = isPhp8() ? null : legacyHint(word);

                    if (hintType == null) {
                        hintTypeClass = analyzer.getRealName((NameToken) next);
                    }
                }

                result.setReturnHintType(hintType);
                result.setReturnHintTypeClass(hintTypeClass);
                if (isPhp8() && hintType == HintType.VOID && result.isReturnOptional()) {
                    unexpectedToken(next, "void cannot be nullable");
                }
            } else {
                unexpectedToken(next, "name");
            }
        }
    }

    protected void processUses(FunctionStmtToken result, ListIterator<Token> iterator){
        Token next = nextToken(iterator);
        if (next instanceof NamespaceUseStmtToken){
            next = nextToken(iterator);
            if (!isOpenedBrace(next, BraceExprToken.Kind.SIMPLE))
                unexpectedToken(next, "(");

            List<ArgumentStmtToken> arguments = new ArrayList<ArgumentStmtToken>();
            while (iterator.hasNext()) {
                ArgumentStmtToken argument = processArgument(iterator);
                if (argument == null)
                    break;

                if (argument.getValue() != null)
                    unexpectedToken(argument.getValue().getSingle());
                arguments.add(argument);

                FunctionStmtToken parent = analyzer.getFunction(true);
                if (parent != null) {
                    parent.variable(argument.getName()).setUsed(true);

                    if (argument.isReference()){
                        parent.variable(argument.getName())
                                .setPassed(true)
                                .setUnstable(true);
                    }

                    parent = analyzer.peekClosure();
                    if (parent != null){
                        parent.variable(argument.getName()).setUnstable(true);
                    }
                }
            }

            result.setUses(arguments);
        } else {
            result.setUses(new ArrayList<ArgumentStmtToken>());
            iterator.previous();
        }
    }

    /** Parse a hook in an independent method scope while retaining its actual declaring class. */
    public PropertyHookStmtToken parsePropertyHook(MethodStmtToken method, PropertyHookStmtToken.Kind kind,
            Token first, ListIterator<Token> iterator,
            php.runtime.reflection.support.PhpTypeDeclaration propertyType,
            List<PropertyHookStmtToken.BackingAccess> backingAccesses) {
        FunctionStmtToken oldFunction=analyzer.getFunction(true);
        List<FunctionStmtToken> outerClosures=new ArrayList<>();
        while(analyzer.peekClosure()!=null) outerClosures.add(analyzer.popClosure());
        analyzer.addScope(true);analyzer.setFunction(method);
        method.setNamespace(analyzer.getNamespace());method.setStatic(false);
        String hookName=method.getName().getName();
        method.setPropertyHookName(hookName.substring(1,hookName.indexOf("::")));
        try {
            Token bodyStart=first;
            if (isOpenedBrace(first,BraceExprToken.Kind.SIMPLE)) {
                if(kind!=PropertyHookStmtToken.Kind.SET) unexpectedToken(first);
                processArguments(method,iterator);
                if(method.getDeclaredReturnType()!=null || method.getReturnHintType()!=null
                        || method.getReturnHintTypeClass()!=null)
                    unexpectedToken(first,"Property hooks cannot declare a return type");
                bodyStart=nextToken(iterator);
            } else {
                List<ArgumentStmtToken> arguments=new ArrayList<>();
                if(kind==PropertyHookStmtToken.Kind.SET) {
                    ArgumentStmtToken value=new ArgumentStmtToken(method.getMeta());
                    value.setName(VariableExprToken.valueOf("value"));value.setDeclaredType(propertyType);
                    arguments.add(value);
                }
                method.setArguments(arguments);
            }
            if(kind==PropertyHookStmtToken.Kind.SET && method.getArguments().size()!=1)
                unexpectedToken(first,"A set hook must accept exactly one value parameter");
            for(ArgumentStmtToken argument : method.getArguments()) {
                if(argument.isReference() || argument.isVariadic() || argument.getValue()!=null
                        || argument.getPromotionModifier()!=null)
                    unexpectedToken(argument,"Invalid property hook parameter");
                analyzer.getScope().addVariable(argument.getName());
                method.variable(argument.getName()).setUsed(true);
                if(argument.getDeclaredType()==null && argument.getHintType()==null
                        && argument.getHintTypeClass()==null && kind==PropertyHookStmtToken.Kind.SET)
                    argument.setDeclaredType(propertyType);
            }
            if(kind==PropertyHookStmtToken.Kind.GET) method.setDeclaredReturnType(propertyType);
            else method.setReturnHintType(HintType.VOID);
            PropertyHookStmtToken.BodyKind bodyKind;
            ExprStmtToken arrow=null;
            if(isOpenedBrace(bodyStart,BraceExprToken.Kind.BLOCK)) {
                bodyKind=PropertyHookStmtToken.BodyKind.BLOCK;
                method.setBody(analyzer.generator(BodyGenerator.class).getToken(bodyStart,iterator));
            } else if(bodyStart.getWord().equals("=>")) {
                bodyKind=PropertyHookStmtToken.BodyKind.EXPRESSION;
                arrow=analyzer.generator(SimpleExprGenerator.class).getToken(nextToken(iterator),iterator,
                        org.develnext.jphp.core.common.Separator.SEMICOLON,null);
                // GET returns the expression. SET keeps its expression for dedicated backing-write lowering.
                if(kind==PropertyHookStmtToken.Kind.GET) {
                    ReturnStmtToken result=new ReturnStmtToken(arrow.getMeta());result.setValue(arrow);
                    method.setBody(BodyStmtToken.of(new ExprStmtToken(analyzer.getEnvironment(),analyzer.getContext(),result)));
                  } else {
                      // Expression SET means an actual write to this property's backing slot.
                      VariableExprToken receiver=VariableExprToken.valueOf("this");
                      org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken access=
                              new org.develnext.jphp.core.tokenizer.token.expr.operator.DynamicAccessExprToken(TokenMeta.of("->",method));
                      NameToken member=new NameToken(TokenMeta.of(method.getPropertyHookName(),method));access.setField(member);
                      method.notePropertyHookBackingAccess(new PropertyHookStmtToken.BackingAccess(receiver,access,member));
                      analyzer.getScope().addVariable(receiver);method.variable(receiver).setUsed(true);
                      List<Token> assignment=new ArrayList<>();assignment.add(receiver);assignment.add(access);
                      assignment.add(new AssignExprToken(TokenMeta.of("=",method)));assignment.addAll(arrow.getTokens());
                      method.setBody(BodyStmtToken.of(new ExprStmtToken(analyzer.getEnvironment(),analyzer.getContext(),assignment)));
                  }
            } else if(bodyStart instanceof SemicolonToken) {
                bodyKind=PropertyHookStmtToken.BodyKind.ABSTRACT;
                method.setAbstract(true);method.setInterfacable(true);method.setBody(null);
            } else {unexpectedToken(bodyStart);return null;}
            method.setTypeInfo(analyzer.getScope().getTypeInfo());
            method.setLabels(analyzer.getScope().getLabels());
            method.setLocal(analyzer.getScope().getVariables());
            return new PropertyHookStmtToken(method.getMeta(),kind,method,bodyKind,arrow,method.getPropertyHookBackingAccesses());
        } finally {
            analyzer.removeScope();analyzer.setFunction(oldFunction);
            for(int i=outerClosures.size()-1;i>=0;i--) analyzer.pushClosure(outerClosures.get(i));
        }
    }

    protected void processBody(FunctionStmtToken result, ListIterator<Token> iterator){
        Token next = nextToken(iterator);
        if (isOpenedBrace(next, BraceExprToken.Kind.BLOCK)){
            BodyStmtToken body = analyzer.generator(BodyGenerator.class).getToken(next, iterator);
            result.setBody(body);
        } else if (next instanceof SemicolonToken) {
            result.setInterfacable(true);
            result.setBody(null);
        } else
            unexpectedToken(next);
    }

    @SuppressWarnings("unchecked")
    public FunctionStmtToken getToken(Token current, ListIterator<Token> iterator, boolean closureAllowed) {
        if (current instanceof FunctionStmtToken) {
            CommentToken commentToken = null;

            iterator.previous();
            if (iterator.hasPrevious()) {

                int cnt = 0;

                while (iterator.hasPrevious()) {
                    cnt++;
                    Token previous = iterator.previous();

                    if (previous.isNamedToken()) continue;

                    if (previous instanceof CommentToken && ((CommentToken) previous).getKind() == CommentToken.Kind.DOCTYPE) {
                        commentToken = ((CommentToken) previous);
                    }

                    break;
                }

                for (int i = 0; i < cnt; i++) {
                    iterator.next();
                }
            }
            iterator.next();

            FunctionStmtToken result = (FunctionStmtToken)current;
            result.setStatic(analyzer.getFunction() == null);

            Class<? extends Token>[] excludes = new Class[] {EchoStmtToken.class, ImportExprToken.class};

            if (analyzer.getClazz() != null) {
                excludes = new Class[0];
            }

            Token next = nextTokenSensitive(iterator, excludes);

            if (next instanceof AmpersandRefToken){
                result.setReturnReference(true);
                next = nextTokenSensitive(iterator, excludes);
            }

            if (next instanceof NameToken){
                /*if (analyzer.getFunction() != null)
                    unexpectedToken(current);*/

                analyzer.addScope(true);
                FunctionStmtToken oldFunction = analyzer.getFunction();
                FunctionStmtToken oldAbsoluteFunction = analyzer.getFunction(true);
                analyzer.setFunction(result);

                BraceExprToken brace = nextAndExpected(iterator, BraceExprToken.class);
                if (!brace.isSimpleOpened())
                    unexpectedToken(brace, "(");

                result.setNamespace(analyzer.getNamespace());
                result.setName((NameToken) next);
                result.setDocComment(commentToken);

                ClassStmtToken oldClass = analyzer.getClazz();
                boolean nestedGlobal = oldFunction != null;
                List<FunctionStmtToken> outerClosures = new ArrayList<>();
                if (nestedGlobal) {
                    while (analyzer.peekClosure() != null) outerClosures.add(analyzer.popClosure());
                }
                if (nestedGlobal) analyzer.setClazz(null);
                try {
                    processArguments(result, iterator);
                    processBody(result, iterator);
                } finally {
                    if (nestedGlobal) analyzer.setClazz(oldClass);
                    for (int i = outerClosures.size() - 1; i >= 0; i--) analyzer.pushClosure(outerClosures.get(i));
                    analyzer.setFunction(oldAbsoluteFunction);
                }

                result.setTypeInfo(analyzer.getScope().getTypeInfo());
                result.setLabels(analyzer.getScope().getLabels());
                result.setLocal(analyzer.removeScope().getVariables());

                Token previous = iterator.previous();
                result.getMeta().setEndLine(previous.getMeta().getStartLine());
                result.getMeta().setEndPosition(previous.getMeta().getStartPosition());
                iterator.next();

                return result;
            } else if (next instanceof BraceExprToken){
                // xClosure
                if (((BraceExprToken) next).isSimpleOpened()){
                    if (closureAllowed){
                        analyzer.pushClosure(result);

                        analyzer.addScope(true);
                        processArguments(result, iterator);
                        processUses(result, iterator);
                        processBody(result, iterator);
                        //boolean thisExists = result.isThisExists();
                        result.setTypeInfo(analyzer.getScope().getTypeInfo());
                        result.setLabels(analyzer.getScope().getLabels());
                        result.setStaticExists(analyzer.getScope().isStaticExists());
                        result.setLocal(analyzer.removeScope().getVariables());
                        //result.setThisExists(thisExists);

                        analyzer.popClosure();

                        FunctionStmtToken prevClosure = analyzer.peekClosure();
                        if (prevClosure != null){
                            if (result.isThisExists()) {
                                analyzer.getScope().addVariable(FunctionStmtToken.thisVariable);
                                //prevClosure.variable(FunctionStmtToken.thisVariable).setUsed(true);
                                //prevClosure.setThisExists(true);
                            }
                        }

                        List<VariableExprToken> uses = new ArrayList<VariableExprToken>();
                        for(ArgumentStmtToken argument : result.getUses()){
                            if (argument.isReference()){
                                if (analyzer.getFunction() != null){
                                    analyzer.getFunction().variable(argument.getName()).setReference(true);
                                }
                            }

                            if (analyzer.getFunction() != null) {
                                analyzer.getFunction().variable(argument.getName()).setUsed(true);
                            }

                            uses.add(argument.getName());
                        }

                        analyzer.getScope().addVariables(uses);

                        Token previous = iterator.previous();
                        result.getMeta().setEndLine(previous.getMeta().getStartLine());
                        result.getMeta().setEndPosition(previous.getMeta().getStartPosition());
                        iterator.next();

                        return result;
                    }

                    iterator.previous();
                    return null;
                }
            }

            unexpectedToken(next);
        }

        return null;
    }

    @Override
    public FunctionStmtToken getToken(Token current, ListIterator<Token> iterator) {
        return getToken(current, iterator, false);
    }
}
