package php.runtime.memory;

import php.runtime.Memory;
import php.runtime.PhpByteStrings;
import php.runtime.PhpComparison;
import php.runtime.PhpNumericString;
import php.runtime.env.TraceInfo;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Immutable PHP string bytes. Java text conversion is an interoperability projection only. */
public final class PhpByteStringMemory extends StringMemory {
    private final byte[] bytes;
    private volatile Object arrayKey;

    public PhpByteStringMemory(byte... source) {
        super("");
        bytes = Objects.requireNonNull(source).clone();
    }

    public PhpByteStringMemory(byte[] source, int from, int length) {
        super("");
        Objects.checkFromIndexSize(from, length, source.length);
        bytes = Arrays.copyOfRange(source, from, from + length);
    }

    private PhpByteStringMemory(byte first) {
        super("");
        bytes = new byte[]{first};
    }

    private PhpByteStringMemory(String binary) {
        super("");
        for (int i = 0; i < binary.length(); i++)
            if (binary.charAt(i) > 255) throw new IllegalArgumentException("Byte literal projection contains a non-byte character");
        bytes = binary.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Compiler constant bridge: each Java character represents exactly one PHP byte. */
    public static PhpByteStringMemory fromBinaryString(String binary) {
        return new PhpByteStringMemory(Objects.requireNonNull(binary));
    }

    public int byteLength() { return bytes.length; }

    /** Exact invalid UTF8 keys remain immutable byte carriers; valid text shares existing key identity. */
    public Object toArrayKey() {
        Object cached = arrayKey;
        if (cached != null) return cached;
        String text = toString();
        Object key = Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))
                ? ArrayMemory.toKey(StringMemory.valueOf(text)) : this;
        arrayKey = key;
        return key;
    }

    /** Copies into caller storage; the immutable backing never crosses the API boundary. */
    public void copyTo(byte[] target, int offset) {
        Objects.checkFromIndexSize(offset, bytes.length, target.length);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    @Override public byte[] getBinaryBytes(Charset ignored) { return bytes.clone(); }

    @Override public String toString() { return new String(bytes, StandardCharsets.UTF_8); }

    @Override public String toBinaryString() { return new String(bytes, StandardCharsets.ISO_8859_1); }

    @Override public char toChar() { return bytes.length == 0 ? 0 : (char)(bytes[0] & 255); }

    @Override public boolean toBoolean() {
        return bytes.length != 0 && (bytes.length != 1 || bytes[0] != '0');
    }

    @Override public Memory toNumeric() { return PhpNumericString.parseLeading(toBinaryString()); }

    @Override public Memory toLongNumeric() { return toNumeric(); }

    @Override public long toLong() {
        Memory number = toNumeric();
        if (number.type == Type.INT) return number.toLong();
        double value = number.toDouble();
        return Double.isFinite(value) ? (long)value : 0;
    }

    @Override public double toDouble() { return PhpNumericString.leadingDouble(toBinaryString()); }

    @Override public Memory toImmutable() { return this; }

    @Override public Memory toBinary() { return this; }

    /** Context-free read; compiler reads requiring warnings use PhpByteStrings.offset. */
    public Memory byteAt(long offset) {
        long index = offset < 0 ? bytes.length + offset : offset;
        if (index < 0 || index >= bytes.length) return CONST_EMPTY_STRING;
        byte value = bytes[(int)index];
        return value >= 0 ? StringMemory.valueOf((char)value) : new PhpByteStringMemory(value);
    }

    @Override public Memory valueOfIndex(TraceInfo trace, long index) { return byteAt(index); }

    @Override public Memory valueOfIndex(TraceInfo trace, double index) { return byteAt((long)index); }

    @Override public Memory valueOfIndex(TraceInfo trace, boolean index) { return byteAt(index ? 1 : 0); }

    @Override public Memory valueOfIndex(TraceInfo trace, String index) {
        return PhpByteStrings.offset(this, StringMemory.valueOf(index), null, trace);
    }

    @Override public Memory valueOfIndex(TraceInfo trace, Memory index) {
        return PhpByteStrings.offset(this, index, null, trace);
    }

    /** Both immutable operands can be compared without defensive return-array allocations. */
    public static int compareBytes(Memory left, Memory right) {
        byte[] a = left instanceof PhpByteStringMemory raw ? raw.bytes : left.getBinaryBytes(StandardCharsets.UTF_8);
        byte[] b = right instanceof PhpByteStringMemory raw ? raw.bytes : right.getBinaryBytes(StandardCharsets.UTF_8);
        return Integer.signum(Arrays.compareUnsigned(a, b));
    }

    @Override public boolean identical(Memory other) {
        other = other.toValue();
        return other.isString() && compareBytes(this, other) == 0;
    }

    @Override public boolean identical(String other) { return identical(StringMemory.valueOf(other)); }

    @Override public boolean equal(Memory other) { return PhpComparison.equal(this, other, null, TraceInfo.UNKNOWN); }
    @Override public boolean equal(String other) { return equal(StringMemory.valueOf(other)); }
    @Override public boolean equal(long other) { return equal(LongMemory.valueOf(other)); }
    @Override public boolean equal(double other) { return equal(DoubleMemory.valueOf(other)); }
    @Override public boolean equal(boolean other) { return toBoolean() == other; }
    @Override public boolean notEqual(Memory other) { return !equal(other); }
    @Override public boolean notEqual(String other) { return !equal(other); }
    @Override public boolean notEqual(long other) { return !equal(other); }
    @Override public boolean notEqual(double other) { return !equal(other); }
    @Override public boolean notEqual(boolean other) { return !equal(other); }

    @Override public boolean smaller(Memory other) { return PhpComparison.smaller(this, other, null, TraceInfo.UNKNOWN); }
    @Override public boolean smallerEq(Memory other) { return PhpComparison.smallerEq(this, other, null, TraceInfo.UNKNOWN); }
    @Override public boolean greater(Memory other) { return PhpComparison.greater(this, other, null, TraceInfo.UNKNOWN); }
    @Override public boolean greaterEq(Memory other) { return PhpComparison.greaterEq(this, other, null, TraceInfo.UNKNOWN); }
    @Override public boolean smaller(String other) { return smaller(StringMemory.valueOf(other)); }
    @Override public boolean smallerEq(String other) { return smallerEq(StringMemory.valueOf(other)); }
    @Override public boolean greater(String other) { return greater(StringMemory.valueOf(other)); }
    @Override public boolean greaterEq(String other) { return greaterEq(StringMemory.valueOf(other)); }
    @Override public boolean smaller(long other) { return smaller(LongMemory.valueOf(other)); }
    @Override public boolean smallerEq(long other) { return smallerEq(LongMemory.valueOf(other)); }
    @Override public boolean greater(long other) { return greater(LongMemory.valueOf(other)); }
    @Override public boolean greaterEq(long other) { return greaterEq(LongMemory.valueOf(other)); }
    @Override public boolean smaller(double other) { return smaller(DoubleMemory.valueOf(other)); }
    @Override public boolean smallerEq(double other) { return smallerEq(DoubleMemory.valueOf(other)); }
    @Override public boolean greater(double other) { return greater(DoubleMemory.valueOf(other)); }
    @Override public boolean greaterEq(double other) { return greaterEq(DoubleMemory.valueOf(other)); }
    @Override public boolean smaller(boolean other) { return !toBoolean() && other; }
    @Override public boolean smallerEq(boolean other) { return !toBoolean() || other; }
    @Override public boolean greater(boolean other) { return toBoolean() && !other; }
    @Override public boolean greaterEq(boolean other) { return toBoolean() || !other; }

    @Override public boolean equals(Object other) {
        return other == this || other instanceof PhpByteStringMemory raw && Arrays.equals(bytes, raw.bytes);
    }

    @Override public int hashCode() { return Arrays.hashCode(bytes); }
}
