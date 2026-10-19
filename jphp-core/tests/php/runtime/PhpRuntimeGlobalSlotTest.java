package php.runtime;

import org.junit.Test;
import php.runtime.env.Environment;
import php.runtime.memory.*;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Actual Environment symbol addresses; no whole-table ARRAY root or retrospective heap import. */
public class PhpRuntimeGlobalSlotTest {
    private static final class Fixture implements AutoCloseable {
        final Environment env=new Environment(new ByteArrayOutputStream());
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
        final PhpOwnershipDomain.Execution execution=authority.enter();
        final PhpRuntimeOwnership.Frame frame=authority.frame();
        ReferenceMemory local(int id)throws Throwable {
            ReferenceMemory carrier=new ReferenceMemory(Memory.UNDEFINED);
            try(var p=frame.prepareLocal(id,"local"+id,carrier,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){p.afterLocalStore();p.commit();}
            return carrier;
        }
        @Override public void close()throws Exception{frame.close();authority.close();execution.close();assertEquals(0,domain.graph().liveNodes());}
    }
    @Test public void missingReadDoesNotLinkButNullStoreAndReferenceExportDo()throws Throwable {
        try(var f=new Fixture()) {
            var slot=f.authority.globalSlot("x");var carrier=slot.carrier();
            assertFalse(slot.isLinked());assertTrue(slot.read().isUndefined());assertNull(f.env.getRuntimeGlobalsTable().getByScalar("x"));
            slot.assign(Memory.NULL);assertTrue(slot.isLinked());assertSame(carrier,f.env.getRuntimeGlobalsTable().getByScalar("x"));
            slot.unset();assertFalse(slot.isLinked());assertSame(carrier,slot.carrier());
            var cell=slot.reference();var cellNode=cell.getRuntimeCell().node();assertTrue(slot.isLinked());assertTrue(cell.getRawValue().isNull());assertEquals(1,cellNode.incoming());
            slot.unset();assertFalse(slot.isLinked());assertEquals(PhpOwnership.State.DISPOSED,cellNode.state());
        }
    }
    @Test public void unsetAndRecreateKeepsEscapedCellAndDoesNotMultiplyPayloadOwners()throws Throwable {
        try(var f=new Fixture()) {
            var array=PhpOwnershipArrays.runtimeFresh(f.frame,100,new ArrayMemory());var node=array.getRuntimeOrigin().node();
            var slot=f.authority.globalSlot("a");slot.assign(array);f.frame.releaseEval(100);assertEquals(1,node.incoming());
            var alias=f.local(0);var old=slot.reference();alias.assignRef(old);assertEquals(2,old.getRuntimeCell().node().incoming());assertEquals(1,node.incoming());
            var carrier=slot.carrier();slot.unset();assertNull(f.env.getRuntimeGlobalsTable().getByScalar("a"));assertEquals(1,old.getRuntimeCell().node().incoming());assertSame(array,alias.toValue());
            slot.assign(LongMemory.valueOf(9));assertSame(carrier,f.env.getRuntimeGlobalsTable().getByScalar("a"));assertEquals(9,slot.read().toLong());assertSame(array,alias.toValue());
            alias.unset();assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void globalValueCopiesAreRealStorageOwnersAcrossUnlinkAndRecreate()throws Throwable {
        try(var f=new Fixture()) {
            var array=PhpOwnershipArrays.runtimeFresh(f.frame,100,new ArrayMemory());var node=array.getRuntimeOrigin().node();
            var a=f.authority.globalSlot("a");a.assign(array);f.frame.releaseEval(100);
            assertEquals(1,node.incoming());
            var copy=f.authority.globalSlot("copy");copy.assign(array.toImmutable());assertEquals(2,node.incoming());
            a.unset();assertEquals(1,node.incoming());assertTrue(a.read().isUndefined());
            a.assign(LongMemory.valueOf(7));assertTrue(a.isLinked());assertEquals(7,a.read().toLong());assertEquals(1,node.incoming());
            copy.unset();assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void syntheticConstructorSlotAndNamedUserGlobalsAreDistinct()throws Throwable {
        try(var f=new Fixture()) {
            var origin=f.env.getSyntheticGlobalsOrigin();var old=origin.carrier();var table=f.env.getRuntimeGlobalsTable();int size=table.size();
            var user=f.authority.globalSlot("GLOBALS");assertTrue(origin.isRetired());assertSame(table,old.getRawValue());assertNotSame(old,user.carrier());
            assertFalse(user.isLinked());assertEquals(size-1,table.size());assertNull(table.getByScalar("GLOBALS"));
            user.assign(LongMemory.valueOf(7));assertEquals(7,table.getByScalar("GLOBALS").toLong());assertSame(table,f.env.getRuntimeGlobalsTable());
            user.unset();assertNull(table.getByScalar("GLOBALS"));assertSame(table,old.getRawValue());
        }
    }
    @Test public void abandonedStoreAndInvalidKeyLeaveActualMembershipUnchanged()throws Throwable {
        try(var f=new Fixture()) {
            var slot=f.authority.globalSlot("x");var table=f.env.getRuntimeGlobalsTable();int size=table.size();
            try(var publication=slot.holder().prepare(LongMemory.valueOf(1),PhpRuntimeOwnership.Role.VALUE)){}
            assertFalse(slot.isLinked());assertEquals(size,table.size());assertTrue(slot.read().isUndefined());
            try{f.authority.globalSlot("3");fail("Non-normalized numeric key admitted");}catch(IllegalArgumentException expected){}
            assertEquals(size,table.size());var integer=f.authority.globalSlot(LongMemory.valueOf(3));integer.assign(LongMemory.valueOf(4));
            assertEquals(4,table.getByScalar(LongMemory.valueOf(3)).toLong());integer.unset();assertEquals(size,table.size());
        }
    }
    @Test public void unknownPreexistingHeapValueIsNotRetrospectivelyEnrolled()throws Throwable {
        try(var f=new Fixture()) {
            var unknown=new ArrayMemory();f.env.getRuntimeGlobalsTable().put("opaque",unknown);
            try{f.authority.globalSlot("opaque");fail("Raw preexisting ARRAY imported");}catch(IllegalArgumentException expected){}
            assertNull(unknown.getRuntimeOrigin());assertEquals(0,f.domain.graph().liveNodes());assertSame(unknown,f.env.getRuntimeGlobalsTable().getByScalar("opaque").getRawValue());
            var valid=f.authority.globalSlot("valid");valid.assign(LongMemory.valueOf(9));assertEquals(9,valid.read().toLong());
        }
    }
    @Test public void reverseResourceOrderClosesNonemptyGlobalsInItsOwnParticipant()throws Throwable {
        var env=new Environment(new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();PhpOwnership.Node node;
        try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
            try(var frame=authority.frame()) {
                var array=PhpOwnershipArrays.runtimeFresh(frame,4_000_070,new ArrayMemory());node=array.getRuntimeOrigin().node();
                authority.globalSlot("array").assign(array);authority.globalSlot("scalar").assign(LongMemory.valueOf(7));frame.releaseEval(4_000_070);
                assertEquals(1,node.incoming());
            }
        }
        assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(0,domain.graph().liveNodes());assertNull(env.getRuntimeGlobalsTable().getByScalar("array"));assertNull(env.getRuntimeGlobalsTable().getByScalar("scalar"));
    }
    @Test public void authorityShutdownRestoresAnAlreadySelectedForeignParticipant()throws Throwable {
        var env=new Environment(new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();var authority=new PhpRuntimeOwnership.Authority(env,domain);PhpOwnership.Node node;
        try(var execution=authority.enter();var frame=authority.frame()) {
            var array=PhpOwnershipArrays.runtimeFresh(frame,4_000_071,new ArrayMemory());node=array.getRuntimeOrigin().node();authority.globalSlot("array").assign(array);frame.releaseEval(4_000_071);
        }
        Object foreignContext=new Object();try(var foreign=domain.participant(foreignContext);var execution=foreign.enter()) {
            assertSame(foreignContext,domain.currentContext());authority.close();assertSame(foreignContext,domain.currentContext());
        }
        assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(0,domain.graph().liveNodes());
    }
}
