package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.NullCoalesceAssignment;
import php.runtime.PhpArithmetic;
import php.runtime.PhpPropertyWriteContext;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Address capture, nested COW and reference publication stay independent of target storage. */
public class PhpArrayTargetStorageTest extends JvmCompilerCase {
    @Test public void appendColdConversionDoesNotRepeatCapturedReadsOrRhsConversion() throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        class ObservedReference extends ReferenceMemory {
            int reads, conversions;
            ObservedReference(Memory value) { super(value); }
            @Override public Memory toValue() { reads++; return super.toValue(); }
            @Override public Memory toImmutable() { conversions++; return super.toImmutable(); }
        }
        try {
            ObservedReference root = new ObservedReference(Memory.NULL);
            ObservedReference rhs = new ObservedReference(LongMemory.valueOf(7));
            NullCoalesceAssignment.variable(root, env, TraceInfo.UNKNOWN).append().assign(rhs);
            assertEquals("one captured fetch and one fetch after null conversion", 2, root.reads);
            assertEquals("RHS value conversion happens once", 1, rhs.conversions);
            assertEquals(7, root.valueOfIndex(0).toLong());
        } finally { env.doFinal(); }
    }

    @Test public void capturedKeyAndNestedWritesKeepTheirOriginalAddressAndCow() throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try {
            ArrayMemory row = new ArrayMemory();
            row.put("leaf", LongMemory.valueOf(3));
            ArrayMemory array = new ArrayMemory();
            array.put("row", row);
            ArrayMemory snapshot = (ArrayMemory) array.toImmutable();
            ReferenceMemory root = new ReferenceMemory(array);
            ReferenceMemory key = new ReferenceMemory(new StringMemory("row"));
            NullCoalesceAssignment target = NullCoalesceAssignment.variable(root, env, TraceInfo.UNKNOWN)
                    .index(key).index(new StringMemory("leaf"));
            key.assign("different");
            target.assign(LongMemory.valueOf(9));
            assertEquals(3, snapshot.valueOfIndex("row").valueOfIndex("leaf").toLong());
            assertEquals(9, root.valueOfIndex("row").valueOfIndex("leaf").toLong());
            assertTrue(root.valueOfIndex("different").isUndefined());
        } finally { env.doFinal(); }
    }

    @Test public void independentAppendPathsAndReferenceAssignmentsDoNotShareState() throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try {
            ReferenceMemory one = new ReferenceMemory(new ArrayMemory());
            ReferenceMemory two = new ReferenceMemory(new ArrayMemory());
            NullCoalesceAssignment first = NullCoalesceAssignment.variable(one, env, TraceInfo.UNKNOWN).append();
            NullCoalesceAssignment second = NullCoalesceAssignment.variable(two, env, TraceInfo.UNKNOWN).append();
            second.assign(LongMemory.valueOf(2));
            first.assign(LongMemory.valueOf(1));
            NullCoalesceAssignment.variable(one, env, TraceInfo.UNKNOWN).append().assign(LongMemory.valueOf(3));
            ReferenceMemory alias = new ReferenceMemory(LongMemory.valueOf(4));
            NullCoalesceAssignment.variable(two, env, TraceInfo.UNKNOWN).index(new StringMemory("alias")).assignRef(alias);
            alias.assign(8);
            assertEquals(1, one.valueOfIndex(0).toLong());
            assertEquals(3, one.valueOfIndex(1).toLong());
            assertEquals(2, two.valueOfIndex(0).toLong());
            assertTrue(two.valueOfIndex(1).isUndefined());
            assertEquals(8, two.valueOfIndex("alias").toLong());
        } finally { env.doFinal(); }
    }

    @Test public void numericIncrementPreservesPostResultCommitAndBothOverflowPolicies() throws Throwable {
        Environment env = new Environment(newScope(), OutputStream.nullOutputStream());
        try {
            for (PhpOptimizationProfile profile : PhpOptimizationProfile.values()) {
                PhpPropertyWriteContext.Frame frame = PhpPropertyWriteContext.enter(env, TraceInfo.UNKNOWN, false, profile.getId());
                try {
                    for (boolean decrement : new boolean[]{false, true}) {
                        long edge = decrement ? Long.MIN_VALUE : Long.MAX_VALUE;
                        ReferenceMemory target = new ReferenceMemory(LongMemory.valueOf(decrement ? edge + 1 : edge - 1));
                        AtomicReference<Memory> committed = new AtomicReference<>();
                        Memory before = PhpArithmetic.incrementReference(target, decrement, true, env, TraceInfo.UNKNOWN, committed::set);
                        assertEquals(decrement ? edge + 1 : edge - 1, before.toLong());
                        assertEquals(edge, target.toLong());
                        assertSame(target.toValue(), committed.get());
                        Memory overflow = PhpArithmetic.incrementReference(target, decrement, false, env, TraceInfo.UNKNOWN);
                        assertEquals(profile == PhpOptimizationProfile.COMPATIBLE ? Memory.Type.DOUBLE : Memory.Type.INT, overflow.type);
                        if (profile == PhpOptimizationProfile.FAST_INT64_WRAP)
                            assertEquals(decrement ? Long.MAX_VALUE : Long.MIN_VALUE, overflow.toLong());
                    }
                } finally { PhpPropertyWriteContext.leave(frame); }
            }
        } finally { env.doFinal(); }
    }
}
