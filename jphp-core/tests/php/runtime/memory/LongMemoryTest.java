package php.runtime.memory;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.MethodSorters;
import php.runtime.Memory;

@RunWith(JUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LongMemoryTest {

    @Test
    public void testOverflow() {
        LongMemory max = new LongMemory(Long.MAX_VALUE);
        LongMemory min = new LongMemory(Long.MIN_VALUE);
        assertFloat((double) Long.MAX_VALUE + 1, max.plus(1L));
        assertFloat((double) Long.MAX_VALUE + 1, max.plus(Memory.CONST_INT_1));
        assertFloat((double) Long.MAX_VALUE + 1, max.plus(true));
        assertFloat((double) Long.MIN_VALUE - 1, min.minus(1L));
        assertFloat((double) Long.MIN_VALUE - 1, min.minus(Memory.CONST_INT_1));
        assertFloat((double) Long.MIN_VALUE - 1, min.minus(true));
        assertFloat((double) Long.MAX_VALUE * 2, max.mul(2L));
        assertFloat((double) Long.MAX_VALUE * 2, max.mul(new LongMemory(2)));
        assertFloat(-(double) Long.MIN_VALUE, min.mul(-1L));
        assertFloat(-(double) Long.MIN_VALUE, min.negative());
        assertFloat((double) Long.MAX_VALUE + 1, max.inc());
        assertFloat((double) Long.MIN_VALUE - 1, min.dec());
        assertFloat((double) Long.MAX_VALUE + 1, Memory.TRUE.plus(max));
        assertFloat(-(double) Long.MIN_VALUE, Memory.FALSE.minus(min));
        assertFloat(-(double) Long.MIN_VALUE, Memory.FALSE.minus(Long.MIN_VALUE));
        Assert.assertEquals(Memory.Type.INT, max.plus(0L).type);
        Assert.assertEquals(Memory.Type.INT, min.mul(1L).type);
        Assert.assertEquals(Long.MIN_VALUE, min.mul(1L).toLong());
        Assert.assertEquals(0L, max.mul(0L).toLong());
        Assert.assertEquals(3037000499L * 3037000499L, new LongMemory(3037000499L).mul(3037000499L).toLong());
        assertFloat((double) 3037000500L * 3037000500L, new LongMemory(3037000500L).mul(3037000500L));
    }

    private static void assertFloat(double expected, Memory actual) {
        Assert.assertEquals(Memory.Type.DOUBLE, actual.type);
        Assert.assertEquals(expected, actual.toDouble(), 0.0);
    }

    @Test
    public void testArithmeticBoundariesAgainstBigInteger() {
        java.util.Random random = new java.util.Random(20261007L);
        long[] boundaries = {Long.MIN_VALUE, Long.MIN_VALUE + 1, -3037000500L, -1L, 0L,
                1L, 3037000499L, 3037000500L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (int i = 0; i < 2000; i++) {
            long left = i < boundaries.length ? boundaries[i] : random.nextLong();
            long right = i < boundaries.length ? boundaries[boundaries.length - i - 1] : random.nextLong();
            java.math.BigInteger a = java.math.BigInteger.valueOf(left);
            java.math.BigInteger b = java.math.BigInteger.valueOf(right);
            LongMemory memory = new LongMemory(left);
            assertIntegerRange(a.add(b), memory.plus(right));
            assertIntegerRange(a.subtract(b), memory.minus(right));
            assertIntegerRange(a.multiply(b), memory.mul(right));
        }
    }

    private static void assertIntegerRange(java.math.BigInteger expected, Memory actual) {
        if (expected.bitLength() <= 63) {
            Assert.assertEquals(Memory.Type.INT, actual.type);
            Assert.assertEquals(expected.longValueExact(), actual.toLong());
        } else {
            Assert.assertEquals(Memory.Type.DOUBLE, actual.type);
        }
    }

    @Test
    public void testPlus(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(12, memory.plus(2).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.plus(2).type);

        Assert.assertEquals(11, memory.plus(true).toLong());
        Assert.assertEquals(10, memory.plus(false).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.plus(false).type);

        Assert.assertEquals(12, memory.plus(2.0).toDouble(), 0.0001);
        Assert.assertEquals(Memory.Type.DOUBLE, memory.plus(2.0).type);
    }

    @Test
    public void testMinus(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(8, memory.minus(2).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.minus(2).type);

        Assert.assertEquals(9, memory.minus(true).toLong());
        Assert.assertEquals(10, memory.minus(false).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.minus(false).type);

        Assert.assertEquals(8.0, memory.minus(2.0).toDouble(), 0.0001);
        Assert.assertEquals(Memory.Type.DOUBLE, memory.minus(2.0).type);
    }

    @Test
    public void testMul(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(20, memory.mul(2).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.mul(2).type);

        Assert.assertEquals(10, memory.mul(true).toLong());
        Assert.assertEquals(0, memory.mul(false).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.mul(false).type);

        Assert.assertEquals(20, memory.mul(2.0).toDouble(), 0.0001);
        Assert.assertEquals(Memory.Type.DOUBLE, memory.mul(2.0).type);
    }

    @Test
    public void testPow(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(100, memory.pow(2).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.pow(2).type);

        Assert.assertEquals(10, memory.pow(true).toLong());
        Assert.assertEquals(1, memory.pow(false).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.pow(false).type);

        Assert.assertEquals(100, memory.pow(2.0).toDouble(), 0.0001);
        Assert.assertEquals(Memory.Type.DOUBLE, memory.pow(2.0).type);

        memory = new LongMemory(Long.MAX_VALUE);
        Assert.assertEquals(Memory.Type.DOUBLE, memory.pow(3).type);
    }

    @Test
    public void testDiv(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(5, memory.div(2).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.div(2).type);

        Assert.assertEquals(10, memory.div(true).toLong());
        Assert.assertEquals(Memory.Type.INT, memory.div(true).type);

        Assert.assertEquals(5, memory.div(2.0).toDouble(), 0.0001);
        Assert.assertEquals(Memory.Type.DOUBLE, memory.div(2.0).type);
    }


    @Test
    public void testDivInvalidFalse(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(Memory.FALSE, memory.div(false));
    }

    @Test
    public void testDivInvalidFalse2(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(Memory.FALSE, memory.div(Memory.FALSE));
    }

    @Test
    public void testDivInvalidNull(){
        LongMemory memory = new LongMemory(10);
        Assert.assertEquals(Memory.FALSE, memory.div(Memory.NULL));
    }
}
