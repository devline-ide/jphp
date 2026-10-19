package org.develnext.jphp.core.syntax.generators;
import java.util.*;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.CommentToken;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.CommaToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.exceptions.support.ErrorType;

public final class AttributeGenerator extends Generator<Token> {
    public AttributeGenerator(SyntaxAnalyzer analyzer) {super(analyzer);}
    /** Leaves the iterator immediately after the final ], including consecutive groups. */
    public List<AttributeStmtToken> parse(Token current, ListIterator<Token> iterator) {
        List<AttributeStmtToken> attributes=new ArrayList<>();
        while(current instanceof AttributeStartToken) {
            Token name=nextToken(iterator);
            if (!(name instanceof NameToken)) unexpectedToken(name,"attribute name");
            while(true) {
                FulledNameToken full=analyzer.generator(NameGenerator.class).getToken(name,iterator);
                if (full == null) unexpectedToken(name,"attribute name");
                full=analyzer.getRealName(full);
                List<CallArgument> arguments=List.of();
                Token next=nextToken(iterator);
                if (isOpenedBrace(next,BraceExprToken.Kind.SIMPLE)) {
                    arguments=analyzer.generator(SimpleExprGenerator.class).processPhp85Arguments(iterator);
                    Set<String> keys=new HashSet<>();
                    for (CallArgument argument:arguments) {
                        if (argument.getKind()==CallArgument.Kind.UNPACK)
                            analyzer.getEnvironment().error(argument.getExpression().toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                    "Cannot use unpacking in attribute argument list");
                        if (argument.getName()!=null && !keys.add(argument.getName()))
                            analyzer.getEnvironment().error(argument.getExpression().toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                                    "Named parameter $%s overwrites previous argument",argument.getName());
                    }
                    next=nextToken(iterator);
                }
                attributes.add(new AttributeStmtToken(name.getMeta(),full,arguments));
                if (isClosedBrace(next,BraceExprToken.Kind.ARRAY)) break;
                if (!(next instanceof CommaToken)) unexpectedToken(next,"]");
                name=nextToken(iterator);
                if (isClosedBrace(name,BraceExprToken.Kind.ARRAY)) break;
                if (!(name instanceof NameToken)) unexpectedToken(name,"attribute name");
            }
            if (!iterator.hasNext()) break;
            current=nextToken(iterator);
            if (!(current instanceof AttributeStartToken)) {iterator.previous();break;}
        }
        return List.copyOf(attributes);
    }
    @Override public Token getToken(Token current,ListIterator<Token> iterator) {
        return getToken(current,iterator,false);
    }
    public Token getToken(Token current,ListIterator<Token> iterator,boolean deferred) {
        if (!(current instanceof AttributeStartToken)) return null;
        List<AttributeStmtToken> attributes=parse(current,iterator);
        Token declaration=nextToken(iterator);
        Token parsed=deferred ? analyzer.generator(ClassGenerator.class).getToken(declaration,iterator,true) : null;
        if(parsed==null) parsed=analyzer.generateToken(declaration,iterator);
        if (parsed==null) unexpectedToken(declaration,"class, function, or const declaration");
        Token target=parsed instanceof ExprStmtToken expression && expression.isSingle() ? expression.getSingle() : parsed;
        if (target instanceof ClosureStmtToken closure) target=closure.getFunction();
        if (!(target instanceof ClassStmtToken || target instanceof FunctionStmtToken || target instanceof ConstStmtToken))
            unexpectedToken(declaration,"class, function, or const declaration");
        ((StmtToken)target).setPhpAttributes(attributes);
        if (target instanceof ConstStmtToken constant && constant.getClazz()==null && constant.items.size()!=1)
            analyzer.getEnvironment().error(constant.toTraceInfo(analyzer.getContext()),ErrorType.E_COMPILE_ERROR,
                    "Cannot apply attributes to multiple constants");
        return parsed;
    }
}
