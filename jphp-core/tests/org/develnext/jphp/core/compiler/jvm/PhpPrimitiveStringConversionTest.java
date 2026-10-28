package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.PhpByteStringValues;
import php.runtime.memory.LongMemory;
import php.runtime.memory.PhpByteStringMemory;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Primitive and boxed decimal operands preserve PHP bytes at cache and integer boundaries. */
public class PhpPrimitiveStringConversionTest {
    @Test public void decimalOperandsPreserveUtf8AndRawBytesInBothOrders() throws Throwable {
        long[] values = {Long.MIN_VALUE, -10000, -1, 0, 1, 9999, 10000, Long.MAX_VALUE};
        byte[] raw = {(byte) 0xff, 0, '/'};
        for (long value : values) {
            String decimal = Long.toString(value);
            assertEquals("ёж/" + decimal, PhpByteStringValues.concat((Object) "ёж/", value, null, null));
            assertEquals(decimal + "/ёж", PhpByteStringValues.concat(value, (Object) "/ёж", null, null));
            assertEquals(decimal + decimal, PhpByteStringValues.concat(value, value, null, null));
            byte[] suffix = decimal.getBytes(StandardCharsets.US_ASCII);
            byte[] expected = new byte[raw.length + suffix.length];
            System.arraycopy(raw, 0, expected, 0, raw.length);
            System.arraycopy(suffix, 0, expected, raw.length, suffix.length);
            assertArrayEquals(expected, PhpByteStringValues.box(PhpByteStringValues.concat(
                    (Object) new PhpByteStringMemory(raw), value, null, null)).getBinaryBytes(StandardCharsets.UTF_8));
            expected = new byte[suffix.length + raw.length];
            System.arraycopy(suffix, 0, expected, 0, suffix.length);
            System.arraycopy(raw, 0, expected, suffix.length, raw.length);
            assertArrayEquals(expected, PhpByteStringValues.box(PhpByteStringValues.concat(
                    (Object) new LongMemory(value), (Object) new PhpByteStringMemory(raw), null, null))
                    .getBinaryBytes(StandardCharsets.UTF_8));
        }
    }
}
