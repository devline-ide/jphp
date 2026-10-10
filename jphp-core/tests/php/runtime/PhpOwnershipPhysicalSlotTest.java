package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.invoke.PhpObjectLifecycle;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import static org.junit.Assert.*;

/** Real physical carriers, exported aliases and certified actual list addresses. */
public class PhpOwnershipPhysicalSlotTest extends JvmCompilerCase {
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain d,Memory v) {return PhpOwnershipValues.bindValue(d,v,null);}
    private Memory load(Environment env,String code,boolean dump) throws Throwable {
        var context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if (dump) {var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
        return module.include(env);
    }
    private ObjectMemory object(Environment env,String code,boolean dump) throws Throwable {
        return load(env,code,dump).toValue(ObjectMemory.class);
    }
    public static Memory observeDestructor(Environment env,Memory[] args) {
        env.getUserValue("psOwnershipObserver",Runnable.class).run();return Memory.NULL;
    }
    private void promote(PhpOwnershipArrays.PhysicalSlot slot) throws Throwable {
        try (var p=slot.preparePromotion(slot.ownerPublisher().current())) {p.store();p.commit();}
    }
    private void rebind(PhpOwnershipArrays.PhysicalSlot slot,PhpOwnershipValues.Binding next) throws Throwable {
        try (var p=slot.prepareRebind(slot.ownerPublisher().current(),next)) {p.store();p.commit();}
    }
    @Test public void actualWrapperPromotionAndRebindKeepOldAliasAndSinglePayloadEdge() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(d.heap());
            try (var allocation=arrays.allocate(false);var target=arrays.allocateCell(scalar(d,new LongMemory(8)))) {
                var entry=allocation.append(scalar(d,Memory.CONST_INT_1));
                var slot=allocation.physicalSlot(Memory.CONST_INT_0);promote(slot);
                {
                    var old=(ReferenceMemory)entry.carrier().getRawValue();var cert=old.getOwnershipCell();
                    assertNotSame(entry.carrier(),old);assertFalse(old.getRawValue() instanceof ReferenceMemory);
                    assertEquals(1,cert.node().incoming());assertEquals(1,cert.node().outgoing().size());
                    try (var alias=PhpOwnershipValues.bindCell(d,old,cert.node()).host()) {
                        slot.payloadPublisher().assign(scalar(d,new LongMemory(3)));assertEquals(3,allocation.array().valueOfIndex(Memory.CONST_INT_0).toLong());
                        rebind(slot,target.binding());assertSame(target.cell(),entry.carrier().getRawValue());assertEquals(3,old.toLong());
                        cert.value().prepare(cert.value().current(),scalar(d,new LongMemory(4))).close();
                    }
                }
            }
            d.graph().collectCycles();assertEquals(0,d.graph().liveNodes());
        } finally {participant.close();}
    }
    @Test public void abortAndStalePromotionLeaveActualCarrierAndSourcesUnchanged() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(false)) {
            var arrays=new PhpOwnershipArrays(d.heap());var entry=allocation.append(scalar(d,Memory.CONST_INT_1));
            var carrier=entry.carrier();var slot=allocation.physicalSlot(Memory.CONST_INT_0);
            var before=slot.ownerPublisher().current();
            try (var p=slot.preparePromotion(before)) {assertSame(Memory.CONST_INT_1,carrier.getRawValue());}
            assertSame(before,slot.ownerPublisher().current());assertFalse(carrier.isSharedIncrementCell());
            try (var p=slot.preparePromotion(before)) {
                slot.payloadPublisher().assign(scalar(d,new LongMemory(2)));
                assertThrows(IllegalArgumentException.class,p::store);assertEquals(2,carrier.toLong());
            }
        } finally {participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void actualTypedDeclarationPromotionRebindAndStrictFailurePreserveSources() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) {
            var env=new Environment(newScope(),new ByteArrayOutputStream());var d=new PhpOwnershipDomain();var participant=d.participant(env);
            try (var execution=participant.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(false)) {
                var arrays=new PhpOwnershipArrays(d.heap());var typed=object(env,"class PSTyped {public int $v=1;} return new PSTyped();",dump);
                var carrier=allocation.append(scalar(d,Memory.CONST_INT_1)).carrier();carrier.attachPropertySource(env,typed.value.getReflection().findProperty("v"));
                var slot=allocation.physicalSlot(Memory.CONST_INT_0);
                var opaqueNode=d.object(typed.value,new PhpOwnershipDomain.Lifecycle(){});
                try (var opaqueRoot=d.hostValue(opaqueNode,typed)) {
                    var opaque=PhpOwnershipValues.bindValue(d,typed,opaqueNode);var before=slot.ownerPublisher().current();
                    int size=allocation.array().size(),nodes=d.graph().liveNodes(),edges=allocation.certificate().node().outgoing().size();
                    assertThrows(IllegalArgumentException.class,()->allocation.append(opaque));
                    assertThrows(IllegalArgumentException.class,()->arrays.allocateCell(opaque));
                    assertThrows(IllegalArgumentException.class,()->slot.payloadPublisher().assign(opaque));
                    assertSame(before,slot.ownerPublisher().current());assertEquals(1,carrier.toLong());
                    assertEquals(size,allocation.array().size());assertEquals(nodes,d.graph().liveNodes());assertEquals(edges,allocation.certificate().node().outgoing().size());
                }
                promote(slot);
                var old=(ReferenceMemory)carrier.getRawValue();var oldCert=old.getOwnershipCell();
                assertTrue(old.hasPropertySources());assertFalse(old.getRawValue() instanceof ReferenceMemory);
                try (var target=arrays.allocateCell(scalar(d,new StringMemory("3")));var bad=arrays.allocateCell(scalar(d,new StringMemory("bad")));var alias=PhpOwnershipValues.bindCell(d,old,oldCert.node()).host()) {
                    var before=slot.ownerPublisher().current();
                    assertThrows(BaseTypeError.class,()->slot.prepareRebind(before,bad.binding()));
                    assertSame(before,slot.ownerPublisher().current());assertEquals("bad",bad.cell().toString());assertFalse(bad.cell().hasPropertySources());
                    var strict=PhpPropertyWriteContext.enter(env,env.trace(),true);
                    try {assertThrows(BaseTypeError.class,()->slot.prepareRebind(before,target.binding()));}
                    finally {PhpPropertyWriteContext.leave(strict);}
                    assertEquals("3",target.cell().toString());assertFalse(target.cell().hasPropertySources());
                    rebind(slot,target.binding());assertEquals(Memory.Type.INT,target.cell().getRawValue().type);assertEquals(3,target.cell().toLong());
                    assertFalse(old.hasPropertySources());assertTrue(target.cell().hasPropertySources());
                    oldCert.value().prepare(oldCert.value().current(),scalar(d,new StringMemory("free"))).close();
                    ((PhpOwnershipArrays.Entry)oldCert.value()).assign(scalar(d,new StringMemory("free")));assertEquals("free",old.toString());
                    var error=assertThrows(BaseTypeError.class,()->slot.payloadPublisher().assign(scalar(d,new StringMemory("bad"))));
                    assertEquals("Cannot assign string to reference held by property PSTyped::$v of type int",error.getMessage(env).toString());
                }
                } finally {participant.close();env.doFinal();}
            assertEquals(0,d.graph().liveNodes());
        }
    }
    @Test public void canonicalSelfListAddressRebindDoesNotWriteOldCanonicalPayload() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(d.heap());
            try (var allocation=arrays.allocate(false);var old=arrays.allocateCell(scalar(d,Memory.CONST_INT_1));var next=arrays.allocateCell(scalar(d,new LongMemory(8)))) {
                var payload=old.value();var slot=allocation.appendCanonical(old.binding());
                assertSame(old.cell(),allocation.array().getByScalar(Memory.CONST_INT_0));assertSame(payload,old.cell().getOwnershipCell().value());
                rebind(slot,next.binding());assertSame(next.cell(),allocation.array().getByScalar(Memory.CONST_INT_0));
                assertEquals(1,old.cell().toLong());assertSame(payload,old.cell().getOwnershipCell().value());assertFalse(old.cell().getRawValue() instanceof ReferenceMemory);
                assertEquals(1,old.cell().getOwnershipCell().node().incoming());assertEquals(2,next.cell().getOwnershipCell().node().incoming());
                arrays.certificate(allocation.array());
            }
            assertEquals(0,d.graph().liveNodes());
        } finally {participant.close();}
    }
    @Test public void canonicalSelfCowCopiesOwnerEdgesAndPreservesOneSharedPayload() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(d.heap());var frame=new PhpOwnershipFrame(d.graph());var first=d.heap().framePublisher(frame.local("a"));var second=d.heap().framePublisher(frame.local("b"));Memory[] raw={null,null};
            try (var cell=arrays.allocateCell(scalar(d,Memory.CONST_INT_1));var allocation=arrays.allocate(false);var next=arrays.allocateCell(scalar(d,new LongMemory(8)))) {
                allocation.appendCanonical(cell.binding());
                var actual=allocation.array();
                try (var p=first.prepare(first.current(),allocation.binding())) {raw[0]=actual;p.commit();}
                try (var p=second.prepare(second.current(),allocation.binding())) {raw[1]=actual;p.commit();}
                try (var write=arrays.prepareWrite(first,first.current())) {
                    assertTrue(write.separated());var value=write.array();try (var p=write.preparePublication()) {raw[0]=value;p.commit();}
                    assertSame(cell.cell(),value.getByScalar(Memory.CONST_INT_0));assertSame(cell.cell(),allocation.array().getByScalar(Memory.CONST_INT_0));
                    assertEquals(1,cell.value().current().binding().rawValue().toLong());assertEquals(1,cell.cell().getOwnershipCell().node().outgoing().size());
                    rebind(write.physicalSlot(Memory.CONST_INT_0),next.binding());assertSame(next.cell(),value.getByScalar(Memory.CONST_INT_0));assertSame(cell.cell(),allocation.array().getByScalar(Memory.CONST_INT_0));
                }
                frame.close();
            }
            assertEquals(0,d.graph().liveNodes());
        } finally {participant.close();}
    }
    @Test public void canonicalArrayAliasRebindKeepsOriginalPropertySourceAtOldCell() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) {
            var env=new Environment(newScope(),new ByteArrayOutputStream());var d=new PhpOwnershipDomain();var p=d.participant(env);
            try (var execution=p.enter()) {
                var arrays=new PhpOwnershipArrays(d.heap());var typed=object(env,"class PSAliasTyped {public int $v=1;} return new PSAliasTyped();",dump);
                try (var old=arrays.allocateCell(scalar(d,Memory.CONST_INT_1));var target=arrays.allocateCell(scalar(d,new StringMemory("free")));var allocation=arrays.allocate(false)) {
                    typed.value.getProperties().getByScalar("v").assignRef(old.cell());
                    var slot=allocation.appendCanonical(old.binding());rebind(slot,target.binding());
                    assertSame(target.cell(),allocation.array().getByScalar(Memory.CONST_INT_0));assertTrue(old.cell().hasPropertySources());assertFalse(target.cell().hasPropertySources());
                    assertEquals(1,typed.value.getProperties().getByScalar("v").toLong());
                    var error=assertThrows(BaseTypeError.class,()->old.value().assign(scalar(d,new StringMemory("bad"))));
                    assertEquals("Cannot assign string to reference held by property PSAliasTyped::$v of type int",error.getMessage(env).toString());
                    target.value().assign(scalar(d,new StringMemory("changed")));assertEquals("changed",allocation.array().valueOfIndex(Memory.CONST_INT_0).toString());
                }
                assertEquals(0,d.graph().liveNodes());
            } finally {p.close();env.doFinal();}
        }
    }
    @Test public void duplicateFacetsAndDisposedCarriersRejectBeforeAnyAdditionalEdge() throws Throwable {
        var d=new PhpOwnershipDomain();var p=d.participant("test");
        try (var execution=p.enter()) {
            var arrays=new PhpOwnershipArrays(d.heap());ReferenceMemory stale;PhpOwnershipArrays.CellCertificate retired;
            try (var cell=arrays.allocateCell(scalar(d,Memory.CONST_INT_1));var allocation=arrays.allocate(false)) {
                allocation.appendCanonical(cell.binding());var node=allocation.certificate().node();var edges=node.outgoing().size();var count=cell.cell().getOwnershipCell().node().incoming();
                assertThrows(IllegalArgumentException.class,()->arrays.attachPhysicalSlot(node,cell.cell(),cell.binding()));
                assertEquals(edges,node.outgoing().size());assertEquals(count,cell.cell().getOwnershipCell().node().incoming());
                try (var other=arrays.allocate(false)) {other.appendCanonical(cell.binding());assertEquals(count+1,cell.cell().getOwnershipCell().node().incoming());}
                assertEquals(count,cell.cell().getOwnershipCell().node().incoming());retired=cell.cell().getOwnershipCell();
            }
            assertEquals(PhpOwnership.State.DISPOSED,retired.node().state());
            try (var allocation=arrays.allocate(false)) {
                var entry=allocation.append(scalar(d,Memory.CONST_INT_1));stale=entry.carrier();var node=allocation.certificate().node();var edges=node.outgoing().size();
                assertThrows(IllegalArgumentException.class,()->arrays.attachPhysicalSlot(node,stale,scalar(d,Memory.CONST_INT_1)));
                assertEquals(edges,node.outgoing().size());
            }
            try (var fresh=arrays.allocate(false)) {
                var node=fresh.certificate().node();var edges=node.outgoing().size();
                assertThrows(IllegalStateException.class,()->arrays.attachPhysicalSlot(node,stale,scalar(d,Memory.CONST_INT_1)));
                assertEquals(edges,node.outgoing().size());
            }
            assertEquals(0,d.graph().liveNodes());
        } finally {p.close();}
    }
    @Test public void foreignAndStaleActualAddressFailBeforePointerMutation() throws Throwable {
        var d=new PhpOwnershipDomain();var p=d.participant("test");var foreign=new PhpOwnershipDomain();var q=foreign.participant("foreign");
        try (var x=p.enter();var y=q.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(false);var old=new PhpOwnershipArrays(d.heap()).allocateCell(scalar(d,Memory.CONST_INT_1));var next=new PhpOwnershipArrays(d.heap()).allocateCell(scalar(d,new LongMemory(8)));var other=new PhpOwnershipArrays(foreign.heap()).allocateCell(scalar(foreign,Memory.NULL))) {
            var slot=allocation.appendCanonical(old.binding());var before=slot.ownerPublisher().current();
            assertThrows(IllegalArgumentException.class,()->slot.prepareRebind(before,other.binding()));
            try (var stale=slot.prepareRebind(before,next.binding())) {
                rebind(slot,next.binding());assertThrows(IllegalArgumentException.class,stale::store);
                assertSame(next.cell(),allocation.array().getByScalar(Memory.CONST_INT_0));assertEquals(1,old.cell().toLong());
            }
        } finally {q.close();p.close();}
        assertEquals(0,d.graph().liveNodes());assertEquals(0,foreign.graph().liveNodes());
    }
    @Test public void referenceCommitPublishesActualPointerBeforeRealDestructorReentryAndThrow() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);var d=new PhpOwnershipDomain();var p=d.participant(env);
            try (var execution=p.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(false)) {
                var arrays=new PhpOwnershipArrays(d.heap());
                var function=new php.runtime.reflection.FunctionEntity(new Context(""));function.setName("ps_ownership_observe");
                function.setParameters(new php.runtime.reflection.ParameterEntity[0]);function.setNativeMethod(getClass().getMethod("observeDestructor",Environment.class,Memory[].class));env.registerFunction(function);
                load(env,"class PSDestructor {function __destruct(){ps_ownership_observe();echo 'drop;';throw new \\Exception('stop');}}",dump);
                var objects=new PhpOwnershipObjects(d.heap(),arrays);var entity=env.fetchClass("PSDestructor",true,PhpLanguageProfile.PHP_8_5);
                var provider=objects.registerPropertiesProvider(entity,entity.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),(e,v)->{});
                PhpOwnershipArrays.PhysicalSlot[] slot={null};ReferenceMemory[] carrier={null};
                env.setUserValue("psOwnershipObserver",(Runnable)()->{
                    assertEquals(8,carrier[0].toLong());assertEquals(1,slot[0].ownerPublisher().current().revision());
                    try {slot[0].payloadPublisher().assign(scalar(d,new LongMemory(9)));} catch (Throwable error) {throw new AssertionError(error);}
                });
                try (var owned=objects.allocate(provider,env,java.util.Collections.emptyMap())) {carrier[0]=allocation.append(owned.binding()).carrier();}
                slot[0]=allocation.physicalSlot(Memory.CONST_INT_0);
                try (var target=arrays.allocateCell(scalar(d,new LongMemory(8)))) {
                    assertThrows(php.runtime.lang.BaseException.class,()->rebind(slot[0],target.binding()));
                    assertSame(target.cell(),carrier[0].getRawValue());assertEquals(9,target.cell().toLong());assertEquals(1,slot[0].ownerPublisher().current().revision());
                    env.getDefaultBuffer().flush();assertEquals("drop;",output.toString("UTF-8"));
                }
            } finally {p.close();env.doFinal();}
            assertEquals(0,d.graph().liveNodes());
        }
    }
}
