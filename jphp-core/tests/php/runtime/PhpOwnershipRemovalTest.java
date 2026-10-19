package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual addressed Memory unlinks; global execution ownership remains explicitly unactivated. */
public class PhpOwnershipRemovalTest extends JvmCompilerCase {
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain d,Memory value) {return PhpOwnershipValues.bindValue(d,value,null);}
    private void remove(PhpOwnershipArrays.UnsetPublication token) throws Throwable {try(token){token.store();token.commit();}}
    private final class Fixture implements AutoCloseable {
        final Environment env=new Environment(newScope(),new ByteArrayOutputStream());
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant=domain.participant(env);
        final PhpOwnershipDomain.Execution execution=participant.enter();
        final PhpOwnershipArrays arrays=new PhpOwnershipArrays(domain.heap());
        final PhpOwnershipObjects objects=new PhpOwnershipObjects(domain.heap(),arrays);
        Fixture(String code,boolean dump) throws Throwable {
            var context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dump){var output=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,output);module=dumper.load(new ByteArrayInputStream(output.toByteArray()));}
            env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
        }
        PhpOwnershipObjects.Allocation object(String name) throws Throwable {
            var cls=env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);
            var provider=objects.registerPropertiesProvider(cls,cls.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
            Map<PropertyEntity,PhpOwnershipValues.Binding> defaults=new IdentityHashMap<>();
            for(var p:provider.properties())defaults.put(p,scalar(domain,p.getDefaultValue(env).toImmutable()));
            return objects.allocate(provider,env,defaults);
        }
        public void close() throws Exception {participant.close();execution.close();try{env.doFinal();}catch(Throwable t){throw new AssertionError(t);}}
    }
    @Test public void listMiddleTailUnsetPreservesNumericKeysAppendAndCanonicalCell() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try(var execution=participant.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(false)){
            var arrays=new PhpOwnershipArrays(d.heap());allocation.append(scalar(d,Memory.CONST_INT_1));
            try(var cell=arrays.allocateCell(scalar(d,new LongMemory(2)))){
                allocation.appendCanonical(cell.binding());allocation.append(scalar(d,new LongMemory(3)));
                // Keep only the allocation root while using its real VALUE publisher as a borrowed address.
                var holder=arrays.allocateCell(allocation.binding());allocation.close();
                try(holder;var write=arrays.prepareWrite(holder.value(),holder.value().current())){
                    var old=write.entry(Memory.CONST_INT_1);var staleAddress=old.current();remove(write.prepareUnset(Memory.CONST_INT_0));
                    assertThrows(IllegalArgumentException.class,()->old.prepare(staleAddress,scalar(d,new LongMemory(6))));
                    assertFalse(write.array().isList());assertNull(write.array().getByScalar(Memory.CONST_INT_0));
                    assertEquals(2,write.array().getByScalar(Memory.CONST_INT_1).toLong());
                    assertSame(cell.cell(),write.array().getByScalar(Memory.CONST_INT_1).getRawValue());
                    write.entry(Memory.CONST_INT_1).assign(scalar(d,new LongMemory(7)));assertEquals(7,cell.cell().toLong());
                    assertSame(old,write.entry(Memory.CONST_INT_1));remove(write.prepareUnset(new LongMemory(2)));
                    assertEquals(1,write.array().size());assertEquals(1,write.certificate().node().outgoing().size());
                    write.append(scalar(d,new LongMemory(11)));assertNull(write.array().getByScalar(new LongMemory(2)));assertEquals(11,write.array().getByScalar(new LongMemory(3)).toLong());
                }
            }
        }finally{participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void abortStaleMissingAndPinnedRemovalAreExactBeforeMutation() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try(var execution=participant.enter()){
            var arrays=new PhpOwnershipArrays(d.heap());var allocation=arrays.allocate(true);var entry=allocation.insertBucket("x",scalar(d,Memory.CONST_INT_1));
            try(var holder=arrays.allocateCell(allocation.binding())){
                allocation.close();try(var write=arrays.prepareWrite(holder.value(),holder.value().current())){
                    assertThrows(IllegalArgumentException.class,()->write.prepareUnset(new Object()));
                    var retained=write.array().keySet();var before=entry.current();try(var token=write.prepareUnset("x")){assertTrue(token.existed());}
                    assertSame(before,entry.current());var stale=write.prepareUnset("x");entry.assign(scalar(d,new LongMemory(2)));
                    assertThrows(IllegalArgumentException.class,stale::store);stale.close();
                    try(var pin=write.array().pinWriteFetch()){remove(write.prepareUnset("x"));assertNull(write.array().getByScalar("x"));assertEquals(0,write.array().size());assertFalse(retained.contains("x"));}
                    assertThrows(IllegalStateException.class,entry::current);
                    try(var missing=write.prepareUnset("x")){assertFalse(missing.existed());missing.store();missing.commit();}
                }
            }
        }finally{participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void addressedCowRemovalPreservesOtherValueAndExternalCellAlias() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try(var execution=participant.enter()){
            var arrays=new PhpOwnershipArrays(d.heap());try(var cell=arrays.allocateCell(scalar(d,Memory.CONST_INT_1));var a=arrays.allocate(false)){
                a.appendCanonical(cell.binding());a.append(scalar(d,new LongMemory(2)));
                try(var owner=arrays.allocateCell(a.binding());var copy=arrays.allocateCell(a.binding())){
                    a.close();try(var write=arrays.prepareWrite(owner.value(),owner.value().current())){
                        assertTrue(write.separated());var carrier=owner.cell();
                        try(var token=(PhpOwnershipArrays.RawPublication)write.preparePublication()){carrier.publishOwnershipRaw(token);token.commit();}
                        remove(write.prepareUnset(Memory.CONST_INT_0));assertEquals(1,write.array().size());
                        assertEquals(2,((ArrayMemory)copy.cell().getRawValue()).size());assertEquals(1,cell.cell().toLong());
                        ((PhpOwnershipArrays.Entry)arrays.certificate(cell.cell()).value()).assign(scalar(d,new LongMemory(9)));
                        assertEquals(9,((ArrayMemory)copy.cell().getRawValue()).getByScalar(Memory.CONST_INT_0).toLong());
                    }
                }
            }
        }finally{participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void tableDeclaredUnsetDetachesExactSourceRevivesTypeAndKeepsOrdinalSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveTyped {public int $v=3;public int $w=4;}",dump);var a=f.object("HRemoveTyped")){
            var cert=a.certificate().properties();var table=cert.table();var carrier=table.getByScalar("v");var slot=cert.entry(carrier);
            PhpOwnershipValues.Binding cell;try(var promotion=slot.preparePromotion(slot.ownerPublisher().current())){cell=promotion.binding();promotion.store();promotion.commit();}
            try(var alias=cell.host()){
                var canonical=(ReferenceMemory)cell.rawValue();assertTrue(canonical.hasPropertySources());
                try(var stale=cert.prepareUnset("v")) {
                    var late=new ReferenceMemory(canonical);late.attachPropertySource(f.env,a.object().getReflection().findProperty("w"));
                    assertThrows(IllegalArgumentException.class,stale::store);late.detachPropertySource();assertSame(carrier,table.getByScalar("v"));
                }
                remove(cert.prepareUnset("v"));assertNull(table.getByScalar("v"));assertTrue(table.isExplicitlyUnset("v"));
                assertFalse(canonical.hasPropertySources());((PhpOwnershipArrays.Entry)f.arrays.certificate(canonical).value()).assign(scalar(f.domain,new StringMemory("free")));assertEquals("free",canonical.toString());
                try(var missing=cert.prepareUnset("v")){assertFalse(missing.existed());missing.store();missing.commit();}
                assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->slot.payloadPublisher().assign(scalar(f.domain,new StringMemory("bad"))));
                assertNull(table.getByScalar("v"));slot.payloadPublisher().assign(scalar(f.domain,new LongMemory(8)));
                assertSame(carrier,table.getByScalar("v"));assertTrue(carrier.hasPropertySources());assertEquals(Arrays.asList("v","w"),new ArrayList<>(table.keySet()));
                cert.validateAll();assertEquals(2,cert.node().outgoing().size());
            }
        }
    }
    @Test public void duplicateTableUnsetRevealsOlderBucketPreservesExportedNewestAlias() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveDynamic {}",dump);var a=f.object("HRemoveDynamic")){
            var cert=a.certificate().properties();var first=cert.insertBucket("x",scalar(f.domain,new LongMemory(4)));var last=cert.insertBucket("x",scalar(f.domain,Memory.CONST_INT_1));
            PhpOwnershipValues.Binding cell;try(var promotion=last.preparePromotion(last.ownerPublisher().current())){cell=promotion.binding();promotion.store();promotion.commit();}
            try(var alias=cell.host()){
                remove(cert.prepareUnset("x"));assertEquals(4,cert.table().getByScalar("x").toLong());assertEquals(1,cert.table().size());
                ((PhpOwnershipArrays.Entry)f.arrays.certificate((ReferenceMemory)cell.rawValue()).value()).assign(scalar(f.domain,new LongMemory(8)));
                assertEquals(8,cell.rawValue().toLong());assertEquals(4,first.payloadPublisher().carrier().toLong());
                remove(cert.prepareUnset("x"));assertEquals(0,cert.table().size());cert.validateAll();
            }
        }
    }
    @Test public void sharedTypedSourcesDetachOneAndNullableUnsetPromotionInitializesNullSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveSources {public int $v=3;public int $w=4;public ?int $n=5;}",dump);var a=f.object("HRemoveSources")){
            var cert=a.certificate().properties();var table=cert.table();var v=cert.entry(table.getByScalar("v"));var w=cert.entry(table.getByScalar("w"));var n=cert.entry(table.getByScalar("n"));
            PhpOwnershipValues.Binding cell;try(var p=v.preparePromotion(v.ownerPublisher().current())){cell=p.binding();p.store();p.commit();}
            try(var p=w.prepareRebind(w.ownerPublisher().current(),cell)){p.store();p.commit();}
            try(var alias=cell.host()){
                var raw=(ReferenceMemory)cell.rawValue();var payload=(PhpOwnershipArrays.Entry)f.arrays.certificate(raw).value();remove(cert.prepareUnset("v"));
                assertTrue(raw.hasPropertySources());var error=assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->payload.assign(scalar(f.domain,new StringMemory("bad"))));
                assertTrue(error.getMessage(f.env).toString().contains("HRemoveSources::$w"));
                int nodes=f.domain.graph().liveNodes();assertThrows(php.runtime.lang.exception.BaseError.class,()->v.preparePromotion(v.ownerPublisher().current()));assertEquals(nodes,f.domain.graph().liveNodes());
                remove(cert.prepareUnset("w"));assertFalse(raw.hasPropertySources());payload.assign(scalar(f.domain,new StringMemory("free")));assertEquals("free",raw.toString());
                remove(cert.prepareUnset("n"));try(var aborted=n.preparePromotion(n.ownerPublisher().current())){assertNull(table.getByScalar("n"));}
                assertNull(table.getByScalar("n"));try(var p=n.preparePromotion(n.ownerPublisher().current())){p.store();p.commit();}
                assertTrue(table.getByScalar("n").isNull());assertTrue(table.getByScalar("n").hasPropertySources());cert.validateAll();
            }
        }
    }
    @Test public void graphRetirementAbortAndClosedEdgeAreVisibleBeforeRealObjectDrain() throws Throwable {
        var graph=new PhpOwnership();var parent=graph.storage(new Object());var root=graph.root();root.replace(parent);var edge=graph.edge(parent);int[] drops={0};
        var child=graph.object(new Object(),new PhpOwnership.Lifecycle(){public void destruct(PhpOwnership.Node node){drops[0]++;assertTrue(edge.isClosed());assertEquals(0,parent.outgoing().size());assertNull(edge.target());}});edge.replace(child);
        try(var abort=edge.prepareRetirement()){assertFalse(edge.isClosed());assertSame(child,edge.target());}
        assertEquals(0,drops[0]);assertFalse(edge.isClosed());try(var token=edge.prepareRetirement()){token.commit();assertTrue(token.isCommitted());}
        assertEquals(1,drops[0]);assertThrows(IllegalStateException.class,edge::prepareRetirement);root.close();assertEquals(0,graph.liveNodes());
    }
    @Test public void actualArrayDestructorSeesRelocatedCommittedLayoutAndReentrantAppendSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveArrayChild {function __destruct(){remove_observe();throw new Exception('array-dtor');}}",dump)){
            var fn=new FunctionEntity(new Context(""));fn.setName("remove_observe");fn.setParameters(new ParameterEntity[0]);fn.setNativeMethod(getClass().getMethod("callback",Environment.class,Memory[].class));f.env.registerFunction(fn);
            var allocation=f.arrays.allocate(false);var child=f.object("HRemoveArrayChild");var node=child.binding().node();allocation.append(child.binding());allocation.append(scalar(f.domain,new LongMemory(2)));
            try(var holder=f.arrays.allocateCell(allocation.binding())){
                allocation.close();child.close();try(var write=f.arrays.prepareWrite(holder.value(),holder.value().current())){
                    f.env.setUserValue("removeObserver",(Runnable)()->{try{
                        assertNull(write.array().getByScalar(Memory.CONST_INT_0));assertEquals(1,write.array().size());assertEquals(1,write.certificate().node().outgoing().size());
                        f.arrays.certificate(write.array());write.append(scalar(f.domain,new LongMemory(9)));
                    }catch(Throwable t){throw new AssertionError(t);}});
                    try(var token=write.prepareUnset(Memory.CONST_INT_0)){
                        token.store();try{token.commit();fail("array dtor failure lost");}catch(php.runtime.lang.BaseException expected){assertEquals("array-dtor",expected.getMessage(f.env).toString());}
                        assertTrue(token.isCommitted());
                    }
                    assertEquals(2,write.array().size());assertEquals(9,write.array().getByScalar(new LongMemory(2)).toLong());assertEquals(2,write.certificate().node().outgoing().size());f.arrays.certificate(write.array());
                    assertEquals(PhpOwnership.State.DISPOSED,node.state());
                }
            }
        }
    }
    @Test public void arrayHolderRemovalNeverDetachesTypedTableSourceFromSurvivingCanonicalAlias() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveArraySource {public int $v=3;}",dump);var object=f.object("HRemoveArraySource")){
            var table=object.certificate().properties();var slot=table.entry(table.table().getByScalar("v"));PhpOwnershipValues.Binding binding;
            try(var p=slot.preparePromotion(slot.ownerPublisher().current())){binding=p.binding();p.store();p.commit();}
            try(var alias=binding.host();var allocation=f.arrays.allocate(false)){
                allocation.appendCanonical(binding);
                try(var holder=f.arrays.allocateCell(allocation.binding())){
                    allocation.close();try(var write=f.arrays.prepareWrite(holder.value(),holder.value().current())){remove(write.prepareUnset(Memory.CONST_INT_0));assertEquals(0,write.array().size());}
                    var raw=(ReferenceMemory)binding.rawValue();assertTrue(raw.hasPropertySources());var payload=(PhpOwnershipArrays.Entry)f.arrays.certificate(raw).value();
                    assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->payload.assign(scalar(f.domain,new StringMemory("bad"))));assertEquals(3,raw.toLong());
                    remove(table.prepareUnset("v"));assertFalse(raw.hasPropertySources());payload.assign(scalar(f.domain,new StringMemory("free")));assertEquals("free",raw.toString());
                }
            }
        }
    }
    @Test public void declaredObjectRemovalPublishesUnsetAnchorBeforeDestructorAndReentryRevivalSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveDeclaredParent {public mixed $x=null;public int $y=2;} class HRemoveDeclaredChild {function __destruct(){remove_observe();throw new Exception('declared-dtor');}}",dump);var parent=f.object("HRemoveDeclaredParent")){
            var fn=new FunctionEntity(new Context(""));fn.setName("remove_observe");fn.setParameters(new ParameterEntity[0]);fn.setNativeMethod(getClass().getMethod("callback",Environment.class,Memory[].class));f.env.registerFunction(fn);
            var cert=parent.certificate().properties();var table=cert.table();var carrier=table.getByScalar("x");var slot=cert.entry(carrier);var child=f.object("HRemoveDeclaredChild");var node=child.binding().node();slot.payloadPublisher().assign(child.binding());child.close();
            f.env.setUserValue("removeObserver",(Runnable)()->{try{
                assertNull(table.getByScalar("x"));assertTrue(table.isExplicitlyUnset("x"));assertFalse(carrier.hasPropertySources());cert.validateAll();
                slot.payloadPublisher().assign(scalar(f.domain,new LongMemory(8)));assertSame(carrier,table.getByScalar("x"));assertTrue(carrier.hasPropertySources());
            }catch(Throwable t){throw new AssertionError(t);}});
            try(var token=cert.prepareUnset("x")){
                token.store();try{token.commit();fail("declared dtor failure lost");}catch(php.runtime.lang.BaseException expected){assertEquals("declared-dtor",expected.getMessage(f.env).toString());}assertTrue(token.isCommitted());
            }
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(8,table.getByScalar("x").toLong());assertEquals(Arrays.asList("x","y"),new ArrayList<>(table.keySet()));assertEquals(2,cert.node().outgoing().size());cert.validateAll();
        }
    }
    @Test public void survivorRevisionAndWritableOwnerRebindingRejectBeforeLayoutOrEdgesChange() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try(var execution=participant.enter()){
            var arrays=new PhpOwnershipArrays(d.heap());var allocation=arrays.allocate(false);allocation.append(scalar(d,Memory.CONST_INT_1));allocation.append(scalar(d,new LongMemory(2)));
            try(var holder=arrays.allocateCell(allocation.binding());var extra=allocation.binding().host()){
                allocation.close();try(var write=arrays.prepareWrite(holder.value(),holder.value().current())){
                    assertTrue(write.separated());var carrier=holder.cell();try(var p=(PhpOwnershipArrays.RawPublication)write.preparePublication()){carrier.publishOwnershipRaw(p);p.commit();}
                    var token=write.prepareUnset(Memory.CONST_INT_0);write.entry(Memory.CONST_INT_1).assign(scalar(d,new LongMemory(4)));
                    assertThrows(IllegalArgumentException.class,token::store);token.close();assertTrue(write.array().isList());assertEquals(2,write.array().size());assertEquals(4,write.array().getByScalar(Memory.CONST_INT_1).toLong());
                    try(var retain=write.binding().host();var stale=write.prepareUnset(Memory.CONST_INT_0)){
                        holder.value().assign(scalar(d,Memory.NULL));assertThrows(IllegalArgumentException.class,stale::store);assertEquals(2,write.array().size());arrays.certificate(write.array());
                    }
                }
            }
        }finally{participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void preparedRevivedReferenceRejectsLateInPlaceTargetSourceWithoutDroppingConstraint() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveLateSource {public int $v=3;public int $w=4;public int $n=5;}",dump);var a=f.object("HRemoveLateSource")){
            var cert=a.certificate().properties();var table=cert.table();var v=cert.entry(table.getByScalar("v"));var w=cert.entry(table.getByScalar("w"));PhpOwnershipValues.Binding target;
            try(var p=w.preparePromotion(w.ownerPublisher().current())){target=p.binding();p.store();p.commit();}
            remove(cert.prepareUnset("v"));var original=v.ownerPublisher().current();var canonical=(ReferenceMemory)target.rawValue();
            try(var p=v.prepareRebind(original,target)){
                var late=new ReferenceMemory(canonical);late.attachPropertySource(f.env,a.object().getReflection().findProperty("n"));
                try{assertThrows(IllegalArgumentException.class,p::store);assertSame(original,v.ownerPublisher().current());assertNull(table.getByScalar("v"));assertEquals(4,canonical.toLong());assertTrue(canonical.hasPropertySources());}
                finally{late.detachPropertySource();}
            }
            cert.validateAll();var payload=(PhpOwnershipArrays.Entry)f.arrays.certificate(canonical).value();
            var error=assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->payload.assign(scalar(f.domain,new StringMemory("bad"))));assertTrue(error.getMessage(f.env).toString().contains("HRemoveLateSource::$w"));
        }
    }
    public static Memory callback(Environment env,Memory[] args){env.getUserValue("removeObserver",Runnable.class).run();return Memory.NULL;}
    @Test public void actualDestructorSeesRetiredHolderReentryAndThrowRemainCommittedSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var f=new Fixture("class HRemoveParent {} class HRemoveChild {function __destruct(){remove_observe();throw new Exception('remove-dtor');}}",dump);var parent=f.object("HRemoveParent")){
            var fn=new FunctionEntity(new Context(""));fn.setName("remove_observe");fn.setParameters(new ParameterEntity[0]);fn.setNativeMethod(getClass().getMethod("callback",Environment.class,Memory[].class));f.env.registerFunction(fn);
            var cert=parent.certificate().properties();var child=f.object("HRemoveChild");var node=child.binding().node();var entry=cert.insertBucket("x",child.binding());child.close();
            f.env.setUserValue("removeObserver",(Runnable)()->{try{assertNull(cert.table().getByScalar("x"));assertEquals(0,cert.node().outgoing().size());cert.validateAll();cert.insertBucket("x",scalar(f.domain,new LongMemory(9)));}catch(Throwable e){throw new AssertionError(e);}});
            try(var token=cert.prepareUnset("x")){
                token.store();try{token.commit();fail("throwing destructor lost");}catch(php.runtime.lang.BaseException expected){assertEquals("remove-dtor",expected.getMessage(f.env).toString());}
                assertTrue(token.isCommitted());
            }
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(9,cert.table().getByScalar("x").toLong());assertEquals(1,cert.node().outgoing().size());cert.validateAll();assertThrows(IllegalStateException.class,entry::ownerPublisher);
        }
    }
}
