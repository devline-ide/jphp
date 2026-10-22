package org.develnext.jphp.ext.intl;

import com.ibm.icu.charset.CharsetProviderICU;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.NativeFunctionContext;
import php.runtime.memory.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.Arrays;

/** Optional grapheme subset. ICU supplies Unicode boundaries; slices retain original PHP bytes. */
public final class IntlFunctions extends FunctionsContainer {
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="length",type=HintType.INT,optional=@Optional("1"))})
    public static Memory grapheme_str_split(Environment env, TraceInfo trace, Memory value, Memory length) throws Throwable {
        byte[] raw = string(env, trace, value, "grapheme_str_split", 1, "string", false);
        long chunk = length(env, trace, length);
        if (chunk < 1 || chunk > 1073741823L) env.exception(trace, php.runtime.lang.exception.BaseValueError.class,
                "grapheme_str_split(): Argument #2 ($length) must be greater than 0 and less than or equal to 1073741823");
        Utf8Text text = utf8(raw);BreakIterator iterator = BreakIterator.getCharacterInstance(ULocale.ROOT);
        iterator.setText(text.value);iterator.first();ArrayMemory result = new ArrayMemory();int start = 0, count = 0;
        for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
            if (++count == chunk) { result.add(new PhpByteStringMemory(Arrays.copyOfRange(raw, text.offsets[start], text.offsets[end])));start = end;count = 0; }
        }
        if (start < text.value.length()) result.add(new PhpByteStringMemory(Arrays.copyOfRange(raw, text.offsets[start], raw.length)));
        return result.toConstant();
    }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="length",type=HintType.INT,optional=@Optional("1"))})
    public static Memory grapheme_str_split(Environment env, TraceInfo trace, Memory value) throws Throwable { return grapheme_str_split(env, trace, value, LongMemory.valueOf(1)); }
    private record Utf8Text(String value, int[] offsets) { }
    private static Utf8Text utf8(byte[] raw) throws CharacterCodingException {
        Charset utf8 = new CharsetProviderICU().charsetForName("UTF-8");
        if (utf8 == null) throw new IllegalStateException("Pinned ICU UTF-8 converter unavailable");
        CharsetDecoder decoder = utf8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("\uFFFD");
        ByteBuffer source = ByteBuffer.wrap(raw);CharBuffer one = CharBuffer.allocate(1), pair = CharBuffer.allocate(2);
        StringBuilder text = new StringBuilder();int[] offsets = new int[raw.length + 1];boolean decoding = true;
        while (true) {
            one.clear();CharBuffer output = one;CoderResult status = decoding ? decoder.decode(source, output, true) : decoder.flush(output);
            if (status.isError()) status.throwException();
            if (status.isOverflow() && output.position() == 0) {
                pair.clear();output = pair;status = decoding ? decoder.decode(source, output, true) : decoder.flush(output);
                if (status.isError()) status.throwException();
                if (output.position() != 2 || !Character.isSurrogatePair(output.get(0), output.get(1)))
                    throw new IllegalStateException("ICU UTF-8 scalar mapping made no bounded progress");
            }
            output.flip();while (output.hasRemaining()) { text.append(output.get());offsets[text.length()] = source.position(); }
            if (status.isUnderflow()) { if (!decoding) break;decoding = false; }
            else if (status.isOverflow() && output.limit() == 0) throw new IllegalStateException("ICU UTF-8 decoder stalled");
        }
        if (source.hasRemaining() || offsets[text.length()] != raw.length) throw new IllegalStateException("ICU UTF-8 mapping omitted original input bytes");
        return new Utf8Text(text.toString(), offsets);
    }
    private static long length(Environment env, TraceInfo trace, Memory argument) {
        Memory value = argument.toValue();if (value.getRealType() == Memory.Type.INT) return value.toLong();
        if (!NativeFunctionContext.isStrict(env, trace, "grapheme_str_split")) {
            if (value.isNull()) { env.error(trace, ErrorType.E_DEPRECATED, "grapheme_str_split(): Passing null to parameter #2 ($length) of type int is deprecated");return 0; }
            Memory converted = php.runtime.reflection.support.TypeChecker.of(HintType.INT).apply(env, value, false, false);
            if (converted != null) {
                Memory numeric = value.isString() ? php.runtime.PhpNumericString.parseStrict(php.runtime.PhpByteStrings.numericText(value)) : value;
                if (numeric != null) php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(value, numeric, env, trace);
                return converted.toLong();
            }
        }
        typeError(env, trace, value, "grapheme_str_split", 2, "length", "int");return 0;
    }
    private static byte[] string(Environment env, TraceInfo trace, Memory argument, String function, int index, String name, boolean nullable) throws Throwable {
        Memory value = argument.toValue();
        if (value.isNull() && nullable) return null;
        if (value.isString()) return value.getBinaryBytes(StandardCharsets.UTF_8);
        if (!NativeFunctionContext.isStrict(env, trace, function)) {
            if (value.isNull()) {
                env.error(trace, ErrorType.E_DEPRECATED, "%s(): Passing null to parameter #%d ($%s) of type string is deprecated", function, index, name);
                return new byte[0];
            }
            if (value.isNumber() || value.getRealType() == Memory.Type.BOOL || value.isObject() && !value.isResource()
                    && value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().methodMagicToString != null)
                return php.runtime.PhpByteStrings.stringValue(value, env, trace).getBinaryBytes(StandardCharsets.UTF_8);
        }
        typeError(env, trace, value, function, index, name, nullable ? "?string" : "string");return null;
    }
    private static void typeError(Environment env, TraceInfo trace, Memory value, String function, int index, String name, String expected) {
        String given = value.isResource() ? "resource" : php.runtime.ext.core.Php85LangFunctions.get_debug_type(value);
        env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                "%s(): Argument #%d ($%s) must be of type %s, %s given", function, index, name, expected, given);
    }
}
