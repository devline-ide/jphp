package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;

/** PHP 8 conversions. Explicit string casts saturate; float casts retain low 64 bits. */
public final class PhpNumericConversions {
    private PhpNumericConversions() {}

    public static Memory checkedScalarCoercion(Memory original, Memory converted, Environment env, TraceInfo trace) {
        if (original.getRealType() != Memory.Type.DOUBLE) return converted;
        double number = original.toDouble();
        if (Double.isNaN(number) && (converted.isString() || converted.getRealType() == Memory.Type.BOOL))
            env.error(trace, ErrorType.E_WARNING, "unexpected NAN value was coerced to %s", converted.isString() ? "string" : "bool");
        if (converted.isString() && !Double.isFinite(number))
            return php.runtime.memory.StringMemory.valueOf(Double.isNaN(number) ? "NAN" : number < 0 ? "-INF" : "INF");
        return converted;
    }

    public static void warnIntegerPrecisionLoss(Memory original, Memory number, Environment env, TraceInfo trace) {
        number = number.toValue();
        if (number.type == Memory.Type.DOUBLE && number.toDouble() != (double) floatToLong(number.toDouble()))
            env.error(trace, ErrorType.E_DEPRECATED,
                    original.isString() ? "Implicit conversion from float-string \"%s\" to int loses precision"
                            : "Implicit conversion from float %s to int loses precision", original.toString());
    }

    public static boolean warnsInteger(Memory value) {
        value = value.toValue();
        return value.isObject() && !value.isResource() || value.type == Memory.Type.DOUBLE && !representable(value.toDouble());
    }

    private static boolean representable(double value) { return value >= -0x1p63 && value < 0x1p63; }

    public static long toLong(Memory value, Environment env, TraceInfo trace) {
        value = value.toValue();
        if (value.isString()) {
            Memory number = PhpNumericString.parseLeading(value.toString());
            if (number.type == Memory.Type.INT) return number.toLong();
            double real = number.toDouble();
            return Double.isFinite(real) ? (long) real : 0;
        }
        if (value.type == Memory.Type.DOUBLE) {
            double real = value.toDouble();
            if (!representable(real)) env.error(trace, ErrorType.E_WARNING,
                    "The float %s is not representable as an int, cast occurred", value.toString());
            return floatToLong(real);
        }
        if (value.isArray()) return value.toValue(ArrayMemory.class).size() == 0 ? 0 : 1;
        if (value.isResource()) return value.getPointer();
        if (value.isObject()) { objectWarning(value, env, trace, "int"); return 1; }
        return value.toLong();
    }

    public static double toDouble(Memory value, Environment env, TraceInfo trace) {
        value = value.toValue();
        if (value.isString()) return PhpNumericString.leadingDouble(value.toString());
        if (value.isArray()) return value.toValue(ArrayMemory.class).size() == 0 ? 0 : 1;
        if (value.isResource()) return value.getPointer();
        if (value.isObject()) { objectWarning(value, env, trace, "float"); return 1; }
        return value.toDouble();
    }

    private static void objectWarning(Memory value, Environment env, TraceInfo trace, String type) {
        env.error(trace, ErrorType.E_WARNING, "Object of class %s could not be converted to %s",
                value.toValue(ObjectMemory.class).getReflection().getName(), type);
    }

    public static long floatToLong(double value) {
        if (!Double.isFinite(value)) return 0;
        long bits = Double.doubleToRawLongBits(value);
        int exponent = (int) ((bits >>> 52) & 0x7ff) - 1023;
        if (exponent < 0 || exponent >= 116) return 0;
        long significand = (bits & 0x000fffffffffffffL) | (1L << 52);
        long integer = exponent <= 52 ? significand >>> (52 - exponent) : significand << (exponent - 52);
        return bits < 0 ? -integer : integer;
    }
}
