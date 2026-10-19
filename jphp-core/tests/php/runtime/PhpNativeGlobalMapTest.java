package php.runtime;

import org.junit.Test;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.support.ArrayMapEntryMemory;
import php.runtime.memory.support.ArrayMemoryMap;

import java.util.ConcurrentModificationException;
import java.util.Map;

import static org.junit.Assert.*;

/** Physical native-table placement; Environment/ownership activation is tested by the connected bridge. */
public class PhpNativeGlobalMapTest {
    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); fail("Expected " + type.getSimpleName()); }
        catch (Throwable failure) { if (!type.isInstance(failure)) throw new AssertionError(failure); }
    }
    private static void store(ArrayMemoryMap.NativeGlobalPlacement placement) {
        try (placement) { placement.beginStore(); placement.store(); }
    }

    @Test public void unlinkRelinkPreservesExactCarrierKeyAndCanonicalPayload() {
        var map = new ArrayMemoryMap(); var canonical = new ReferenceMemory(LongMemory.valueOf(10));
        map.put("x", canonical); var carrier = map.getEntry("x");
        var slots = map.sealNativeGlobals(); var x = slots.slot("x");
        assertSame(carrier, x.carrier()); store(slots.prepareUnlink(x));
        assertFalse(map.containsKey("x")); assertEquals(0, map.size()); assertFalse(x.isLinked());
        assertSame(canonical, carrier.getRawValue()); assertEquals(10, canonical.toLong()); assertEquals("x", carrier.getKey());
        assertSame(x, slots.slot("x")); assertFalse(map.containsKey("x"));
        store(slots.prepareLink(x));
        assertSame(carrier, map.getEntry("x")); assertSame(canonical, carrier.getRawValue());
        assertEquals(2, x.generation()); assertEquals(1, map.size());
    }

    @Test public void freshUnlinkedSlotDoesNotCreateMembershipUntilActualPlacement() {
        var map = new ArrayMemoryMap(); var slots = map.sealNativeGlobals(); var x = slots.slot("x");
        assertEquals(ArrayMapEntryMemory.class, x.carrier().getClass()); assertSame(Memory.UNDEFINED, x.carrier().getRawValue());
        assertFalse(x.isLinked()); assertEquals(0, map.size());
        try (var placement = slots.prepareLink(x)) { placement.validate(); }
        assertFalse(map.containsKey("x")); assertEquals(0, x.generation());
        store(slots.prepareLink(x)); assertSame(x.carrier(), map.getEntry("x")); assertEquals(1, x.generation());
    }

    @Test public void rehashAndCollisionChainsKeepStableSlotsAndOrder() {
        var map = new ArrayMemoryMap(); var slots = map.sealNativeGlobals();
        var stable = new ArrayMemoryMap.NativeGlobalSlot[96];
        for (int i = 0; i < stable.length; i++) { stable[i] = slots.slot("k" + i); store(slots.prepareLink(stable[i])); }
        for (int i = 0; i < stable.length; i++) assertSame(stable[i].carrier(), map.getEntry("k" + i));
        for (int i = 0; i < stable.length; i += 3) store(slots.prepareUnlink(stable[i]));
        for (int i = 0; i < stable.length; i += 3) store(slots.prepareLink(stable[i]));
        assertEquals(96, map.size());
        assertEquals("k1", map.firstKey()); assertEquals("k93", map.lastKey());
        for (int i = 0; i < stable.length; i++) assertSame(stable[i].carrier(), map.getEntry("k" + i));
    }

    @Test public void foreignStaleRepeatedAndCompetingPlacementsRejectBeforePointersChange() {
        var a = new ArrayMemoryMap(); var aa = a.sealNativeGlobals(); var x = aa.slot("x");
        var b = new ArrayMemoryMap(); var bb = b.sealNativeGlobals(); var foreign = bb.slot("x");
        rejects(IllegalArgumentException.class, () -> aa.prepareLink(foreign));
        var abandoned = aa.prepareLink(x); abandoned.close();
        rejects(IllegalArgumentException.class, abandoned::beginStore);
        try (var placement = aa.prepareLink(x)) {
            rejects(IllegalStateException.class, () -> aa.prepareLink(x));
            rejects(IllegalStateException.class, () -> aa.slot("other"));
            rejects(IllegalStateException.class, placement::store);
            placement.beginStore(); placement.store();
            rejects(IllegalStateException.class, placement::store);
        }
        assertSame(x.carrier(), a.getEntry("x")); assertEquals(1, a.size());
        rejects(IllegalArgumentException.class, () -> aa.prepareLink(x));
    }

    @Test public void escapedCarrierRekeyRejectsBeforeAnyPlacementOrKeyChange() {
        var map = new ArrayMemoryMap(); var slots = map.sealNativeGlobals(); var x = slots.slot("x");
        try (var placement = slots.prepareLink(x)) {
            rejects(IllegalStateException.class,()->x.carrier().setKey("forged"));
            assertSame(x.key(),x.carrier().getKey());assertEquals(0,map.size());placement.validate();
        }
        store(slots.prepareLink(x)); assertSame(x.carrier(), map.getEntry("x"));
    }

    @Test public void sealedStructuralAndRetainedViewMutationCannotCompete() {
        var map = new ArrayMemoryMap(); map.put("x", LongMemory.valueOf(1));
        var retainedKeys = map.keySet(); var retainedEntries = map.entrySet(); var iterator = map.mapIterator(); iterator.next();
        var slots = map.sealNativeGlobals();
        rejects(IllegalStateException.class, () -> map.put("x", LongMemory.valueOf(9)));
        rejects(IllegalStateException.class, () -> map.remove("x"));
        rejects(IllegalStateException.class, map::clear);
        rejects(IllegalStateException.class, () -> retainedKeys.remove("x"));
        rejects(IllegalStateException.class, () -> iterator.setValue(LongMemory.valueOf(9)));
        rejects(IllegalStateException.class, iterator::remove);
        Map.Entry<Object, Memory> readOnly = retainedEntries.iterator().next();
        rejects(UnsupportedOperationException.class, () -> readOnly.setValue(LongMemory.valueOf(9)));
        rejects(IllegalStateException.class, map::enableBucketLayout);
        rejects(IllegalStateException.class, map::appendBucketHole);
        rejects(IllegalStateException.class, () -> map.insertBucket("x", LongMemory.valueOf(9)));
        assertSame(slots.slot("x").carrier(), map.getEntry("x")); assertEquals(1, map.getEntry("x").toLong());
    }

    @Test public void normalizationAndSealingRejectUnprovedMapKinds() {
        var map = new ArrayMemoryMap(); var slots = map.sealNativeGlobals();
        rejects(IllegalStateException.class, map::sealNativeGlobals);
        rejects(IllegalArgumentException.class, () -> slots.slot("0"));
        rejects(IllegalArgumentException.class, () -> slots.slot("-1"));
        rejects(IllegalArgumentException.class, () -> slots.slot(Integer.valueOf(0)));
        var integer = slots.slot(LongMemory.valueOf(0)); var string = slots.slot("08");
        store(slots.prepareLink(integer)); store(slots.prepareLink(string)); assertEquals(2, map.size());
        assertSame(integer.carrier(), map.getEntry(LongMemory.valueOf(0))); assertSame(string.carrier(), map.getEntry("08"));
        var duplicates = new ArrayMemoryMap(); duplicates.enableBucketLayout();
        rejects(IllegalArgumentException.class, duplicates::sealNativeGlobals);
        var subclass = new ArrayMemoryMap() {}; rejects(IllegalArgumentException.class, subclass::sealNativeGlobals);
    }

    private static ArrayMemoryMap environmentMap(php.runtime.env.Environment environment) throws Exception {
        var field=php.runtime.memory.ArrayMemory.class.getDeclaredField("map");field.setAccessible(true);
        return (ArrayMemoryMap)field.get(environment.getRuntimeGlobalsTable());
    }

    @Test public void genuineConstructorRetirementLeavesDistinctUserSlotAbsentUntilStore() throws Exception {
        var env=new php.runtime.env.Environment(new php.runtime.env.CompileScope());
        var origin=env.getSyntheticGlobalsOrigin();var map=environmentMap(env);var slots=map.sealNativeGlobals();
        var old=slots.slot("GLOBALS");assertSame(origin.carrier(),old.carrier());int initial=map.size();
        try(var aborted=slots.prepareSyntheticRetirement(old,origin)) {
            assertNotSame(old.carrier(),aborted.slot().carrier());aborted.validate();
        }
        assertFalse(origin.isRetired());assertSame(old.carrier(),map.getEntry("GLOBALS"));
        var retirement=slots.prepareSyntheticRetirement(old,origin);var fresh=retirement.slot();store(retirement);
        assertTrue(origin.isRetired());assertNull(old.carrier().getSyntheticGlobalsOrigin());
        assertSame(env.getRuntimeGlobalsTable(),old.carrier().getRawValue());
        assertFalse(map.containsKey("GLOBALS"));assertEquals(initial-1,map.size());
        assertSame(fresh,slots.slot("GLOBALS"));assertSame(Memory.UNDEFINED,fresh.carrier().getRawValue());
        assertFalse(fresh.isLinked());assertNotSame(old.carrier(),fresh.carrier());
        rejects(IllegalArgumentException.class,old::validate);
        rejects(IllegalArgumentException.class,()->slots.prepareLink(old));
        fresh.carrier().assign(LongMemory.valueOf(7));store(slots.prepareLink(fresh));
        assertSame(fresh.carrier(),map.getEntry("GLOBALS"));assertEquals(7,map.getEntry("GLOBALS").toLong());
        assertSame(env.getRuntimeGlobalsTable(),old.carrier().getRawValue());assertEquals(initial,map.size());
    }

    @Test public void foreignOrMutatedConstructorOriginRejectsBeforeRetirement() throws Exception {
        var env=new php.runtime.env.Environment(new php.runtime.env.CompileScope());
        var other=new php.runtime.env.Environment(new php.runtime.env.CompileScope());
        var map=environmentMap(env);var slots=map.sealNativeGlobals();var old=slots.slot("GLOBALS");
        rejects(IllegalArgumentException.class,()->slots.prepareSyntheticRetirement(old,other.getSyntheticGlobalsOrigin()));
        assertSame(old.carrier(),map.getEntry("GLOBALS"));assertFalse(env.getSyntheticGlobalsOrigin().isRetired());
        old.carrier().assign(env.getRuntimeGlobalsTable());
        assertTrue(env.getSyntheticGlobalsOrigin().isRetired());
        rejects(IllegalArgumentException.class,()->slots.prepareSyntheticRetirement(old,env.getSyntheticGlobalsOrigin()));
        assertSame(old.carrier(),map.getEntry("GLOBALS"));
    }

    @Test public void changedFreshRetirementCarrierRejectsWithoutClearingConstructorOrigin() throws Exception {
        var env=new php.runtime.env.Environment(new php.runtime.env.CompileScope());
        var origin=env.getSyntheticGlobalsOrigin();var map=environmentMap(env);var slots=map.sealNativeGlobals();
        var old=slots.slot("GLOBALS");
        try(var prepared=slots.prepareSyntheticRetirement(old,origin)) {
            prepared.slot().carrier().assign(LongMemory.valueOf(4));
            rejects(IllegalArgumentException.class,prepared::beginStore);
        }
        assertSame(old.carrier(),map.getEntry("GLOBALS"));assertFalse(origin.isRetired());
        origin.validateCarrier(old.carrier());
        store(slots.prepareSyntheticRetirement(old,origin));assertFalse(map.containsKey("GLOBALS"));
    }

    @Test public void unrequestedAndUnlinkedCarriersKeepStructuralGuardAcrossPayloadPromotion() {
        var map=new ArrayMemoryMap();map.put("unrequested",LongMemory.valueOf(7));
        var escaped=map.getEntry("unrequested");var slots=map.sealNativeGlobals();
        rejects(IllegalStateException.class,()->escaped.setKey("changed"));
        rejects(IllegalStateException.class,()->escaped.setKey("unrequested"));
        assertEquals("unrequested",escaped.getKey());assertSame(escaped,map.getEntry("unrequested"));
        var canonical=escaped.getReferenceForBinding();assertNotSame(escaped,canonical);assertTrue(canonical.isCanonicalReferenceCell());
        escaped.assign(LongMemory.valueOf(9));assertEquals(9,canonical.toLong());
        rejects(IllegalStateException.class,()->escaped.setKey("after-promotion"));
        var slot=slots.slot("unrequested");store(slots.prepareUnlink(slot));
        rejects(IllegalStateException.class,()->escaped.setKey("after-unlink"));
        assertSame(canonical,escaped.getRawValue());store(slots.prepareLink(slot));assertEquals(9,escaped.toLong());
    }

    @Test public void structuralAttachmentsCannotBeForgedOrRepeated() {
        var map=new ArrayMemoryMap();var slots=map.sealNativeGlobals();var slot=slots.slot("x");
        rejects(IllegalArgumentException.class,()->new ReferenceMemory(Memory.UNDEFINED).prepareNativeGlobalSlot(slot));
        rejects(IllegalArgumentException.class,()->slot.carrier().prepareNativeGlobalSlot(slot));
        assertFalse(slot.isLinked());assertSame(Memory.UNDEFINED,slot.carrier().getRawValue());
        store(slots.prepareLink(slot));assertSame(slot.carrier(),map.getEntry("x"));
    }

    @Test public void constructorOriginAndRetiredCarrierRetainStructuralGuard() throws Exception {
        var env=new php.runtime.env.Environment(new php.runtime.env.CompileScope());
        var origin=env.getSyntheticGlobalsOrigin();var map=environmentMap(env);var old=map.getEntry("GLOBALS");
        var slots=map.sealNativeGlobals();assertSame(origin,old.getSyntheticGlobalsOrigin());
        rejects(IllegalStateException.class,()->old.setKey("changed"));assertFalse(origin.isRetired());origin.validateCarrier(old);
        var retirement=slots.prepareSyntheticRetirement(slots.slot("GLOBALS"),origin);var fresh=retirement.slot();
        rejects(IllegalStateException.class,()->fresh.carrier().setKey("changed"));store(retirement);
        assertTrue(origin.isRetired());assertNull(old.getSyntheticGlobalsOrigin());
        rejects(IllegalStateException.class,()->old.setKey("changed"));
        assertEquals("GLOBALS",old.getKey());assertSame(env.getRuntimeGlobalsTable(),old.getRawValue());
        assertSame(fresh,slots.slot("GLOBALS"));assertFalse(fresh.isLinked());
    }

    @Test public void ordinaryArraysKeepExistingMutationAndIteratorContracts() {
        var map = new ArrayMemoryMap(); map.put("x", LongMemory.valueOf(1));
        var iterator = map.mapIterator(); iterator.next(); iterator.setValue(LongMemory.valueOf(2));
        assertEquals(2, map.getEntry("x").toLong()); map.entrySet().iterator().next().setValue(LongMemory.valueOf(3));
        assertEquals(3, map.getEntry("x").toLong()); map.remove("x"); assertTrue(map.isEmpty());
        map.insertBucket("x", LongMemory.valueOf(4)); map.insertBucket("x", LongMemory.valueOf(1));
        assertEquals(2, map.size()); assertTrue(map.hasBucketLayout()); assertEquals(1, map.getEntry("x").toLong());
        map.clear(); assertEquals(0, map.size());
    }
}
