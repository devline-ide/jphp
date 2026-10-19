package php.runtime.ext.core;

import php.runtime.Memory;
import php.runtime.annotation.Runtime;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.memory.output.serialization.Deserializer;
import php.runtime.memory.output.serialization.Serializer;
import php.runtime.memory.output.serialization.PhpSerialization;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import java.util.ArrayList;
import java.util.Collection;

public class StringFunctions extends FunctionsContainer {
    @Override
    public Collection<CompileFunctionSpec> getFunctionSpecs() {
        Collection<CompileFunctionSpec> result = new ArrayList<>();
        for (CompileFunctionSpec spec : super.getFunctionSpecs()) {
            if (!spec.getName().equals("serialize") && !spec.getName().equals("unserialize")
                    && !spec.getName().equals("ord") && !spec.getName().equals("chr")) result.add(spec);
        }
        CompileFunctionSpec serialize = new CompileFunctionSpec("serialize");
        serialize.addMethod(getNative(PhpSerialization.class, "serialize", Environment.class, TraceInfo.class, Memory.class));
        result.add(serialize);
        CompileFunctionSpec unserialize = new CompileFunctionSpec("unserialize");
        unserialize.addMethod(getNative(PhpSerialization.class, "unserialize", Environment.class, TraceInfo.class, Memory.class));
        unserialize.addMethod(getNative(PhpSerialization.class, "unserialize", Environment.class, TraceInfo.class, Memory.class, Memory.class));
        result.add(unserialize);
        CompileFunctionSpec ord = new CompileFunctionSpec("ord");
        ord.addMethod(getNative(StringFunctions.class, "nativeOrd", Environment.class, TraceInfo.class, Memory.class));
        result.add(ord);
        CompileFunctionSpec chr = new CompileFunctionSpec("chr");
        chr.addMethod(getNative(StringFunctions.class, "nativeChr", Environment.class, TraceInfo.class, Memory.class));
        result.add(chr);
        return result;
    }

    @Runtime.Immutable
    public static String chr(int codePoint){
        return String.valueOf((char) codePoint);
    }

    @Runtime.Immutable
    public static int ord(char value){
        return (int) value;
    }

    @php.runtime.annotation.Reflection.Ignore
    @php.runtime.annotation.Reflection.Signature(
            value = @php.runtime.annotation.Reflection.Arg(value = "character", type = php.runtime.common.HintType.STRING),
            result = @php.runtime.annotation.Reflection.Arg(type = php.runtime.common.HintType.INT))
    @Runtime.ScalarStringCallback(argument = 0)
    public static long nativeOrd(Environment env, TraceInfo trace, Memory character) throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.JPHP_LEGACY)
            return ord(character.toChar());
        Memory actual = character.toValue();
        boolean strict = php.runtime.invoke.NativeFunctionContext.isStrict(env, trace, "ord");
        byte[] bytes;
        if (actual.isString()) {
            bytes = actual.getBinaryBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (!strict && actual.isNull()) {
            env.error(trace, php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                    "ord(): Passing null to parameter #1 ($character) of type string is deprecated");
            bytes = new byte[0];
        } else if (!strict && (actual.isNumber() || actual.getRealType() == Memory.Type.BOOL
                || actual.isObject() && !actual.isResource()
                    && actual.toValue(php.runtime.memory.ObjectMemory.class).getReflection().methodMagicToString != null)) {
            Memory converted;
            if (actual.isObject() && php.runtime.PhpRuntimeOwnership.current(env) != null) {
                var body = php.runtime.PhpRuntimeOwnership.currentNativeBody(env);
                if (body == null)
                    throw new IllegalStateException("Native scalar Stringable requires its exact BODY envelope");
                converted = body.scalarStringable(0, actual.toValue(php.runtime.memory.ObjectMemory.class), trace);
            } else {
                converted = php.runtime.PhpByteStrings.stringValue(actual, env, trace);
            }
            bytes = converted.getBinaryBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            invalidByteCharacterArgument(env, trace, "ord", "character", "string", actual);
            return 0;
        }
        if (bytes.length == 0) {
            env.error(trace, php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                    "ord(): Providing an empty string is deprecated");
            return 0;
        }
        if (bytes.length != 1)
            env.error(trace, php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                    "ord(): Providing a string that is not one byte long is deprecated. Use ord($str[0]) instead");
        return bytes[0] & 0xff;
    }

    private static final php.runtime.reflection.support.TypeChecker BYTE_CHARACTER_INTEGER =
            php.runtime.reflection.support.TypeChecker.of(php.runtime.common.HintType.INT);

    @php.runtime.annotation.Reflection.Ignore
    @php.runtime.annotation.Reflection.Signature(
            value = @php.runtime.annotation.Reflection.Arg(value = "codepoint", type = php.runtime.common.HintType.INT),
            result = @php.runtime.annotation.Reflection.Arg(type = php.runtime.common.HintType.STRING))
    public static Memory nativeChr(Environment env, TraceInfo trace, Memory codepoint) {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.JPHP_LEGACY)
            return php.runtime.memory.StringMemory.valueOf(chr(codepoint.toInteger()));
        Memory actual = codepoint.toValue();
        boolean strict = php.runtime.invoke.NativeFunctionContext.isStrict(env, trace, "chr");
        long number;
        if (actual.isNull() && !strict) {
            env.error(trace, php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                    "chr(): Passing null to parameter #1 ($codepoint) of type int is deprecated");
            number = 0;
        } else {
            Memory converted = BYTE_CHARACTER_INTEGER.check(env, actual, false, null) ? actual
                    : BYTE_CHARACTER_INTEGER.apply(env, actual, false, strict);
            if (converted == null) {
                invalidByteCharacterArgument(env, trace, "chr", "codepoint", "int", actual);
                return Memory.NULL;
            }
            Memory numeric = actual.isString() ? php.runtime.PhpNumericString.parseStrict(actual.toString()) : actual;
            if (numeric != null) php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(actual, numeric, env, trace);
            number = converted.toLong();
        }
        if (number < 0 || number > 255)
            env.error(trace, php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                    "chr(): Providing a value not in-between 0 and 255 is deprecated, this is because a byte value must be in the [0, 255] interval. The value used will be constrained using % 256");
        return new php.runtime.memory.PhpByteStringMemory(new byte[]{(byte) number});
    }

    private static void invalidByteCharacterArgument(Environment env, TraceInfo trace,
                                                     String function, String parameter, String type, Memory value) {
        String given = value.isResource() ? "resource" : value.isObject()
                ? value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName()
                : value.isNull() ? "null" : value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false")
                : value.getRealType() == Memory.Type.INT ? "int"
                : value.getRealType() == Memory.Type.DOUBLE ? "float"
                : value.getRealType().toString();
        env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                "%s(): Argument #1 ($%s) must be of type %s, %s given", function, parameter, type, given);
    }

    public static String serialize(Environment env, TraceInfo trace, Memory value){
        StringBuilder writer = new StringBuilder();
        Serializer serializer = new Serializer(env, trace, writer);

        serializer.write(value);
        return writer.toString();
    }

    public static Memory unserialize(Environment env, TraceInfo trace, String value){
        Deserializer deserializer = new Deserializer(env, trace);
        return deserializer.read(value);
    }
}
