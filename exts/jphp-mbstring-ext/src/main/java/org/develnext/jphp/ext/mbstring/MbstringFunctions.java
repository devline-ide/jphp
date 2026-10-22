package org.develnext.jphp.ext.mbstring;

import com.ibm.icu.charset.CharsetProviderICU;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ULocale;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.NativeFunctionContext;
import php.runtime.memory.PhpByteStringMemory;
import php.runtime.memory.StringMemory;
import java.nio.*;
import java.nio.charset.*;
import java.util.Locale;
import java.util.Arrays;

/** Explicit eight-encoding subset. ICU does conversion/casing; no JVM-global encoding state. */
public final class MbstringFunctions extends FunctionsContainer {
    private static final String STATE = MbstringFunctions.class.getName() + "#internal-encoding";
    private static final CharsetProviderICU CHARSETS = new CharsetProviderICU();
    private static final UnicodeSet SPACE = new UnicodeSet("[\\u0000\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u180E\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]").freeze();

    @Signature(value=@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL")))
    public static Memory mb_internal_encoding(Environment env, TraceInfo trace, Memory encoding) throws Throwable {
        byte[] requested = string(env, trace, encoding, "mb_internal_encoding", 1, "encoding", true);
        if (requested == null) return StringMemory.valueOf(current(env, trace, "mb_internal_encoding"));
        String selected = encoding(env, trace, requested, "mb_internal_encoding", 1);
        // Validation/conversion callbacks finish before atomic immutable state publication.
        try (var admission = env.enterCallbackDomain()) { env.setUserValue(STATE, selected); }
        return Memory.TRUE;
    }
    @Signature(value=@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL")))
    public static Memory mb_internal_encoding(Environment env, TraceInfo trace) throws Throwable { return mb_internal_encoding(env, trace, Memory.NULL); }

    private static String current(Environment env, TraceInfo trace, String function) {
        String selected = env.getUserValue(STATE, String.class);
        if (selected != null) return selected;
        Memory configured = env.getConfigValue("mbstring.internal_encoding", Memory.CONST_EMPTY_STRING);
        if (configured.toString().isEmpty()) configured = env.getConfigValue("default_charset", StringMemory.valueOf("UTF-8"));
        return encoding(env, trace, configured.getBinaryBytes(StandardCharsets.UTF_8), function, 0);
    }
    private static String encoding(Environment env, TraceInfo trace, byte[] bytes, String function, int index) {
        String name = new String(bytes, StandardCharsets.UTF_8);
        String canonical = switch (name.toUpperCase(Locale.ROOT)) {
            case "UTF-8", "UTF8" -> "UTF-8";
            case "UTF-16LE" -> "UTF-16LE";
            case "UTF-16BE" -> "UTF-16BE";
            case "UTF-32LE" -> "UTF-32LE";
            case "UTF-32BE" -> "UTF-32BE";
            case "ASCII", "US-ASCII" -> "ASCII";
            case "ISO-8859-1", "ISO8859-1", "LATIN1" -> "ISO-8859-1";
            case "WINDOWS-1251", "CP1251" -> "Windows-1251";
            default -> null;
        };
        if (canonical == null) env.exception(trace, php.runtime.lang.exception.BaseValueError.class,
                "%s(): Argument #%d ($encoding) must be a valid encoding, \"%s\" given", function, index, name);
        return canonical;
    }
    private static Charset charset(String name) {
        Charset value = CHARSETS.charsetForName(name.equals("ASCII") ? "US-ASCII" : name);
        if (value == null) throw new IllegalStateException("Pinned ICU converter unavailable: " + name);
        return value;
    }
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith("?").decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static Memory encode(String text, Charset charset) throws CharacterCodingException {
        byte[] substitute = "?".getBytes(charset);
        ByteBuffer result = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(substitute).encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[result.remaining()];result.get(bytes);return new PhpByteStringMemory(bytes);
    }
    private record Decoded(String value, int[] offsets) { }
    private static Decoded decoded(byte[] raw, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("\uFFFD");
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
        return new Decoded(text.toString(), offsets);
    }
    private static boolean originalReplacement(byte[] bytes, int start, int end, Charset charset) {
        return Arrays.equals(Arrays.copyOfRange(bytes, start, end), "\uFFFD".getBytes(charset));
    }
    private static Memory trim(Environment env, TraceInfo trace, Memory value, Memory characters, Memory requested, String function, boolean left, boolean right) throws Throwable {
        byte[] input = string(env, trace, value, function, 1, "string", false);
        byte[] custom = string(env, trace, characters, function, 2, "characters", true);
        byte[] selected = string(env, trace, requested, function, 3, "encoding", true);
        Charset charset = charset(selected == null ? current(env, trace, function) : encoding(env, trace, selected, function, 3));
        Decoded decoded = decoded(input, charset);String text = decoded.value;
        UnicodeSet set = SPACE;boolean malformed = false;
        if (custom != null) {
            set = new UnicodeSet();Decoded charactersDecoded = decoded(custom, charset);
            for (int i = 0; i < charactersDecoded.value.length();) {
                int cp = charactersDecoded.value.codePointAt(i), next = i + Character.charCount(cp);
                if (cp == 0xfffd && !originalReplacement(custom, charactersDecoded.offsets[i], charactersDecoded.offsets[next], charset)) malformed = true;
                else set.add(cp);
                i = next;
            }
        }
        int start = 0, end = text.length();
        if (left) while (start < end) {
            int cp = text.codePointAt(start), next = start + Character.charCount(cp);
            boolean invalid = cp == 0xfffd && !originalReplacement(input, decoded.offsets[start], decoded.offsets[next], charset);
            if (!(invalid ? malformed : set.contains(cp))) break;
            start = next;
        }
        if (right) while (end > start) {
            int cp = text.codePointBefore(end), previous = end - Character.charCount(cp);
            boolean invalid = cp == 0xfffd && !originalReplacement(input, decoded.offsets[previous], decoded.offsets[end], charset);
            if (!(invalid ? malformed : set.contains(cp))) break;
            end = previous;
        }
        return new PhpByteStringMemory(Arrays.copyOfRange(input, decoded.offsets[start], decoded.offsets[end]));
    }
    private static Memory first(Environment env, TraceInfo trace, Memory value, Memory requested, String function, boolean title) throws Throwable {
        byte[] input = string(env, trace, value, function, 1, "string", false);
        byte[] selected = string(env, trace, requested, function, 2, "encoding", true);
        Charset charset = charset(selected == null ? current(env, trace, function) : encoding(env, trace, selected, function, 2));
        String text = decode(input, charset);
        if (text.isEmpty()) return new PhpByteStringMemory(new byte[0]);
        int end = Character.charCount(text.codePointAt(0));String head = text.substring(0, end);
        String converted = title ? UCharacter.toTitleCase(ULocale.ROOT, head, null, UCharacter.TITLECASE_NO_BREAK_ADJUSTMENT | UCharacter.TITLECASE_NO_LOWERCASE) : UCharacter.toLowerCase(ULocale.ROOT, head);
        return converted.equals(head) ? new PhpByteStringMemory(input) : encode(converted + text.substring(end), charset);
    }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_trim(Environment env, TraceInfo trace, Memory value, Memory characters, Memory encoding) throws Throwable { return trim(env, trace, value, characters, encoding, "mb_trim", true, true); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_trim(Environment env, TraceInfo trace, Memory value, Memory characters) throws Throwable { return mb_trim(env, trace, value, characters, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_trim(Environment env, TraceInfo trace, Memory value) throws Throwable { return mb_trim(env, trace, value, Memory.NULL, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_ltrim(Environment env, TraceInfo trace, Memory value, Memory characters, Memory encoding) throws Throwable { return trim(env, trace, value, characters, encoding, "mb_ltrim", true, false); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_ltrim(Environment env, TraceInfo trace, Memory value, Memory characters) throws Throwable { return mb_ltrim(env, trace, value, characters, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_ltrim(Environment env, TraceInfo trace, Memory value) throws Throwable { return mb_ltrim(env, trace, value, Memory.NULL, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_rtrim(Environment env, TraceInfo trace, Memory value, Memory characters, Memory encoding) throws Throwable { return trim(env, trace, value, characters, encoding, "mb_rtrim", false, true); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_rtrim(Environment env, TraceInfo trace, Memory value, Memory characters) throws Throwable { return mb_rtrim(env, trace, value, characters, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="characters",type=HintType.STRING,nullable=true,optional=@Optional("NULL")),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_rtrim(Environment env, TraceInfo trace, Memory value) throws Throwable { return mb_rtrim(env, trace, value, Memory.NULL, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_ucfirst(Environment env, TraceInfo trace, Memory value, Memory encoding) throws Throwable { return first(env, trace, value, encoding, "mb_ucfirst", true); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_ucfirst(Environment env, TraceInfo trace, Memory value) throws Throwable { return mb_ucfirst(env, trace, value, Memory.NULL); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_lcfirst(Environment env, TraceInfo trace, Memory value, Memory encoding) throws Throwable { return first(env, trace, value, encoding, "mb_lcfirst", false); }
    @Signature(value={@Arg(value="string",type=HintType.STRING),@Arg(value="encoding",type=HintType.STRING,nullable=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory mb_lcfirst(Environment env, TraceInfo trace, Memory value) throws Throwable { return mb_lcfirst(env, trace, value, Memory.NULL); }
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
