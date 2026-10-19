package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.PhpByteStringMemory;
import php.runtime.memory.StringMemory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Ordinary interpolation uses only a text builder; raw bytes promote it once. */
public final class PhpByteStringBuilder {
    private StringBuilder text;
    private byte[] bytes;
    private int count;

    public PhpByteStringBuilder() { text = new StringBuilder(); }

    /** Union promotion reuses its existing ordinary text builder. */
    PhpByteStringBuilder(StringBuilder text) { this.text = text; }

    public PhpByteStringBuilder append(Memory value, Environment env, TraceInfo trace) throws Throwable {
        return appendStringValue(PhpByteStrings.stringValue(value, env, trace));
    }

    PhpByteStringBuilder appendStringValue(Memory string) {
        if (string instanceof PhpByteStringMemory raw) {
            promote();
            ensure(raw.byteLength());
            raw.copyTo(bytes, count);
            count += raw.byteLength();
        } else append(string.toString());
        return this;
    }

    public PhpByteStringBuilder append(String value) {
        if (value == null) return this;
        if (bytes == null) text.append(value);
        else {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            ensure(encoded.length);
            System.arraycopy(encoded, 0, bytes, count, encoded.length);
            count += encoded.length;
        }
        return this;
    }

    private void promote() {
        if (bytes != null) return;
        byte[] prefix = text.toString().getBytes(StandardCharsets.UTF_8);
        bytes = Arrays.copyOf(prefix, Math.max(32, prefix.length));
        count = prefix.length;
        text = null;
    }

    private void ensure(int additional) {
        int required = Math.addExact(count, additional);
        if (required > bytes.length)
            bytes = Arrays.copyOf(bytes, Math.max(required, bytes.length > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : bytes.length * 2));
    }

    public Memory toImmutable() {
        return bytes == null ? StringMemory.valueOf(text.toString()) : new PhpByteStringMemory(bytes, 0, count);
    }
}
