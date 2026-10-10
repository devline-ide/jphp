package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.support.IComparableObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;

/** PHP 8 comparisons, also used by constant folding and compiled switch. */
public final class PhpComparison {
    private static final int UNORDERED = 2;

    private PhpComparison() {}

    public static boolean equal(Memory a, Memory b, Environment env, TraceInfo trace) {
        return compare(a, b, env, trace, null) == 0;
    }

    public static boolean notEqual(Memory a, Memory b, Environment env, TraceInfo trace) {
        return !equal(a, b, env, trace);
    }

    public static boolean smaller(Memory a, Memory b, Environment env, TraceInfo trace) {
        return compare(a, b, env, trace, null) == -1;
    }

    public static boolean smallerEq(Memory a, Memory b, Environment env, TraceInfo trace) {
        int result = compare(a, b, env, trace, null);
        return result == -1 || result == 0;
    }

    public static boolean greater(Memory a, Memory b, Environment env, TraceInfo trace) {
        return compare(a, b, env, trace, null) == 1;
    }

    public static boolean greaterEq(Memory a, Memory b, Environment env, TraceInfo trace) {
        int result = compare(a, b, env, trace, null);
        return result == 1 || result == 0;
    }

    public static long spaceshipCompare(Memory a, Memory b, Environment env, TraceInfo trace) {
        int result = compare(a, b, env, trace, null);
        return result == UNORDERED ? 1 : result;
    }

    private static int compare(Memory a, Memory b, Environment env, TraceInfo trace,
                               IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> active) {
        a = a.toValue();
        b = b.toValue();
        if (a.type == Memory.Type.BOOL || b.type == Memory.Type.BOOL) return Boolean.compare(a.toBoolean(), b.toBoolean());
        if (a.isNull() || b.isNull()) {
            if (a.isString()) return lexical(a, Memory.CONST_EMPTY_STRING);
            if (b.isString()) return lexical(Memory.CONST_EMPTY_STRING, b);
            return Boolean.compare(a.toBoolean(), b.toBoolean());
        }

        if (a.isArray() || b.isArray()) {
            if (a.isArray() && b.isArray()) {
                return arrays(a.toValue(ArrayMemory.class), b.toValue(ArrayMemory.class), env, trace, active);
            }
            return a.isArray() ? (b.isObject() && !b.isResource() ? -1 : 1)
                    : (a.isObject() && !a.isResource() ? 1 : -1);
        }

        if (a.isResource() || b.isResource()) {
            Memory left = a.isResource() ? LongMemory.valueOf(a.getPointer()) : a;
            Memory right = b.isResource() ? LongMemory.valueOf(b.getPointer()) : b;
            if (left.isObject() || right.isObject()) return left.isObject() ? 1 : -1;
            return numeric(left.toNumeric(), right.toNumeric());
        }

        if (a.isObject() || b.isObject()) {
            if (a.isObject() && b.isObject()) {
                ObjectMemory left = a.toValue(ObjectMemory.class);
                ObjectMemory right = b.toValue(ObjectMemory.class);
                if (left.value == right.value) return 0;
                if (left.getReflection().getId() != right.getReflection().getId()) return UNORDERED;
                if (left.value instanceof IComparableObject) {
                    if (a.equal(b)) return 0;
                    if (a.smaller(b)) return -1;
                    if (a.greater(b)) return 1;
                    return UNORDERED;
                }
                return arrays(left.getProperties(), right.getProperties(), env, trace, active);
            }
            boolean leftObject = a.isObject();
            ObjectMemory object = (leftObject ? a : b).toValue(ObjectMemory.class);
            Memory scalar = leftObject ? b : a;
            if (scalar.isString()) {
                if (object.getReflection().methodMagicToString == null) return leftObject ? 1 : -1;
                Memory string = StringMemory.valueOf(object.toString());
                return leftObject ? compare(string, scalar, env, trace, active)
                        : compare(scalar, string, env, trace, active);
            }
            env.error(trace, ErrorType.E_WARNING, "Object of class %s could not be converted to %s",
                    object.getReflection().getName(), scalar.type == Memory.Type.DOUBLE ? "float" : "int");
            return leftObject ? numeric(Memory.CONST_INT_1, scalar) : numeric(scalar, Memory.CONST_INT_1);
        }

        boolean leftNumber = a.isNumber();
        boolean rightNumber = b.isNumber();
        if (leftNumber && rightNumber) return numeric(a, b);
        if (a.isString() && b.isString()) {
            Memory left = StringMemory.toNumericStrict(a.toString());
            Memory right = StringMemory.toNumericStrict(b.toString());
            if (left != null && right != null) {
                // Infinite numeric strings need the textual fallback used by Zend.
                if (left.type == Memory.Type.DOUBLE && right.type == Memory.Type.DOUBLE && !Double.isFinite(left.toDouble())
                        && left.toDouble() == right.toDouble()) return lexical(a, b);
                return numeric(left, right);
            }
            return lexical(a, b);
        }
        if (leftNumber && b.isString()) return numberString(a, b, env);
        if (a.isString() && rightNumber) {
            int result = numberString(b, a, env);
            return result == UNORDERED ? UNORDERED : -result;
        }
        if (a.equal(b)) return 0;
        if (a.smaller(b)) return -1;
        if (a.greater(b)) return 1;
        return UNORDERED;
    }

    private static int numeric(Memory a, Memory b) {
        if (a.type == Memory.Type.INT && b.type == Memory.Type.INT) return Long.compare(a.toLong(), b.toLong());
        double left = a.toDouble();
        double right = b.toDouble();
        if (Double.isNaN(left) || Double.isNaN(right)) return UNORDERED;
        return left == right ? 0 : left < right ? -1 : 1;
    }

    private static int numberString(Memory number, Memory string, Environment env) {
        if (number.type == Memory.Type.DOUBLE && Double.isNaN(number.toDouble())) return UNORDERED;
        Memory numeric = StringMemory.toNumericStrict(string.toString());
        if (numeric != null) return numeric(number, numeric);
        return lexical(StringMemory.valueOf(number.type == Memory.Type.INT ? number.toString()
                : floatString(number.toDouble(), env)), string);
    }

    private static int lexical(Memory a, Memory b) {
        return Integer.signum(Arrays.compareUnsigned(a.getBinaryBytes(StandardCharsets.UTF_8),
                b.getBinaryBytes(StandardCharsets.UTF_8)));
    }

    private static String floatString(double value, Environment env) {
        if (value == Double.POSITIVE_INFINITY) return "INF";
        if (value == Double.NEGATIVE_INFINITY) return "-INF";
        int precision = env.getConfigValue("precision", LongMemory.valueOf(14)).toInteger();
        String text = precision < 0 ? Double.toString(value)
                : String.format(Locale.ROOT, "%." + Math.max(1, Math.min(precision, 100)) + "G", value);
        int exponent = text.indexOf('E');
        String mantissa = exponent < 0 ? text : text.substring(0, exponent);
        if (mantissa.indexOf('.') >= 0) {
            while (mantissa.endsWith("0")) mantissa = mantissa.substring(0, mantissa.length() - 1);
            if (mantissa.endsWith(".")) mantissa = mantissa.substring(0, mantissa.length() - 1);
        }
        if (exponent < 0) return mantissa;
        if (mantissa.indexOf('.') < 0) mantissa += ".0";
        int power = Integer.parseInt(text.substring(exponent + 1));
        return mantissa + "E" + (power >= 0 ? "+" : "") + power;
    }

    private static int arrays(ArrayMemory a, ArrayMemory b, Environment env, TraceInfo trace,
                              IdentityHashMap<Object, IdentityHashMap<Object, Boolean>> active) {
        if (a == b) return 0;
        if (a.size() != b.size()) return Integer.compare(a.size(), b.size());
        if (active == null) active = new IdentityHashMap<>();
        IdentityHashMap<Object, Boolean> partners = active.computeIfAbsent(a, key -> new IdentityHashMap<>());
        if (partners.put(b, Boolean.TRUE) != null) {
            env.exception(trace, BaseError.class, "Nesting level too deep - recursive dependency?");
            return UNORDERED;
        }
        try {
            ForeachIterator iterator = a.foreachIterator(false, false);
            while (iterator.next()) {
                Memory other = b.getByScalar(iterator.getKey());
                if (other == null) return UNORDERED;
                int result = compare(iterator.getValue(), other, env, trace, active);
                if (result != 0) return result;
            }
            return 0;
        } finally {
            partners.remove(b);
            if (partners.isEmpty()) active.remove(a);
        }
    }
}
