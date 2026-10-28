package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AssignExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.AssignPlusExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.PlusExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.ArrayExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.IntegerExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.develnext.jphp.core.compiler.jvm.statement.ScalarMethodCompiler.*;

/** Bounded local value-array reduction. No PHP array or iterator escapes the admitted body. */
final class ScalarArraySumCompiler {
    private ScalarArraySumCompiler() {}

    static Value analyze(BodyStmtToken body, Map<String, Local> parameters,
                         Function<ExprStmtToken, List<Token>> tokens,
                         BiFunction<List<Token>, Map<String, Local>, Value> expression,
                         Set<String> superGlobals) {
        try {
            List<ExprStmtToken> statements = body.getInstructions();
            require(statements != null && statements.size() == 4);
            List<Token> arrayAssignment = tokens.apply(statements.get(0));
            require(arrayAssignment.size() == 3 && arrayAssignment.get(0) instanceof VariableExprToken
                    && arrayAssignment.get(1) instanceof ArrayExprToken);
            require(arrayAssignment.get(2).getClass() == AssignExprToken.class
                    && !((AssignExprToken) arrayAssignment.get(2)).isAsReference());
            String arrayName = ((VariableExprToken) arrayAssignment.get(0)).getName();
            ArrayExprToken array = (ArrayExprToken) arrayAssignment.get(1);
            require(!array.isListSyntax() && array.getParameters().size() <= 64);

            List<Token> initial = tokens.apply(statements.get(1));
            require(initial.size() == 3 && initial.get(0) instanceof VariableExprToken
                    && initial.get(1) instanceof IntegerExprToken zero && !zero.isBigValue() && zero.getValue() == 0
                    && initial.get(2).getClass() == AssignExprToken.class
                    && !((AssignExprToken) initial.get(2)).isAsReference());
            String accumulator = ((VariableExprToken) initial.get(0)).getName();
            require(statements.get(2).isSingle() && statements.get(2).getSingle() instanceof ForeachStmtToken);
            ForeachStmtToken loop = (ForeachStmtToken) statements.get(2).getSingle();
            require(loop.getKey() == null && !loop.isKeyReference() && !loop.isValueReference());
            require(variable(loop.getIterator()).equals(arrayName));
            String element = variable(loop.getValue());
            require(Set.of(arrayName, accumulator, element).size() == 3);
            for (String name : List.of(arrayName, accumulator, element)) {
                require(!parameters.containsKey(name) && !name.equals("this") && !name.equals("GLOBALS")
                        && !superGlobals.contains(name));
            }
            require(loop.getBody() != null && loop.getBody().getInstructions().size() == 1);
            List<Token> addition = tokens.apply(loop.getBody().getInstructions().getFirst());
            boolean compound = addition.size() == 3 && isVariable(addition.get(0), accumulator)
                    && isVariable(addition.get(1), element) && addition.get(2).getClass() == AssignPlusExprToken.class;
            boolean expanded = addition.size() == 5 && isVariable(addition.get(0), accumulator)
                    && isVariable(addition.get(1), accumulator) && isVariable(addition.get(2), element)
                    && addition.get(3).getClass() == PlusExprToken.class && addition.get(4).getClass() == AssignExprToken.class
                    && !((AssignExprToken) addition.get(4)).isAsReference();
            require(compound || expanded);
            require(statements.get(3).isSingle() && statements.get(3).getSingle() instanceof ReturnStmtToken);
            ReturnStmtToken returned = (ReturnStmtToken) statements.get(3).getSingle();
            require(!returned.isImplicit() && variable(returned.getValue()).equals(accumulator));

            Value sum = expression.apply(List.of(initial.get(1)), parameters);
            for (int index = 0; index < array.getParameters().size(); index++) {
                require(!array.isUnpacked(index));
                List<Token> entry = tokens.apply(array.getParameters().get(index));
                // Only direct integer literals/parameters: no keys, references, calls,
                // reads of external state, or evaluation that could be replayed on overflow.
                require(entry.size() == 1 && (entry.getFirst() instanceof VariableExprToken
                        || entry.getFirst() instanceof IntegerExprToken));
                Value value = expression.apply(entry, parameters);
                require(value.kind() == Kind.LONG);
                sum = binary("plus", sum, value);
            }
            return sum;
        } catch (Unsupported ignored) {
            return null;
        } catch (IllegalArgumentException duplicateNames) {
            return null;
        }
    }

    private static String variable(ExprStmtToken expression) {
        require(expression != null && expression.isSingle() && expression.getSingle() instanceof VariableExprToken);
        return ((VariableExprToken) expression.getSingle()).getName();
    }

    private static boolean isVariable(Token token, String name) {
        return token instanceof VariableExprToken variable && variable.getName().equals(name);
    }
}
