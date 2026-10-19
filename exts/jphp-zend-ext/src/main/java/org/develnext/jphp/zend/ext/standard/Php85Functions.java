package org.develnext.jphp.zend.ext.standard;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.Arg;
import php.runtime.annotation.Reflection.Signature;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.lang.exception.BaseArgumentCountError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standard PHP APIs with explicit PHP signatures and byte-oriented string semantics. */
public class Php85Functions extends FunctionsContainer {
    @Signature(value = {@Arg(value = "haystack", type = HintType.STRING), @Arg(value = "needle", type = HintType.STRING)},
            result = @Arg(type = HintType.BOOLEAN))
    @php.runtime.annotation.Runtime.Frameless({2})
    public static boolean str_contains(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) throws Throwable {
        byte[][] strings = strings(env, trace, "str_contains", args);
        byte[] haystack = strings[0], needle = strings[1];
        if (needle.length == 0) return true;
        if (needle.length > haystack.length) return false;
        // KMP bounds repeated-prefix inputs to O(haystack + needle).
        int[] prefix = new int[needle.length];
        for (int i = 1, matched = 0; i < needle.length; i++) {
            while (matched > 0 && needle[i] != needle[matched]) matched = prefix[matched - 1];
            if (needle[i] == needle[matched]) matched++;
            prefix[i] = matched;
        }
        for (int i = 0, matched = 0; i < haystack.length; i++) {
            while (matched > 0 && haystack[i] != needle[matched]) matched = prefix[matched - 1];
            if (haystack[i] == needle[matched] && ++matched == needle.length) return true;
        }
        return false;
    }

    @Signature(value = {@Arg(value = "haystack", type = HintType.STRING), @Arg(value = "needle", type = HintType.STRING)},
            result = @Arg(type = HintType.BOOLEAN))
    @php.runtime.annotation.Runtime.Frameless({2})
    public static boolean str_starts_with(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) throws Throwable {
        byte[][] strings = strings(env, trace, "str_starts_with", args);
        return strings[1].length <= strings[0].length
                && Arrays.equals(strings[0], 0, strings[1].length, strings[1], 0, strings[1].length);
    }

    @Signature(value = {@Arg(value = "haystack", type = HintType.STRING), @Arg(value = "needle", type = HintType.STRING)},
            result = @Arg(type = HintType.BOOLEAN))
    public static boolean str_ends_with(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) throws Throwable {
        byte[][] strings = strings(env, trace, "str_ends_with", args);
        return strings[1].length <= strings[0].length && Arrays.equals(strings[0],
                strings[0].length - strings[1].length, strings[0].length, strings[1], 0, strings[1].length);
    }

    @Signature(value = @Arg(value = "string", type = HintType.STRING), result = @Arg(type = HintType.STRING))
    public static Memory str_increment(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) throws Throwable {
        return stringStep(env, trace, "str_increment", args, false);
    }

    @Signature(value = @Arg(value = "string", type = HintType.STRING), result = @Arg(type = HintType.STRING))
    public static Memory str_decrement(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) throws Throwable {
        return stringStep(env, trace, "str_decrement", args, true);
    }

    private static Memory stringStep(Environment env, TraceInfo trace, String function, Memory[] args, boolean decrement) throws Throwable {
        arity(env, trace, function, args, 1);
        byte[] value = string(env, trace, function, 1, "string", args[0]);
        if (value.length == 0) env.exception(trace, php.runtime.lang.exception.BaseValueError.class,
                "%s(): Argument #1 ($string) must not be empty", function);
        for (byte digit : value) {
            if (!(digit >= '0' && digit <= '9' || digit >= 'A' && digit <= 'Z' || digit >= 'a' && digit <= 'z')) {
                env.exception(trace, php.runtime.lang.exception.BaseValueError.class,
                        "%s(): Argument #1 ($string) must be composed only of alphanumeric ASCII characters", function);
            }
        }
        // Leading zeroes have no predecessor, even when a later digit could decrease.
        String original = decrement ? new String(value, StandardCharsets.US_ASCII) : null;
        if (decrement && value[0] == '0') decrementRangeError(env, trace, original);
        byte[] result = value.clone();
        boolean carry = true;
        for (int i = result.length - 1; i >= 0 && carry; i--) {
            byte digit = result[i];
            byte first = (byte) (digit <= '9' ? '0' : digit <= 'Z' ? 'A' : 'a');
            byte last = (byte) (digit <= '9' ? '9' : digit <= 'Z' ? 'Z' : 'z');
            carry = digit == (decrement ? first : last);
            result[i] = carry ? (decrement ? last : first) : (byte) (digit + (decrement ? -1 : 1));
        }
        if (decrement && (carry || result.length > 1 && result[0] == '0')) {
            if (result.length == 1) decrementRangeError(env, trace, original);
            return php.runtime.memory.StringMemory.valueOf(new String(result, 1, result.length - 1, StandardCharsets.US_ASCII));
        }
        String text = new String(result, StandardCharsets.US_ASCII);
        if (!decrement && carry) text = (result[0] == '0' ? "1" : Character.toString((char) result[0])) + text;
        return php.runtime.memory.StringMemory.valueOf(text);
    }

    private static void decrementRangeError(Environment env, TraceInfo trace, String original) {
        env.exception(trace, php.runtime.lang.exception.BaseValueError.class,
                "str_decrement(): Argument #1 ($string) \"%s\" is out of decrement range", original);
    }

    @Signature(value = @Arg(value = "array", type = HintType.ARRAY, readOnly = true), result = @Arg(type = HintType.BOOLEAN))
    public static boolean array_is_list(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) {
        ArrayMemory array = array(env, trace, "array_is_list", args);
        var iterator = array.getNewIterator(env);
        long index = 0;
        while (iterator.next()) {
            Memory key = iterator.getMemoryKey();
            if (key.getRealType() != Memory.Type.INT || key.toLong() != index++) return false;
        }
        return true;
    }

    @Signature(value = @Arg(value = "array", type = HintType.ARRAY, readOnly = true), result = @Arg(type = HintType.MIXED))
    public static Memory array_first(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) {
        var iterator = array(env, trace, "array_first", args).getNewIterator(env);
        return iterator.next() ? iterator.getValue().toImmutable() : Memory.NULL;
    }

    @Signature(value = @Arg(value = "array", type = HintType.ARRAY, readOnly = true), result = @Arg(type = HintType.MIXED))
    public static Memory array_last(Environment env, TraceInfo trace, @php.runtime.annotation.Runtime.Reference Memory... args) {
        Memory value = array(env, trace, "array_last", args).peek();
        return value == null ? Memory.NULL : value.toImmutable();
    }

    private static void arity(Environment env, TraceInfo trace, String function, Memory[] args, int count) {
        int supplied = args == null ? 0 : args.length;
        if (supplied != count) env.exception(trace, BaseArgumentCountError.class,
                "%s() expects exactly %d argument%s, %d given", function, count, count == 1 ? "" : "s", supplied);
    }

    private static ArrayMemory array(Environment env, TraceInfo trace, String function, Memory[] args) {
        arity(env, trace, function, args, 1);
        Memory value = args[0].toValue();
        if (!value.isArray()) invalid(env, trace, function, 1, "array", "array", value);
        return value.toValue(ArrayMemory.class);
    }

    private static byte[][] strings(Environment env, TraceInfo trace, String function, Memory[] args) throws Throwable {
        arity(env, trace, function, args, 2);
        return new byte[][]{string(env, trace, function, 1, "haystack", args[0]),
                string(env, trace, function, 2, "needle", args[1])};
    }

    static byte[] string(Environment env, TraceInfo trace, String function, int index, String name, Memory argument) throws Throwable {
        Memory value = argument.toValue();
        if (value.isString()) return value.getBinaryBytes(StandardCharsets.UTF_8);
        if (!php.runtime.invoke.NativeFunctionContext.isStrict(env, trace, function)) {
            if (value.isNull()) {
                env.error(trace, ErrorType.E_DEPRECATED, "%s(): Passing null to parameter #%d ($%s) of type string is deprecated", function, index, name);
                return new byte[0];
            }
            if (value.isNumber() || value.getRealType() == Memory.Type.BOOL) return value.toString().getBytes(StandardCharsets.UTF_8);
            if (value.isObject() && !value.isResource()) {
                var object = value.toValue(ObjectMemory.class);
                var method = object.getReflection().methodMagicToString;
                if (method != null) {
                    env.pushCall(trace, object.value, method.getName(), new Memory[0]);
                    Memory result;
                    try { result = method.invokeDynamic(object.value, env, trace); }
                    finally { env.popCall(); }
                    if (!result.isString()) env.exception(trace, BaseTypeError.class,
                            "%s::__toString() must return a string", object.getReflection().getName());
                    return result.getBinaryBytes(StandardCharsets.UTF_8);
                }
            }
        }
        invalid(env, trace, function, index, name, "string", value);
        return new byte[0];
    }

    static void invalid(Environment env, TraceInfo trace, String function, int index, String name, String type, Memory value) {
        String given = value.isResource() ? "resource" : value.isObject() ? value.toValue(ObjectMemory.class).getReflection().getName()
                : value.isNull() ? "null" : value.getRealType() == Memory.Type.INT ? "int"
                : value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false") : value.getRealType().toString();
        env.exception(trace, BaseTypeError.class, "%s(): Argument #%d ($%s) must be of type %s, %s given", function, index, name, type, given);
    }
}
