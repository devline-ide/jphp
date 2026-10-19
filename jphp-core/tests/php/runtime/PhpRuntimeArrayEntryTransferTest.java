package php.runtime;

import org.junit.Test;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Exact producer transfer into actual ARRAY addresses, independent of whole script admission. */
public class PhpRuntimeArrayEntryTransferTest {
    private static final class Fixture implements AutoCloseable {
        final Environment env=new Environment(new ByteArrayOutputStream());
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
        final PhpOwnershipDomain.Execution execution=authority.enter();
        final PhpRuntimeOwnership.Frame frame=authority.frame();
        ReferenceMemory local(int id) throws Throwable {
            var carrier=new ReferenceMemory(Memory.UNDEFINED);
            try(var publication=frame.prepareLocal(id,"local"+id,carrier,PhpRuntimeOwnership.Role.BORROW_ADDRESS)) {
                publication.afterLocalStore();publication.commit();
            }
            return carrier;
        }
        ArrayMemory fresh(int eval) throws Throwable {return PhpOwnershipArrays.runtimeFresh(frame,eval,new ArrayMemory());}
        @Override public void close() throws Exception {
            frame.close();authority.close();execution.close();
            try{env.doFinal();}catch(Throwable failure){throw new RuntimeException(failure);}
            assertEquals(0,domain.graph().liveNodes());
        }
    }
    @Test public void appendMovesActualProducerAndExactImmutableView() throws Throwable {
        try(var f=new Fixture()) {
            var local=f.local(0);var parent=f.fresh(100);local.assign(parent);f.frame.releaseEval(100);
            var child=f.fresh(101);var node=child.getRuntimeOrigin().node();
            parent.assignRuntimeEntry(null,child.toImmutable(),f.frame,101,f.env,TraceInfo.UNKNOWN);
            assertTrue(f.frame.evalHolder(101).raw().isUndefined());
            assertEquals(1,node.incoming());assertSame(child.getStorageIdentity(),parent.getByScalar(LongMemory.valueOf(0)).toValue(ArrayMemory.class).getStorageIdentity());
            parent.removeByScalar(LongMemory.valueOf(0));assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void existingCellKeepsAliasesWhileValueProducerReplacesPayload() throws Throwable {
        try(var f=new Fixture()) {
            var local=f.local(0);var parent=f.fresh(100);local.assign(parent);f.frame.releaseEval(100);
            var alias=f.local(1);alias.assign(LongMemory.valueOf(9));var cell=alias.getReferenceForBinding();parent.put("ref",cell);
            var cellNode=cell.getRuntimeCell().node();assertEquals(2,cellNode.incoming());
            var child=f.fresh(101);parent.assignRuntimeEntry(new php.runtime.memory.StringMemory("ref"),child,f.frame,101,f.env,TraceInfo.UNKNOWN);
            assertSame(cell,parent.getByScalar("ref").getRawValue());assertEquals(2,cellNode.incoming());
            assertSame(child,alias.toValue());assertEquals(1,child.getRuntimeOrigin().node().incoming());
            parent.removeByScalar("ref");assertEquals(1,cellNode.incoming());assertSame(child,alias.toValue());
        }
    }
    @Test public void wrongOrForeignProducerCannotPublishAndLeavesSharedBackingUnchanged() throws Throwable {
        try(var f=new Fixture()) {
            var local=f.local(0);var parent=f.fresh(100);local.assign(parent);f.frame.releaseEval(100);
            var child=f.fresh(101);
            var copy=f.local(1);copy.assign(parent.toImmutable());var backing=parent.getStorageIdentity();
            try{parent.assignRuntimeEntry(null,child,f.frame,999,f.env,TraceInfo.UNKNOWN);fail("Unknown producer admitted");}
            catch(IllegalArgumentException expected){}
            assertEquals(0,parent.size());assertSame(child,f.frame.evalHolder(101).raw());
            assertSame(backing,local.toValue(ArrayMemory.class).getStorageIdentity());assertEquals(2,parent.getRuntimeOrigin().node().incoming());
            var sink=f.fresh(102);sink.assignRuntimeEntry(null,child,f.frame,101,f.env,TraceInfo.UNKNOWN);
            try{parent.assignRuntimeEntry(null,child,f.frame,101,f.env,TraceInfo.UNKNOWN);fail("Consumed producer admitted again");}
            catch(IllegalArgumentException expected){}
            assertTrue(f.frame.evalHolder(101).raw().isUndefined());assertEquals(1,child.getRuntimeOrigin().node().incoming());
            assertEquals(0,parent.size());assertSame(backing,local.toValue(ArrayMemory.class).getStorageIdentity());
            assertEquals(2,parent.getRuntimeOrigin().node().incoming());
            try(var other=f.authority.frame()) {
                try{parent.assignRuntimeEntry(null,child,other,101,f.env,TraceInfo.UNKNOWN);fail("Foreign Frame admitted");}
                catch(IllegalArgumentException expected){}
                assertEquals(0,parent.size());
            }
        }
    }
    @Test public void diagnosticSurvivalCountsOnlyActualValueOwnersAndRejectsProducerExclusion() throws Throwable {
        try(var f=new Fixture()) {
            var parent=f.fresh(100);var origin=parent.getRuntimeOrigin();var producer=f.frame.evalHolder(100);
            try{origin.hasOwnersAfterDiagnostic(producer);fail("Producer accepted as diagnostic exclusion");}
            catch(IllegalArgumentException expected){}
            assertEquals(1,origin.node().incoming());assertSame(parent,producer.raw());
            var local=f.local(0);
            try(var diagnostic=f.frame.pinDiagnostic(parent)) {
                assertTrue(origin.hasOwnersAfterDiagnostic(diagnostic.holder()));assertEquals(2,origin.node().incoming());
                local.assign(parent);assertTrue(origin.hasOwnersAfterDiagnostic(diagnostic.holder()));assertEquals(3,origin.node().incoming());
                f.frame.releaseEval(100);assertTrue(origin.hasOwnersAfterDiagnostic(diagnostic.holder()));assertEquals(2,origin.node().incoming());
                local.unset();assertFalse(origin.hasOwnersAfterDiagnostic(diagnostic.holder()));assertEquals(1,origin.node().incoming());
            }
            assertEquals(PhpOwnership.State.DISPOSED,origin.node().state());
        }
    }
}
