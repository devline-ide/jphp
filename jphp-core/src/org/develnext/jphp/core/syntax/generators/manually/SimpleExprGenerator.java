package org.develnext.jphp.core.syntax.generators.manually;


import org.develnext.jphp.core.common.Separator;
import org.develnext.jphp.core.compiler.common.ASMExpression;
import org.develnext.jphp.core.syntax.ExpressionInfo;
import org.develnext.jphp.core.syntax.Scope;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.ExprGenerator;
import org.develnext.jphp.core.syntax.generators.FunctionGenerator;
import org.develnext.jphp.core.syntax.generators.Generator;
import org.develnext.jphp.core.syntax.generators.LambdaGenerator;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.BreakToken;
import org.develnext.jphp.core.tokenizer.token.ColonToken;
import org.develnext.jphp.core.tokenizer.token.SemicolonToken;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.*;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.operator.cast.CastExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.cast.UnsetCastExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.macro.MacroToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.common.Callback;
import php.runtime.common.Messages;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.FatalException;
import php.runtime.exceptions.ParseException;
import php.runtime.exceptions.support.ErrorType;

import java.util.*;

import static org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken.Kind.ARRAY;
import static org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken.Kind.BLOCK;
import static org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken.Kind.SIMPLE;

public class SimpleExprGenerator extends Generator<ExprStmtToken> {

    private boolean isRef = false;
    private boolean canStartByReference = false;
    private static final Set<String> dynamicLocalFunctions = new HashSet<String>(){{
        add("extract");
        add("compact");
        add("get_defined_vars");
        add("eval");
    }};

    public SimpleExprGenerator(SyntaxAnalyzer analyzer) {
        super(analyzer);
    }

    public SimpleExprGenerator setCanStartByReference(boolean canStartByReference) {
        this.canStartByReference = canStartByReference;
        return this;
    }

    @Override
    public boolean isSingleton() {
        return false;
    }

    protected Token processLambda(Token current, ListIterator<Token> iterator, Separator separator, BraceExprToken.Kind braceKind) {
        LambdaStmtToken token = analyzer.generator(LambdaGenerator.class).getToken(current, iterator, separator, braceKind);

        ClosureStmtToken closureStmtToken = new ClosureStmtToken(current.getMeta());
        closureStmtToken.setFunction(token.getFunction());
        closureStmtToken.setOwnerClass(analyzer.getClazz());
        closureStmtToken.setParentFunction(token.getParentFunction());
        analyzer.registerClosure(closureStmtToken);

        return closureStmtToken;
    }

    protected Token processClosure(Token current, Token next, ListIterator<Token> iterator) {
        FunctionStmtToken parentFunction=analyzer.getFunction();
        FunctionStmtToken functionStmtToken = analyzer.generator(FunctionGenerator.class).getToken(
            current, iterator, true
        );

        if (functionStmtToken.getName() == null){
            //unexpectedToken(functionStmtToken.getName());

            ClosureStmtToken result = new ClosureStmtToken(current.getMeta());
            result.setFunction(functionStmtToken);
            result.setOwnerClass(analyzer.getClazz());
            result.setParentFunction(parentFunction);
            analyzer.registerClosure(result);

            return result;
        } else {
            analyzer.registerFunction(functionStmtToken);
            return functionStmtToken;
        }
    }

    public ListExprToken processSingleList(Token current, ListIterator<Token> iterator) {
        return processList(current, iterator, null, null, -1);
    }

    @SuppressWarnings("unchecked")
    protected ListExprToken processList(Token current, ListIterator<Token> iterator, List indexes,
                                        BraceExprToken.Kind closedBraceKind, int braceOpened) {

        boolean arraySyntax = isOpenedBrace(current, BraceExprToken.Kind.ARRAY);

        ListExprToken result = arraySyntax ? new ListExprToken(current.getMeta()) : (ListExprToken)current;

        Token next;

        if (!arraySyntax) {
            next = nextToken(iterator);
            if (!isOpenedBrace(next, SIMPLE)) {
                unexpectedToken(next, "(");
            }
        }

        int i = 0;
        boolean withKeys = false;
        ExprStmtToken keyExpr = null;

        while (true){
            next = nextToken(iterator);
            if (next instanceof ListExprToken || isOpenedBrace(next, ARRAY)) {
                if ((arraySyntax && next instanceof ListExprToken) || (!arraySyntax && isOpenedBrace(next, ARRAY))) {
                    analyzer.getEnvironment().error(
                            next.toTraceInfo(analyzer.getContext()),
                            ErrorType.E_ERROR,
                            Messages.ERR_CANNOT_MIX_ARRAY_AND_LIST
                    );
                }

                List indexes_ = new ArrayList();
                if (indexes != null) {
                    indexes_.addAll(indexes);
                }

                indexes_.add(keyExpr == null ? i : keyExpr);

                ListExprToken tmp = processList(next, iterator, indexes_, null, -1);
                result.addList(tmp);

                if (nextTokenAndPrev(iterator) instanceof CommaToken) {
                    iterator.next();
                }

                i++;
            } else if (isClosedBrace(next, arraySyntax ? ARRAY : SIMPLE)){
                break;
            } else if (next instanceof CommaToken){
                i++;
            } else {
                SimpleExprGenerator generator = analyzer.generator(SimpleExprGenerator.class);
                int currIndex = iterator.nextIndex() - 1;

                ExprStmtToken var = generator.getToken(next, iterator, Separator.COMMA, arraySyntax ? ARRAY :  SIMPLE);

                int k = 0;
                keyExpr = null;
                ExprStmtToken valueExpr = var;

                for (Token token : var.getTokens()) {
                    if (token instanceof KeyValueExprToken) {
                        keyExpr = new ExprStmtToken(
                                analyzer.getEnvironment(), analyzer.getContext(), var.getTokens().subList(0, k)
                        );

                        valueExpr = new ExprStmtToken(
                                analyzer.getEnvironment(),
                                analyzer.getContext(),
                                var.getTokens().subList(k + 1, var.getTokens().size())
                        );

                        if (valueExpr.getSingle() instanceof ArrayExprToken || valueExpr.getSingle() instanceof ListExprToken) {
                            if ((arraySyntax && valueExpr.getSingle() instanceof ListExprToken) || (!arraySyntax && valueExpr.getSingle() instanceof ArrayExprToken)) {
                                analyzer.getEnvironment().error(
                                        next.toTraceInfo(analyzer.getContext()),
                                        ErrorType.E_ERROR,
                                        Messages.ERR_CANNOT_MIX_ARRAY_AND_LIST
                                );
                            }

                            int doneIndex = iterator.nextIndex() - 1;

                            int prevCount = doneIndex - currIndex;

                            for (int m = 0; m < prevCount; m++) {
                                iterator.previous();
                            }

                            do {
                                current = iterator.next();
                                if (current instanceof KeyValueExprToken) {
                                    current = iterator.next();
                                    break;
                                }
                            } while (iterator.hasNext());

                            List indexes_ = new ArrayList();
                            if (indexes != null) {
                                indexes_.addAll(indexes);
                            }

                            indexes_.add(keyExpr);


                            ListExprToken listExprToken = processList(
                                    current, iterator, indexes_, null, -1
                            );

                            result.addList(listExprToken);

                            if (nextTokenAndPrev(iterator) instanceof CommaToken) {
                                iterator.next();
                            }
                        }

                        break;
                    }

                    k++;
                }

                Token single = valueExpr.getLast();

                if (single instanceof ArrayExprToken || single instanceof ListExprToken) {
                    i++;
                    continue;
                }

                if (!(single instanceof VariableExprToken
                        || single instanceof ArrayGetExprToken
                        || single instanceof DynamicAccessExprToken
                        || single instanceof ArrayPushExprToken
                        || (single instanceof StaticAccessExprToken && ((StaticAccessExprToken) single).isGetStaticField()))){
                    unexpectedToken(single);
                }

                if (single instanceof ArrayGetExprToken){
                    single = new ArrayGetRefExprToken((ArrayGetExprToken)single);
                    valueExpr.getTokens().set(var.getTokens().size() - 1, single);
                    valueExpr.updateAsmExpr(analyzer.getEnvironment(), analyzer.getContext());
                }

                if (keyExpr == null) {
                    if (withKeys) {
                        analyzer.getEnvironment().error(
                                next.toTraceInfo(analyzer.getContext()),
                                ErrorType.E_ERROR,
                                Messages.ERR_CANNOT_MIX_KEYED_AND_UNKEYED_ARRAY_ENTRIES
                        );
                    }

                    result.addVariable(valueExpr, i, indexes);
                } else {
                    if (!withKeys && i > 0) {
                        analyzer.getEnvironment().error(
                                next.toTraceInfo(analyzer.getContext()),
                                ErrorType.E_ERROR,
                                Messages.ERR_CANNOT_MIX_KEYED_AND_UNKEYED_ARRAY_ENTRIES
                        );
                    }

                    withKeys = true;
                    result.addVariable(valueExpr, keyExpr, indexes);
                }

                cont:
                i++;
            }
        }

        if (braceOpened != -1){
            next = nextToken(iterator);
            if (!(next instanceof AssignExprToken)) {
                iterator.previous();
                return result;
                //unexpectedToken(next, "=");
            }

            ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                    nextToken(iterator), iterator, BraceExprToken.Kind.ANY
            );
            result.setValue(value);
        }

        return result;
    }

    protected DieExprToken processDie(Token current, Token next, ListIterator<Token> iterator){
        DieExprToken die = (DieExprToken)current;
        if (isOpenedBrace(next, SIMPLE)){
            die.setValue(
                    analyzer.generator(ExprGenerator.class).getInBraces(SIMPLE, iterator)
            );
        }

        return die;
    }

    protected EmptyExprToken processEmpty(Token current, ListIterator<Token> iterator){
        ExprStmtToken value = analyzer.generator(ExprGenerator.class).getInBraces(SIMPLE, iterator);
        if (value == null)
            unexpectedToken(iterator.previous());

        assert value != null;

        Token last = value.getLast();
        if (last instanceof DynamicAccessExprToken){
            last = new DynamicAccessEmptyExprToken((DynamicAccessExprToken)last);
            value.getTokens().set(value.getTokens().size() - 1, last);
        } else if (last instanceof VariableExprToken || last instanceof GetVarExprToken){
            // nop
        } else if (last instanceof StaticAccessExprToken && ((StaticAccessExprToken) last).isGetStaticField()){
            last = new StaticAccessIssetExprToken((StaticAccessExprToken)last);
            value.getTokens().set(value.getTokens().size() - 1, last);
        } else if (last instanceof ArrayGetExprToken){
            ArrayGetEmptyExprToken el = new ArrayGetEmptyExprToken(last.getMeta());
            el.setParameters(((ArrayGetExprToken) last).getParameters());
            value.getTokens().set(value.getTokens().size() - 1, el);
        } else {
            // is only in < php 5.5 as unexcpected.
            //unexpectedToken(last);
        }

        EmptyExprToken result = (EmptyExprToken)current;
        if (analyzer.getPhpLanguageProfile()
                == php.runtime.common.PhpLanguageProfile.PHP_8_5) markCoalescingPropertyReads(value.getTokens());
        value.updateAsmExpr(analyzer.getEnvironment(), analyzer.getContext());
        result.setValue(value);
        return result;
    }

    protected IssetExprToken processIsset(Token previous, Token current, ListIterator<Token> iterator){
        Token next = nextTokenAndPrev(iterator);
        if (!isOpenedBrace(next, SIMPLE))
            unexpectedToken(next, "(");

        CallExprToken call = processCall(current, nextToken(iterator), iterator);

        for(ExprStmtToken param : call.getParameters()){
            List<Token> tokens = param.getTokens();
            Token last = tokens.get(tokens.size() - 1);
            Token newToken = null;

            if (last instanceof DynamicAccessExprToken){
                newToken = new DynamicAccessIssetExprToken((DynamicAccessExprToken)last);
                if (analyzer.getClazz() != null && !"__isset".equals(analyzer.getFunction().getFulledName())){
                    ((DynamicAccessIssetExprToken)newToken).setWithMagic(false);
                }
            } else if (last instanceof VariableExprToken || last instanceof GetVarExprToken){
                // nop
            } else if (last instanceof StaticAccessExprToken && ((StaticAccessExprToken) last).isGetStaticField()){
                newToken = new StaticAccessIssetExprToken((StaticAccessExprToken)last);
            } else if (last instanceof StaticAccessOperatorExprToken
                    && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && (((StaticAccessOperatorExprToken) last).getOrigin().isGetStaticField()
                    || ((StaticAccessOperatorExprToken) last).getOrigin().getFieldExpr() != null
                    && !((StaticAccessOperatorExprToken) last).getOrigin().isDynamicConstant())) {
                newToken = new StaticAccessOperatorExprToken(new StaticAccessIssetExprToken(
                        ((StaticAccessOperatorExprToken) last).getOrigin()));
            } else if (last instanceof ArrayGetExprToken){
                ArrayGetIssetExprToken el = new ArrayGetIssetExprToken(last.getMeta());
                el.setParameters(((ArrayGetExprToken) last).getParameters());
                newToken = el;
            } else
                unexpectedToken(param.getSingle());

            if (newToken != null) {
                tokens.set(tokens.size() - 1, newToken);
                if (analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5) markCoalescingPropertyReads(tokens);
                param.updateAsmExpr(analyzer.getEnvironment(), analyzer.getContext());
            }
        }

        IssetExprToken result = (IssetExprToken)current;
        result.setParameters(call.getParameters());
        return result;
    }

    protected UnsetExprToken processUnset(Token previous, Token current, ListIterator<Token> iterator){
        Token next = nextTokenAndPrev(iterator);
        if (!isOpenedBrace(next, SIMPLE))
            unexpectedToken(next, "(");

        CallExprToken call = processCall(current, nextToken(iterator), iterator);

        for(ExprStmtToken param : call.getParameters()){
            if (analyzer.getEnvironment()!=null && org.develnext.jphp.core.compiler.common.NullsafeChain.isResult(param))
                analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                        "Can't use nullsafe operator in write context");
            List<Token> tokens = param.getTokens();
            Token last = tokens.get(tokens.size() - 1);
            Token newToken = null;

            if (param.getSingle() instanceof StaticAccessExprToken && ((StaticAccessExprToken) param.getSingle()).isGetStaticField()) {
                // allow class::$var
            } else if (!(param.getSingle() instanceof VariableValueExprToken))
                unexpectedToken(param);

            if (last instanceof VariableExprToken || last instanceof GetVarExprToken){
                newToken = last;
                // nop
            } else if (last instanceof ArrayGetExprToken){
                ArrayGetUnsetExprToken el = new ArrayGetUnsetExprToken(last.getMeta());
                el.setParameters(((ArrayGetExprToken) last).getParameters());
                newToken = el;
            } else if (last instanceof DynamicAccessExprToken){
                newToken = new DynamicAccessUnsetExprToken((DynamicAccessExprToken)last);
            } else if (last instanceof StaticAccessExprToken){
                newToken = new StaticAccessUnsetExprToken((StaticAccessExprToken)last);
            } else
                unexpectedToken(last);

            tokens.set(tokens.size() - 1, newToken);
            if (last instanceof ArrayGetExprToken && analyzer.getPhpLanguageProfile()
                    == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                markCoalescingPropertyReads(tokens);
                // Unset borrows an array container, but object property containers remain value reads.
                for(int i=tokens.size()-2;i>=0;i--) {
                    Token container=tokens.get(i);
                    if(container instanceof ArrayGetExprToken)continue;
                    if(container instanceof DynamicAccessExprToken property)property.setArrayUnsetRead(true);
                    break;
                }
            }

            param.updateAsmExpr(analyzer.getEnvironment(), analyzer.getContext());
        }

        UnsetExprToken result = (UnsetExprToken)current;
        result.setParameters(call.getParameters());
        return result;
    }

    private void markArgumentArrayRoot(ExprStmtToken argument) {
        if (argument.getAsmExpr() == null || analyzer.getFunction() == null || analyzer.getPhpLanguageProfile()
                != php.runtime.common.PhpLanguageProfile.PHP_8_5) return;
        List<Token> tokens = argument.getAsmExpr().getTokens();
        if (tokens.size() < 2 || !(tokens.getFirst() instanceof VariableExprToken)
                || !(tokens.get(1).getClass() == ArrayGetExprToken.class || tokens.get(1).getClass() == ArrayPushExprToken.class)) return;
        for (int i = 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getClass() != ArrayGetExprToken.class && token.getClass() != ArrayPushExprToken.class
                    && token.getClass() != DynamicAccessExprToken.class) return;
        }
        // The runtime callee chooses FETCH_DIM_R/W. Reserve a writable root before
        // JVM local declarations, including when the call lives in a skipped branch.
        analyzer.getFunction().variable((VariableExprToken) tokens.getFirst()).setReference(true).setUnstable(true).setMutable(true);
    }

    public List<CallArgument> processPhp85Arguments(ListIterator<Token> iterator) {
        List<CallArgument> arguments = new ArrayList<>();
        boolean named = false, unpacked = false;
        while (true) {
            Token first = nextToken(iterator);
            if (isClosedBrace(first, SIMPLE)) break;
            String name = null;
            CallArgument.Kind kind = first instanceof ArgumentUnpackExprToken
                    ? CallArgument.Kind.UNPACK : CallArgument.Kind.POSITIONAL;
            if (nextTokenAndPrev(iterator) instanceof ColonToken
                    && first.getWord().matches("[a-zA-Z_\\x{0080}-\\x{FFFF}][a-zA-Z0-9_\\x{0080}-\\x{FFFF}]*")) {
                name = first.getWord();
                nextToken(iterator);
                kind = CallArgument.Kind.NAMED;
            }
            if (kind == CallArgument.Kind.UNPACK) {
                Token after = nextTokenAndPrev(iterator);
                if ((isClosedBrace(after, SIMPLE) || after instanceof CommaToken) && analyzer.getEnvironment() != null)
                    analyzer.getEnvironment().error(after.toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                            "syntax error, unexpected token \"%s\"", after.getWord());
            }
            if (kind == CallArgument.Kind.UNPACK && named)
                analyzer.getEnvironment().error(first.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                        "Cannot use argument unpacking after named arguments");
            if (kind == CallArgument.Kind.POSITIONAL && (named || unpacked))
                analyzer.getEnvironment().error(first.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                        named ? "Cannot use positional argument after named argument" : "Cannot use positional argument after argument unpacking");
            Token expressionStart = name == null ? first : nextToken(iterator);
            if (name != null && expressionStart instanceof ArgumentUnpackExprToken)
                analyzer.getEnvironment().error(expressionStart.toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                        "syntax error, unexpected token \"...\"");
            ExprStmtToken expression = analyzer.generator(SimpleExprGenerator.class)
                    .getNextExpression(expressionStart, iterator, Separator.COMMA, SIMPLE);
            if (expression == null) unexpectedToken(expressionStart);
            arguments.add(new CallArgument(expression, name, kind, first.getMeta()));
            markArgumentArrayRoot(expression);
            if (expression.isSingle() && expression.getSingle() instanceof VariableExprToken && analyzer.getFunction() != null)
                analyzer.getFunction().variable((VariableExprToken) expression.getSingle()).setPassed(true);
            named |= kind == CallArgument.Kind.NAMED;
            unpacked |= kind == CallArgument.Kind.UNPACK;
            Token separator = nextToken(iterator);
            if (isClosedBrace(separator, SIMPLE)) break;
            if (!(separator instanceof CommaToken)) unexpectedToken(separator);
        }
        return arguments;
    }

    protected CallExprToken processCall(Token previous, Token current, ListIterator<Token> iterator){
        ExprStmtToken param;

        List<ExprStmtToken> parameters = new ArrayList<>();
        boolean acquisition=false;
        if (analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && nextTokenAndPrev(iterator) instanceof ArgumentUnpackExprToken) {
            nextToken(iterator);
            if (isClosedBrace(nextTokenAndPrev(iterator), SIMPLE)) {
                nextToken(iterator);acquisition=true;
            } else {
                Token after = nextTokenAndPrev(iterator);
                if (after instanceof CommaToken && analyzer.getEnvironment() != null)
                    analyzer.getEnvironment().error(after.toTraceInfo(analyzer.getContext()), php.runtime.exceptions.support.ErrorType.E_PARSE,
                            "syntax error, unexpected token \",\"");
                iterator.previous();
            }
        }
        List<CallArgument> arguments = null;
        if (!acquisition && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            arguments = processPhp85Arguments(iterator);
        else if (!acquisition) do {
            param = analyzer.generator(SimpleExprGenerator.class)
                    .getNextExpression(nextToken(iterator), iterator, Separator.COMMA, SIMPLE);

            if (param != null) {
                parameters.add(param);
                markArgumentArrayRoot(param);
                if (param.isSingle()){
                    if (param.getTokens().get(0) instanceof VariableExprToken) {
                        if (analyzer.getFunction() != null)
                            analyzer.getFunction().variable((VariableExprToken)param.getTokens().get(0)).setPassed(true);
                    }
                }
            }

            Token separator = nextToken(iterator);
            if (isClosedBrace(separator, SIMPLE)) {
                break;
            }

            if (param != null && separator instanceof CommaToken
                    && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && isClosedBrace(nextTokenAndPrev(iterator), SIMPLE)) {
                nextToken(iterator);
                break;
            }

        } while (param != null);
        //nextToken(iterator);

        CallExprToken result = new CallExprToken(TokenMeta.of(previous, current));
        if (previous instanceof CallExprToken && analyzer.getPhpLanguageProfile()
                == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            result.setName(null); // Consume the evaluated result, including an object-method receiver spine.
        } else if (previous instanceof ValueExprToken) {
            result.setName(analyzer.getRealName((ValueExprToken)previous, NamespaceUseStmtToken.UseType.FUNCTION));

            if (analyzer.getFunction() != null){
                if (result.getName() instanceof NameToken) {
                    String name = ((NameToken) result.getName()).getName().toLowerCase();
                    if (result.getName() instanceof FulledNameToken) {
                        name = ((FulledNameToken) result.getName()).getLastName().getName().toLowerCase();
                    }

                    if (dynamicLocalFunctions.contains(name.toLowerCase())) {
                        analyzer.getFunction().setDynamicLocal(true);
                    }

                    if ("get_called_class".equalsIgnoreCase(name)) {
                        analyzer.getScope().setStaticExists(true);
                    }
                }
            }
        } else {
            if (previous instanceof StaticAccessOperatorExprToken
                    && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                result.setName((ExprToken) previous);
            } else if (previous instanceof DynamicAccessExprToken) {
                result.setName((ExprToken)previous);
            } else
                result.setName(null);
        }

        if (arguments == null) result.setParameters(parameters);
        else result.setArguments(arguments);
        result.setFirstClassCallable(acquisition);
        if(previous instanceof ParentPropertyHookExprToken && acquisition)
            analyzer.getEnvironment().error(previous.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Cannot create Closure for parent property hook call");

        if (analyzer.getFunction() != null){
            analyzer.getFunction().setCallsExist(true);
        }

        return result;
    }

    protected Token processYield(Token current, Token next, ListIterator<Token> iterator, BraceExprToken.Kind closedBrace) {
        if (analyzer.getFunction() == null) {
            analyzer.getEnvironment().error(
                    current.toTraceInfo(analyzer.getContext()), Messages.ERR_YIELD_CAN_ONLY_INSIDE_FUNCTION.fetch()
            );
        }

        analyzer.getFunction().setGenerator(true);

        YieldExprToken result = (YieldExprToken) current;

        // if yield from <iterable>
        if (next instanceof FromExprToken) {
            nextToken(iterator);
            result.setDelegating(true);
        }

        if (next instanceof OperatorExprToken && ((OperatorExprToken) next).isBinary()) {
            result.setValue(null);
        } else {
            ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                    nextToken(iterator), iterator, BraceExprToken.Kind.ANY
            );
            result.setValue(value);
            // A simple variable yielded by reference escapes to the iterator consumer.
            // Reserve its writable cell before JVM locals and constant caches are chosen.
            if (!result.isDelegating() && analyzer.getFunction().isReturnReference() && value != null
                    && value.getTokens().size() == 1 && value.getTokens().getFirst() instanceof VariableExprToken variable)
                analyzer.getFunction().variable(variable).setReference(true).setUnstable(true).setMutable(true);
            else if(!result.isDelegating() && analyzer.getFunction().isReturnReference() && value!=null
                    && analyzer.getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                List<Token> path=new ArrayList<>(value.getTokens());int baseEnd=path.size();
                while(baseEnd>0 && (path.get(baseEnd-1) instanceof ArrayGetExprToken
                        || path.get(baseEnd-1) instanceof DynamicAccessExprToken))baseEnd--;
                if(baseEnd==1 && path.getFirst() instanceof VariableExprToken root) {
                    analyzer.getFunction().variable(root).setReference(true).setUnstable(true).setMutable(true);
                    for(int i=baseEnd;i<path.size();i++) {
                        Token step=path.get(i);
                        if(step.getClass()==ArrayGetExprToken.class) {
                            var reference=new ArrayGetRefExprToken((ArrayGetExprToken)step);reference.setShortcut(true);path.set(i,reference);
                        } else if(step.getClass()==DynamicAccessExprToken.class)
                            path.set(i,new DynamicAccessGetRefExprToken((DynamicAccessExprToken)step));
                    }
                    result.setValue(new ExprStmtToken(analyzer.getEnvironment(),analyzer.getContext(),path));
                }
            }
        }

        if(!result.isDelegating() && analyzer.getFunction().isReturnReference())
            result.setValue(org.develnext.jphp.core.syntax.generators.PhpSetVisibility.reference(analyzer,result.getValue()));
        return result;
    }

    protected ImportExprToken processImport(Token current, Token next, ListIterator<Token> iterator,
                                              BraceExprToken.Kind closedBrace, int braceOpened){
        ImportExprToken result = (ImportExprToken)current;
        ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                nextToken(iterator), iterator, BraceExprToken.Kind.ANY
        );
        result.setValue(value);

        FunctionStmtToken function = analyzer.getFunction();
        if (function != null) {
            function.setDynamicLocal(true);
            function.setCallsExist(true);
            function.setThisExists(true);
        }

        return result;
    }

    protected CallExprToken processPrint(Token current, Token next, ListIterator<Token> iterator,
                                         BraceExprToken.Kind closedBrace, int braceOpened){
        CallExprToken callExprToken = new CallExprToken(current.getMeta());
        callExprToken.setName((ExprToken) current);

        ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                nextToken(iterator), iterator, BraceExprToken.Kind.ANY
        );

        if (value == null)
            unexpectedToken(iterator.previous());

        callExprToken.setParameters(Arrays.asList(value));
        return callExprToken;
    }

    private void validateRelativeClass(Token token) {
        if (analyzer.getPhpLanguageProfile()
                != php.runtime.common.PhpLanguageProfile.PHP_8_5) return;
        String keyword = token instanceof ParentExprToken ? "parent" : token instanceof SelfExprToken ? "self"
                : token instanceof StaticExprToken ? "static" : null;
        FunctionStmtToken function = analyzer.getFunction();
        // Anonymous functions acquire their scope when bound; top-level expressions are runtime checks.
        if (keyword == null || function == null || function.getName() == null) return;
        ClassStmtToken scope = analyzer.getClazz();
        if (scope == null) analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()),
                ErrorType.E_COMPILE_ERROR, "Cannot use \"%s\" when no class scope is active", keyword);
        else if (token instanceof ParentExprToken && !scope.isTrait() && scope.getExtend() == null)
            analyzer.getEnvironment().error(token.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                    "Cannot use \"parent\" when current class scope has no parent");
    }

    protected Token processStaticAccess(Token current, Token previous, ListIterator<Token> iterator) {
        Token name = previous;
        validateRelativeClass(name);

        if (name != null && !isTokenClass(name, SelfExprToken.class, StaticExprToken.class, ParentExprToken.class)) {
            name = makeSensitive(previous);
        }

        if (name == null || name instanceof NameToken || name instanceof VariableExprToken
                || name instanceof SelfExprToken || name instanceof StaticExprToken
                || name instanceof ParentExprToken) {
            if (name instanceof StaticExprToken) {
                analyzer.getScope().setStaticExists(true);
            }

            StaticAccessExprToken result = (StaticAccessExprToken) current;
            ValueExprToken clazz = (ValueExprToken) name;

            if (clazz instanceof NameToken){
                clazz = analyzer.getRealName((NameToken)clazz);
            } else if (clazz instanceof SelfExprToken){
                if (analyzer.getClazz() == null) {
                    ;
                } else {
                    if (!analyzer.getClazz().isTrait() && analyzer.getPhpLanguageProfile() != php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                        clazz = new FulledNameToken(clazz.getMeta(), new ArrayList<Token>() {{
                            if (analyzer.getClazz().getNamespace().getName() != null)
                                addAll(analyzer.getClazz().getNamespace().getName().getNames());
                            add(analyzer.getClazz().getName());
                        }});
                    }
                }
            }

            result.setClazz(clazz);

            if (name != null) {
                nextToken(iterator);
            }

            current = nextToken(iterator);

            if (!isTokenClass(current, ClassStmtToken.class)) {
                current = makeSensitive(current);
            }

            if (isOpenedBrace(current, BLOCK)){
                ExprStmtToken expr = getToken(nextToken(iterator), iterator, false, BLOCK);
                result.setFieldExpr(expr);
                result.setDynamicConstant(analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5);
                nextAndExpected(iterator, BraceExprToken.class);
            } else if (current instanceof NameToken || current instanceof VariableExprToken){
                result.setField((ValueExprToken)current);
                if(clazz instanceof ParentExprToken && current instanceof VariableExprToken
                        && analyzer.getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5
                        && nextTokenAndPrev(iterator) instanceof StaticAccessExprToken) {
                    int parentLookahead=iterator.nextIndex();
                    nextToken(iterator);
                    Token operation=makeSensitive(nextToken(iterator));
                    if(operation instanceof NameToken) {
                        String hook=((NameToken)operation).getName().toLowerCase(Locale.ROOT);
                        if(hook.equals("get") || hook.equals("set")) {
                            String property=((VariableExprToken)current).getName();
                            FunctionStmtToken active=analyzer.getFunction();
                            MethodStmtToken method=active instanceof MethodStmtToken?(MethodStmtToken)active:null;
                            if(method==null || method.getPropertyHookName()==null)
                                analyzer.getEnvironment().error(operation.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                        "Must not use parent::$%s::%s() outside a property hook",property,hook);
                            else if(!property.equals(method.getPropertyHookName()))
                                analyzer.getEnvironment().error(operation.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                        "Must not use parent::$%s::%s() in a different property ($%s)",property,hook,method.getPropertyHookName());
                            else if(!method.getName().getName().endsWith("::"+hook))
                                analyzer.getEnvironment().error(operation.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                        "Must not use parent::$%s::%s() in a different property hook (%s)",property,hook,
                                        method.getName().getName().endsWith("::get")?"get":"set");
                            if(!isOpenedBrace(nextTokenAndPrev(iterator),SIMPLE))unexpectedToken(nextTokenAndPrev(iterator),"(");
                            return new ParentPropertyHookExprToken(result,hook.equals("get")?1:2);
                        }
                    }
                    while(iterator.nextIndex()>parentLookahead)iterator.previous();
                }
                if (current instanceof VariableExprToken && analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    if (analyzer.getScope() != null) analyzer.getScope().addVariable((VariableExprToken) current);
                    if (analyzer.getFunction() != null) analyzer.getFunction().variable((VariableExprToken) current).setUsed(true);
                }
            } else if (current instanceof DollarExprToken) {
                Token nm = nextToken(iterator);
                if (nm instanceof VariableExprToken) {
                    result.setFieldExpr(new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), nm));
                } else if (nm instanceof DollarExprToken) {
                    result.setFieldExpr(new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), processVarVar(nm, nextTokenAndPrev(iterator), iterator)));
                } else if (isOpenedBrace(nm, BLOCK)) {
                    iterator.previous();
                    result.setFieldExpr(
                            analyzer.generator(ExprGenerator.class).getInBraces(BLOCK, iterator)
                    );
                } else
                    unexpectedToken(current);
            } else if (current instanceof ClassStmtToken) { // PHP 5.5 ::class
                if (clazz instanceof ParentExprToken || clazz instanceof StaticExprToken
                        || clazz instanceof SelfExprToken && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    if (clazz instanceof StaticExprToken) {
                        analyzer.getScope().setStaticExists(true);
                    }

                    result.setField(new ClassExprToken(current.getMeta()));
                } else if (clazz instanceof NameToken) {
                    return new StringExprToken(
                            TokenMeta.of(((NameToken) clazz).getName(), clazz),
                            StringExprToken.Quote.SINGLE
                    );
                } else if (analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5 && (clazz == null || clazz instanceof VariableExprToken)) {
                    result.setField(new ClassExprToken(current.getMeta()));
                } else
                    unexpectedToken(current);
            } else
                unexpectedToken(current);

            if (name == null) {
                return new StaticAccessOperatorExprToken(result);
            }

            return result;
        } else
            unexpectedToken(name);

        return null;
    }

    protected DynamicAccessExprToken processDynamicAccess(Token current, Token next, ListIterator<Token> iterator,
            BraceExprToken.Kind closedBraceKind, int braceOpened){
        if (next != null) next = makeSensitive(next);

        DynamicAccessExprToken result = (DynamicAccessExprToken)current;
        if (result.isNullsafe() && analyzer.getPhpLanguageProfile() != php.runtime.common.PhpLanguageProfile.PHP_8_5)
            unexpectedToken(current);
        if (next instanceof NameToken || next instanceof VariableExprToken){
            result.setField((ValueExprToken) next);
            if (next instanceof VariableExprToken && analyzer.getPhpLanguageProfile()
                    == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                if (analyzer.getScope() != null) analyzer.getScope().addVariable((VariableExprToken) next);
                if (analyzer.getFunction() != null) analyzer.getFunction().variable((VariableExprToken) next).setUsed(true);
            }
            next = iterator.next();
            if (result.getField() instanceof VariableExprToken && analyzer.getPhpLanguageProfile()
                    == php.runtime.common.PhpLanguageProfile.JPHP_LEGACY) {
                if (isOpenedBrace(nextTokenAndPrev(iterator), ARRAY)){
                    Token arr = processArrayToken(next, nextToken(iterator), iterator);
                    result.setFieldExpr(new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), result.getField(), arr));
                    result.setField(null);
                }
            }
        } else if (isOpenedBrace(next, BLOCK)){
            ExprStmtToken name = analyzer.generator(ExprGenerator.class).getInBraces(
                    BLOCK, iterator
            );
            result.setFieldExpr(name);
        }

        if (iterator.hasNext()){
            next = iterator.next();
            if (next instanceof AssignableOperatorToken && !(next instanceof AssignNullCoalesceExprToken)){
                DynamicAccessAssignExprToken dResult = new DynamicAccessAssignExprToken(result);
                dResult.setAssignOperator(next);

                ExprStmtToken value = analyzer.generator(SimpleExprGenerator.class)
                        .setCanStartByReference(true)
                        .getNextExpression(nextToken(iterator), iterator, BraceExprToken.Kind.ANY);

                dResult.setValue(value);
                if (analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5
                        && next instanceof AssignExprToken && value != null && !value.getTokens().isEmpty()
                        && value.getTokens().getFirst() instanceof AmpersandRefToken)
                    ((AssignExprToken) next).setAsReference(true);
                return dResult;
            }
            iterator.previous();
        }

        return result;
    }

    public GetVarExprToken processVarVar(Token current, Token next, ListIterator<Token> iterator){
        ExprStmtToken name = null;
        if (next instanceof VariableExprToken){ // $$var
            name = new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), next);
            nextToken(iterator);
        } else if (next instanceof DollarExprToken){ // $$$var
            current = nextToken(iterator);
            next    = nextToken(iterator);
            name    = new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), processVarVar(current, next, iterator));
            iterator.previous();
        } else if (isOpenedBrace(next, BLOCK)){ // ${var}
            name = analyzer.generator(ExprGenerator.class).getInBraces(
                    BLOCK, iterator
            );
        } else if (next == null) {
            unexpectedEnd(current);
        }

        if (name == null) {
            unexpectedToken(next);
        }

        if (analyzer.getFunction() != null){
            analyzer.getFunction().setDynamicLocal(true);
            analyzer.getFunction().setVarsExists(true);
        }

        GetVarExprToken result = new GetVarExprToken(TokenMeta.of(current, name));
        result.setName(name);
        return result;
    }

    private Token processNullCoalesceAssign(Token operator, List<Token> tokens, ListIterator<Token> iterator,
                                           Separator separator) {
        if (analyzer.getPhpLanguageProfile()
                != php.runtime.common.PhpLanguageProfile.PHP_8_5)
            throw new ParseException("Null coalescing assignment requires PHP_8_5", operator.toTraceInfo(analyzer.getContext()));
        int start = 0, depth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (isClosedBrace(token, SIMPLE)) depth++;
            else if (isOpenedBrace(token, SIMPLE)) {
                if (depth == 0) { start = i + 1; break; }
                depth--;
            } else if (depth == 0 && token instanceof OperatorExprToken
                    && !(token instanceof ArrayGetExprToken) && !(token instanceof DynamicAccessExprToken)
                    && !(token instanceof IncExprToken) && !(token instanceof DecExprToken)) {
                start = i + 1; break;
            }
        }
        if (start == tokens.size()) unexpectedToken(operator);
        List<Token> targetTokens = new ArrayList<>(tokens.subList(start, tokens.size()));
        validateConstantWrite(targetTokens, operator);
        ExprStmtToken target = new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), targetTokens);
        if (targetTokens.get(0) instanceof VariableExprToken && analyzer.getFunction() != null) {
            analyzer.getFunction().variable((VariableExprToken) targetTokens.get(0)).setReference(true).setUnstable(true);
        }
        // Bound the token stream as well as this parser: recursive logical/ternary
        // parsers must not consume the lower-precedence expression after the RHS.
        List<Token> rightTokens = new ArrayList<>();
        int nesting = 0, ternary = 0;
        while (iterator.hasNext()) {
            Token token = iterator.next();
            boolean boundary = nesting == 0 && (isBreak(token) || token instanceof SemicolonToken
                    || separator != null && separator.is(token) && !(token instanceof ColonToken && ternary > 0)
                    || token instanceof BraceExprToken && ((BraceExprToken) token).isClosed()
                    || token instanceof OperatorExprToken && ((OperatorExprToken) token).getPriority() > 150);
            if (boundary) { iterator.previous(); break; }
            if (token instanceof BraceExprToken) nesting += ((BraceExprToken) token).isOpened() ? 1 : -1;
            if (nesting == 0 && token.getClass() == ValueIfElseToken.class) ternary++;
            if (nesting == 0 && token instanceof ColonToken && ternary > 0) ternary--;
            rightTokens.add(token);
        }
        if (rightTokens.isEmpty()) unexpectedToken(operator);
        rightTokens.add(new SemicolonToken(TokenMeta.of(";", operator)));
        ListIterator<Token> rightIterator = rightTokens.listIterator();
        ExprStmtToken value = getToken(rightIterator.next(), rightIterator, Separator.SEMICOLON, null);
        if (value == null) unexpectedToken(operator);
        tokens.subList(start, tokens.size()).clear();
        return new NullCoalesceAssignExprToken(TokenMeta.of(operator, value), target, value);
    }

    private void validateConstantWrite(List<Token> tokens, Token at) {
        ExprStmtToken target=new ExprStmtToken(analyzer.getEnvironment(),analyzer.getContext(),tokens);
        if (analyzer.getEnvironment()!=null && org.develnext.jphp.core.compiler.common.NullsafeChain.isResult(target))
            analyzer.getEnvironment().error(at.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Can't use nullsafe operator in write context");
        if (analyzer.getPhpLanguageProfile() != php.runtime.common.PhpLanguageProfile.PHP_8_5) return;
        for (Token token : tokens) if (token instanceof DynamicAccessExprToken) return;
        for (Token token : tokens) {
            StaticAccessExprToken access = token instanceof StaticAccessExprToken ? (StaticAccessExprToken) token
                    : token instanceof StaticAccessOperatorExprToken ? ((StaticAccessOperatorExprToken) token).getOrigin() : null;
            if (access != null && (access.isDynamicConstant() || access.getField() instanceof NameToken || access.getField() instanceof ClassExprToken))
                analyzer.getEnvironment().error(at.toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR,
                        "Cannot use temporary expression in write context");
        }
    }

    private static StaticAccessExprToken staticAccess(Token token) {
        return token instanceof StaticAccessExprToken ? (StaticAccessExprToken)token
                : token instanceof StaticAccessOperatorExprToken ? ((StaticAccessOperatorExprToken)token).getOrigin() : null;
    }

    private void markReferencePropertyReads(List<Token> tokens) {
        for (int i=0;i<tokens.size();i++) {
            StaticAccessExprToken access=staticAccess(tokens.get(i));
            Token next=i+1<tokens.size()?tokens.get(i+1):null;
            Token previous=i>0?tokens.get(i-1):null;
            if (access != null && (next instanceof AssignExprToken || next instanceof ArrayGetRefExprToken
                    || next instanceof ArrayPushExprToken || next instanceof AssignOperatorExprToken
                    || next instanceof IncExprToken || next instanceof DecExprToken
                    || (previous instanceof IncExprToken || previous instanceof DecExprToken)
                    && !(next instanceof DynamicAccessExprToken) && !(next instanceof ArrayGetExprToken)
                    && !(next instanceof ArrayPushExprToken) && !(next instanceof CallOperatorToken))) {
                access.setWriteAccess(true);
                access.setDirectAssignment(next!=null && next.getClass()==AssignExprToken.class
                        && !((AssignExprToken)next).isAsReference());
            }
            if (next instanceof DynamicAccessAssignExprToken && tokens.get(i).getClass() == DynamicAccessExprToken.class) {
                if (analyzer.getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5)
                    ((DynamicAccessExprToken)tokens.get(i)).setObjectWriteReceiverRead(true);
                else tokens.set(i,new DynamicAccessGetRefExprToken((DynamicAccessExprToken)tokens.get(i)));
            }
        }
        for (int i=0; i<tokens.size(); i++) {
            if (!(tokens.get(i) instanceof AmpersandRefToken)) continue;
            int lastAccess=-1;
            for (int j=i+1; j<tokens.size(); j++) {
                Token token=tokens.get(j);
                if (token instanceof DynamicAccessExprToken || token instanceof ArrayGetExprToken
                        || staticAccess(token) != null || token instanceof CallOperatorToken) lastAccess=j;
                else if (token instanceof OperatorExprToken) break;
            }
            if (lastAccess >= 0 && tokens.get(lastAccess).getClass() == DynamicAccessExprToken.class)
                tokens.set(lastAccess,new DynamicAccessGetRefExprToken((DynamicAccessExprToken)tokens.get(lastAccess)));
            if (lastAccess >= 0 && staticAccess(tokens.get(lastAccess)) != null)
                staticAccess(tokens.get(lastAccess)).setWriteAccess(true);
        }
    }

    private void markCoalescingPropertyReads(List<Token> tokens) {
        int start = 0, depth = 0;
        for (int i = tokens.size()-1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (isClosedBrace(token, SIMPLE)) depth++;
            else if (isOpenedBrace(token, SIMPLE)) {
                if (depth == 0) {start = i+1; break;}
                depth--;
            } else if (token instanceof OperatorExprToken && !(token instanceof DynamicAccessExprToken)
                    && !(token instanceof ArrayGetExprToken) && !(token instanceof StaticAccessOperatorExprToken)
                    && !(token instanceof CallOperatorToken)) {
                // Arithmetic/call arguments still perform ordinary reads. Only a direct access spine is quiet.
                if (depth != 0 || ((OperatorExprToken) token).getPriority() < 140) return;
                start = i+1; break;
            }
        }
        for (int i=start; i<tokens.size(); i++) {
            Token token=tokens.get(i);
            if (token instanceof DynamicAccessExprToken) ((DynamicAccessExprToken)token).setCoalescingRead(true);
            if (staticAccess(token) != null) staticAccess(token).setQuietRead(true);
        }
    }

    private void deferArrayAssignment(List<Token> tokens) {
        if (tokens.isEmpty() || !(tokens.get(tokens.size() - 1) instanceof ArrayGetExprToken
                || tokens.get(tokens.size() - 1) instanceof ArrayPushExprToken)) return;
        int start = 0, depth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (isClosedBrace(token, SIMPLE)) depth++;
            else if (isOpenedBrace(token, SIMPLE)) {
                if (depth == 0) { start = i + 1; break; }
                depth--;
            } else if (depth == 0 && token instanceof OperatorExprToken
                    && !(token instanceof ArrayGetExprToken) && !(token instanceof ArrayPushExprToken)
                    && !(token instanceof DynamicAccessExprToken) && !(token instanceof CallOperatorToken)) {
                start = i + 1; break;
            }
        }
        List<Token> targetTokens = new ArrayList<>(tokens.subList(start, tokens.size()));
        validateConstantWrite(targetTokens, tokens.get(tokens.size() - 1));
        if (analyzer.getFunction() != null) {
            int baseStart = 0, baseEnd = targetTokens.size();
            while (baseEnd > 0 && (targetTokens.get(baseEnd - 1) instanceof ArrayGetExprToken
                    || targetTokens.get(baseEnd - 1) instanceof ArrayPushExprToken
                    || targetTokens.get(baseEnd - 1) instanceof DynamicAccessExprToken)) baseEnd--;
            while (baseEnd - baseStart > 2 && isOpenedBrace(targetTokens.get(baseStart), SIMPLE)
                    && isClosedBrace(targetTokens.get(baseEnd - 1), SIMPLE)) { baseStart++; baseEnd--; }
            if (baseEnd - baseStart == 1 && targetTokens.get(baseStart) instanceof VariableExprToken)
                analyzer.getFunction().variable((VariableExprToken) targetTokens.get(baseStart)).setReference(true).setUnstable(true);
            for (Token step : targetTokens) if (step instanceof ArrayGetExprToken) {
                for (ExprStmtToken key : ((ArrayGetExprToken) step).getParameters())
                    if (key.isSingle() && key.getSingle() instanceof VariableExprToken)
                        analyzer.getFunction().variable((VariableExprToken) key.getSingle()).setReference(true).setUnstable(true);
            } else if (step instanceof DynamicAccessExprToken) {
                DynamicAccessExprToken property = (DynamicAccessExprToken) step;
                VariableExprToken field = property.getField() instanceof VariableExprToken ? (VariableExprToken) property.getField()
                        : property.getFieldExpr() != null && property.getFieldExpr().isSingle() && property.getFieldExpr().getSingle() instanceof VariableExprToken
                        ? (VariableExprToken) property.getFieldExpr().getSingle() : null;
                if (field != null) analyzer.getFunction().variable(field).setReference(true).setUnstable(true);
            }
        }
        ExprStmtToken target = new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), targetTokens);
        tokens.subList(start, tokens.size()).clear();
        tokens.add(new ArrayAssignTargetExprToken(target.getMeta(), target));
    }

    protected Token processValueIfElse(ValueIfElseToken current, Token next, ListIterator<Token> iterator,
                                       BraceExprToken.Kind closedBrace, int braceOpened, Separator separator){
        ExprStmtToken value = null;

        if (current instanceof ValueNullCoalesceIfElseToken) {
            value = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                    nextToken(iterator), iterator, separator, BraceExprToken.Kind.ANY
            );

            current.setAlternative(value);
        } else {
            value = analyzer.generator(SimpleExprGenerator.class).getToken(
                    nextToken(iterator), iterator, Separator.COLON, closedBrace
            );

            current.setValue(value);

            if (!((next = iterator.previous()) instanceof ColonToken)) {
                unexpectedToken(next, ":");
            }

            iterator.next();
            ExprStmtToken alternative = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                    nextToken(iterator), iterator, separator, BraceExprToken.Kind.ANY
            );

            if (alternative == null)
                unexpectedToken(iterator.next());

            current.setAlternative(alternative);
            if (analyzer.getEnvironment() != null && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                int depth = 0;
                for (Token token : alternative.getTokens()) {
                    if (token instanceof BraceExprToken) {
                        depth += ((BraceExprToken) token).isOpened() ? 1 : -1;
                    } else if (depth == 0 && token instanceof ValueIfElseToken
                            && !(token instanceof ValueNullCoalesceIfElseToken)) {
                        if (current.getValue() != null || ((ValueIfElseToken) token).getValue() != null) {
                            analyzer.getEnvironment().error(current.toTraceInfo(analyzer.getContext()),
                                    php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                                    "Unparenthesized nested ternary is not supported; use explicit parentheses");
                        }
                    }
                }
            }
        }

        return current;
    }

    protected ExprStmtToken processNewExpr(Token next, BraceExprToken.Kind closedBrace, int braceOpened,
            ListIterator<Token> iterator, boolean first) {
        List<Token> tmp = new ArrayList<Token>();
        if (first) {
            if (next instanceof VariableExprToken) {
                analyzer.getScope().addVariable((VariableExprToken) next);
                if (analyzer.getFunction() != null) {
                    analyzer.getFunction().setVarsExists(true);
                    analyzer.getFunction().variable((VariableExprToken) next).setUsed(true);
                }
            }

            tmp.add(next);
        }

        Token previous = next;
        Token token = nextToken(iterator);

        if (isOpenedBrace(token, ARRAY) || isOpenedBrace(token, BLOCK)) {
            tmp.add(processArrayToken(previous, token, iterator));
            if (iterator.hasNext()) {
                if (nextTokenAndPrev(iterator) instanceof DynamicAccessExprToken)
                    tmp.addAll(processNewExpr(next, closedBrace, braceOpened, iterator, false).getTokens());
            }
        } else if (token instanceof DynamicAccessExprToken) {
            next = null;
            if (iterator.hasNext()) {
                next = iterator.next();
                iterator.previous();
            }

            tmp.add(processDynamicAccess(token, next, iterator, closedBrace, braceOpened));
            if (iterator.hasNext()) {
                token = nextTokenAndPrev(iterator);
                if (isOpenedBrace(token, ARRAY)
                        || isOpenedBrace(token, BLOCK)
                        || token instanceof DynamicAccessExprToken) {
                    tmp.addAll(processNewExpr(next, closedBrace, braceOpened, iterator, false).getTokens());
                }
            }
        } else
            iterator.previous();

        if (!first) {
            return new ExprStmtToken(null, null, tmp);
        }

        return new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), tmp);
    }

    protected Token processNew(Token current, BraceExprToken.Kind closedBrace, int braceOpened, ListIterator<Token> iterator){
        NewExprToken result = (NewExprToken)current;
        Token next = nextToken(iterator);
        List<AttributeStmtToken> attributes=List.of();
        if(next instanceof AttributeStartToken) {
            attributes=analyzer.generator(org.develnext.jphp.core.syntax.generators.AttributeGenerator.class).parse(next,iterator);
            next=nextToken(iterator);
        }
        boolean readonlyAnonymous=false;
        if(analyzer.getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5
                && next instanceof NameToken && next.getWord().equalsIgnoreCase("readonly")) {
            next=nextToken(iterator);readonlyAnonymous=true;
            if(!(next instanceof ClassStmtToken)) unexpectedToken(next,"class");
        }

        ClassStmtToken anonymous = analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && next instanceof ClassStmtToken ? (ClassStmtToken) next : null;
        if (anonymous == null && !isTokenClass(next, StaticExprToken.class, ParentExprToken.class, SelfExprToken.class)) {
            next = makeSensitive(next);
        }

        validateRelativeClass(next);
        if (anonymous != null) {
            anonymous.setPhpAttributes(attributes);
            anonymous.setPhpReadonly(readonlyAnonymous);
            result.setAnonymousClass(anonymous);
        } else if (next instanceof NameToken){
            if(!attributes.isEmpty()) unexpectedToken(next,"anonymous class");
            FulledNameToken nameToken = analyzer.getRealName((NameToken)next);
            result.setName(nameToken);

            iterator.previous();
            iterator.previous();

            if (iterator.hasPrevious()) {
                Token previous = iterator.previous();

                if (previous instanceof AssignExprToken) {
                    if (iterator.hasPrevious()) {
                        previous = iterator.previous();

                        if (previous instanceof VariableExprToken) {
                            analyzer.getScope().typeInfoOf(previous).addType(nameToken.getName());
                        }

                        iterator.next();
                    }
                }

                iterator.next();
            }

            iterator.next();
            iterator.next();
        } else if (next instanceof VariableExprToken) {
            result.setName(processNewExpr(next, closedBrace, braceOpened, iterator, true));
        } else if (next instanceof StaticExprToken) {
            Scope scope = analyzer.getScope();
            scope.setStaticExists(true);
            result.setName((StaticExprToken)next);
        } else if (next instanceof SelfExprToken){
            if (analyzer.getClazz() == null || analyzer.getPhpLanguageProfile()
                    == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                result.setName((SelfExprToken) next);
            } else {
                if (analyzer.getClazz().isTrait()) {
                    result.setName((SelfExprToken) next);
                } else {
                    result.setName(new FulledNameToken(next.getMeta(), new ArrayList<Token>() {{
                        if (analyzer.getClazz().getNamespace().getName() != null)
                            addAll(analyzer.getClazz().getNamespace().getName().getNames());
                        add(analyzer.getClazz().getName());
                    }}));
                }
            }

        } else
            unexpectedToken(next);

        next = nextToken(iterator);
        if (isOpenedBrace(next, SIMPLE)){
            if (analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && nextTokenAndPrev(iterator) instanceof ArgumentUnpackExprToken) {
                nextToken(iterator);
                if (isClosedBrace(nextTokenAndPrev(iterator), SIMPLE)) {
                    if (analyzer.getEnvironment() != null) analyzer.getEnvironment().error(
                            current.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                            "Cannot create Closure for new expression");
                    unexpectedToken(current);
                }
                iterator.previous();
            }
            if (analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                result.setArguments(processPhp85Arguments(iterator));
            else {
            ExprStmtToken param;
            List<ExprStmtToken> parameters = new ArrayList<ExprStmtToken>();
            do {
                param = analyzer.generator(SimpleExprGenerator.class)
                        .getToken(nextToken(iterator), iterator, true, SIMPLE);

                if (param != null)
                    parameters.add(param);
            } while (param != null);
            nextToken(iterator);
            result.setParameters(parameters);
            }
        } else {
            result.setParameters(new ArrayList<ExprStmtToken>());
            iterator.previous();
        }

        if (anonymous != null) analyzer.generator(org.develnext.jphp.core.syntax.generators.ClassGenerator.class)
                .processAnonymous(anonymous, iterator);
        if (analyzer.getFunction() != null){
            analyzer.getFunction().setCallsExist(true);
        }

        return result;
    }

    protected Token processString(StringExprToken string) {
        if (string.getSegments().isEmpty()){
            if (string.getQuote() == StringExprToken.Quote.SHELL) {
                return new ShellExecExprToken(string.getMeta(), Arrays.<Token>asList(string));
            }

            return string;
        }

        List<Token> tokens = new ArrayList<Token>();
        int i = 0;
        String value = string.getValue();
        TokenMeta meta = string.getMeta();

        for(StringExprToken.Segment segment : string.getSegments()){
            String prev = value.substring(i, segment.from);
            if (!prev.isEmpty()) {
                StringExprToken item = analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                        ? string.slice(i, segment.from) : new StringExprToken(new TokenMeta(
                        prev, meta.getStartLine() + i, meta.getEndLine(), meta.getStartLine(), meta.getEndLine()
                ), StringExprToken.Quote.SINGLE);

                tokens.add(item);
            }

            String dynamic = value.substring(segment.from, segment.to);
            if (!segment.isVariable)
                dynamic = dynamic.substring(1, dynamic.length() - 1);

            Tokenizer tokenizer = new Tokenizer(dynamic + ";", analyzer.getContext());
            boolean sourceOrigin = segment instanceof StringExprToken.SourceSegment;
            try {
                SyntaxAnalyzer syntaxAnalyzer;
                if (sourceOrigin) {
                    StringExprToken.SourceSegment origin = (StringExprToken.SourceSegment) segment;
                    syntaxAnalyzer = new SyntaxAnalyzer(analyzer.getEnvironment(), tokenizer, analyzer.getFunction(),
                            origin.sourceLine, origin.sourcePosition + (segment.isVariable ? 0 : 1));
                } else {
                    syntaxAnalyzer = new SyntaxAnalyzer(analyzer.getEnvironment(), tokenizer, analyzer.getFunction());
                }
                List<Token> tree = syntaxAnalyzer.getTree();
                analyzer.getScope().addVariables(syntaxAnalyzer.getScope().getVariables());

                assert tree.size() > 0;

                Token item = tree.get(0);
                if (!(item instanceof ExprStmtToken))
                    unexpectedToken(item);

                ExprStmtToken expr = (ExprStmtToken)item;
                if (expr.isSingle()){
                    tokens.add(expr.getSingle());
                } else
                    tokens.add(expr);
            } catch (ParseException e){
                if (sourceOrigin) throw e;
                TraceInfo oldTrace = e.getTraceInfo();
                e.setTraceInfo(new TraceInfo(
                                analyzer.getContext(),
                                meta.getStartLine() + oldTrace.getStartLine(),
                                meta.getEndLine() + oldTrace.getEndLine(),
                                meta.getStartLine() + oldTrace.getStartLine(),
                                meta.getEndLine() + oldTrace.getEndLine()
                ));
                throw e;
            }

            i = segment.to;
        }

        String prev = value.substring(i);
        if (!prev.isEmpty()){
            StringExprToken item = analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    ? string.slice(i, value.length()) : new StringExprToken(new TokenMeta(
                    prev, meta.getStartLine() + i, meta.getEndLine(), meta.getStartLine(), meta.getEndLine()
            ), StringExprToken.Quote.SINGLE);

            tokens.add(item);
        }

        if (string.getQuote() == StringExprToken.Quote.SHELL) {
            return new ShellExecExprToken(meta, tokens);
        }

        StringBuilderExprToken result = new StringBuilderExprToken(meta, tokens);
        result.setBinary(string.isBinary());
        return result;
    }

    protected Token processSimpleToken(Token current, Token previous, Token next, ListIterator<Token> iterator,
                                       BraceExprToken.Kind closedBraceKind, int braceOpened, Separator separator){
        if (current instanceof DynamicAccessExprToken){
            DynamicAccessExprToken access=processDynamicAccess(current,next,iterator,closedBraceKind,braceOpened);
            if(previous instanceof VariableExprToken receiver && receiver.getName().equals("this")
                    && !access.isNullsafe() && access.getField() instanceof NameToken member
                    && analyzer.getFunction() instanceof MethodStmtToken hook
                    && member.getName().equals(hook.getPropertyHookName()))
                hook.notePropertyHookBackingAccess(new PropertyHookStmtToken.BackingAccess(receiver,access,member));
            return access;
        }

        if (current instanceof OperatorExprToken) {
            isRef = false;

            if (current instanceof InstanceofExprToken && next instanceof NameToken) {
                if (previous instanceof VariableExprToken) {
                    analyzer.getScope().typeInfoOf(previous).addType(analyzer.getRealName((NameToken) next, NamespaceUseStmtToken.UseType.CLASS).getName());
                }
            }
        }

        if (current instanceof NameToken && next instanceof StringExprToken) {
            // binary string
            if (((NameToken) current).getName().equalsIgnoreCase("b")) {
                ((StringExprToken) next).setBinary(true);
                iterator.next();
                return processString((StringExprToken)next);
            }
        }

        if (current instanceof YieldExprToken) {
            return processYield(current, next, iterator, closedBraceKind);
        }

        if (current instanceof ImportExprToken) {
            return processImport(current, next, iterator, closedBraceKind, braceOpened);
        }

        if (current instanceof PrintNameToken) {
            return processPrint(current, next, iterator, closedBraceKind, braceOpened);
        }

        if (current instanceof NewExprToken) {
            return processNew(current, closedBraceKind, braceOpened, iterator);
        }

        if (current instanceof DollarExprToken){
            return processVarVar(current, next, iterator);
        }

        if (current instanceof VariableExprToken){
            analyzer.getScope().addVariable((VariableExprToken) current);
            if (analyzer.getFunction() != null) {
                analyzer.getFunction().setVarsExists(true);
                analyzer.getFunction().variable((VariableExprToken)current).setUsed(true);
            }
        }

        // Если переменная меняется, значит она нестабильна и не может быть заменена на костантное значение.
        if ((current instanceof AssignOperatorExprToken || current instanceof IncExprToken || current instanceof DecExprToken)
                && previous instanceof VariableExprToken) {

            if (analyzer.getFunction() != null) {
                analyzer.getFunction().variable((VariableExprToken) previous).setUnstable(true);
            }
        }

        if (current instanceof AssignExprToken && previous instanceof VariableExprToken && next instanceof VariableExprToken) {
            ExpressionInfo info = analyzer.getScope().typeInfoOf(next);
            analyzer.getScope().typeInfoOf(previous).addTypes(info.getTypes());
        }

        if (current instanceof ValueIfElseToken){
            return processValueIfElse((ValueIfElseToken)current, next, iterator, closedBraceKind, braceOpened, separator);
        }

        // &
        if (current instanceof AmpersandRefToken){
            /*if (previous == null)
                unexpectedToken(current);*/

            isRef = true;
            if (next instanceof VariableExprToken)
                if (analyzer.getFunction() != null) {
                    analyzer.getFunction().variable((VariableExprToken)next)
                        .setReference(true)
                        .setMutable(true);
                }

            if (previous instanceof AssignExprToken || previous instanceof KeyValueExprToken
                    || (canStartByReference && previous == null)) {
                if (previous instanceof AssignExprToken)
                    ((AssignExprToken) previous).setAsReference(true);
                if (previous instanceof KeyValueExprToken)
                    ((KeyValueExprToken) previous).setValueReference(true);

                iterator.previous();
                Token token = iterator.previous(); // =
                if (iterator.hasPrevious()) {
                    token = iterator.previous();
                    if (token instanceof VariableExprToken && analyzer.getFunction() != null){
                        analyzer.getFunction().variable((VariableExprToken)token)
                                .setReference(true)
                                .setMutable(true);
                       // analyzer.getFunction().getUnstableLocal().add((VariableExprToken)token); TODO: check is needed?
                    }

                    iterator.next();
                }
                iterator.next();
                iterator.next();
                if (!(next instanceof ValueExprToken) && !(next instanceof DollarExprToken
                        && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5))
                    unexpectedToken(token);

            } else {
                return new AndExprToken(current.getMeta());
            }

            return current;
        }
        // &$var, &$obj->prop['x'], &class::$prop, &$arr['x'], &call()->x;
        if (previous instanceof AmpersandRefToken){
            if (current instanceof VariableExprToken)
                if (analyzer.getFunction() != null)
                    analyzer.getFunction().variable((VariableExprToken)current).setReference(true);
        }

        if (analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            if (current instanceof PowExprToken) return new PhpPowExprToken(current.getMeta());
            if ((current instanceof MinusExprToken || current instanceof PlusExprToken)
                    && !(previous instanceof ValueExprToken || previous instanceof ArrayGetExprToken
                    || previous instanceof DynamicAccessExprToken || isClosedBrace(previous)))
                return new UnaryNumericExprToken(current.getMeta(), current instanceof MinusExprToken);
        }

        if ((current instanceof MinusExprToken || current instanceof PlusExprToken)
                && (next instanceof IntegerExprToken || next instanceof DoubleExprToken)){

            if (!(previous instanceof ValueExprToken
                    || previous instanceof ArrayGetExprToken || previous instanceof DynamicAccessExprToken
                    || isClosedBrace(previous, SIMPLE))){
                iterator.next();
                // if it minus
                if (current instanceof MinusExprToken){
                    if (analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                        TokenMeta meta = TokenMeta.of(current, next);
                        if (next instanceof IntegerExprToken)
                            return new IntegerExprToken(meta, -((IntegerExprToken) next).getValue());
                        return new DoubleExprToken(meta, -((DoubleExprToken) next).getValue());
                    }
                    if (next instanceof IntegerExprToken){
                        return new IntegerExprToken(TokenMeta.of(current, next));
                    } else if (next instanceof DoubleExprToken){
                        return new DoubleExprToken(TokenMeta.of(current, next));
                    }
                }

                // if it plus nothing
                return next;
            }
        }

        if (current instanceof MinusExprToken){
            if (!(previous instanceof ValueExprToken
                    || previous instanceof ArrayGetExprToken
                    || previous instanceof DynamicAccessExprToken
                    || isClosedBrace(previous))){
                return new UnarMinusExprToken(current.getMeta());
            }
        }

        if (current instanceof PipeExprToken
                && (analyzer.getPhpLanguageProfile() != php.runtime.common.PhpLanguageProfile.PHP_8_5
                    || next instanceof LambdaStmtToken)) unexpectedToken(current);

        if (current instanceof LogicOperatorExprToken){
            if (next == null)
                unexpectedToken(current);

            final LogicOperatorExprToken logic = (LogicOperatorExprToken)current;
            ExprStmtToken result;
            if (analyzer.getPhpLanguageProfile()
                    == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                // Bound recursive parsing so a weaker logical or ternary operator stays outside this RHS.
                List<Token> right = new ArrayList<>();
                int nesting = 0;
                while (iterator.hasNext()) {
                    Token token = iterator.next();
                    if (nesting == 0 && (isBreak(token) || token instanceof SemicolonToken
                            || separator != null && separator.is(token)
                            || token instanceof BraceExprToken && ((BraceExprToken) token).isClosed()
                            || logic instanceof PipeExprToken && token instanceof AmpersandRefToken
                                && !right.isEmpty() && !(right.get(right.size()-1) instanceof FunctionStmtToken)
                            || (token instanceof LogicOperatorExprToken || token instanceof ValueIfElseToken
                                || logic instanceof PipeExprToken && token instanceof OperatorExprToken
                                    && ((OperatorExprToken) token).isBinary())
                                && ((OperatorExprToken) token).getPriority() >= logic.getPriority())) {
                        iterator.previous();
                        break;
                    }
                    if (token instanceof BraceExprToken) nesting += ((BraceExprToken) token).isOpened() ? 1 : -1;
                    right.add(token);
                }
                if (right.isEmpty()) unexpectedToken(current);
                if (logic instanceof PipeExprToken && right.size() > 1
                        && right.get(0) instanceof StaticExprToken && right.get(1) instanceof LambdaStmtToken)
                    unexpectedToken(right.get(1));
                right.add(new SemicolonToken(TokenMeta.of(";", current)));
                ListIterator<Token> bounded = right.listIterator();
                result = analyzer.generator(SimpleExprGenerator.class).getToken(
                        bounded.next(), bounded, Separator.SEMICOLON, null);
            } else {
                result = analyzer.generator(SimpleExprGenerator.class).getNextExpression(
                        nextToken(iterator), iterator, separator, braceOpened > 0 ? SIMPLE : closedBraceKind);
            }

            logic.setRightValue(result);
            return logic;
        }

        if (next instanceof StaticAccessExprToken) {
            return processStaticAccess(next, current, iterator);
        }

        if (current instanceof StaticAccessExprToken) {
            return processStaticAccess(current, null, iterator);
        }

        if (current instanceof StringExprToken){
            return processString((StringExprToken) current);
        }

        if (current instanceof NameToken) {
            if (previous instanceof InstanceofExprToken) {
                return analyzer.getRealName((NameToken) current, NamespaceUseStmtToken.UseType.CLASS);
            } else {
                return analyzer.getRealName((NameToken) current, NamespaceUseStmtToken.UseType.CONSTANT);
            }
        }

        if (current instanceof MacroToken) {
            return null;
        }

        if (current instanceof OperatorExprToken) {
            return null;
        }

        if (current.isNamedToken()) {
            return makeSensitive(current);
        }

        return null;
    }

    protected Token processNewArray(Token current, ListIterator<Token> iterator){
        ArrayExprToken result = new ArrayExprToken(current.getMeta());
        List<ExprStmtToken> parameters = new ArrayList<ExprStmtToken>();

        Token next;
        BraceExprToken.Kind braceKind;
        if (isOpenedBrace(current, ARRAY)) {
            next = current;
            braceKind = ARRAY;
            result.setShortSyntax(true);
        } else {
            next = nextToken(iterator);

            if (!isOpenedBrace(next, SIMPLE)) {
                unexpectedToken(next, "(");
            }

            braceKind = SIMPLE;
            result.setShortSyntax(false);
        }

        do {
            SimpleExprGenerator generator = analyzer.generator(SimpleExprGenerator.class);
            generator.setCanStartByReference(true);

            Token nextToken = nextToken(iterator);

            if (nextToken instanceof CommaToken && result.isShortSyntax()) {
                result.setListSyntax(true);
                parameters.add(null);
                continue;
            }

            boolean unpack = nextToken instanceof ArgumentUnpackExprToken;
            if (unpack) {
                generator.setCanStartByReference(false);
                nextToken = nextToken(iterator);
            }

            ExprStmtToken argument = generator.getToken(nextToken, iterator, Separator.COMMA, braceKind);
            if (argument == null) {
                if (unpack) unexpectedToken(nextToken);
                break;
            }

            if (unpack) {
                for (Token token : argument.getTokens()) {
                    if (token instanceof KeyValueExprToken) unexpectedToken(token);
                }
                result.setUnpacked(parameters.size());
            }

            for (Token token : argument.getTokens()) {
                if (token instanceof KeyValueExprToken) ((KeyValueExprToken) token).setArrayEntry(true);
            }
            parameters.add(argument);
        } while (true);
        nextToken(iterator); // skip )

        result.setParameters(parameters);
        return result;
    }

    protected Token processArrayToken(Token previous, Token current, ListIterator<Token> iterator){
        BraceExprToken.Kind braceKind = ARRAY;
        Separator separator = Separator.ARRAY;
        if (isOpenedBrace(current, BLOCK)){
            braceKind = BLOCK;
            separator = Separator.ARRAY_BLOCK;
        }

        VariableExprToken var = null;
        if (previous instanceof VariableExprToken) {
            if (analyzer.getFunction() != null){
                analyzer.getFunction().variable(var = (VariableExprToken)previous).setArrayAccess(true);
            }
        }

        Token next = nextToken(iterator);
        if (isClosedBrace(next, braceKind)){
            //Token tk = nextTokenAndPrev(iterator);
            //if (tk instanceof AssignableOperatorToken || isOpenedBrace(tk, BraceExprToken.Kind.ARRAY)) {
            // !!! allow [] anywhere
            return new ArrayPushExprToken(TokenMeta.of(current, next));
            /*} else
                unexpectedToken(tk);*/
        } else
            iterator.previous();

        ExprStmtToken param;
        List<ExprStmtToken> parameters = new ArrayList<>();
        boolean lastPush = false;

        do {
            Token token = nextToken(iterator);

            if (isClosedBrace(token, ARRAY)) {
                iterator.previous();

                if (iterator.hasPrevious()) {
                    iterator.previous();
                }

                lastPush = true;
                break;
            } else {
                param = analyzer.generator(SimpleExprGenerator.class).getNextExpression(token, iterator, separator, braceKind);
            }

            if (param != null) {
                parameters.add(param);

                if (iterator.hasNext()){
                    iterator.next();

                    if (iterator.hasNext()) {
                        Token tmp = nextToken(iterator);
                        if (isOpenedBrace(tmp, ARRAY)) {
                            braceKind = ARRAY;
                            separator = Separator.ARRAY;
                            continue;
                        } else if (isOpenedBrace(tmp, BLOCK)) {
                            braceKind = BLOCK;
                            separator = Separator.ARRAY_BLOCK;
                            continue;
                        }
                        iterator.previous();
                    }

                    break;
                }
            }

        } while (param != null);
        //nextToken(iterator); // skip ]

        ArrayGetExprToken result;
        result = new ArrayGetExprToken(current.getMeta());
        result.setParameters(parameters);

        if (isRef) {
            result = new ArrayGetRefExprToken(result);
            ((ArrayGetRefExprToken)result).setShortcut(true);

            if (var != null && analyzer.getFunction() != null) {
                analyzer.getFunction().variable(var).setMutable(true);
            }
        } else if (iterator.hasNext()){
            next = iterator.next();

            if (next instanceof AssignableOperatorToken || lastPush){
                result = new ArrayGetRefExprToken(result);
                if (next instanceof OperatorExprToken && org.develnext.jphp.core.compiler.common.util.CompilerUtils.isPhpArithmetic((OperatorExprToken) next)
                        && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    ((ArrayGetRefExprToken) result).setArithmeticTarget(true);
                    if (var != null && analyzer.getFunction() != null)
                        analyzer.getFunction().variable(var).setReference(true).setUnstable(true);
                }

                if (var != null && analyzer.getFunction() != null) {
                    analyzer.getFunction().variable(var).setMutable(true);
                }
            }

            iterator.previous();
        }

        return result;
    }

    public ExprStmtToken getToken(Token current, ListIterator<Token> iterator,
                                  boolean commaSeparator, BraceExprToken.Kind closedBraceKind) {
        return getToken(
                current, iterator, commaSeparator ? Separator.COMMA : Separator.SEMICOLON, closedBraceKind,
                null
        );
    }

    public ExprStmtToken getNextExpression(Token current, ListIterator<Token> iterator,
                                           BraceExprToken.Kind closedBraceKind){
        return getNextExpression(current, iterator, Separator.COMMA_OR_SEMICOLON, closedBraceKind);
    }

    public ExprStmtToken getNextExpression(Token current, ListIterator<Token> iterator,
                                           Separator separator,
                                           BraceExprToken.Kind closedBraceKind){
        ExprStmtToken value = getToken(
                current, iterator, separator, closedBraceKind
        );
        Token tk = iterator.previous();
        if (!isBreak(tk) && (separator == null || !separator.is(tk))){
            iterator.next();
        }
        return value;
    }

    public ExprStmtToken getToken(Token current, ListIterator<Token> iterator,
                                  Separator separator, BraceExprToken.Kind closedBraceKind) {
        return getToken(
                current, iterator, separator, closedBraceKind,
                null
        );
    }

    @SuppressWarnings("unchecked")
    public ExprStmtToken getToken(Token current, ListIterator<Token> iterator,
                                  Separator separator, BraceExprToken.Kind closedBraceKind,
                                  Callback<Boolean, Token> breakCallback) {
        isRef = false;

        List<Token> tokens = new ArrayList<>();
        Token previous = null;
        Token next = iterator.hasNext() ? iterator.next() : null;
        if (next != null)
            iterator.previous();

        int braceOpened = 0;
        boolean needBreak = false;
        do {
            if (breakCallback != null && current != null && breakCallback.call(current)){
                break;
            }

            if (braceOpened == 0 && separator == Separator.MATCH_CONDITION && current instanceof KeyValueExprToken)
                break;
            if (isOpenedBrace(current, SIMPLE)){
                boolean isFunc = false;
                if (previous instanceof NameToken && previous.getMeta().getWord().equalsIgnoreCase("array")){
                    iterator.previous();
                    tokens.set(tokens.size() - 1, current = processNewArray(previous, iterator));
                } else {
                    if (previous instanceof NameToken
                            || previous instanceof VariableExprToken
                            || previous instanceof ClosureStmtToken
                            || previous instanceof ArrayGetExprToken
                            || previous instanceof CallExprToken)
                        isFunc = true;
                    else if (previous instanceof StaticAccessExprToken){
                        isFunc = true; // !((StaticAccessExprToken)previous).isGetStaticField(); TODO check it!
                    } else if (previous instanceof DynamicAccessExprToken
                            || previous instanceof StaticAccessOperatorExprToken && analyzer.getPhpLanguageProfile()
                                == php.runtime.common.PhpLanguageProfile.PHP_8_5){
                        isFunc = true;
                    } else if (isClosedBrace(previous, SIMPLE)
                            && analyzer.getPhpLanguageProfile()
                                == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                        isFunc = true;
                    }

                    if (isFunc){
                        CallExprToken call = processCall(previous, current, iterator);
                        if (call.getName() != null) {
                            current = call;
                            tokens.set(tokens.size() - 1, call);
                        } else {
                            tokens.add(current = new CallOperatorToken(call));
                        }
                    } else {
                        if (needBreak)
                            unexpectedToken(current);

                        braceOpened += 1;
                        tokens.add(current);
                    }
                }
            } else if (braceOpened > 0 && isClosedBrace(current, SIMPLE)){
                braceOpened -= 1;
                tokens.add(current);
                if (isOpenedBrace(previous, SIMPLE))
                    unexpectedToken(current);
            } else if (isOpenedBrace(current, ARRAY)
                    || isOpenedBrace(current, BLOCK)){
                if (isTokenClass(previous,
                        NameToken.class,
                        VariableExprToken.class,
                        GetVarExprToken.class,
                        CallExprToken.class,
                        ArrayGetExprToken.class,
                        ArrayExprToken.class,
                        DynamicAccessExprToken.class,
                        StringExprToken.class,
                        StringBuilderExprToken.class,
                        CallOperatorToken.class,
                        ArrayPushExprToken.class) ||
                        isClosedBrace(previous, SIMPLE) ||
                        (previous instanceof StaticAccessExprToken && (((StaticAccessExprToken)previous).isGetStaticField()
                                || analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5))
                        || previous instanceof StaticAccessOperatorExprToken && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5){
                    // array
                    current = processArrayToken(previous, current, iterator);
                    if (previous instanceof DynamicAccessExprToken && current instanceof ArrayGetRefExprToken){
                        tokens.set(tokens.size() - 1, new DynamicAccessGetRefExprToken((DynamicAccessExprToken)previous));
                    }
                    tokens.add(current);
                } else if (previous instanceof OperatorExprToken
                        || previous == null
                        || isOpenedBrace(previous, SIMPLE)
                        || isOpenedBrace(previous, BLOCK)) {
                    int currIndex = iterator.nextIndex() - 1;
                    ArrayExprToken arrToken = (ArrayExprToken) processNewArray(current, iterator);

                    boolean nextAssign = nextTokenAndPrev(iterator) instanceof AssignExprToken;

                    if (nextAssign) {
                        int doneIndex = iterator.nextIndex();
                        int prevCount = doneIndex - currIndex;

                        for (int i = 0; i < prevCount; i++) {
                            current = iterator.previous();
                        }

                        iterator.next();

                        tokens.add(processList(current, iterator, null, closedBraceKind, braceOpened));
                    } else {
                        if (arrToken.isListSyntax()) {
                            unexpectedToken(nextToken(iterator), "=");
                        }

                        tokens.add(current = arrToken);
                    }
                } else {
                    unexpectedToken(current);
                }
            } else if (braceOpened == 0 && isClosedBrace(current, ARRAY)){
                if (separator == Separator.ARRAY)
                    break;
                if (closedBraceKind == ARRAY || closedBraceKind == BraceExprToken.Kind.ANY){
                    //if (tokens.isEmpty())
                    iterator.previous();
                    break;
                }
                unexpectedToken(current);
            } else if (separator == Separator.ARRAY_BLOCK
                    && braceOpened == 0 && isClosedBrace(current, BLOCK)){
                break;
            } else if (current instanceof AttributeStartToken) {
                var attributes=analyzer.generator(org.develnext.jphp.core.syntax.generators.AttributeGenerator.class).parse(current,iterator);
                Token declaration=nextToken(iterator);boolean staticClosure=declaration instanceof StaticExprToken;
                if(staticClosure) declaration=nextToken(iterator);
                Token closure=declaration instanceof FunctionStmtToken ? processClosure(declaration,null,iterator)
                        : declaration instanceof LambdaStmtToken ? processLambda(declaration,iterator,separator,braceOpened>0?SIMPLE:closedBraceKind) : null;
                if(!(closure instanceof ClosureStmtToken)) unexpectedToken(declaration,"function or arrow function");
                ((ClosureStmtToken)closure).getFunction().setPhpAttributes(attributes);
                ((ClosureStmtToken)closure).setStaticClosure(staticClosure);tokens.add(closure);
            } else if (current instanceof StaticExprToken
                    && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && (next instanceof FunctionStmtToken || next instanceof LambdaStmtToken)) {
                Token function = nextToken(iterator);
                current = function instanceof FunctionStmtToken
                        ? processClosure(function, null, iterator)
                        : processLambda(function, iterator, separator, braceOpened > 0 ? SIMPLE : closedBraceKind);
                if (!(current instanceof ClosureStmtToken)) unexpectedToken(function);
                ((ClosureStmtToken) current).setStaticClosure(true);
                tokens.add(current);
            } else if (current instanceof FunctionStmtToken) {
                current = processClosure(current, next, iterator);
                tokens.add(current);
            } else if (current instanceof MatchExprToken) {
                current = analyzer.generator(org.develnext.jphp.core.syntax.generators.MatchGenerator.class).getToken(current, iterator);
                tokens.add(current);
            } else if (current instanceof ThrowStmtToken && analyzer.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                ExprStmtToken thrown = analyzer.generator(SimpleExprGenerator.class).getToken(nextToken(iterator), iterator, separator, closedBraceKind);
                if (thrown == null) unexpectedToken(current);
                tokens.add(new ThrowExprToken(current.getMeta(), thrown));
                break;
            } else if (current instanceof LambdaStmtToken) {
                current = processLambda(current, iterator, separator, braceOpened > 0 ? SIMPLE : closedBraceKind);
                tokens.add(current);
            } else if (current instanceof ListExprToken && isOpenedBrace(next, SIMPLE)){
                current = processList(current, iterator, null, closedBraceKind, braceOpened);
                tokens.add(current);
            } else if (current instanceof DieExprToken){
                processDie(current, next, iterator);
                tokens.add(current);
            } else if (current instanceof EmptyExprToken){
                processEmpty(current, iterator);
                tokens.add(current);
            } else if (current instanceof IssetExprToken){
                processIsset(previous, current, iterator);
                tokens.add(current);
            } else if (current instanceof UnsetExprToken){
                if (isOpenedBrace(previous, SIMPLE)
                        && isClosedBrace(next, SIMPLE)){
                    current = new UnsetCastExprToken(current.getMeta());
                    tokens.set(tokens.size() - 1, current);
                    iterator.next();
                    braceOpened--;
                } else {
                    if (previous != null)
                        unexpectedToken(current);

                    processUnset(previous, current, iterator);
                    tokens.add(current);
                    needBreak = true;
                }
            } else if (current instanceof CommaToken){
                if (separator == Separator.COMMA || separator == Separator.COMMA_OR_SEMICOLON || separator == Separator.MATCH_CONDITION){
                    if (tokens.isEmpty())
                        unexpectedToken(current);
                    break;
                } else {
                    unexpectedToken(current);
                }
            } else if (current instanceof AsStmtToken){
                if (separator == Separator.AS)
                    break;
                unexpectedToken(current);
            } else if (isClosedBrace(current, closedBraceKind)){
                iterator.previous();
                break;
            } else if (current instanceof BreakToken){
                break;
            } else if (current instanceof ColonToken){
                if (separator == Separator.COLON) {
                    /*if (tokens.isEmpty()) see: issues/93
                        unexpectedToken(current);*/
                    break;
                }
                unexpectedToken(current);
            } else if (current instanceof SemicolonToken){ // TODO refactor!
                if (separator == Separator.SEMICOLON || separator == Separator.COMMA_OR_SEMICOLON) {
                    /*if (tokens.isEmpty()) see: issues/94
                        unexpectedToken(current);*/
                    break;
                }

                if (separator == Separator.COMMA || closedBraceKind != null || tokens.isEmpty())
                    unexpectedToken(current);
                break;
            } else if (current instanceof BraceExprToken){
                if (closedBraceKind == BraceExprToken.Kind.ANY && isClosedBrace(current)) {
                    iterator.previous();
                    break;
                }
                unexpectedToken(current);
            } else if (current instanceof ArrayExprToken){
                if (needBreak)
                    unexpectedToken(current);

                tokens.add(current = processNewArray(current, iterator));
            } else if (current instanceof AssignNullCoalesceExprToken) {
                tokens.add(current = processNullCoalesceAssign(current, tokens, iterator, separator));
            } else if (current instanceof ExprToken) {
                if (needBreak)
                    unexpectedToken(current);

                if (current instanceof AssignExprToken && analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5) deferArrayAssignment(tokens);

                if (current instanceof ValueNullCoalesceIfElseToken && analyzer.getPhpLanguageProfile()
                        == php.runtime.common.PhpLanguageProfile.PHP_8_5) markCoalescingPropertyReads(tokens);
                CastExprToken cast = null;
                if (current instanceof NameToken && isOpenedBrace(previous, SIMPLE)
                        && isClosedBrace(next, SIMPLE)){
                    cast = CastExprToken.valueOf(((NameToken)current).getName(), current.getMeta());
                    if(cast instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.VoidCastExprToken){
                        if(analyzer.getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5)cast=null;
                        else if(tokens.size()!=1)throw new php.runtime.exceptions.ParseException("syntax error, unexpected token \"(void)\"",current.toTraceInfo(analyzer.getContext()));
                    }
                    if (cast != null){
                        current = cast;
                        iterator.next();
                        braceOpened--;
                        tokens.set(tokens.size() - 1, current);
                    }
                }

                if (cast == null){
                    Token token = processSimpleToken(
                            current, previous, next, iterator, closedBraceKind, braceOpened, separator
                    );
                    if (token != null)
                        current = token;

                    tokens.add(current);
                }
            } else
                unexpectedToken(current);

            previous = current;
            if (iterator.hasNext()){
                current = nextToken(iterator);
                next = iterator.hasNext() ? iterator.next() : null;
                if (next != null)
                    iterator.previous();
            } else
                current = null;

            if (current == null)
                nextToken(iterator);

        } while (current != null);

        if (tokens.isEmpty())
            return null;

        if (braceOpened != 0)
            unexpectedToken(iterator.previous());

        if (analyzer.getPhpLanguageProfile()
                == php.runtime.common.PhpLanguageProfile.PHP_8_5) markReferencePropertyReads(tokens);
        ExprStmtToken exprStmtToken = new ExprStmtToken(analyzer.getEnvironment(), analyzer.getContext(), tokens);

        return exprStmtToken;
    }

    @Override
    public ExprStmtToken getToken(Token current, ListIterator<Token> iterator){
        return getToken(current, iterator, false, null);
    }

    @Override
    public boolean isAutomatic() {
        return false;
    }
}
