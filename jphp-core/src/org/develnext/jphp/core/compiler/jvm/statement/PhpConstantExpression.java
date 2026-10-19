package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.*;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.operator.cast.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.macro.*;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import php.runtime.Memory;
import php.runtime.common.PhpClassNames;
import php.runtime.exceptions.support.ErrorType;
import java.util.*;

/** Validates the entire constant expression, retaining PHP's compile-time dead-branch pruning. */
public final class PhpConstantExpression {
    private static final String INVALID = "Constant expression contains invalid operations";
    private static final Set<Class<?>> OPERATORS = Set.of(
            PlusExprToken.class, MinusExprToken.class, MulExprToken.class, DivExprToken.class, ModExprToken.class,
            PowExprToken.class, PhpPowExprToken.class, ConcatExprToken.class,
            AndExprToken.class, OrExprToken.class, XorExprToken.class, ShiftLeftExprToken.class, ShiftRightExprToken.class,
            EqualExprToken.class, BooleanNotEqualExprToken.class, IdenticalExprToken.class, NotIdenticalExprToken.class,
            SmallerExprToken.class, SmallerOrEqualToken.class, GreaterExprToken.class, GreaterOrEqualExprToken.class,
            SpaceshipExprToken.class, BooleanXorExprToken.class, BooleanNotExprToken.class, NotExprToken.class,
            UnarMinusExprToken.class, UnaryNumericExprToken.class, KeyValueExprToken.class,
            IntCastExprToken.class, DoubleCastExprToken.class, StringCastExprToken.class, BinaryCastExprToken.class,
            BooleanCastExprToken.class, ArrayCastExprToken.class);
    private record Result(Token at, String error, int start, boolean foldable) {
        static Result valid(int start, boolean foldable) { return new Result(null, null, start, foldable); }
        static Result invalid(Token at, String error, int start) { return new Result(at, error, start, false); }
    }
    private record Literal(Token token, int baseStart) {}
    private final Map<Token, Literal> literals = new IdentityHashMap<>();
    private final ExpressionStmtCompiler calculator;
    private boolean parameterDefault;
    private PhpConstantExpression(ExpressionStmtCompiler calculator) { this.calculator = calculator; }

    public static Memory checkAttribute(ExprStmtToken expression, ExpressionStmtCompiler calculator) {
        return check(expression,calculator,true);
    }
    public static Memory checkParameter(ExprStmtToken expression, ExpressionStmtCompiler calculator) {
        return check(expression,calculator,true);
    }
    public static Memory check(ExprStmtToken expression, ExpressionStmtCompiler calculator) {
        return check(expression,calculator,false);
    }
    private static Memory check(ExprStmtToken expression, ExpressionStmtCompiler calculator, boolean parameterDefault) {
        PhpConstantExpression validator = new PhpConstantExpression(calculator);
        validator.parameterDefault=parameterDefault;
        Result result = validator.expression(expression);
        if (result.error != null) calculator.getCompiler().getEnvironment().error(
                result.at.toTraceInfo(calculator.getCompiler().getContext()), ErrorType.E_COMPILE_ERROR, result.error);
        return validator.known(expression.getAsmExpr().getTokens(), result, expression.getAsmExpr().getTokens().size());
    }

    private Result expression(ExprStmtToken expression) {
        List<Token> tokens = expression.getAsmExpr().getTokens();
        Deque<Result> stack = new ArrayDeque<>();
        for (int i=0; i<tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token instanceof OperatorExprToken operator) {
                Result right = operator.isBinary() ? stack.pop() : null;
                Result left = stack.pop();
                Result result;
                if (left.error != null) result = left;
                else if (operator instanceof PipeExprToken) result = Result.invalid(token, INVALID, left.start);
                else if (operator instanceof LogicOperatorExprToken logic) {
                    Memory value = known(tokens, left, i);
                    boolean and = operator instanceof BooleanAndExprToken || operator instanceof BooleanAnd2ExprToken;
                    if (value != null && (and ? !value.toBoolean() : value.toBoolean())) result = left;
                    else result = combine(left, expression(logic.getRightValue()));
                } else if (operator instanceof ValueIfElseToken conditional) {
                    Memory value = known(tokens, left, i);
                    if (operator instanceof ValueNullCoalesceIfElseToken) {
                        result = value != null && !value.isNull() ? left : combine(left, expression(conditional.getAlternative()));
                    } else if (value != null) {
                        result = combine(left, value.toBoolean()
                                ? conditional.getValue() == null ? left : expression(conditional.getValue())
                                : expression(conditional.getAlternative()));
                    } else {
                        result = conditional.getValue() == null ? left : combine(left, expression(conditional.getValue()));
                        result = combine(result, expression(conditional.getAlternative()));
                    }
                } else if (operator instanceof StaticAccessOperatorExprToken access) {
                    result = combine(left, access(access.getOrigin(), i, known(tokens, left, i)));
                    Literal literal = literals.get(access.getOrigin());
                    if (literal != null) literals.put(token, new Literal(literal.token, left.start));
                } else if (operator.getClass() == ArrayGetExprToken.class) {
                    result = left;
                    for (ExprStmtToken key : ((ArrayGetExprToken) operator).getParameters()) result = combine(result, expression(key));
                } else if (operator.getClass() == DynamicAccessExprToken.class) {
                    DynamicAccessExprToken access = (DynamicAccessExprToken) operator;
                    result = combine(left, access.getFieldExpr() != null ? expression(access.getFieldExpr())
                            : access.getField() instanceof NameToken ? Result.valid(i, false) : Result.invalid(token, INVALID, i));
                } else if (operator instanceof ObjectCastExprToken) {
                    result = Result.invalid(token, "Object casts are not supported in this context", left.start);
                } else if (OPERATORS.contains(operator.getClass())) {
                    result = right == null ? left : combine(left, right);
                } else result = Result.invalid(token, INVALID, left.start);
                stack.push(result);
            } else stack.push(value(token, i));
        }
        return stack.pop();
    }

    private Result value(Token token, int index) {
        if (token instanceof CallExprToken call && call.isFirstClassCallable()) {
            Token name=call.getName();
            if (name instanceof NameToken) return Result.valid(index,false);
            if (name instanceof StaticAccessExprToken access) {
                if (!(access.getClazz() instanceof NameToken || access.getClazz() instanceof SelfExprToken
                        || access.getClazz() instanceof ParentExprToken))
                    return Result.invalid(token,"Cannot use dynamic class name in constant expression",index);
                if (!(access.getField() instanceof NameToken) || access.getFieldExpr() != null)
                    return Result.invalid(token,"Cannot use dynamic method name in constant expression",index);
                return Result.valid(index,false);
            }
            return Result.invalid(token,"Cannot use dynamic function name in constant expression",index);
        }
        if (token instanceof ClosureStmtToken closure) {
            if (closure.getFunction().isAutoUses()) return Result.invalid(token, INVALID, index);
            if (!closure.isStaticClosure()) return Result.invalid(token, "Closures in constant expressions must be static", index);
            if (!closure.getFunction().getUses().isEmpty()) return Result.invalid(token, "Cannot use(...) variables in constant expression", index);
            return Result.valid(index, false);
        }
        if (token instanceof ArrayExprToken array) {
            Result result = Result.valid(index, true);
            for (ExprStmtToken item : array.getParameters()) result = combine(result, expression(item));
            return new Result(result.at, result.error, index, result.foldable);
        }
        if (token instanceof StaticAccessExprToken access) return access(access, index, null);
        if (token instanceof NewExprToken creation) {
            if (!parameterDefault) return Result.invalid(token, "New expressions are not supported in this context", index);
            if (creation.getAnonymousClass() != null) return Result.invalid(token,"Cannot use anonymous class in constant expression",index);
            if (creation.isDynamic() || !(creation.getName() instanceof NameToken || creation.getName() instanceof SelfExprToken))
                return Result.invalid(token,"Cannot use dynamic class name in constant expression",index);
            for (ExprStmtToken argument : creation.getParameters()) {
                if (argument.getTokens().stream().anyMatch(t -> t instanceof ArgumentUnpackExprToken))
                    return Result.invalid(token,"Argument unpacking in constant expressions is not supported",index);
                Result value=expression(argument);if (value.error != null) return value;
            }
            return Result.valid(index,false);
        }
        if (token instanceof IntegerExprToken || token instanceof DoubleExprToken
                || token instanceof BooleanExprToken || token instanceof NullExprToken) return Result.valid(index, true);
        if (token instanceof StringExprToken string && string.getSegments().isEmpty()) return Result.valid(index, true);
        if (token instanceof MacroToken) return Result.valid(index, !(token instanceof ClassMacroToken
                && calculator.getMethod().clazz.getEntity().isTrait()));
        if (token instanceof NameToken name) return Result.valid(index,
                calculator.getCompiler().getScope().findCompileConstant(name.getName()) != null);
        return Result.invalid(token, INVALID, index);
    }

    private Result access(StaticAccessExprToken access, int index, Memory dynamicClass) {
        if (access.isGetStaticField()) return Result.invalid(access, INVALID, index);
        ValueExprToken clazz = access.getClazz();
        if (clazz instanceof StaticExprToken) return Result.invalid(access,
                access.getField() instanceof ClassExprToken ? "static::class cannot be used for compile-time class name resolution"
                        : "\"static::\" is not allowed in compile-time constants", index);
        if (!(clazz instanceof NameToken || clazz instanceof SelfExprToken || clazz instanceof ParentExprToken
                || clazz instanceof StringExprToken || clazz == null && dynamicClass != null && dynamicClass.isString()))
            return Result.invalid(access, "Dynamic class names are not allowed in compile-time class constant references", index);
        if (access.getFieldExpr() != null) {
            Result field = expression(access.getFieldExpr());
            return new Result(field.at, field.error, index, false);
        }
        Memory value = constantValue(access, calculator, dynamicClass);
        Token literal = value == null ? null : literal(value, access);
        if (literal != null) literals.put(access, new Literal(literal, -1));
        return Result.valid(index, value != null);
    }

    private static boolean sameClassName(String left, String right, ExpressionStmtCompiler calculator) {
        return calculator.getCompiler().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                ? PhpClassNames.canonicalKey(left).equals(PhpClassNames.canonicalKey(right)) : left.equalsIgnoreCase(right);
    }

    static Memory constantValue(StaticAccessExprToken access, ExpressionStmtCompiler calculator, Memory dynamicClass) {
        var owner = calculator.getMethod().clazz.getEntity();
        // A module constant has no lexical ClassEntity; retain its checked runtime initializer.
        if (owner == null) return null;
        var clazz = access.getClazz();
        php.runtime.reflection.ClassEntity target = clazz instanceof SelfExprToken ? owner
                : clazz instanceof ParentExprToken ? owner.getParent()
                : clazz instanceof NameToken ? sameClassName(owner.getName(), ((NameToken) clazz).getName(), calculator) ? owner
                    : calculator.getCompiler().getModule().findClass(((NameToken) clazz).getName())
                : null;
        if (clazz == null && dynamicClass != null) target = sameClassName(owner.getName(), dynamicClass.toString(), calculator) ? owner
                : calculator.getCompiler().getModule().findClass(dynamicClass.toString());
        if (access.getField() instanceof ClassExprToken) {
            if (clazz instanceof NameToken && !(clazz instanceof ParentExprToken))
                return php.runtime.memory.StringMemory.valueOf(((NameToken) clazz).getName());
            if (target != null && !owner.isTrait()) return php.runtime.memory.StringMemory.valueOf(target.getName());
            return null;
        }
        if (target == null || owner.isTrait() || !(access.getField() instanceof NameToken)) return null;
        var constant = target.findConstant(((NameToken) access.getField()).getName());
        if (constant == null || target != owner || constant.getClazz() != owner) return null;
        var trace = constant.getTrace();
        if (constant.getClazz() == owner && (trace.getStartLine() > access.getMeta().getStartLine()
                || trace.getStartLine() == access.getMeta().getStartLine()
                    && trace.getStartPosition() >= access.getMeta().getStartPosition())) return null;
        return constant.getValue();
    }

    private static Token literal(Memory value, Token source) {
        value = value.toValue();
        var meta = org.develnext.jphp.core.tokenizer.TokenMeta.of(value.toString(), source);
        return switch (value.type) {
            case INT -> new IntegerExprToken(meta, value.toLong());
            case DOUBLE -> new DoubleExprToken(meta, value.toDouble());
            case STRING -> new StringExprToken(meta, StringExprToken.Quote.SINGLE);
            case BOOL -> new BooleanExprToken(org.develnext.jphp.core.tokenizer.TokenMeta.of(value.toBoolean() ? "true" : "false", source));
            case NULL -> new NullExprToken(org.develnext.jphp.core.tokenizer.TokenMeta.of("null", source));
            default -> null;
        };
    }

    private static Result combine(Result left, Result right) {
        Result error = left.error != null ? left : right.error != null ? right : null;
        return error != null ? new Result(error.at, error.error, left.start, false)
                : Result.valid(left.start, left.foldable && right.foldable);
    }

    private Memory known(List<Token> tokens, Result result, int end) {
        if (!result.foldable || result.error != null) return null;
        List<Token> values = new ArrayList<>();
        Map<Integer, Integer> positions = new HashMap<>();
        for (int i=result.start; i<end; i++) {
            positions.put(i, values.size());
            Literal literal = literals.get(tokens.get(i));
            if (literal != null) {
                if (literal.baseStart >= result.start) values.subList(positions.get(literal.baseStart), values.size()).clear();
                values.add(literal.token);
            } else values.add(tokens.get(i));
        }
        ExprStmtToken prefix = new ExprStmtToken(null, null, values);
        prefix.setAsmExpr(prefix);
        return calculator.tryCalculateExpression(prefix);
    }
}
