package php.runtime;

import org.junit.Test;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.memory.*;
import php.runtime.lang.StdClass;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

/** Actual OBJECT/TABLE publishers receive ARRAY children before their source producer is released. */
public class PhpOwnershipStdClassTest {
    private static final class Fixture implements AutoCloseable {
        final CompileScope scope=new CompileScope();
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final Environment env=new Environment(scope,output);
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority=new PhpRuntimeOwnership.Authority(env,domain);
        final PhpOwnershipDomain.Execution execution=authority.enter();
        final PhpRuntimeOwnership.Frame frame=authority.frame();
        final PhpOwnershipStdClass.Plan plan=PhpOwnershipStdClass.prepare(env);
        Fixture(){scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);}
        ArrayMemory fresh(int id,ArrayMemory array){return PhpOwnershipArrays.runtimeFresh(frame,id,array);}
        ReferenceMemory local(int id)throws Throwable {
            var carrier=new ReferenceMemory(Memory.UNDEFINED);
            try(var publication=frame.prepareLocal(id,"local"+id,carrier,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){publication.afterLocalStore();publication.commit();}
            return carrier;
        }
        @Override public void close()throws Exception {
            frame.close();authority.close();try{domain.collectCycles();}catch(Throwable failure){throw new RuntimeException(failure);}assertEquals(0,domain.graph().liveNodes());execution.close();
            try{env.doFinal();}catch(Throwable failure){throw new RuntimeException(failure);}
        }
    }
    @Test public void arrayValueChildMovesFromActualBucketToObjectTableWithoutExtraOwner()throws Throwable {
        try(var f=new Fixture()) {
            var child=f.fresh(100,new ArrayMemory());var childNode=child.getRuntimeOrigin().node();
            var parent=new ArrayMemory();parent.put("value",child.toImmutable());f.fresh(101,parent);
            var sourceNode=parent.getRuntimeOrigin().node();assertEquals(2,childNode.incoming());
            var result=(ObjectMemory)f.plan.cast(f.frame,102,101,parent);var object=(StdClass)result.value;
            assertEquals(PhpOwnership.State.DISPOSED,sourceNode.state());assertNull(f.frame.evalHolder(101));
            var table=object.getOwnershipObject().properties();var carrier=object.getProperties().getByScalar("value");
            assertSame(child.getStorageIdentity(),carrier.toValue(ArrayMemory.class).getStorageIdentity());
            assertEquals(PhpOwnershipValues.Role.VALUE,table.entry(carrier).ownerPublisher().current().binding().role());
            assertEquals(2,childNode.incoming());f.frame.releaseEval(100);assertEquals(1,childNode.incoming());
            f.frame.releaseEval(102);assertEquals(PhpOwnership.State.DISPOSED,childNode.state());
        }
    }
    @Test public void canonicalCellChildKeepsAliasesAndItsSinglePayloadPublisher()throws Throwable {
        try(var f=new Fixture()) {
            var alias=f.local(0);alias.assign(LongMemory.valueOf(9));var cell=alias.getReferenceForBinding();var node=cell.getRuntimeCell().node();
            var parent=new ArrayMemory();parent.put("ref",cell);f.fresh(100,parent);assertEquals(2,node.incoming());
            var result=(ObjectMemory)f.plan.cast(f.frame,101,100,parent);var object=(StdClass)result.value;
            var carrier=object.getProperties().getByScalar("ref");assertSame(cell,carrier.getRawValue());assertEquals(2,node.incoming());
            alias.assign(LongMemory.valueOf(11));assertEquals(11,carrier.toLong());
            assertSame(cell.getRuntimeCell().payload(),cell.getRuntimeHolder());
            f.frame.releaseEval(101);assertEquals(1,node.incoming());alias.unset();assertEquals(PhpOwnership.State.DISPOSED,node.state());
        }
    }
    @Test public void emptyAndNumericKeyArraysUseTheExactBuiltinClassAndStringPropertyKeys()throws Throwable {
        try(var f=new Fixture()) {
            var empty=f.fresh(100,new ArrayMemory());var result=(ObjectMemory)f.plan.cast(f.frame,101,100,empty);
            assertEquals(StdClass.class,result.value.getClass());assertSame(f.env.fetchClass(StdClass.class),result.value.getReflection());assertEquals(0,result.value.getProperties().size());
            var source=new ArrayMemory();source.put(LongMemory.valueOf(3),LongMemory.valueOf(8));source.put("value",Memory.NULL);f.fresh(102,source);
            var second=(ObjectMemory)f.plan.cast(f.frame,103,102,source);assertEquals(8,second.value.getProperties().getByScalar("3").toLong());assertTrue(second.value.getProperties().getByScalar("value").isNull());
        }
    }
    @Test public void foreignUnownedConsumedAndAliasedResultIdsRejectBeforeConstruction()throws Throwable {
        try(var f=new Fixture()) {
            var source=f.fresh(100,new ArrayMemory());int nodes=f.domain.graph().liveNodes();
            assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,101,100,new ArrayMemory()));
            assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,100,100,source));
            assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,101,999,source));
            assertEquals(nodes,f.domain.graph().liveNodes());assertEquals(0,f.output.size());assertNull(f.frame.evalHolder(101));assertSame(source,f.frame.evalHolder(100).raw());
            f.plan.cast(f.frame,101,100,source);
            assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,102,100,source));assertNull(f.frame.evalHolder(102));
        }
    }
    @Test public void publishedResultSlotCanBeReusedWithoutRetainingPreviousObjects()throws Throwable {
        try(var f=new Fixture()) {
            var destination=f.local(0);PhpRuntimeOwnership.Holder reused=null;PhpOwnership.Node previous=null;
            for(int iteration=0;iteration<4;iteration++) {
                var source=f.fresh(100,new ArrayMemory());
                var result=(ObjectMemory)f.plan.cast(f.frame,101,100,source);
                var holder=f.frame.evalHolder(101);if(reused!=null)assertSame(reused,holder);reused=holder;
                var node=holder.node();assertEquals(1,node.incoming());
                f.frame.assignLocalFromEval(101,0,result,0);
                assertSame(reused,f.frame.evalHolder(101));assertTrue(reused.raw().isUndefined());assertNull(reused.node());
                assertEquals(1,node.incoming());assertSame(result.value,destination.toValue(ObjectMemory.class).value);
                if(previous!=null)assertEquals(PhpOwnership.State.DISPOSED,previous.state());previous=node;
            }
            destination.unset();assertEquals(PhpOwnership.State.DISPOSED,previous.state());
        }
    }
    @Test public void occupiedResultRejectsBeforeConstructionAndKeepsBothActualProducers()throws Throwable {
        try(var f=new Fixture()) {
            var first=f.fresh(100,new ArrayMemory());var result=f.plan.cast(f.frame,101,100,first);
            var second=f.fresh(100,new ArrayMemory());var holder=f.frame.evalHolder(101);int nodes=f.domain.graph().liveNodes();
            assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,101,100,second));
            assertEquals(nodes,f.domain.graph().liveNodes());assertSame(result,holder.raw());assertSame(second,f.frame.evalHolder(100).raw());
            assertEquals(0,f.output.size());
        }
    }
    @Test public void emptyDiagnosticAndUnconsumedScalarResultsCannotBecomeAllocationProducers()throws Throwable {
        try(var f=new Fixture()) {
            var source=f.fresh(100,new ArrayMemory());
            try(var publication=f.frame.prepareEval(101,Memory.NULL,PhpRuntimeOwnership.Role.VALUE)){publication.storeDirect();publication.commit();}
            int nodes=f.domain.graph().liveNodes();
            assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,101,100,source));
            assertEquals(nodes,f.domain.graph().liveNodes());assertSame(Memory.NULL,f.frame.evalHolder(101).raw());
            try(var allocation=f.authority.arrays().allocate(false)) {
                assertThrows(IllegalArgumentException.class,()->f.frame.stageAllocation(101,allocation));
                assertSame(Memory.NULL,f.frame.evalHolder(101).raw());assertEquals(1,allocation.binding().node().incoming());
            }
            assertEquals(nodes,f.domain.graph().liveNodes());
            try(var diagnostic=f.frame.pinDiagnostic(source)) {
                var holder=diagnostic.holder();assertSame(holder,f.frame.evalHolder(Integer.MIN_VALUE));
                try(var publication=holder.prepare(Memory.UNDEFINED,PhpRuntimeOwnership.Role.VALUE)){publication.storeDirect();publication.commit();}
                nodes=f.domain.graph().liveNodes();
                assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,Integer.MIN_VALUE,100,source));
                assertEquals(nodes,f.domain.graph().liveNodes());assertTrue(holder.raw().isUndefined());assertNull(holder.node());
            }
            assertSame(source,f.frame.evalHolder(100).raw());assertEquals(0,f.output.size());
        }
    }
    @Test public void allocationPublicationReusesOnlyConsumedActualArrayResults()throws Throwable {
        try(var f=new Fixture()) {
            var destination=f.local(0);PhpRuntimeOwnership.Holder reused=null;PhpOwnership.Node previous=null;
            for(int iteration=0;iteration<4;iteration++)try(var allocation=f.authority.arrays().allocate(false)) {
                var raw=f.frame.stageAllocation(100,allocation);var holder=f.frame.evalHolder(100);var node=holder.node();
                if(reused!=null)assertSame(reused,holder);reused=holder;
                f.frame.assignLocalFromEval(100,0,raw,0);assertTrue(holder.raw().isUndefined());assertNull(holder.node());
                assertEquals(1,node.incoming());if(previous!=null)assertEquals(PhpOwnership.State.DISPOSED,previous.state());previous=node;
            }
            destination.unset();assertEquals(PhpOwnership.State.DISPOSED,previous.state());
        }
    }
    @Test public void changedBuiltinDescriptorRejectsBeforeEffectsAndPreservesSourceProducer()throws Throwable {
        try(var f=new Fixture()) {
            var source=f.fresh(100,new ArrayMemory());var entity=f.env.fetchClass(StdClass.class);String name=entity.getName();int nodes=f.domain.graph().liveNodes();
            try{entity.setName("AlteredStdClass");assertThrows(IllegalArgumentException.class,()->f.plan.cast(f.frame,101,100,source));}
            finally{entity.setName(name);}
            assertEquals(nodes,f.domain.graph().liveNodes());assertSame(source,f.frame.evalHolder(100).raw());assertNull(f.frame.evalHolder(101));assertEquals(0,f.output.size());
            f.plan.validate();f.plan.cast(f.frame,101,100,source);
        }
    }
}
