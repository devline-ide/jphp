package org.develnext.jphp.zend.ext.standard;

import php.runtime.Memory;
import php.runtime.PhpNumericConversions;
import php.runtime.PhpNumericString;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.NativeFunctionContext;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.support.TypeChecker;

import java.nio.charset.StandardCharsets;

/** Scalar parsing for the three fixed PHP signatures; bodies retain native parameter order. */
final class NativeScalarArguments {
    private static final TypeChecker INTEGER = TypeChecker.of(HintType.INT);

    private NativeScalarArguments() {}

    static boolean modern(Environment env, TraceInfo trace) {
        return env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
    }

    static byte[] string(Environment env, TraceInfo trace, String function, int index,
                         String name, Memory argument) throws Throwable {
        Memory value = argument.toValue();
        if (value.type == Memory.Type.DOUBLE && !NativeFunctionContext.isStrict(env, trace, function)) {
            Memory converted = PhpNumericConversions.checkedScalarCoercion(value,
                    StringMemory.valueOf(value.toString()), env, trace);
            return converted.getBinaryBytes(StandardCharsets.UTF_8);
        }
        return Php85Functions.string(env, trace, function, index, name, value);
    }

    /** Called only after exact-string eligibility; Java UTF-8 replaces isolated surrogates with '?'. */
    static Memory utf8StringLength(String text) {
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current < 0x80) length++;
            else if (current < 0x800) length += 2;
            else if (Character.isHighSurrogate(current) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                length += 4; i++;
            } else if (Character.isSurrogate(current)) length++;
            else length += 3;
        }
        return length <= Integer.MAX_VALUE ? LongMemory.valueOf((int) length) : LongMemory.valueOf(length);
    }

    static long integer(Environment env, TraceInfo trace, String function, int index,
                        String name, Memory argument) {
        return integer(env, trace, function, index, name, argument.toValue(), false);
    }

    static Long nullableInteger(Environment env, TraceInfo trace, String function, int index,
                                String name, Memory argument) {
        Memory value = argument.toValue();
        return value.isNull() ? null : integer(env, trace, function, index, name, value, true);
    }

    private static long integer(Environment env, TraceInfo trace, String function, int index,
                                String name, Memory value, boolean nullable) {
        if (value.type == Memory.Type.INT) return value.toLong();
        boolean strict = NativeFunctionContext.isStrict(env, trace, function);
        if (!nullable && value.isNull() && !strict) {
            env.error(trace, ErrorType.E_DEPRECATED,
                    "%s(): Passing null to parameter #%d ($%s) of type int is deprecated", function, index, name);
            return 0;
        }
        Memory converted = INTEGER.apply(env, value, false, strict);
        if (converted == null) {
            Php85Functions.invalid(env, trace, function, index, name, nullable ? "?int" : "int", value);
            return 0; // Environment.exception throws the actual PHP TypeError.
        }
        Memory numeric = value.isString() ? PhpNumericString.parseStrict(value.toString()) : value;
        if (numeric != null) PhpNumericConversions.warnIntegerPrecisionLoss(value, numeric, env, trace);
        return converted.toLong();
    }
}
