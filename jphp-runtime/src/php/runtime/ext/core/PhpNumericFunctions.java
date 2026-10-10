package php.runtime.ext.core;

import php.runtime.Memory;
import php.runtime.PhpNumericConversions;
import php.runtime.PhpNumericString;
import php.runtime.annotation.Runtime.Reference;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseValueError;

/** Trace-selected builtins; old LangFunctions Java entry points retain their ABI. */
public final class PhpNumericFunctions {
    private PhpNumericFunctions() {}
    private static boolean modern(Environment env, TraceInfo trace) {
        return env.getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5;
    }

    public static boolean is_numeric(Environment env, TraceInfo trace, Memory value) {
        if (!modern(env, trace)) return LangFunctions.is_numeric(value);
        value = value.toValue();
        return value.isNumber() || value.isString() && PhpNumericString.isNumeric(value.toString());
    }

    public static long intval(Environment env, TraceInfo trace, Memory value) { return intval(env, trace, value, 10); }
    public static long intval(Environment env, TraceInfo trace, Memory value, int base) {
        if (!modern(env, trace)) return LangFunctions.intval(value);
        value = value.toValue();
        return value.isString() && base != 10 ? PhpNumericString.integerInBase(value.toString(), base)
                : PhpNumericConversions.toLong(value, env, trace);
    }

    public static double floatval(Environment env, TraceInfo trace, Memory value) {
        return modern(env, trace) ? PhpNumericConversions.toDouble(value, env, trace) : LangFunctions.floatval(value);
    }

    public static double doubleval(Environment env, TraceInfo trace, Memory value) { return floatval(env, trace, value); }

    public static boolean settype(Environment env, TraceInfo trace, @Reference Memory value, String type) {
        if (!modern(env, trace)) return LangFunctions.settype(value, type);
        if (!value.isReference()) return false;
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "int": case "integer": value.assign(PhpNumericConversions.toLong(value, env, trace)); break;
            case "float": case "double": value.assign(PhpNumericConversions.toDouble(value, env, trace)); break;
            case "string": value.assign(value.toString()); break;
            case "bool": case "boolean": value.assign(value.toBoolean()); break;
            case "array": value.assign(value.toValue().toArray()); break;
            case "object": value.assign(value.toValue().toObject(env)); break;
            case "null": value.assign(Memory.NULL); break;
            default: env.exception(trace, BaseValueError.class, "settype(): Argument #2 ($type) must be a valid type");
        }
        return true;
    }
}
