package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.PhpByteStringMemory;

/** Compiler interpolation union: one StringBuilder until the first actual raw byte operand. */
public final class PhpByteStringBuilders {
    private PhpByteStringBuilders() {}

    public static Object append(Object builder, String value, Environment env, TraceInfo trace) {
        if (builder instanceof StringBuilder text) {
            if (value != null) text.append(value);
        } else bytes(builder).append(value);
        return builder;
    }

    public static Object append(Object builder, char value, Environment env, TraceInfo trace) {
        if (builder instanceof StringBuilder text) text.append(value);
        else bytes(builder).append(String.valueOf(value));
        return builder;
    }

    public static Object append(Object builder, long value, Environment env, TraceInfo trace) {
        if (builder instanceof StringBuilder text) text.append(value);
        else bytes(builder).append(Long.toString(value));
        return builder;
    }

    public static Object append(Object builder, boolean value, Environment env, TraceInfo trace) {
        if (value) return append(builder, '1', env, trace);
        validate(builder);
        return builder;
    }

    public static Object append(Object builder, double value, Environment env, TraceInfo trace) {
        // No DoubleMemory boxing or transient StringMemory is needed for a primitive append.
        String converted = PhpByteStrings.floatString(value, env);
        if (env != null && Double.isNaN(value))
            env.error(trace, ErrorType.E_WARNING, "unexpected NAN value was coerced to string");
        return append(builder, converted, env, trace);
    }

    public static Object append(Object builder, Memory value, Environment env, TraceInfo trace) throws Throwable {
        value = value.toValue();
        if (value.isString()) {
            if (value instanceof PhpByteStringMemory raw) return appendRaw(builder, raw);
            if (value instanceof BinaryMemory)
                return appendRaw(builder, (PhpByteStringMemory)PhpByteStrings.stringValue(value, env, trace));
            return append(builder, value.toString(), env, trace);
        }
        if (value.type == Memory.Type.INT) return append(builder, value.toLong(), env, trace);
        if (value.type == Memory.Type.DOUBLE) return append(builder, value.toDouble(), env, trace);
        if (value.type == Memory.Type.BOOL) return append(builder, value.toBoolean(), env, trace);
        if (value.isNull()) { validate(builder); return builder; }
        if (value.isArray()) {
            if (env == null) throw new IllegalArgumentException("Array string conversion needs its Environment");
            env.error(trace, ErrorType.E_WARNING, "Array to string conversion");
            return append(builder, "Array", env, trace);
        }
        if (value.isResource()) return append(builder, "Resource id #" + value.getPointer(), env, trace);
        // Preserve the actual converted Memory; no second __toString or scalar return conversion.
        Memory converted = PhpByteStrings.stringValue(value, env, trace);
        return converted instanceof PhpByteStringMemory raw ? appendRaw(builder, raw)
                : append(builder, converted.toString(), env, trace);
    }

    private static Object appendRaw(Object builder, PhpByteStringMemory value) {
        PhpByteStringBuilder raw = builder instanceof StringBuilder text
                ? new PhpByteStringBuilder(text) : bytes(builder);
        raw.appendStringValue(value);
        return raw;
    }

    /** The normal path returns Java String directly, without an intermediate StringMemory. */
    public static Object finishObject(Object builder) {
        if (builder instanceof StringBuilder text) return text.toString();
        Memory result = bytes(builder).toImmutable();
        return result instanceof PhpByteStringMemory ? result : result.toString();
    }

    private static void validate(Object builder) {
        if (!(builder instanceof StringBuilder)) bytes(builder);
    }

    private static PhpByteStringBuilder bytes(Object builder) {
        if (!(builder instanceof PhpByteStringBuilder))
            throw new IllegalArgumentException("String builder union requires its exact text or byte carrier");
        return (PhpByteStringBuilder)builder;
    }
}
