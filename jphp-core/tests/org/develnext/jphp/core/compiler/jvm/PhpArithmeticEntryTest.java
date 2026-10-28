package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.PhpArithmetic;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;

import java.math.BigInteger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

/** Boxed arithmetic keeps evaluation, reference getters and overflow contracts. */
public class PhpArithmeticEntryTest extends JvmCompilerCase {
    @Test public void integerEntryRetainsCompatibleAndWrappingBoundaries() {
        long[][] pairs = {{Long.MAX_VALUE, 1}, {Long.MIN_VALUE, -1}, {Long.MAX_VALUE, 2}, {-3, 5}};
        for (PhpOptimizationProfile profile : PhpOptimizationProfile.values()) for (String op : new String[]{"plus", "minus", "mul"}) {
            for (long[] pair : pairs) {
                BigInteger a = BigInteger.valueOf(pair[0]), b = BigInteger.valueOf(pair[1]);
                BigInteger expected = op.equals("plus") ? a.add(b) : op.equals("minus") ? a.subtract(b) : a.multiply(b);
                Memory actual = PhpArithmetic.binary(new ReferenceMemory(LongMemory.valueOf(pair[0])),
                        new ReferenceMemory(LongMemory.valueOf(pair[1])), op, null, null, profile.getId());
                if (profile == PhpOptimizationProfile.FAST_INT64_WRAP || expected.bitLength() <= 63) {
                    assertEquals(Memory.Type.INT, actual.type);
                    assertEquals(expected.longValue(), actual.toLong());
                } else {
                    assertEquals(Memory.Type.DOUBLE, actual.type);
                    assertEquals(expected.doubleValue(), actual.toDouble(), 0);
                }
                ReferenceMemory target = new ReferenceMemory(LongMemory.valueOf(pair[0]));
                ReferenceMemory alias = new ReferenceMemory();
                alias.assignRef(target);
                Memory assigned = PhpArithmetic.assign(alias, LongMemory.valueOf(pair[1]), op, null, null, profile.getId());
                assertEquals(actual.type, assigned.type);
                assertEquals(actual.type, target.toValue().type);
                if (actual.type == Memory.Type.INT) {
                    assertEquals(actual.toLong(), assigned.toLong());
                    assertEquals(actual.toLong(), target.toLong());
                } else {
                    assertEquals(actual.toDouble(), assigned.toDouble(), 0);
                    assertEquals(actual.toDouble(), target.toDouble(), 0);
                }
            }
        }
    }

    @Test public void conversionOrderAndColdOperatorsArePreserved() {
        StringBuilder calls = new StringBuilder();
        ReferenceMemory right = new ReferenceMemory(LongMemory.valueOf(2)) {
            @Override public Memory toValue() { calls.append('R'); return super.toValue(); }
        };
        ReferenceMemory left = new ReferenceMemory(LongMemory.valueOf(1)) {
            @Override public Memory toValue() { calls.append('L'); right.assign(9); return super.toValue(); }
        };
        assertEquals(10, PhpArithmetic.binary(left, right, "plus", null, null, 0).toLong());
        assertEquals("LR", calls.toString());
        assertEquals(5, PhpArithmetic.binary(new StringMemory("4"), Memory.TRUE, "plus", null, null, 0).toLong());
        assertEquals(4.5, PhpArithmetic.binary(LongMemory.valueOf(9), LongMemory.valueOf(2), "div", null, null, 0).toDouble(), 0);
        assertEquals(1, PhpArithmetic.binary(LongMemory.valueOf(9), LongMemory.valueOf(2), "mod", null, null, 0).toLong());
    }

    @Test public void oneHopProofDoesNotReplaceAnOverriddenReferenceGetter() {
        ReferenceMemory observed = new ReferenceMemory(Memory.NULL) {
            @Override public Memory getValue() { return LongMemory.valueOf(41); }
        };
        ReferenceMemory alias = new ReferenceMemory(observed);
        assertEquals(41, alias.toValue().toLong());
        assertSame(observed, alias.getReference());
        assertSame(Memory.NULL, observed.getRawValue());
    }

    @Test public void coldDivisionDoesNotReadAnIntegerSubclassBeforeItsUsualOrder() {
        StringBuilder calls = new StringBuilder();
        LongMemory numerator = new LongMemory(9) {
            @Override public long toLong() { calls.append('L'); return super.toLong(); }
            @Override public double toDouble() { calls.append('D'); return super.toDouble(); }
        };
        assertEquals(4.5, PhpArithmetic.binary(numerator, LongMemory.valueOf(2), "div", null, null, 0).toDouble(), 0);
        assertEquals("LD", calls.toString());
    }

    @Test public void assignmentFallbackKeepsVirtualEvaluationAndInvalidProfileOrder() {
        StringBuilder calls = new StringBuilder();
        ReferenceMemory right = new ReferenceMemory(LongMemory.valueOf(2)) {
            @Override public Memory toValue() { calls.append('R'); return super.toValue(); }
        };
        ReferenceMemory left = new ReferenceMemory(LongMemory.valueOf(1)) {
            @Override public Memory toValue() { calls.append('L'); right.assign(9); return super.toValue(); }
        };
        assertEquals(10, PhpArithmetic.assign(left, right, "plus", null, null, 0).toLong());
        assertEquals("LR", calls.toString());
        calls.setLength(0);
        try { PhpArithmetic.assign(left, right, "plus", null, null, 99); fail("Invalid profile accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("", calls.toString()); }
        ReferenceMemory ordinary = new ReferenceMemory(LongMemory.valueOf(4));
        assertEquals(7, PhpArithmetic.assign(ordinary, LongMemory.valueOf(3), new String("plus"), null, null, 0).toLong());
        assertEquals(7, ordinary.toLong());
    }

    @Test public void propertyReferenceOverflowRetainsTypeGuardsAcrossSourceAndDump() throws Throwable {
        String source = "declare(strict_types=1);class NumericProperty{public int $n=9223372036854775807;}"
                + "$o=new NumericProperty();$r=&$o->n;$failed=false;try{$r+=1;}catch(TypeError $e){$failed=true;}"
                + "return [$failed,$o->n,$r];";
        for (PhpOptimizationProfile profile : PhpOptimizationProfile.values()) for (boolean dumped : new boolean[]{false, true}) {
            Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
            try {
                Context context = new Context(source);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                context.setPhpOptimizationProfile(profile);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                Memory result = module.include(env);
                assertEquals(profile == PhpOptimizationProfile.COMPATIBLE, result.valueOfIndex(0).toBoolean());
                long expected = profile == PhpOptimizationProfile.COMPATIBLE ? Long.MAX_VALUE : Long.MIN_VALUE;
                assertEquals(expected, result.valueOfIndex(1).toLong());
                assertEquals(expected, result.valueOfIndex(2).toLong());
            } finally { env.doFinal(); }
        }
    }
}
