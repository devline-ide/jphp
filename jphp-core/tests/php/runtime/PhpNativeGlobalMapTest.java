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
    @Test public void ordinaryPhysicalCursorKeepsSurvivingBucketAcrossNativeSnapshots() {
        for(boolean rawNumeric:new boolean[]{false,true})for(boolean scalarRemoval:new boolean[]{false,true})for(int snapshots=0;snapshots<=2;snapshots++) {
            String label="rawNumeric="+rawNumeric+",scalarRemoval="+scalarRemoval+",nativeSnapshots="+snapshots;
            var array=php.runtime.memory.ArrayMemory.createHashed(3);
            array.insertBucket(rawNumeric?"0":"x",Memory.CONST_INT_1);
            array.insertBucket("y",LongMemory.valueOf(2));
            array.insertBucket(rawNumeric?Memory.CONST_INT_0:"x",LongMemory.valueOf(3));
            assertEquals(label,1,array.getCurrentIteratorForNative().getValue().toLong());
            var writable=array;
            for(int i=0;i<snapshots;i++)writable=writable.toImmutableForNativeRead();
            writable.checkCopied();
            var first=writable.bucketAt(0);var removedBucket=writable.bucketAt(2);
            assertNotSame(label,first,removedBucket);
            Memory removed=scalarRemoval?writable.removeByScalar(rawNumeric?0L:"x")
                    :writable.remove(rawNumeric?Memory.CONST_INT_0:new php.runtime.memory.StringMemory("x"));
            assertNotNull(label,removed);assertEquals(label,3,removed.toLong());
            assertEquals(label,2,writable.size());assertSame(label,first,writable.bucketAt(0));assertNull(label,writable.bucketAt(2));
            assertEquals(label,1,first.toLong());
            var current=writable.getCurrentIteratorForNative();
            assertEquals(label,Memory.Type.STRING,current.getMemoryKey().getRealType());
            assertEquals(label,rawNumeric?"0":"x",current.getMemoryKey().toString());
            assertNotNull(label,current.getValue());assertEquals(label,1,current.getValue().toLong());
        }
    }

    private static php.runtime.memory.ArrayMemory cowCursorArray(int layout) {
        var a=layout==0?new php.runtime.memory.ArrayMemory():php.runtime.memory.ArrayMemory.createHashed();
        for(int i=0;i<3;i++) {
            var value=php.runtime.memory.ArrayMemory.ofLongs(101+i);
            if(layout==0)a.add(value);
            else if(layout==1)a.put("k"+i,value);
            else {if(i==1)a.appendBucketHole();a.insertBucket(i==2?"x":"x"+i,value);}
        }
        return a;
    }

    private static void cowCursorPosition(php.runtime.lang.ForeachIterator cursor,int state) {
        switch(state) {
            case 0:break;
            case 1:cursor.next();break;
            case 2:cursor.next();cursor.next();break;
            case 3:cursor.end();break;
            case 4:cursor.next();cursor.next();cursor.prev();break;
            case 5:while(cursor.next()){}break;
            case 6:cursor.next();cursor.prev();cursor.prev();break;
            default:throw new AssertionError(state);
        }
    }

    private static void cowCursorState(String label,php.runtime.lang.ForeachIterator expected,php.runtime.lang.ForeachIterator actual) {
        assertEquals(label+" key",expected.getMemoryKey().getRealType(),actual.getMemoryKey().getRealType());
        assertEquals(label+" key",expected.getMemoryKey().toString(),actual.getMemoryKey().toString());
        if(expected.getValue()==null)assertNull(label+" value",actual.getValue());
        else {
            assertNotNull(label+" value",actual.getValue());
            var a=(php.runtime.memory.ArrayMemory)actual.getValue().toValue();
            var e=(php.runtime.memory.ArrayMemory)expected.getValue().toValue();
            assertEquals(label+" payload",e.getByScalar(Memory.CONST_INT_0).toLong(),a.getByScalar(Memory.CONST_INT_0).toLong());
        }
    }

    private static void cowCursorOwnValue(String label,php.runtime.memory.ArrayMemory array) {
        var cursor=array.getCurrentIterator();
        if(cursor.getKey()==null)return;
        if(array.hasBucketLayout()) {
            boolean actual=false;
            for(int i=0;i<array.bucketLimit();i++)if(array.bucketAt(i)!=null&&array.bucketAt(i).getValue()==cursor.getValue())actual=true;
            assertTrue(label+" cursor must use a writer-local physical payload",actual);
        } else {
            var entry=array.getByScalar(php.runtime.memory.ArrayMemory.toKey(cursor.getMemoryKey()));
            assertNotNull(label+" selected writer-local cell",entry);
            assertSame(label+" cursor must use its writer-local cell payload",entry.getValue(),cursor.getValue());
        }
    }

    @Test public void ordinaryCowForkPreservesContinuationAndWriterLocalPayloads() {
        // Public foreach remains the independent preexisting continuation oracle, including list end/prev quirks.
        for(int layout=0;layout<3;layout++)for(int state=0;state<7;state++)for(int detach=0;detach<3;detach++) {
            String label="layout="+layout+",state="+state+",detach="+detach;
            var source=cowCursorArray(layout);var controlA=cowCursorArray(layout);var controlB=cowCursorArray(layout);
            var a=source.getCurrentIterator();var expectedA=controlA.foreachIterator(false,true);var expectedB=controlB.foreachIterator(false,true);
            cowCursorPosition(a,state);cowCursorPosition(expectedA,state);cowCursorPosition(expectedB,state);
            var copy=(php.runtime.memory.ArrayMemory)source.toImmutable();
            assertNotSame(label,a,copy.getCurrentIterator());cowCursorState(label,expectedA,source.getCurrentIterator());
            cowCursorState(label,expectedB,copy.getCurrentIterator());
            if(detach==1)source.checkCopied();if(detach==2)copy.checkCopied();
            cowCursorOwnValue(label,source);cowCursorOwnValue(label,copy);
            boolean next=expectedB.next();assertEquals(label,next,copy.getCurrentIterator().next());
            cowCursorState(label,expectedB,copy.getCurrentIterator());cowCursorState(label,expectedA,source.getCurrentIterator());
            if(state==6) {
                // The old map foreach oracle dereferences its discarded key iterator here. The contract stays false.
                for(int repeat=0;repeat<3;repeat++)assertFalse(label+" prev remains false before first, repeat="+repeat,source.getCurrentIterator().prev());
                assertNull(label+" before-first key",source.getCurrentIterator().getKey());
                assertNull(label+" before-first payload",source.getCurrentIterator().getValue());
            } else assertEquals(label,expectedA.prev(),source.getCurrentIterator().prev());
            cowCursorState(label,expectedA,source.getCurrentIterator());
            cowCursorState(label,expectedB,copy.getCurrentIterator());
            var second=(php.runtime.memory.ArrayMemory)copy.toImmutable();second.checkCopied();
            assertEquals(label,expectedB.next(),second.getCurrentIterator().next());cowCursorState(label,expectedB,second.getCurrentIterator());
            cowCursorOwnValue(label,second);
        }
        // A native read freezes a position but a subsequent mutable VALUE copy resumes the exact saved continuation.
        for(int layout=0;layout<3;layout++)for(int state=1;state<7;state++) {
            String label="native layout="+layout+",state="+state;
            var source=cowCursorArray(layout);var control=cowCursorArray(layout);var expected=control.foreachIterator(false,true);
            cowCursorPosition(source.getCurrentIterator(),state);cowCursorPosition(expected,state);
            var read=source.toImmutableForNativeRead();var frozen=read.getCurrentIterator();
            cowCursorState(label,expected,frozen);assertFalse(label,frozen.next());assertFalse(label,frozen.prev());cowCursorState(label,expected,frozen);
            source.getCurrentIterator().next();cowCursorState(label,expected,frozen);
            var writable=(php.runtime.memory.ArrayMemory)read.toImmutable();writable.checkCopied();
            assertEquals(label,expected.next(),writable.getCurrentIterator().next());cowCursorState(label,expected,writable.getCurrentIterator());cowCursorOwnValue(label,writable);
        }
        // A list becomes a map after an interior unset. Copied key continuation remains independent through detach.
        for(boolean detachSource:new boolean[]{false,true}) {
            var source=cowCursorArray(0);source.getCurrentIteratorForNative().next();
            var copy=(php.runtime.memory.ArrayMemory)source.toImmutable();
            var writer=detachSource?source:copy;var survivor=detachSource?copy:source;
            writer.checkCopied();writer.removeByScalar(0L);
            assertTrue(writer.isMap());assertEquals(1,writer.getCurrentIterator().getMemoryKey().toLong());
            assertEquals(102,((php.runtime.memory.ArrayMemory)writer.getCurrentIterator().getValue()).getByScalar(Memory.CONST_INT_0).toLong());
            assertTrue(writer.getCurrentIterator().next());assertEquals(2,writer.getCurrentIterator().getMemoryKey().toLong());
            assertEquals(1,survivor.getCurrentIterator().getMemoryKey().toLong());cowCursorOwnValue("cross-representation",writer);
        }
    }

    @Test public void ordinaryCowMapToBucketMigrationPreservesPhysicalCurrentAndContinuation() {
        // Unique-map migration keeps the preexisting continuation, not a canonical seek to its current key.
        for(int state=0;state<7;state++)for(int transport=0;transport<3;transport++) {
            String label="migration state="+state+",transport="+transport;
            var source=cowCursorArray(1);var control=cowCursorArray(1);
            var expected=control.foreachIterator(false,true);
            cowCursorPosition(source.getCurrentIterator(),state);cowCursorPosition(expected,state);
            php.runtime.memory.ArrayMemory array=source;
            if(transport==1)array=(php.runtime.memory.ArrayMemory)source.toImmutable();
            if(transport==2)array=(php.runtime.memory.ArrayMemory)source.toImmutableForNativeRead().toImmutable();
            // An already-materialized before-first pointer stays before first across native read.
            array.checkCopied();array.enableBucketLayout();
            cowCursorState(label,expected,array.getCurrentIterator());cowCursorOwnValue(label,array);
            var copy=(php.runtime.memory.ArrayMemory)array.toImmutable();copy.checkCopied();
            assertEquals(label,expected.next(),copy.getCurrentIterator().next());
            cowCursorState(label,expected,copy.getCurrentIterator());cowCursorOwnValue(label,copy);
            if(state==6) {
                for(int repeat=0;repeat<3;repeat++)assertFalse(label,copy.getCurrentIterator().prev());
                assertNull(label,copy.getCurrentIterator().getKey());assertNull(label,copy.getCurrentIterator().getValue());
            } else {
                assertEquals(label,expected.prev(),copy.getCurrentIterator().prev());
                cowCursorState(label,expected,copy.getCurrentIterator());
            }
        }
        // A sibling's layout change must not invalidate this view's still-unique cursor/backing pair.
        for(boolean nativeRead:new boolean[]{false,true}) {
            var source=cowCursorArray(1);source.getCurrentIterator().next();source.getCurrentIterator().next();
            var sibling=nativeRead?source.toImmutableForNativeRead():(php.runtime.memory.ArrayMemory)source.toImmutable();
            var payload=source.getCurrentIterator().getValue();sibling.enableBucketLayout();
            assertSame(payload,source.getCurrentIterator().getValue());assertFalse(source.hasBucketLayout());
            source.checkCopied();source.removeByScalar("k1");
            assertEquals("k2",source.getCurrentIterator().getMemoryKey().toString());
            assertEquals(103,((php.runtime.memory.ArrayMemory)source.getCurrentIterator().getValue()).getByScalar(Memory.CONST_INT_0).toLong());
            assertEquals("k1",sibling.getCurrentIterator().getMemoryKey().toString());
            assertEquals(102,((php.runtime.memory.ArrayMemory)sibling.getCurrentIterator().getValue()).getByScalar(Memory.CONST_INT_0).toLong());
        }
        for(boolean duplicate:new boolean[]{false,true}) {
            var array=cowCursorArray(1);var cursor=array.getCurrentIterator();cursor.next();cursor.next();
            var original=array.getByScalar("k1");var payload=cursor.getValue();
            // insertBucket itself must capture the original cell before its duplicate becomes the hash winner.
            if(duplicate)array.insertBucket("k1",php.runtime.memory.ArrayMemory.ofLongs(999));
            else array.enableBucketLayout();
            assertSame(payload,array.getCurrentIterator().getValue());
            var copy=(php.runtime.memory.ArrayMemory)array.toImmutable();copy.checkCopied();
            assertEquals(102,((php.runtime.memory.ArrayMemory)copy.getCurrentIterator().getValue()).getByScalar(Memory.CONST_INT_0).toLong());
            cowCursorOwnValue("migrated physical copy",copy);
            array.removeByScalar("k1");
            if(duplicate) {
                assertSame(original,array.getByScalar("k1"));assertSame(payload,array.getCurrentIterator().getValue());
                assertTrue(array.getCurrentIterator().next());
            }
            assertEquals("k2",array.getCurrentIterator().getMemoryKey().toString());
            assertEquals(103,((php.runtime.memory.ArrayMemory)array.getCurrentIterator().getValue()).getByScalar(Memory.CONST_INT_0).toLong());
        }
    }

    @Test public void nativePointerKeepsPhysicalIdentityCopiesMutationAndLegacyBoundary() throws Throwable {
        for(int transport=0;transport<3;transport++) {
            String label="native pointer transport="+transport;
            var source=php.runtime.memory.ArrayMemory.createHashed();
            source.put("a",LongMemory.valueOf(11));source.put("b",LongMemory.valueOf(22));source.put("c",LongMemory.valueOf(33));
            var original=source.getCurrentPointerForNative();assertTrue(label,original.next());
            var copy=transport==0?(php.runtime.memory.ArrayMemory)source.toImmutable():source.toImmutableForNativeRead();
            if(transport==2)copy=(php.runtime.memory.ArrayMemory)copy.toImmutable();
            copy.checkCopied();var pointer=copy.getMutableCurrentIteratorForNative();
            assertTrue(label,pointer.prev());assertEquals(label,"a",pointer.getMemoryKey().toString());
            assertEquals(label,"b",original.getMemoryKey().toString());
            assertTrue(label,pointer.next());copy.put("b",LongMemory.valueOf(222));assertEquals(label,222,pointer.getValue().toLong());
            copy.enableBucketLayout();copy.insertBucket("b",LongMemory.valueOf(999));
            assertEquals(label,222,pointer.getValue().toLong());copy.removeByScalar("b");
            assertEquals(label,222,pointer.getValue().toLong());assertTrue(label,pointer.next());
            assertEquals(label,"c",pointer.getMemoryKey().toString());assertTrue(label,pointer.prev());
            assertEquals(label,222,pointer.getValue().toLong());copy.removeByScalar("b");
            assertEquals(label,"c",pointer.getMemoryKey().toString());assertEquals(label,33,pointer.getValue().toLong());
            assertTrue(label,pointer.end());assertFalse(label,pointer.next());assertFalse(label,pointer.prev());
            copy.insertBucket("new",LongMemory.valueOf(44));copy.insertBucket("later",LongMemory.valueOf(55));
            assertEquals(label,"new",pointer.getMemoryKey().toString());assertEquals(label,44,pointer.getValue().toLong());
            copy.removeByScalar("new");assertEquals(label,"later",pointer.getMemoryKey().toString());
            assertEquals(label,"b",original.getMemoryKey().toString());assertEquals(label,22,original.getValue().toLong());
        }
        var list=php.runtime.memory.ArrayMemory.ofLongs(11,22,33);var listPointer=list.getCurrentPointerForNative();listPointer.next();
        var listCopy=(php.runtime.memory.ArrayMemory)list.toImmutable();listCopy.checkCopied();
        var listCopyPointer=listCopy.getMutableCurrentIteratorForNative();listCopy.removeByScalar(0L);
        assertTrue(listCopy.isMap());assertEquals(1,listCopyPointer.getMemoryKey().toLong());assertEquals(22,listCopyPointer.getValue().toLong());
        assertFalse(listCopyPointer.prev());assertFalse(listCopyPointer.next());
        listCopy.put(LongMemory.valueOf(3),LongMemory.valueOf(44));assertEquals(3,listCopyPointer.getMemoryKey().toLong());
        assertEquals(1,listPointer.getMemoryKey().toLong());assertEquals(22,listPointer.getValue().toLong());
        // Equal-looking raw numeric keys are distinct physical addresses, including through VALUE copies.
        var physical=php.runtime.memory.ArrayMemory.createHashed();
        physical.insertBucket(LongMemory.valueOf(0),LongMemory.valueOf(10));
        physical.insertBucket("0",LongMemory.valueOf(20));physical.insertBucket("tail",LongMemory.valueOf(30));
        var pointer=physical.getCurrentPointerForNative();assertEquals(10,pointer.getValue().toLong());
        assertTrue(pointer.next());assertTrue(pointer.getMemoryKey().isString());assertEquals(20,pointer.getValue().toLong());
        var fork=(php.runtime.memory.ArrayMemory)physical.toImmutable();fork.checkCopied();
        var forkPointer=fork.getMutableCurrentIteratorForNative();var removedNumeric=fork.removeByScalar("0");
        // removeByScalar canonicalizes numeric strings. The selected raw-string bucket is not that address.
        assertNotNull(removedNumeric);assertEquals(10,removedNumeric.toLong());assertNull(fork.bucketAt(0));
        assertNotNull(fork.bucketAt(1));assertEquals(20,fork.bucketAt(1).toLong());
        assertTrue(forkPointer.getMemoryKey().isString());assertEquals("0",forkPointer.getMemoryKey().toString());
        assertEquals(20,forkPointer.getValue().toLong());assertTrue(forkPointer.next());
        assertEquals("tail",forkPointer.getMemoryKey().toString());assertTrue(forkPointer.prev());
        assertTrue(forkPointer.getMemoryKey().isString());assertEquals(20,forkPointer.getValue().toLong());
        assertEquals(20,pointer.getValue().toLong());
        var currentNumeric=(php.runtime.memory.ArrayMemory)physical.toImmutable();currentNumeric.checkCopied();
        var numericPointer=currentNumeric.getMutableCurrentIteratorForNative();assertTrue(numericPointer.prev());
        assertFalse(numericPointer.getMemoryKey().isString());assertEquals(10,numericPointer.getValue().toLong());
        assertEquals(10,currentNumeric.removeByScalar(0L).toLong());
        assertTrue(numericPointer.getMemoryKey().isString());assertEquals(20,numericPointer.getValue().toLong());
        assertTrue(numericPointer.next());assertEquals("tail",numericPointer.getMemoryKey().toString());
        assertTrue(pointer.getMemoryKey().isString());assertEquals(20,pointer.getValue().toLong());
        // Empty/before-first revival belongs to the first successful insert, not the last later append.
        var empty=new php.runtime.memory.ArrayMemory();var emptyPointer=empty.getCurrentPointerForNative();
        assertFalse(emptyPointer.prev());empty.add(Memory.FALSE);empty.add(LongMemory.valueOf(2));
        assertEquals(0,emptyPointer.getMemoryKey().toLong());assertSame(Memory.FALSE,emptyPointer.getValue());
        var merged=php.runtime.memory.ArrayMemory.createHashed();var mergedPointer=merged.getCurrentPointerForNative();
        var incoming=php.runtime.memory.ArrayMemory.createHashed();incoming.put("first",LongMemory.valueOf(1));incoming.put("second",LongMemory.valueOf(2));
        merged.putAllRef(incoming);assertEquals("first",mergedPointer.getMemoryKey().toString());assertTrue(mergedPointer.next());
        assertEquals("second",mergedPointer.getMemoryKey().toString());
        // Only an already-native cursor uses current-then-advance each; legacy-only continuation remains unchanged.
        var scope=new php.runtime.env.CompileScope();scope.setPhpLanguageProfile(php.runtime.common.PhpLanguageProfile.JPHP_LEGACY);
        var env=new php.runtime.env.Environment(scope);Throwable primary=null;
        try {
            var array=php.runtime.memory.ArrayMemory.createHashed();array.put("a",LongMemory.valueOf(11));array.put("b",LongMemory.valueOf(22));array.put("c",LongMemory.valueOf(33));
            var ref=new ReferenceMemory(array);array.getCurrentPointerForNative().next();
            var each=org.develnext.jphp.zend.ext.standard.ArrayFunctions.each(env,php.runtime.env.TraceInfo.UNKNOWN,ref);
            assertEquals("b",each.valueOfIndex("key").toString());assertEquals(22,each.valueOfIndex("value").toLong());
            assertEquals("c",array.getCurrentPointerForNative().getMemoryKey().toString());
            assertEquals(11,org.develnext.jphp.zend.ext.standard.ArrayFunctions.reset(env,php.runtime.env.TraceInfo.UNKNOWN,ref).toLong());
            assertFalse(array.hasNativeCurrentIterator());
            // Existing legacy reset intentionally leaves foreach.next on first; it is not silently modernized.
            assertEquals(11,org.develnext.jphp.zend.ext.standard.ArrayFunctions.next(env,php.runtime.env.TraceInfo.UNKNOWN,ref).toLong());
            scope.setPhpLanguageProfile(php.runtime.common.PhpLanguageProfile.PHP_8_5);
            assertEquals(11,org.develnext.jphp.zend.ext.standard.ArrayFunctions.current(env,php.runtime.env.TraceInfo.UNKNOWN,ref).toLong());
            assertEquals(22,org.develnext.jphp.zend.ext.standard.ArrayFunctions.next(env,php.runtime.env.TraceInfo.UNKNOWN,ref).toLong());
            var legacyCopy=(php.runtime.memory.ArrayMemory)array.toImmutableForLegacy();
            assertFalse(array.hasNativeCurrentIterator());assertFalse(legacyCopy.hasNativeCurrentIterator());
            assertEquals("a",array.getCurrentPointerForNative().getMemoryKey().toString());
            assertEquals("a",legacyCopy.getCurrentPointerForNative().getMemoryKey().toString());
        }catch(Throwable failure){primary=failure;throw failure;}
        finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
    }

    private static php.runtime.memory.ArrayMemory liveOverwriteArray(int representation, Memory payload) {
        var array=representation==0 ? new php.runtime.memory.ArrayMemory() : php.runtime.memory.ArrayMemory.createHashed();
        if(representation==0) {array.add(payload);array.add(LongMemory.valueOf(99));}
        else if(representation==1) {array.put("x",payload);array.put("tail",LongMemory.valueOf(99));}
        else {array.insertBucket("x",payload);array.insertBucket("x",LongMemory.valueOf(88));array.insertBucket("tail",LongMemory.valueOf(99));}
        return array;
    }
    private static void overwriteSelectedLiveSlot(php.runtime.memory.ArrayMemory array,int representation,Memory replacement) {
        if(representation==0)array.put(Memory.CONST_INT_0,replacement);
        else if(representation==1)array.put("x",replacement);
        else array.bucketAt(0).assign(replacement); // Exact first bucket, not its equal-key hash winner.
    }
    private static java.lang.ref.WeakReference<Memory> installSelectedOverwritePayload(php.runtime.memory.ArrayMemory array,int representation) {
        Memory payload=php.runtime.memory.ArrayMemory.ofLongs(1234567);
        overwriteSelectedLiveSlot(array,representation,payload);
        assertSame(payload,array.getCurrentIterator().getValue());
        return new java.lang.ref.WeakReference<>(payload);
    }
    private static void assertOverwritePayloadCollects(String label,java.lang.ref.WeakReference<Memory> weak,Object... roots) throws InterruptedException {
        for(int attempt=0;attempt<80 && weak.get()!=null;attempt++) {
            System.gc();Thread.sleep(10);
            java.lang.ref.Reference.reachabilityFence(roots);
        }
        assertNull(label,weak.get());java.lang.ref.Reference.reachabilityFence(roots);
    }

    @Test public void genericLiveCurrentReadsOverwritesWithoutRetainingPayload() throws Exception {
        for(int representation=0;representation<3;representation++) {
            String label="live overwrite representation="+representation;
            var array=liveOverwriteArray(representation,Memory.NULL);
            var cursor=array.getCurrentIterator();assertTrue(label,cursor.next());
            var weak=installSelectedOverwritePayload(array,representation);
            overwriteSelectedLiveSlot(array,representation,LongMemory.valueOf(777));
            assertEquals(label,777,cursor.getValue().toLong());
            assertOverwritePayloadCollects(label,weak,array,cursor);
            assertEquals(label,777,cursor.getValue().toLong());
            if(representation==2)assertEquals(label,88,array.bucketAt(1).toLong());
            // Both VALUE forks read their own current carrier after writer-local detach/overwrite.
            var copy=(php.runtime.memory.ArrayMemory)array.toImmutable();copy.checkCopied();
            var copied=copy.getCurrentIterator();overwriteSelectedLiveSlot(copy,representation,LongMemory.valueOf(888));
            assertEquals(label,888,copied.getValue().toLong());assertEquals(label,777,cursor.getValue().toLong());
            if(representation==1) {
                copy.enableBucketLayout();copy.bucketAt(0).assign(LongMemory.valueOf(999));
                assertEquals(label,999,copy.getCurrentIterator().getValue().toLong());
                assertEquals(label,777,cursor.getValue().toLong());
            }
        }
        // Readonly-fork -> ordinary VALUE -> detached writer clears the readonly payload cache.
        var array=liveOverwriteArray(1,LongMemory.valueOf(1));array.getCurrentIterator().next();
        var frozen=array.toImmutableForNativeRead();var writer=(php.runtime.memory.ArrayMemory)frozen.toImmutable();writer.checkCopied();
        writer.put("x",LongMemory.valueOf(2));assertEquals(2,writer.getCurrentIterator().getValue().toLong());
        assertEquals(1,frozen.getCurrentIterator().getValue().toLong());assertEquals(1,array.getCurrentIterator().getValue().toLong());
    }

    @Test public void readonlyCurrentAndOrdinaryForeachKeepCapturedValuesUntilReleased() throws Exception {
        for(int representation=0;representation<3;representation++) {
            String label="captured VALUE representation="+representation;
            var array=liveOverwriteArray(representation,Memory.NULL);array.getCurrentIterator().next();
            var weak=installSelectedOverwritePayload(array,representation);
            var frozen=array.toImmutableForNativeRead();array.checkCopied();
            overwriteSelectedLiveSlot(array,representation,LongMemory.valueOf(777));
            System.gc();assertNotNull(label,weak.get());
            assertSame(label,weak.get(),frozen.getCurrentIterator().getValue());
            assertFalse(label,frozen.getCurrentIterator().next());assertFalse(label,frozen.getCurrentIterator().prev());
            assertEquals(label,777,array.getCurrentIterator().getValue().toLong());
            java.lang.ref.Reference.reachabilityFence(frozen);frozen=null;
            assertOverwritePayloadCollects(label+" readonly released",weak,array);
            // Ordinary foreach VALUE deliberately captures a yielded value even if its live slot changes.
            var yielded=liveOverwriteArray(representation,Memory.NULL);
            var current=yielded.getCurrentIterator();current.next();
            weak=installSelectedOverwritePayload(yielded,representation);
            var foreach=yielded.foreachIterator(false,false,false);assertTrue(label,foreach.next());
            overwriteSelectedLiveSlot(yielded,representation,LongMemory.valueOf(888));
            System.gc();assertNotNull(label,weak.get());assertSame(label,weak.get(),foreach.getValue());
            assertEquals(label,888,current.getValue().toLong());
            assertTrue(label,foreach.next());assertNotSame(label,weak.get(),foreach.getValue());
            assertOverwritePayloadCollects(label+" foreach advanced",weak,yielded,current,foreach);
        }
    }
}
