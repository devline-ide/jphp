package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.helper.ArrayKeyMemory;
import php.runtime.memory.helper.ArrayValueMemory;
import static org.junit.Assert.*;

/** Structural lvalues and Java overridden getters are not canonical explicit-reference allocations. */
public class PhpCanonicalCellIdentityTest {
    @Test public void testPromotionAndAliasRebindingExposeOnlyTerminalCanonicalCell() {
        ArrayMemory array = new ArrayMemory();
        ReferenceMemory first = new ReferenceMemory(array);
        assertFalse(first.isCanonicalReferenceCell());
        ReferenceMemory cell = first.getReferenceForBinding();
        assertTrue(cell.isCanonicalReferenceCell());
        assertSame(array, cell.getRawValue());
        assertSame(cell, first.getRawValue());
        ReferenceMemory alias = new ReferenceMemory(); alias.assignRef(cell);
        assertFalse(alias.isCanonicalReferenceCell()); assertSame(cell, alias.getRawValue());
        ReferenceMemory other = new ReferenceMemory(LongMemory.valueOf(9)).getReferenceForBinding();
        alias.assignRef(other);
        assertSame(other, alias.getRawValue()); assertSame(cell, first.getRawValue());
        assertTrue(cell.isCanonicalReferenceCell()); assertTrue(other.isCanonicalReferenceCell());
        // Java may construct a nested reference chain; its non-terminal head is not a resolved identity.
        cell.assignRef(other);
        assertFalse(cell.isCanonicalReferenceCell()); assertSame(other, cell.getRawValue());
    }

    @Test public void testRawCarrierInspectionNeverRunsAnOverriddenValueOrReferenceGetter() {
        Memory raw = LongMemory.valueOf(7);
        ReferenceMemory getter = new ReferenceMemory(raw) {
            @Override public Memory getValue() { throw new AssertionError("PHP getter evaluated"); }
            @Override public ReferenceMemory getReference() { throw new AssertionError("carrier canonicalized"); }
        };
        assertSame(raw, getter.getRawValue()); assertFalse(getter.isCanonicalReferenceCell());
        ArrayMemory array = new ArrayMemory();
        ReferenceMemory cell = new ReferenceMemory(raw).getReferenceForBinding();
        ArrayValueMemory valueCarrier = new ArrayValueMemory(LongMemory.valueOf(0), array, cell);
        assertSame(cell, valueCarrier.getRawValue()); assertFalse(valueCarrier.isCanonicalReferenceCell());
        ArrayKeyMemory keyCarrier = new ArrayKeyMemory(array, LongMemory.valueOf(0));
        assertFalse(keyCarrier.isCanonicalReferenceCell()); assertEquals(0, keyCarrier.getRawValue().toLong());
    }
}
