package php.runtime;

import org.junit.Test;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;

import static org.junit.Assert.*;

/** Cached ArrayMemory metadata follows the actual native map token, with no ARRAY owner. */
public class PhpNativeGlobalsAdapterTest {
    private static Environment environment() {return new Environment(new CompileScope());}
    private static void rejects(Runnable action) {
        try {action.run();fail("Expected rejection");}catch(IllegalArgumentException expected) {}
    }
    private static void store(ArrayMemory.NativeGlobals.NativeGlobalPlacement placement) {
        try(placement){placement.beginStore();placement.store();}
    }
    private static long frontier(ArrayMemory table,String name) throws Exception {
        var field=ArrayMemory.class.getDeclaredField(name);field.setAccessible(true);return field.getLong(table);
    }

    @Test public void syntheticRetirementAndNamedPlacementUpdateActualTableMirrors() throws Exception {
        var env=environment();var table=env.getRuntimeGlobalsTable();var origin=env.getSyntheticGlobalsOrigin();
        var oldCarrier=origin.carrier();int before=table.size();Object identity=table.getStorageIdentity();
        var facade=table.prepareNativeGlobals(env,carrier->{});
        assertSame(table,facade.actual());assertSame(identity,table.getStorageIdentity());assertNull(table.getRuntimeOrigin());
        var synthetic=facade.slot("GLOBALS");assertSame(oldCarrier,synthetic.carrier());
        try(var retirement=facade.prepareSyntheticRetirement(synthetic,origin)) {
            var fresh=retirement.slot();assertNotSame(oldCarrier,fresh.carrier());
            assertFalse(fresh.isLinked());assertSame(Memory.UNDEFINED,fresh.carrier().getRawValue());
            retirement.beginStore();retirement.store();
            assertEquals(before-1,table.size());facade.validate(env);
            assertTrue(origin.isRetired());assertSame(table,oldCarrier.getRawValue());
            assertSame(fresh,facade.slot("GLOBALS"));
        }
        var user=facade.slot("GLOBALS");store(facade.prepareLink(user));
        assertEquals(before,table.size());assertSame(user.carrier(),table.getByScalar("GLOBALS"));
        var integer=facade.slot(LongMemory.valueOf(9));store(facade.prepareLink(integer));
        assertEquals(before+1,table.size());assertEquals(9,frontier(table,"lastLongIndex"));
        assertEquals(10,frontier(table,"nextFreeElement"));
        store(facade.prepareUnlink(integer));assertEquals(before,table.size());
        assertEquals(9,frontier(table,"lastLongIndex"));assertEquals(10,frontier(table,"nextFreeElement"));
        assertSame(integer,facade.slot(LongMemory.valueOf(9)));
        facade.validate(env);assertNull(table.getRuntimeOrigin());
    }

    @Test public void foreignEnvironmentAndCarrierPreflightLeaveTableUnsealed() {
        var env=environment();var table=env.getRuntimeGlobalsTable();var other=environment();
        int before=table.size();Object identity=table.getStorageIdentity();
        rejects(()->table.prepareNativeGlobals(other,carrier->{}));
        rejects(()->table.prepareNativeGlobals(env,carrier->{throw new IllegalArgumentException("unproved carrier");}));
        assertEquals(before,table.size());assertSame(identity,table.getStorageIdentity());
        assertFalse(env.getSyntheticGlobalsOrigin().isRetired());
        table.put("stillOrdinary",LongMemory.valueOf(7));assertEquals(7,table.getByScalar("stillOrdinary").toLong());
        var facade=table.prepareNativeGlobals(env,carrier->{});facade.validate(env);
        rejects(()->table.prepareNativeGlobals(other,carrier->{}));
        assertNull(table.getRuntimeOrigin());assertSame(facade,table.prepareNativeGlobals(env,carrier->{}));
    }
}
