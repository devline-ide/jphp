package php.runtime;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.support.ArrayMapEntryMemory;

import static org.junit.Assert.*;

/** Constructor origin is metadata only; successful raw stores retire it even for an identical value. */
public class PhpSyntheticGlobalsOriginTest {
    private static Environment environment() {return new Environment(new CompileScope());}
    private static void rejects(Runnable operation) {
        try {operation.run();fail("Expected rejection");}catch(IllegalArgumentException expected) {}
    }
    @Test public void identicalAssignmentAndReferencePromotionRetireExactCreationStamp() {
        var env=environment();var origin=env.getSyntheticGlobalsOrigin();var carrier=origin.carrier();
        assertSame(origin,carrier.getSyntheticGlobalsOrigin());origin.validateCarrier(carrier);
        assertSame(env.getRuntimeGlobalsTable(),carrier.toValue());assertFalse(origin.isRetired());
        carrier.assign(env.getRuntimeGlobalsTable());assertTrue(origin.isRetired());assertNull(carrier.getSyntheticGlobalsOrigin());
        var retiredOrigin=origin;var retiredCarrier=carrier;rejects(()->retiredOrigin.validateCarrier(retiredCarrier));
        env=environment();origin=env.getSyntheticGlobalsOrigin();carrier=origin.carrier();
        var canonical=carrier.getReferenceForBinding();assertNotSame(carrier,canonical);
        assertSame(env.getRuntimeGlobalsTable(),canonical.toValue());assertTrue(origin.isRetired());
    }
    @Test public void unsetRekeyAndRebindRetireOnlyAfterSuccessfulCarrierMutation() {
        var env=environment();var origin=env.getSyntheticGlobalsOrigin();origin.carrier().unset();
        assertTrue(origin.isRetired());assertSame(Memory.UNDEFINED,origin.carrier().getRawValue());
        env=environment();origin=env.getSyntheticGlobalsOrigin();
        ((ArrayMapEntryMemory)origin.carrier()).setKey("user");assertTrue(origin.isRetired());
        env=environment();origin=env.getSyntheticGlobalsOrigin();
        var target=new ReferenceMemory(LongMemory.valueOf(9));origin.carrier().assignRef(target);
        assertTrue(origin.isRetired());assertEquals(9,origin.carrier().toLong());
    }
    @Test public void forgedOrDuplicateAttachmentsRejectWithoutRetiringGenuineOrigin() {
        var env=environment();var origin=env.getSyntheticGlobalsOrigin();var carrier=origin.carrier();
        rejects(()->new ReferenceMemory(env.getRuntimeGlobalsTable()).attachSyntheticGlobalsOrigin(origin));
        rejects(()->carrier.attachSyntheticGlobalsOrigin(origin));
        rejects(()->carrier.attachGlobalTableBorrow(null));
        assertFalse(origin.isRetired());assertSame(origin,carrier.getSyntheticGlobalsOrigin());origin.validateCarrier(carrier);
        assertSame(env.getRuntimeGlobalsTable(),carrier.getRawValue());
    }
}
