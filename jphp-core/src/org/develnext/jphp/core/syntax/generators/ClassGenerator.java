package org.develnext.jphp.core.syntax.generators;

import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.token.CommentToken;
import org.develnext.jphp.core.tokenizer.token.SemicolonToken;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.CommaToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AssignExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.common.LangMode;
import php.runtime.common.Messages;
import php.runtime.common.Modifier;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpClassNames;
import org.develnext.jphp.core.common.Separator;
import php.runtime.common.StringUtils;
import php.runtime.env.Package;
import php.runtime.exceptions.ParseException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;

import java.util.*;

public class ClassGenerator extends Generator<ClassStmtToken> {

    private boolean isReadonly(Token token) {
        return analyzer.getPhpLanguageProfile()==PhpLanguageProfile.PHP_8_5 && token instanceof NameToken
                && token.getWord().equalsIgnoreCase("readonly");
    }

    private static final Set<String> php8ReservedTypeNames = new HashSet<>(Arrays.asList(
            "int", "float", "bool", "string", "array", "object", "callable", "iterable", "void", "mixed",
            "never", "true", "false", "null", "self", "parent", "static"));

    @SuppressWarnings("unchecked")
    private final static Class<? extends Token>[] modifiers = new Class[]{
        PrivateStmtToken.class,
        ProtectedStmtToken.class,
        PublicStmtToken.class,
        StaticExprToken.class,
        FinalStmtToken.class,
        AbstractStmtToken.class,
        VarStmtToken.class,
        ConstStmtToken.class
    };

    @SuppressWarnings("unchecked")
    private final static Class<? extends Token>[] constModifiers = new Class[]{
        PrivateStmtToken.class,
        ProtectedStmtToken.class,
        PublicStmtToken.class,
    };

    public ClassGenerator(SyntaxAnalyzer analyzer) {
        super(analyzer);
    }

    protected void processName(ClassStmtToken result, ListIterator<Token> iterator){
        Token name = nextTokenSensitive(iterator);
        if (name instanceof NameToken){
            if (analyzer.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5
                    && php8ReservedTypeNames.contains(((NameToken) name).getName().toLowerCase(Locale.ROOT))) {
                analyzer.getEnvironment().error(name.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                        "Cannot use '%s' as class name as it is reserved", name.getWord());
            }
            result.setName((NameToken)name);
            if (name instanceof ParentExprToken)
                unexpectedToken(name);
        } else
            unexpectedToken(name, TokenType.T_STRING);
    }

    protected void _processImplements(Token token, ClassStmtToken result, ListIterator<Token> iterator){
        ImplementsStmtToken implement = new ImplementsStmtToken(token.getMeta());

        Token prev = null;
        List<FulledNameToken> names = new ArrayList<FulledNameToken>();
        do {
            token = nextToken(iterator);
            if (token instanceof NameToken){
                names.add(analyzer.getRealName((NameToken)token));
            } else if (token instanceof CommaToken){
                if (!(prev instanceof NameToken))
                    unexpectedToken(token);
            } else if (isOpenedBrace(token, BraceExprToken.Kind.BLOCK)){
                iterator.previous();
                break;
            } else
                unexpectedToken(token);

            prev = token;
        } while (true);

        implement.setNames(names);
        result.setImplement(implement);
    }

    private void processEnumBacking(ClassStmtToken result,ListIterator<Token> iterator) {
        if(!result.isEnum())return;
        Token token=nextToken(iterator);
        if(token.getWord().equals(":")) {
            Token type=nextToken(iterator);String name=type.getWord().toLowerCase(Locale.ROOT);
            if(!name.equals("int") && !name.equals("string"))analyzer.getEnvironment().error(type.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Enum backing type must be int or string, %s given",name);
            result.setEnumBackingType(name);
        } else iterator.previous();
    }
    private EnumCaseStmtToken processEnumCase(ClassStmtToken owner,Token token,ListIterator<Token> iterator) {
        EnumCaseStmtToken result=new EnumCaseStmtToken(token.getMeta());result.setClazz(owner);
        NameToken name=nextAndExpectedSensitive(iterator,NameToken.class);
        Token next=nextToken(iterator);ExprStmtToken value=null;
        if(next instanceof AssignExprToken) {
            value=analyzer.generator(SimpleExprGenerator.class).getToken(nextToken(iterator),iterator,Separator.SEMICOLON,null);
            if(value==null)unexpectedToken(next);
            Token end=iterator.previous();if(!(end instanceof SemicolonToken))unexpectedToken(end,";");
            iterator.next();
        } else if(!(next instanceof SemicolonToken))unexpectedToken(next,";");
        if(owner.getEnumBackingType()==null && value!=null)analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Case %s of non-backed enum %s must not have a value",name.getName(),owner.getFulledName());
        if(owner.getEnumBackingType()!=null && value==null)analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Case %s of backed enum %s must have a value",name.getName(),owner.getFulledName());
        result.add(name,value);return result;
    }
    protected void processExtends(ClassStmtToken result, ListIterator<Token> iterator){
        Token token = nextToken(iterator);
        if (token instanceof ExtendsStmtToken){
            if (result.isTrait() || result.isEnum())
                unexpectedToken(token);

            if (result.isInterface()){
                _processImplements(token, result, iterator);
            } else {
                ExtendsStmtToken extend = (ExtendsStmtToken)token;
                FulledNameToken what = analyzer.generator(NameGenerator.class).getToken(nextToken(iterator), iterator);

                if (what == null) {
                    unexpectedToken(extend);
                }

                extend.setName(analyzer.getRealName(what));
                result.setExtend(extend);
            }
        } else
            iterator.previous();
    }

    protected void processImplements(ClassStmtToken result, ListIterator<Token> iterator){
        checkUnexpectedEnd(iterator);
        Token token = iterator.next();
        if (token instanceof ImplementsStmtToken){
            if (result.isTrait())
                unexpectedToken(token);

            if (result.isInterface())
                unexpectedToken(token, "extends");

            _processImplements(token, result, iterator);
            /*
            ImplementsStmtToken implement = new ImplementsStmtToken(token.getMeta());

            Token prev = null;
            List<FulledNameToken> names = new ArrayList<FulledNameToken>();
            do {
                token = nextToken(iterator);
                if (token instanceof NameToken){
                    names.add(analyzer.getRealName((NameToken)token));
                } else if (token instanceof CommaToken){
                    if (!(prev instanceof NameToken))
                        unexpectedToken(token);
                } else if (isOpenedBrace(token, BraceExprToken.Kind.BLOCK)){
                    iterator.previous();
                    break;
                } else
                    unexpectedToken(token);

                prev = token;
            } while (true);

            implement.setNames(names);
            result.setImplement(implement);*/
        } else
            iterator.previous();
    }

    protected List<ClassVarStmtToken> processProperty(ClassStmtToken clazz, VariableExprToken current,
                                                      List<Token> modifiers,
                                                      ListIterator<Token> iterator){
        Token next = current;
        Token prev = null;
        Set<VariableExprToken> variables = new LinkedHashSet<VariableExprToken>();
        List<ExprStmtToken> initValues = new ArrayList<>();

        ExprStmtToken initValue = null;

        List<ClassVarStmtToken> result = new ArrayList<ClassVarStmtToken>();

        Modifier modifier = Modifier.PUBLIC;
        boolean isStatic = false;
        for(Token token : modifiers){
            if (token instanceof PrivateStmtToken)
                modifier = Modifier.PRIVATE;
            else if (token instanceof ProtectedStmtToken)
                modifier = Modifier.PROTECTED;
            else if (token instanceof StaticExprToken)
                isStatic = true;
        }

        do {
            if (next instanceof VariableExprToken){
                if (!variables.add((VariableExprToken)next)) {
                    throw new ParseException(
                            Messages.ERR_IDENTIFIER_X_ALREADY_USED.fetch(next.getWord()),
                            next.toTraceInfo(analyzer.getContext())
                    );
                }
                initValues.add(null);
            } else if (next instanceof CommaToken){
                if (!(prev instanceof VariableExprToken))
                    unexpectedToken(next);
            } else if (next instanceof AssignExprToken){
                if (!(prev instanceof VariableExprToken))
                    unexpectedToken(next);

                initValue = analyzer.generator(SimpleExprGenerator.class)
                        .getToken(nextToken(iterator), iterator, Separator.COMMA_OR_SEMICOLON, null);
                initValues.set(initValues.size() - 1, initValue);

                if (iterator.hasPrevious() && isBreak(iterator.previous())) {
                    iterator.next();
                    break;
                }

                if (iterator.hasNext()) {
                    iterator.next();
                }
                //break;
            } else if (next instanceof SemicolonToken){
                if (!(prev instanceof VariableExprToken))
                    unexpectedToken(next);
                break;
            }

            prev = next;
            next = nextToken(iterator);
        } while (true);

        int i = 0;

        for(VariableExprToken variable : variables){
            ClassVarStmtToken classVar = new ClassVarStmtToken(variable.getMeta());
            classVar.setModifier(modifier);
            classVar.setStatic(isStatic);
            classVar.setValue(initValues.get(i));
            classVar.setVariable(variable);
            classVar.setClazz(clazz);

            result.add(classVar);
            i++;
        }

        return result;
    }

    protected void processUse(ClassStmtToken result, ListIterator<Token> iterator) {
        Token useToken = iterator.previous(); iterator.next();
        if (result.isInterface())
            unexpectedToken(useToken);

        Token prev = null;
        List<NameToken> uses = new ArrayList<NameToken>();
        List<ClassStmtToken.TraitAdaptation> adaptations = new ArrayList<>();
        do {
            Token token = nextToken(iterator);
            if (token instanceof NameToken){
                uses.add(analyzer.getRealName((NameToken)token));
            } else if (token instanceof CommaToken){
                if (!(prev instanceof NameToken))
                    unexpectedToken(token);
            } else if (token instanceof SemicolonToken) {
                break;
            } else if (isOpenedBrace(token, BraceExprToken.Kind.BLOCK)){
                processBlock(result, uses.get(0), iterator, adaptations);
                break;
            } else
                unexpectedToken(token);

            prev = token;
        } while (true);

        result.getUses().addAll(uses);
        result.addTraitUseGroup(new ClassStmtToken.TraitUseGroup(uses, adaptations, useToken));
    }

    protected void processBlock(ClassStmtToken result, NameToken firstTraitName, ListIterator<Token> iterator) {
        processBlock(result, firstTraitName, iterator, new ArrayList<>());
    }

    protected void processBlock(ClassStmtToken result, NameToken firstTraitName, ListIterator<Token> iterator,
                                List<ClassStmtToken.TraitAdaptation> adaptations) {
        if (result.isInterface())
            unexpectedToken(iterator.previous());

        boolean modern = analyzer.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
        if (modern && isClosedBrace(nextTokenAndPrev(iterator), BraceExprToken.Kind.BLOCK)) {
            iterator.next(); return;
        }
        while (true) {
            NameToken className = nextAndExpectedSensitive(iterator, NameToken.class);
            Token traceToken = className;
            NameToken methodName;
            NameToken declaredTrait = null;

            Token token = nextToken(iterator);
            if (token instanceof StaticAccessExprToken) {
                className = analyzer.getRealName(className);
                declaredTrait = className;
                Token nextTokenSensitive = nextTokenSensitive(iterator);

                if (nextTokenSensitive instanceof NameToken) {
                    methodName = (NameToken) nextTokenSensitive;
                } else {
                    unexpectedToken(nextTokenSensitive);
                    return;
                }
            } else {
                iterator.previous();
                methodName = className;
                if (className.getClass() != NameToken.class)
                    unexpectedToken(className);

                // Compatibility maps historically chose the first trait. The recipe keeps null.
                className = firstTraitName;
            }

            Token what = nextToken(iterator);
            if (what instanceof AsStmtToken) {
                Token one = nextTokenSensitive(iterator, PrivateStmtToken.class, ProtectedStmtToken.class,
                        PublicStmtToken.class, FinalStmtToken.class, StaticExprToken.class);
                Modifier visibility = null;
                NameToken alias = null;
                boolean makeFinal = false;
                if (one instanceof NameToken) {
                    alias = (NameToken) one;
                } else if (isTokenClass(one, PrivateStmtToken.class, ProtectedStmtToken.class, PublicStmtToken.class)
                        || modern && one instanceof FinalStmtToken) {
                    if (one instanceof FinalStmtToken) makeFinal = true;
                    else if (one instanceof ProtectedStmtToken) visibility = Modifier.PROTECTED;
                    else if (one instanceof PublicStmtToken) visibility = Modifier.PUBLIC;
                    else visibility = Modifier.PRIVATE;

                    token = nextTokenSensitive(iterator);
                    if (token instanceof NameToken) {
                        alias = (NameToken) token;
                        if (alias.getClass() != NameToken.class)
                            unexpectedToken(alias);
                    } else if (token instanceof SemicolonToken) {
                        iterator.previous();
                    } else
                        unexpectedToken(token);
                } else
                    unexpectedToken(one);

                result.addAlias(className.getName(), methodName.getName(), visibility,
                        alias == null ? null : alias.getName(), makeFinal);
                adaptations.add(new ClassStmtToken.TraitAlias(declaredTrait, methodName, alias,
                        visibility, makeFinal, traceToken));
                nextAndExpected(iterator, SemicolonToken.class);
            } else if (what instanceof InsteadofStmtToken) {
                if (modern && declaredTrait == null) unexpectedToken(what);
                Set<String> names = new HashSet<String>();
                Set<String> namesLower = new HashSet<String>();
                List<NameToken> excluded = new ArrayList<>();
                NameToken cls;
                while (true) {
                    cls = nextAndExpected(iterator, NameToken.class);
                    cls = analyzer.getRealName(cls);

                    if (!namesLower.add(PhpClassNames.key(cls.getName(), analyzer.getPhpLanguageProfile()))) {
                        analyzer.getEnvironment().error(
                                iterator.previous().toTraceInfo(analyzer.getContext()),
                                ErrorType.E_ERROR,
                                Messages.ERR_TRAIT_MULTIPLE_RULE,
                                methodName.getName(),
                                cls.getName()
                        );
                    }

                    names.add(cls.getName()); excluded.add(cls);
                    Token next = nextToken(iterator);

                    if (next instanceof CommaToken) continue;
                    if (next instanceof SemicolonToken) break;
                }

                if (!result.addReplacement(className.getName(), methodName.getName(), names)){
                    analyzer.getEnvironment().error(
                            iterator.previous().toTraceInfo(analyzer.getContext()),
                            ErrorType.E_ERROR,
                            Messages.ERR_TRAIT_MULTIPLE_RULE,
                            methodName.getName(),
                            cls.getName()
                    );
                }
                // Legacy permits the first-trait shorthand; preserve that path's resolved origin.
                adaptations.add(new ClassStmtToken.TraitPrecedence(
                        declaredTrait == null ? className : declaredTrait, methodName, excluded, traceToken));
            } else
                unexpectedToken(what);

            if (isClosedBrace(nextTokenAndPrev(iterator), BraceExprToken.Kind.BLOCK)) {
                iterator.next();
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void processBody(ClassStmtToken result, ListIterator<Token> iterator){
        analyzer.setClazz(result);

        Token token = nextToken(iterator);
        if (token instanceof BraceExprToken){
            BraceExprToken brace = (BraceExprToken)token;
            if (brace.isBlockOpened()){

                List<ConstStmtToken> constants = new ArrayList<ConstStmtToken>();
                List<MethodStmtToken> methods = new ArrayList<MethodStmtToken>();
                List<ClassVarStmtToken> properties = new ArrayList<ClassVarStmtToken>();

                List<Token> modifiers = new ArrayList<Token>();
                List<AttributeStmtToken> attributes = List.of();
                CommentToken lastComment = null;

                boolean breakByClose = false;

                while (iterator.hasNext()){
                    Token current = iterator.next();
                    if (current instanceof AttributeStartToken) {
                        if (!modifiers.isEmpty() || !attributes.isEmpty()) unexpectedToken(current);
                        attributes=analyzer.generator(AttributeGenerator.class).parse(current,iterator);
                        current=nextToken(iterator);
                    }
                    php.runtime.reflection.support.PhpTypeDeclaration propertyType = null;
                    boolean modernProperty = analyzer.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
                    boolean typeStart = current instanceof NameToken || current.getWord().equals("?") || current.getWord().equals("(")
                            || php.runtime.reflection.support.PhpTypeDeclaration.BUILTINS.contains(current.getWord().toLowerCase(Locale.ROOT))
                            || current.getWord().equalsIgnoreCase("self") || current.getWord().equalsIgnoreCase("parent");
                    if (modernProperty && typeStart && !isReadonly(current)) {
                        if (modifiers.isEmpty()) {
                            analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                                    "syntax error, unexpected " + ((current instanceof NameToken && !current.getWord().equalsIgnoreCase("callable")) || current.getWord().equalsIgnoreCase("self") ? "identifier" : "token")
                                            + " \"" + current.getWord() + "\", expecting \"function\"");
                        }
                        List<Token> typeTokens = new ArrayList<>();
                        while (!(current instanceof VariableExprToken)) {
                            if (!(current instanceof CommentToken)) typeTokens.add(current); current = nextToken(iterator);
                        }
                        if((result.isInterface() || modifiers.stream().anyMatch(modifier->modifier instanceof AbstractStmtToken))
                                && !PropertyHookGenerator.hasHooksAhead(iterator)) {
                            if(result.isInterface()) analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                    "Interfaces may only include hooked properties");
                            for(Token modifier : modifiers) if(modifier instanceof AbstractStmtToken)
                                analyzer.getEnvironment().error(modifier.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                        "Only hooked properties may be declared abstract");
                        }
                        propertyType = PhpTypeDeclarationParser.parseProperty(analyzer, typeTokens,
                                result.getFulledName() + "::$" + ((VariableExprToken) current).getName());
                    }
                    if (current instanceof ExprStmtToken)
                        unexpectedToken(current, "expression");

                    if(current instanceof CaseStmtToken && result.isEnum()) {
                        if(!modifiers.isEmpty())unexpectedToken(current);
                        EnumCaseStmtToken one=processEnumCase(result,current,iterator);one.setDocComment(lastComment);
                        one.setPhpAttributes(attributes);attributes=List.of();
                        constants.add(one);lastComment=null;
                    } else if (current instanceof ConstStmtToken) {
                        if (result.isTrait() && analyzer.getPhpLanguageProfile()
                                != php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                            unexpectedToken(current);
                        }

                        ConstStmtToken one = analyzer.generator(ConstGenerator.class).getToken(current, iterator);
                        one.setPhpAttributes(attributes);attributes=List.of();
                        one.setClazz(result);
                        one.setDocComment(lastComment);
                        one.setModifier(Modifier.PUBLIC);

                        for (Token modifier : modifiers) {
                            if (modifier instanceof PrivateStmtToken) {
                                one.setModifier(Modifier.PRIVATE);
                            } else if (modifier instanceof ProtectedStmtToken) {
                                one.setModifier(Modifier.PROTECTED);
                            } else if (modifier instanceof PublicStmtToken) {
                                one.setModifier(Modifier.PUBLIC);
                            } else if (modifier instanceof FinalStmtToken && analyzer.getPhpLanguageProfile()
                                    == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                                one.setFinal(true);
                            } else {
                                unexpectedToken(modifier);
                            }
                        }

                        lastComment = null;

                        constants.add(one);
                        modifiers.clear();
                    } else if (isTokenClass(current, ClassGenerator.modifiers) || isReadonly(current)){
                        current=PhpSetVisibility.parse(analyzer,current,iterator);
                        for(Token modifier : modifiers){
                            if(current instanceof PropertySetVisibilityStmtToken && modifier instanceof PropertySetVisibilityStmtToken)
                                analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,"Multiple access type modifiers are not allowed");
                            if (modernProperty && modifier instanceof VarStmtToken)
                                analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                                        "syntax error, unexpected token \"" + current.getWord() + "\", expecting variable");
                            if (modernProperty && modifier instanceof StaticExprToken && current instanceof StaticExprToken)
                                analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                                        "Multiple static modifiers are not allowed");
                            if (modifier.getType() == current.getType() && (!isReadonly(current) || isReadonly(modifier)))
                                unexpectedToken(current);
                        }
                        modifiers.add(current);
                    } else if (current instanceof VariableExprToken) {
                        if (result.isInterface() && !modernProperty) {
                            analyzer.getEnvironment().error(
                                    result.toTraceInfo(analyzer.getContext()),
                                    ErrorType.E_ERROR,
                                    Messages.ERR_INTERFACE_MAY_NOT_INCLUDE_VARS
                            );
                        }

                        for(Token modifier : modifiers){

                            if (!modernProperty && (modifier instanceof AbstractStmtToken || modifier instanceof FinalStmtToken)) {
                                unexpectedToken(modifier);
                            }
                        }

                        List<ClassVarStmtToken> vars = modernProperty && PropertyHookGenerator.hasHooksAhead(iterator)
                                ? List.of(analyzer.generator(PropertyHookGenerator.class).getToken(
                                        (VariableExprToken)current,iterator,result,modifiers,propertyType))
                                : processProperty(result,(VariableExprToken)current,modifiers,iterator);

                        for (ClassVarStmtToken var : vars) {
                            var.setPhpAttributes(attributes);
                            if(modernProperty && !var.hasPropertyHooks()) {
                                if(result.isInterface()) analyzer.getEnvironment().error(var.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                        "Interfaces may only include hooked properties");
                                for(Token modifier : modifiers) if(modifier instanceof AbstractStmtToken)
                                    analyzer.getEnvironment().error(modifier.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                            "Only hooked properties may be declared abstract");
                            }
                            var.setDeclaredType(propertyType);
                            for(Token modifier:modifiers)if(modifier instanceof PropertySetVisibilityStmtToken set)var.setPhpSetModifier(set.getModifier());
                            var.setPhpReadonly(result.isPhpReadonly() || modifiers.stream().anyMatch(this::isReadonly));
                            for (Token modifier : modifiers) if (modifier instanceof FinalStmtToken) var.setFinal(true);
                        }
                        if (lastComment != null) {
                            for (ClassVarStmtToken var : vars) {
                                var.setDocComment(lastComment);
                            }

                            lastComment = null;
                        }

                        attributes=List.of();
                        properties.addAll(vars);
                        modifiers.clear();
                    } else if (current instanceof FunctionStmtToken) {
                        FunctionStmtToken function = analyzer.generator(FunctionGenerator.class).getToken(current, iterator);

                        if (function == null) {
                            nextToken(iterator);
                            unexpectedToken(iterator);
                        }

                        MethodStmtToken method = new MethodStmtToken(function);
                        method.setPhpAttributes(attributes);attributes=List.of();
                        method.setClazz(result);
                        method.setDocComment(lastComment);

                        lastComment = null;

                        for (Token modifier : modifiers) {
                            if (isReadonly(modifier)) analyzer.getEnvironment().error(modifier.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR, "Cannot use the readonly modifier on a method");
                            if (modifier instanceof AbstractStmtToken)
                                method.setAbstract(true);
                            else if (modifier instanceof StaticExprToken)
                                method.setStatic(true);
                            else if (modifier instanceof FinalStmtToken) {
                                method.setFinal(true);
                            } else if (modifier instanceof PublicStmtToken) {
                                if (method.getModifier() != null)
                                    unexpectedToken(modifier);

                                method.setModifier(Modifier.PUBLIC);
                            } else if (modifier instanceof PrivateStmtToken) {
                                if (method.getModifier() != null)
                                    unexpectedToken(modifier);

                                method.setModifier(Modifier.PRIVATE);
                            } else if (modifier instanceof ProtectedStmtToken) {
                                if (method.getModifier() != null)
                                    unexpectedToken(modifier);

                                method.setModifier(Modifier.PROTECTED);
                            }
                        }
                        if (method.getModifier() == null)
                            method.setModifier(Modifier.PUBLIC);

                        for (ArgumentStmtToken argument : method.getArguments()) if (argument.getPromotionModifier() != null) {
                            if (method.isAbstract() || method.isInterfacable())
                                analyzer.getEnvironment().error(argument.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                                        "Cannot declare promoted property in an abstract constructor");
                            if (method.isStatic())
                                analyzer.getEnvironment().error(argument.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                                        "Method %s::__construct() cannot be static", result.getFulledName());
                            var property = new ClassVarStmtToken(argument.getMeta());
                            property.setClazz(result);property.setVariable(argument.getName());
                            property.setModifier(argument.getPromotionModifier());property.setDeclaredType(argument.getPromotedType());
                            property.setPhpSetModifier(argument.getPhpSetModifier());
                            property.setPhpReadonly(result.isPhpReadonly() || argument.isPhpReadonly());
                            property.setPhpAttributes(argument.getPhpAttributes());
                            property.setPromoted(true);
                            property.setPropertyHooks(argument.getPromotedPropertyHooks());
                            properties.add(property);
                        }
                        methods.add(method);
                        modifiers.clear();
                    } else if (current instanceof NamespaceUseStmtToken) {
                        if (!attributes.isEmpty()) unexpectedToken(current);
                        processUse(result, iterator);
                        lastComment = null;
                    } else if (isClosedBrace(current, BraceExprToken.Kind.BLOCK)) {
                        if (!attributes.isEmpty()) unexpectedToken(current);
                        breakByClose = true;
                        break;
                    } else if (current instanceof CommentToken){
                        lastComment = (CommentToken) current;
                    } else
                        unexpectedToken(current);
                }

                if (!breakByClose) { // bug-fix from DN.
                    token = nextTokenAndPrev(iterator);

                    if (!isClosedBrace(token, BraceExprToken.Kind.BLOCK)) {
                        unexpectedToken(token);
                    }
                } // ---

                result.setConstants(constants);

                result.setMethods(methods);
                if (analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    var names = new java.util.HashSet<String>();
                    for (ClassVarStmtToken property : properties) if (!names.add(property.getVariable().getName()))
                        analyzer.getEnvironment().error(property.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                "Cannot redeclare %s::$%s",result.getFulledName(),property.getVariable().getName());
                }
                result.setProperties(properties);
                analyzer.setClazz(null);
                return;
            }
        }

        unexpectedToken(token, "{");
    }

    @SuppressWarnings("unchecked")
    protected ClassStmtToken processDefine(Token current, ListIterator<Token> iterator){
        ClassStmtToken result = null;
        if (analyzer.getPhpLanguageProfile()==PhpLanguageProfile.PHP_8_5
                && (isReadonly(current) || current instanceof FinalStmtToken || current instanceof AbstractStmtToken)) {
            int start=iterator.nextIndex(),count=0;boolean readonly=false,fin=false,abs=false;Token modifier=current;
            while (isReadonly(modifier) || modifier instanceof FinalStmtToken || modifier instanceof AbstractStmtToken) {
                if (isReadonly(modifier)) {if(readonly) unexpectedToken(modifier);readonly=true;}
                else if(modifier instanceof FinalStmtToken) {if(fin) unexpectedToken(modifier);fin=true;}
                else {if(abs) unexpectedToken(modifier);abs=true;}
                count++;modifier=nextToken(iterator);
            }
            if (modifier instanceof ClassStmtToken) {
                if(fin&&abs) unexpectedToken(current);
                result=(ClassStmtToken)modifier;result.setFinal(fin);result.setAbstract(abs);result.setPhpReadonly(readonly);
                int end=iterator.nextIndex();for(int i=0;i<count+1&&iterator.hasPrevious();i++)iterator.previous();
                if(iterator.hasPrevious()){Token comment=iterator.previous();if(comment instanceof CommentToken)result.setDocComment((CommentToken)comment);}
                while(iterator.nextIndex()<end)iterator.next();return result;
            }
            while(iterator.nextIndex()>start)iterator.previous();
            if(readonly && (modifier instanceof InterfaceStmtToken || modifier instanceof TraitStmtToken || modifier.getWord().equalsIgnoreCase("enum"))) unexpectedToken(modifier);
            return null;
        }

        if (isTokenClass(current, FinalStmtToken.class, AbstractStmtToken.class)){
            Token next = nextToken(iterator);
            if (next instanceof ClassStmtToken){
                result = (ClassStmtToken)next;
                result.setInterface(false);

                result.setAbstract(current instanceof AbstractStmtToken);
                result.setFinal(current instanceof FinalStmtToken);

                //return result;
            } else if (next instanceof InterfaceStmtToken || next instanceof TraitStmtToken) {
                unexpectedToken(current);
            } else if (next instanceof AbstractStmtToken || next instanceof FinalStmtToken){
                unexpectedToken(next);
            } else {
                iterator.previous();
            }
        }

        if(analyzer.getPhpLanguageProfile()==PhpLanguageProfile.PHP_8_5 && current.getClass()==NameToken.class
                && current.getWord().equalsIgnoreCase("enum") && iterator.hasNext()
                && nextTokenAndPrev(iterator) instanceof NameToken) {
            result=new ClassStmtToken(current.getMeta());result.setClassType(ClassEntity.Type.ENUM);
        } else if (current instanceof ClassStmtToken)
            result = (ClassStmtToken)current;
        else if (current instanceof InterfaceStmtToken){
            result = new ClassStmtToken(current.getMeta());
            result.setInterface(true);
        } else if (current instanceof TraitStmtToken) {
            result = new ClassStmtToken(current.getMeta());
            result.setClassType(ClassEntity.Type.TRAIT);
        }

        if (result != null) {
            iterator.previous();
            if (result.isFinal() || result.isAbstract())
                iterator.previous();

            if (iterator.hasPrevious()) {
                Token tk = iterator.previous();
                if (tk instanceof CommentToken) {
                    result.setDocComment((CommentToken)tk);
                }
                iterator.next();
            }

            if (result.isFinal() || result.isAbstract())
                iterator.next();

            iterator.next();
        }

        return result;
    }

    public void processAnonymous(ClassStmtToken result, ListIterator<Token> iterator) {
        ClassStmtToken outerClass = analyzer.getClazz();
        FunctionStmtToken outerFunction = analyzer.getFunction(true);
        List<FunctionStmtToken> outerClosures = new ArrayList<>();
        while (analyzer.peekClosure() != null) outerClosures.add(analyzer.popClosure());
        analyzer.setFunction(null);
        analyzer.addScope(true);
        result.setAnonymous(true);
        result.setClassNameProfile(analyzer.getPhpLanguageProfile());
        result.setNamespace(analyzer.getNamespace());
        analyzer.registerAnonymousClass(result);
        analyzer.setClazz(result);
        try {
            processExtends(result, iterator);
            processImplements(result, iterator);
            processBody(result, iterator);
            analyzer.finishAnonymousClass(result);
        } finally {
            analyzer.removeScope();
            analyzer.setClazz(outerClass);
            analyzer.setFunction(outerFunction);
            for (int i=outerClosures.size()-1;i>=0;i--) analyzer.pushClosure(outerClosures.get(i));
        }
    }

    @Override
    public ClassStmtToken getToken(Token current, ListIterator<Token> iterator) {
        return getToken(current, iterator, false);
    }

    public ClassStmtToken getToken(Token current, ListIterator<Token> iterator, boolean deferred) {
        ClassStmtToken result = processDefine(current, iterator);

        if (result != null) {
            if (analyzer.getClazz() != null) {
                if (analyzer.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5)
                    throw new php.runtime.exceptions.CompileException(
                            "Class declarations may not be nested", current.toTraceInfo(analyzer.getContext()));
                unexpectedToken(current);
            }

            result.setClassNameProfile(analyzer.getPhpLanguageProfile());
            result.setNamespace(analyzer.getNamespace());
            if (deferred) {
                if (analyzer.getPhpLanguageProfile() != PhpLanguageProfile.PHP_8_5)
                    unexpectedToken(current);
                analyzer.registerDeferredClass(result);
                try (SyntaxAnalyzer.NamedClassContext ignored = analyzer.enterNamedClass(result)) {
                    processName(result, iterator);
                    processEnumBacking(result,iterator);
                    processExtends(result, iterator);
                    processImplements(result, iterator);
                    processBody(result, iterator);
                    analyzer.finishDeferredClass(result);
                }
            } else {
                analyzer.setClazz(result);
                processName(result, iterator);
                processEnumBacking(result,iterator);
                processExtends(result, iterator);
                processImplements(result, iterator);
                processBody(result, iterator);
                analyzer.setClazz(null);
            }
        }

        return result;
    }
}
