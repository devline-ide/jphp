package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.PhpByteStringMemory;
import php.runtime.memory.StringMemory;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/** Explicit PHP85 byte-aware conversions. Historical Java String concat signatures remain unchanged. */
public final class PhpByteStrings {
    private PhpByteStrings() {}

    public static String numericText(Memory value) {
        return value instanceof PhpByteStringMemory raw ? raw.toBinaryString() : value.toString();
    }

    public static Memory stringValue(Memory value, Environment env, TraceInfo trace) throws Throwable {
        value = value.toValue();
        if (value.isString()) {
            if (value instanceof BinaryMemory) return new PhpByteStringMemory(value.getBinaryBytes(StandardCharsets.UTF_8));
            return value.toImmutable();
        }
        if (value.isArray()) {
            Objects.requireNonNull(env, "Array string conversion needs its Environment");
            env.error(trace, ErrorType.E_WARNING, "Array to string conversion");
            return StringMemory.valueOf("Array");
        }
        if (value.isResource()) return StringMemory.valueOf("Resource id #" + value.getPointer());
        if (value.isObject()) {
            ObjectMemory object = value.toValue(ObjectMemory.class);
            // Memory's context-free comparison signatures still carry the object's owning Environment.
            if (env == null) env = object.value.getEnvironment();
            Objects.requireNonNull(env, "Object string conversion needs its Environment");
            var method = object.getReflection().methodMagicToString;
            if (method == null) {
                env.exception(trace, BaseError.class, "Object of class %s could not be converted to string", object.getReflection().getName());
                return Memory.CONST_EMPTY_STRING;
            }
            var session = PhpOwnershipExecution.current(env);
            if (session == null) {
                if (PhpRuntimeOwnership.current(env) != null)
                    throw new IllegalArgumentException("Connected Stringable conversion requires its exact callback envelope");
                return stringResult(ObjectInvokeHelper.invokeMethod(object.value, method, env, trace, null, false), method, env, trace);
            }
            try (var roots = session.newCallRoots()) {
                roots.stageReceiver(session.value(value));
                return stringResult(session.invokeCheckedMethod(method, object.value, trace, roots, false), method, env, trace);
            }
        }
        if (value.type == Memory.Type.DOUBLE) {
            Memory converted = StringMemory.valueOf(floatString(value.toDouble(), env));
            return env == null ? converted : PhpNumericConversions.checkedScalarCoercion(value, converted, env, trace);
        }
        return StringMemory.valueOf(value.toString());
    }

    private static Memory stringResult(Memory result, php.runtime.reflection.MethodEntity method, Environment env, TraceInfo trace) {
        result = result.toValue();
        if (!result.isString()) {
            String type = result.type == Memory.Type.INT ? "int" : result.type == Memory.Type.BOOL ? "bool"
                    : result.isNull() ? "null" : result.isObject() ? result.toValue(ObjectMemory.class).getReflection().getName() : result.type.toString();
            env.exception(trace, BaseTypeError.class, "%s::__toString(): Return value must be of type string, %s returned", method.getClazzName(), type);
            return Memory.CONST_EMPTY_STRING;
        }
        return result instanceof BinaryMemory ? new PhpByteStringMemory(result.getBinaryBytes(StandardCharsets.UTF_8)) : result.toImmutable();
    }

    static Memory comparisonStringValue(Memory value, Environment env, TraceInfo trace) {
        try { return stringValue(value, env, trace); }
        catch (Throwable failure) { PhpByteStrings.<RuntimeException>raise(failure); return Memory.NULL; }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raise(Throwable failure) throws T { throw (T)failure; }

    public static Memory concat(Memory left, Memory right, Environment env, TraceInfo trace) throws Throwable {
        // Conversion is ordered. A right reference remains live across the left conversion callback.
        Memory a = stringValue(left, env, trace);
        Memory b = stringValue(right, env, trace);
        if (!(a instanceof PhpByteStringMemory) && !(b instanceof PhpByteStringMemory))
            return StringMemory.valueOf(a.toString().concat(b.toString()));
        return new PhpByteStringBuilder().appendStringValue(a).appendStringValue(b).toImmutable();
    }

    /** Keep legacy scalar coercion; only canonical byte operands select the byte builder. */
    public static Memory concatLegacy(Memory left, Memory right) {
        if (!(left.toValue() instanceof PhpByteStringMemory) && !(right.toValue() instanceof PhpByteStringMemory))
            return StringMemory.valueOf(left.concat(right));
        Memory a = left.toValue();
        if (!(a instanceof PhpByteStringMemory)) a = StringMemory.valueOf(left.toString());
        // A right reference is read after the left object's historical string conversion.
        Memory b = right.toValue();
        if (!(b instanceof PhpByteStringMemory)) b = StringMemory.valueOf(right.toString());
        return new PhpByteStringBuilder().appendStringValue(a).appendStringValue(b).toImmutable();
    }

    public static Memory assignConcatLegacy(Memory left, Memory right) {
        return left.assignConcatLegacy(right);
    }

    /** Preserve ordinary virtual operator/type/readonly behavior outside an actual overloaded address. */
    public static Memory assignConcatLegacyDefault(Memory left, Memory right) {
        if (!(left.toValue() instanceof PhpByteStringMemory) && !(right.toValue() instanceof PhpByteStringMemory))
            return left.assignConcat(right);
        return left.assign(concatLegacy(left, right));
    }

    /** The ArrayAccess address has already performed its one offsetGet. Convert left before reading right. */
    public static Memory concatLegacyArrayAccess(Memory leftValue, Memory right) {
        Memory a = leftValue.toValue();
        if (!(a instanceof PhpByteStringMemory)) a = StringMemory.valueOf(a.toString());
        Memory b = right.toValue();
        if (!(b instanceof PhpByteStringMemory)) b = StringMemory.valueOf(b.toString());
        if (!(a instanceof PhpByteStringMemory) && !(b instanceof PhpByteStringMemory))
            return StringMemory.valueOf(a.toString().concat(b.toString()));
        return new PhpByteStringBuilder().appendStringValue(a).appendStringValue(b).toImmutable();
    }

    /** PHP reads use byte offsets even for ordinary UTF8 StringMemory values. */
    public static Memory offset(Memory string, Memory index, Environment env, TraceInfo trace) {
        string = string.toValue();
        if (!string.isString()) throw new IllegalArgumentException("Byte offset needs a string value");
        index = index.toValue();
        long offset;
        if (index.isString()) {
            String spelling = numericText(index);
            Memory number = PhpNumericString.parseStrict(spelling);
            if (number != null && number.type == Memory.Type.INT) offset = number.toLong();
            else {
                Memory leading = PhpNumericString.parseLeading(spelling);
                if (number == null && PhpNumericString.hasLeadingNumber(spelling) && leading.type == Memory.Type.INT) {
                    if (env != null) env.error(trace, ErrorType.E_WARNING, "Illegal string offset \"%s\"", spelling);
                    offset = leading.toLong();
                } else return invalidOffset(index, env, trace);
            }
        } else if (index.type == Memory.Type.INT) offset = index.toLong();
        else if (index.type == Memory.Type.DOUBLE || index.type == Memory.Type.BOOL || index.isNull()) {
            if (env != null) env.error(trace, ErrorType.E_WARNING, "String offset cast occurred");
            offset = index.type == Memory.Type.DOUBLE ? PhpNumericConversions.floatToLong(index.toDouble()) : index.toLong();
        } else return invalidOffset(index, env, trace);
        PhpByteStringMemory raw = string instanceof PhpByteStringMemory bytes ? bytes
                : new PhpByteStringMemory(string.getBinaryBytes(StandardCharsets.UTF_8));
        long position = offset < 0 ? raw.byteLength() + offset : offset;
        if (position < 0 || position >= raw.byteLength()) {
            if (env != null) env.error(trace, ErrorType.E_WARNING, "Uninitialized string offset %s", offset);
            return Memory.CONST_EMPTY_STRING;
        }
        return raw.byteAt(offset);
    }

    private static Memory invalidOffset(Memory index, Environment env, TraceInfo trace) {
        String type = index.isObject() && !index.isResource() ? index.toValue(ObjectMemory.class).getReflection().getName() : index.type.toString();
        if (env == null) throw new IllegalArgumentException("Cannot access offset of type " + type + " on string");
        env.exception(trace, BaseTypeError.class, "Cannot access offset of type %s on string", type);
        return Memory.CONST_EMPTY_STRING;
    }

    /** Runtime precision is read after operand evaluation; constant folding supplies its compile environment. */
    static String floatString(double value, Environment env) {
        if (Double.isNaN(value)) return "NAN";
        if (value == Double.POSITIVE_INFINITY) return "INF";
        if (value == Double.NEGATIVE_INFINITY) return "-INF";
        if (Double.doubleToRawLongBits(value) == Long.MIN_VALUE) return "-0";
        int precision = env == null ? 14 : env.getConfigValue("precision", LongMemory.valueOf(14)).toInteger();
        int digits = precision < 0 ? 17 : Math.max(1, Math.min(precision, 100));
        // Formatting the shortest decimal first rounds 2.675 incorrectly at precision=3.
        BigDecimal number = (precision < 0 ? BigDecimal.valueOf(value)
                : new BigDecimal(value).round(new MathContext(digits, RoundingMode.HALF_EVEN))).stripTrailingZeros();
        int exponent = number.precision() - number.scale() - 1;
        if (exponent >= -4 && exponent < digits) return number.toPlainString();
        String mantissa = number.movePointLeft(exponent).toPlainString();
        if (mantissa.indexOf('.') < 0) mantissa += ".0";
        return mantissa + "E" + (exponent >= 0 ? "+" : "") + exponent;
    }
}
