package org.develnext.jphp.core.compiler.common.util;

import org.objectweb.asm.Opcodes;
import org.develnext.jphp.core.compiler.common.misc.StackItem;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.PhpByteStringMemory;
import java.nio.charset.StandardCharsets;
import php.runtime.Memory;
import php.runtime.PhpComparison;
import php.runtime.common.PhpLanguageProfile;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;

final public class CompilerUtils {

    private CompilerUtils(){ }

    protected static Memory toCallMemory(CallExprToken call, Memory... arguments){
        return null; // TODO
    }

    public static Memory toMemory(ValueExprToken value, Memory... arguments){
        if (value instanceof IntegerExprToken){
            return LongMemory.valueOf(((IntegerExprToken) value).getValue());
        } else if (value instanceof DoubleExprToken){
            return DoubleMemory.valueOf(((DoubleExprToken) value).getValue());
        } else if (value instanceof StringExprToken && !((StringExprToken) value).isBinary()){
            StringExprToken string = (StringExprToken) value;
            return string.hasRawBytes() ? new PhpByteStringMemory(string.getPhp85Bytes())
                    : StringMemory.valueOf(string.getValue());
        } else if (value instanceof BooleanExprToken){
            return ((BooleanExprToken) value).getValue() ? Memory.TRUE : Memory.FALSE;
        } else if (value instanceof NullExprToken){
            return Memory.NULL;
        } else if (value instanceof CallExprToken){
            return toCallMemory((CallExprToken)value, arguments);
        }

        return null;
    }

    /** Modern constant transport retains byte values even when a historical operator returns BinaryMemory. */
    public static Memory immutableConstant(Memory value, PhpLanguageProfile profile) {
        return profile==PhpLanguageProfile.PHP_8_5 && value instanceof php.runtime.memory.BinaryMemory
                ? new PhpByteStringMemory(value.getBinaryBytes(StandardCharsets.UTF_8)) : value;
    }

    public static Memory calcUnary(Environment env, TraceInfo trace, Memory o1, OperatorExprToken operator){
        return calcUnary(env, trace, o1, operator,
                env == null ? PhpLanguageProfile.JPHP_LEGACY : env.getScope().getPhpLanguageProfile());
    }

    public static Memory calcUnary(Environment env, TraceInfo trace, Memory o1, OperatorExprToken operator, PhpLanguageProfile profile){
        if (operator.isBinary())
            throw new IllegalArgumentException("Operator is not unary");

        if (profile == PhpLanguageProfile.PHP_8_5) {
            if(operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.StringCastExprToken) {
                if(o1.isArray() || o1.isObject() || o1.type==Memory.Type.DOUBLE && Double.isNaN(o1.toDouble()))return null;
                try{return php.runtime.PhpByteStrings.stringValue(o1,env,trace);}
                catch(Throwable failure){throw new IllegalStateException("Constant string conversion failed",failure);}
            }
            // Mutation and diagnostics must run against the actual target.
            if (operator instanceof IncExprToken || operator instanceof DecExprToken) return null;
            if (operator instanceof UnaryNumericExprToken) {
                if (!php.runtime.PhpArithmetic.canFoldUnary(o1)) return null;
                return php.runtime.PhpArithmetic.unary(o1, ((UnaryNumericExprToken) operator).isNegative(), env, trace);
            }
            if (operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.IntCastExprToken) {
                if (php.runtime.PhpNumericConversions.warnsInteger(o1)) return null;
                return LongMemory.valueOf(php.runtime.PhpNumericConversions.toLong(o1, env, trace));
            }
            if (operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.DoubleCastExprToken)
                return DoubleMemory.valueOf(php.runtime.PhpNumericConversions.toDouble(o1, env, trace));
        }
        return immutableConstant(operator.calc(env, trace, o1, null), profile);
    }

    public static Memory calcBinary(Environment env, TraceInfo trace, Memory o1, Memory o2, OperatorExprToken operator, boolean right){
        return calcBinary(env, trace, o1, o2, operator, right,
                env == null ? PhpLanguageProfile.JPHP_LEGACY : env.getScope().getPhpLanguageProfile());
    }

    public static Memory calcBinary(Environment env, TraceInfo trace, Memory o1, Memory o2, OperatorExprToken operator,
                                    boolean right, PhpLanguageProfile profile){
        if (!operator.isBinary())
            throw new IllegalArgumentException("Operator is not binary");

        if (right) {
            Memory o = o1;
            o1 = o2;
            o2 = o;
        }

        o1=immutableConstant(o1,profile);o2=immutableConstant(o2,profile);
        if (profile == PhpLanguageProfile.PHP_8_5 && "concat".equals(operator.getCode())
                && (o1 instanceof PhpByteStringMemory || o2 instanceof PhpByteStringMemory)) {
            if(o1.isArray() || o2.isArray() || o1.isObject() || o2.isObject()
                    || o1.type==Memory.Type.DOUBLE && Double.isNaN(o1.toDouble())
                    || o2.type==Memory.Type.DOUBLE && Double.isNaN(o2.toDouble()))return null;
            byte[] left;byte[] other;
            try {
                left=php.runtime.PhpByteStrings.stringValue(o1,env,trace).getBinaryBytes(StandardCharsets.UTF_8);
                other=php.runtime.PhpByteStrings.stringValue(o2,env,trace).getBinaryBytes(StandardCharsets.UTF_8);
            }catch(Throwable failure){throw new IllegalStateException("Scalar byte constant conversion failed",failure);}
            byte[] joined = java.util.Arrays.copyOf(left, Math.addExact(left.length, other.length));
            System.arraycopy(other, 0, joined, left.length, other.length);
            return new PhpByteStringMemory(joined);
        }

        if (profile == PhpLanguageProfile.PHP_8_5 && isPhpArithmetic(operator)) {
            String code = arithmeticCode(operator);
            return php.runtime.PhpArithmetic.canFold(code, o1, o2)
                    ? php.runtime.PhpArithmetic.binary(o1, o2, code, env, trace) : null;
        }
        if (profile == PhpLanguageProfile.PHP_8_5 && isPhpComparison(operator)) {
            switch (operator.getCode()) {
                case "equal": return php.runtime.memory.TrueMemory.valueOf(PhpComparison.equal(o1, o2, env, trace));
                case "notEqual": return php.runtime.memory.TrueMemory.valueOf(PhpComparison.notEqual(o1, o2, env, trace));
                case "smaller": return php.runtime.memory.TrueMemory.valueOf(PhpComparison.smaller(o1, o2, env, trace));
                case "smallerEq": return php.runtime.memory.TrueMemory.valueOf(PhpComparison.smallerEq(o1, o2, env, trace));
                case "greater": return php.runtime.memory.TrueMemory.valueOf(PhpComparison.greater(o1, o2, env, trace));
                case "greaterEq": return php.runtime.memory.TrueMemory.valueOf(PhpComparison.greaterEq(o1, o2, env, trace));
                case "spaceshipCompare": return LongMemory.valueOf(PhpComparison.spaceshipCompare(o1, o2, env, trace));
            }
        }
        return immutableConstant(operator.calc(env, trace, o1, o2), profile);
    }

    public static boolean isPhpComparison(OperatorExprToken operator) {
        return operator instanceof EqualExprToken || operator instanceof BooleanNotEqualExprToken
                || operator instanceof SmallerExprToken || operator instanceof SmallerOrEqualToken
                || operator instanceof GreaterExprToken || operator instanceof GreaterOrEqualExprToken
                || operator instanceof SpaceshipExprToken;
    }

    public static boolean isPhpArithmetic(OperatorExprToken operator) {
        return operator instanceof PlusExprToken || operator instanceof MinusExprToken || operator instanceof MulExprToken
                || operator instanceof DivExprToken || operator instanceof ModExprToken || operator instanceof PowExprToken
                || operator instanceof AssignPlusExprToken || operator instanceof AssignMinusExprToken
                || operator instanceof AssignMulExprToken || operator instanceof AssignDivExprToken
                || operator instanceof AssignModExprToken || operator instanceof AssignPowExprToken;
    }

    public static String arithmeticCode(OperatorExprToken operator) {
        return operator instanceof AssignOperatorExprToken ? ((AssignOperatorExprToken) operator).getOperatorCode() : operator.getCode();
    }

    public static int getOperatorOpcode(OperatorExprToken operator, StackItem.Type type){
        if (operator instanceof PlusExprToken){
            switch (type){
                case DOUBLE: return Opcodes.DADD;
                case FLOAT: return Opcodes.FADD;
                case LONG: return Opcodes.LADD;
                case BYTE:
                case SHORT:
                case INT: return Opcodes.IADD;
            }
        }

        if (operator instanceof MinusExprToken){
            switch (type){
                case DOUBLE: return Opcodes.DSUB;
                case FLOAT: return Opcodes.FSUB;
                case LONG: return Opcodes.LSUB;
                case BYTE:
                case SHORT:
                case INT: return Opcodes.ISUB;
            }
        }

        if (operator instanceof MulExprToken){
            switch (type){
                case DOUBLE: return Opcodes.DMUL;
                case FLOAT: return Opcodes.FMUL;
                case LONG: return Opcodes.LMUL;
                case BYTE:
                case SHORT:
                case INT: return Opcodes.IMUL;
            }
        }

        throw new IllegalArgumentException("Unknown operator " + operator.getWord() + " for type " + type.name());
    }

    public static boolean isOperatorAlwaysReturn(OperatorExprToken operator) {
        return operator instanceof KeyValueExprToken;

    }
}
