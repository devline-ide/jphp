package php.runtime;

import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Existing certified physical storage enters ordinary custody without another origin or edge set. */
public class PhpRuntimeCertifiedArrayCowTest extends JvmCompilerCase {
    private void publish(PhpOwnershipHeap.Publisher owner,PhpOwnershipValues.Binding value,Memory[] raw,int index)throws Throwable {
        Memory next=value.rawValue();
        try(var token=owner.prepare(owner.current(),value)){raw[index]=next;token.commit();}
    }
    @Test public void separatedAllocationBecomesOneActualEvalAndCannotRetainAgain()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        var authority=new PhpRuntimeOwnership.Authority(env,domain);
        try(var execution=authority.enter()) {
            var owners=new PhpOwnershipFrame(domain.graph());
            try(var frame=authority.frame()) {
                var arrays=authority.arrays();Memory[] raw=new Memory[2];
                var a=domain.heap().framePublisher(owners.local("a"));var b=domain.heap().framePublisher(owners.local("b"));
                try(var allocation=arrays.allocate(true)) {
                    allocation.insertBucket("key",PhpOwnershipValues.bindValue(domain,Memory.CONST_INT_1,null));
                    publish(a,allocation.binding(),raw,0);publish(b,allocation.binding(),raw,1);
                }
                var old=(ArrayMemory)raw[0];var oldNode=old.getOwnershipStorage().node();
                try(var write=arrays.prepareWrite(a,a.current())) {
                    assertTrue(write.separated());var copy=write.array();var node=write.certificate().node();
                    assertNull(copy.getRuntimeOrigin());assertEquals(1,node.incoming());
                    assertThrows(IllegalArgumentException.class,()->write.requireOwner(b));
                    assertSame(old,raw[0]);assertEquals(2,oldNode.incoming());assertEquals(1,node.incoming());
                    assertSame(copy,write.stageAllocation(frame,4_000_091));
                    assertSame(copy,frame.evalHolder(4_000_091).raw());assertEquals(1,node.incoming());
                    assertNull(copy.getRuntimeOrigin());write.requireOwner(a);
                    assertThrows(IllegalArgumentException.class,()->write.stageAllocation(frame,4_000_092));
                    assertThrows(IllegalStateException.class,write::preparePublication);
                    assertSame(old,raw[0]);assertEquals(2,oldNode.incoming());assertEquals(1,node.incoming());
                    frame.releaseEval(4_000_091);assertEquals(PhpOwnership.State.DISPOSED,node.state());
                }
            } finally {owners.close();authority.close();domain.collectCycles();assertEquals(0,domain.graph().liveNodes());}
        } finally {env.doFinal();}
    }
    @Test public void staleOwnerAndWrongCurrentFrameCannotStageCow()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        var authority=new PhpRuntimeOwnership.Authority(env,domain);
        try(var execution=authority.enter()) {
            var owners=new PhpOwnershipFrame(domain.graph());
            try(var frame=authority.frame()) {
                var arrays=authority.arrays();Memory[] raw=new Memory[2];
                var a=domain.heap().framePublisher(owners.local("a"));var b=domain.heap().framePublisher(owners.local("b"));
                try(var allocation=arrays.allocate(false)) {
                    allocation.append(PhpOwnershipValues.bindValue(domain,Memory.CONST_INT_1,null));
                    publish(a,allocation.binding(),raw,0);publish(b,allocation.binding(),raw,1);
                }
                var old=(ArrayMemory)raw[0];var oldNode=old.getOwnershipStorage().node();
                try(var write=arrays.prepareWrite(a,a.current())) {
                    var fresh=write.certificate().node();
                    try(var nested=authority.frame()) {
                        assertThrows(IllegalArgumentException.class,()->write.stageAllocation(frame,4_000_093));
                        assertNull(frame.evalHolder(4_000_093));assertEquals(1,fresh.incoming());assertEquals(2,oldNode.incoming());
                    }
                    publish(a,PhpOwnershipValues.bindValue(domain,Memory.NULL,null),raw,0);
                    assertThrows(IllegalArgumentException.class,()->write.requireOwner(a));
                    assertThrows(IllegalArgumentException.class,()->write.stageAllocation(frame,4_000_094));
                    assertNull(frame.evalHolder(4_000_094));assertEquals(1,fresh.incoming());assertEquals(1,oldNode.incoming());
                    assertSame(old,raw[1]);
                }
            } finally {owners.close();authority.close();domain.collectCycles();assertEquals(0,domain.graph().liveNodes());}
        } finally {env.doFinal();}
    }
    @Test public void actualAddressCowAndClassicCellResultTransferKeepExactOwnerCounts()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
        var authority=new PhpRuntimeOwnership.Authority(env,domain);
        try(var execution=authority.enter()) {
            try(var frame=authority.frame()) {
                var arrays=authority.arrays();var a=new php.runtime.memory.ReferenceMemory();
                var b=new php.runtime.memory.ReferenceMemory();var r=new php.runtime.memory.ReferenceMemory();
                for(int id=1;id<=3;id++) {
                    var carrier=id==1?a:id==2?b:r;
                    try(var publication=frame.prepareLocal(id,"cv"+id,carrier,PhpRuntimeOwnership.Role.BORROW_ADDRESS)) {
                        publication.afterLocalStore();publication.commit();
                    }
                }
                try(var allocation=arrays.allocate(true)) {
                    allocation.insertBucket("key",PhpOwnershipValues.bindValue(domain,Memory.CONST_INT_1,null));
                    var actual=frame.stageAllocation(4_000_095,allocation);
                    frame.assignLocalFromEval(4_000_095,1,actual,0);
                }
                frame.assignLocal(2,a.getRawValue().toImmutable(),0);
                var old=(ArrayMemory)a.getRawValue();var bView=(ArrayMemory)b.getRawValue();
                assertSame(old.getStorageIdentity(),bView.getStorageIdentity());var oldNode=old.getOwnershipStorage().node();
                assertEquals(2,oldNode.incoming());var writer=frame.arrayValueHolder(1,a);
                try(var write=arrays.prepareWrite(writer.borrowValuePublisher(),writer.borrowValuePublisher().current())) {
                    var copy=write.array();var fresh=write.certificate().node();
                    write.stageAllocation(frame,4_000_096);
                    assertThrows(IllegalArgumentException.class,()->frame.publishArrayCow(frame.arrayValueHolder(2,b),write,4_000_096));
                    assertThrows(IllegalArgumentException.class,()->frame.publishArrayCow(writer,write,4_000_098));
                    assertSame(old,a.getRawValue());assertSame(bView,b.getRawValue());
                    assertEquals(2,oldNode.incoming());assertEquals(1,fresh.incoming());
                    frame.publishArrayCow(writer,write,4_000_096);
                    assertSame(copy,a.getRawValue());assertSame(bView,b.getRawValue());
                    assertEquals(1,oldNode.incoming());assertEquals(1,fresh.incoming());
                    assertTrue(frame.evalHolder(4_000_096).raw().isUndefined());assertNull(copy.getRuntimeOrigin());
                    var slot=write.physicalSlot("key");php.runtime.memory.ReferenceMemory canonical;
                    try(var promotion=slot.preparePromotion(slot.ownerPublisher().current())) {
                        canonical=promotion.cell();promotion.store();promotion.commit();
                    }
                    assertNotNull(canonical.getOwnershipCell());assertNull(canonical.getRuntimeCell());
                    var cellNode=canonical.getOwnershipCell().node();assertEquals(1,cellNode.incoming());
                    frame.stageArrayReferenceResult(4_000_097,canonical);assertEquals(2,cellNode.incoming());
                    assertSame(canonical,frame.assignLocalFromEval(4_000_097,3,canonical,1));
                    assertSame(canonical,r.getRawValue());assertEquals(2,cellNode.incoming());
                    assertTrue(frame.evalHolder(4_000_097).raw().isUndefined());
                    frame.unsetLocal(3,r);assertEquals(1,cellNode.incoming());
                    assertEquals(1,old.getByScalar("key").toLong());
                }
            } finally {authority.close();domain.collectCycles();assertEquals(0,domain.graph().liveNodes());}
        } finally {env.doFinal();}
    }
}
