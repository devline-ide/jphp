package php.runtime;

import org.junit.Test;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;

import static org.junit.Assert.*;

public class PhpOwnershipValuesTest {
    @Test public void existingViewsIdentifyOneStorageWithoutAcquiringOwners() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var array=new ArrayMemory();array.add(1);var view=(ArrayMemory)array.toImmutable();
            var storage=domain.storage(array.getStorageIdentity());
            var binding=PhpOwnershipValues.bindValue(domain,array,storage);
            assertSame(storage,binding.view(view).node());assertEquals(0,storage.incoming());
            var frame=new PhpOwnershipFrame(domain.graph());var slot=frame.local("a");
            var token=binding.view(view).prepareCommit(slot);token.commit();
            assertSame(view,slot.rawValue());assertEquals(1,storage.incoming());frame.close();
        }
        participant.close();
    }

    @Test public void cowDetachRejectsStaleBindingAndLeavesPublishedRootUnchanged() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var array=new ArrayMemory();array.add(1);var view=(ArrayMemory)array.toImmutable();
            var storage=domain.storage(array.getStorageIdentity());var binding=PhpOwnershipValues.bindValue(domain,view,storage);
            var frame=new PhpOwnershipFrame(domain.graph());var slot=frame.local("a");slot.commit(storage,array);
            view.refOfIndex(0).assign(2);
            assertNotSame(array.getStorageIdentity(),view.getStorageIdentity());
            assertThrows(IllegalArgumentException.class,()->binding.prepareCommit(slot));
            assertSame(array,slot.rawValue());assertSame(storage,slot.node());assertEquals(1,storage.incoming());frame.close();
        }
        participant.close();
    }

    @Test public void referenceCarriersCannotRunGettersDuringClassification() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var carrier=new ReferenceMemory(Memory.NULL) {
                @Override public Memory getValue() {throw new AssertionError("Getter must not run");}
                @Override public Memory toValue() {throw new AssertionError("Value fetch must not run");}
            };
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindValue(domain,carrier,null));
            var node=domain.canonicalCell(carrier);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindCell(domain,carrier,node));
            assertEquals(0,node.incoming());
        }
        participant.close();
    }

    @Test public void cellAndItsHeapPayloadAreSeparateExplicitOperations() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var array=new ArrayMemory();array.add(1);
            var reference=(ReferenceMemory)ReferenceMemory.exportReference(new ReferenceMemory(array));
            var cell=domain.canonicalCell(reference);var storage=domain.storage(array.getStorageIdentity());
            assertSame(cell,PhpOwnershipValues.bindCell(domain,reference,cell).node());
            assertSame(storage,PhpOwnershipValues.bindValue(domain,array,storage).node());
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindValue(domain,reference,cell));
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindCell(domain,reference,storage));
            assertEquals(0,cell.incoming());assertEquals(0,storage.incoming());
        }
        participant.close();
    }

    @Test public void unexportedAndNestedReferenceCellsRemainUncertified() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var reference=new ReferenceMemory(Memory.NULL);var node=domain.canonicalCell(reference);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindCell(domain,reference,node));
            var canonical=(ReferenceMemory)ReferenceMemory.exportReference(reference);
            var canonicalNode=domain.canonicalCell(canonical);var inner=new ReferenceMemory(Memory.NULL);
            canonical.assignRef(inner);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindCell(domain,canonical,canonicalNode));
        }
        participant.close();
    }

    @Test public void foreignNodeOrFrameCannotMutateExistingDestination() throws Throwable {
        var first=new PhpOwnershipDomain();var second=new PhpOwnershipDomain();
        var pa=first.participant("first");var pb=second.participant("second");
        try (var ea=pa.enter();var eb=pb.enter()) {
            var array=new ArrayMemory();var a=first.storage(array.getStorageIdentity());
            var binding=PhpOwnershipValues.bindValue(first,array,a);
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindValue(second,array,a));
            var frame=new PhpOwnershipFrame(second.graph());var slot=frame.local("value");slot.commit(null,Memory.CONST_INT_1);
            assertThrows(IllegalArgumentException.class,()->binding.prepareCommit(slot));
            assertSame(Memory.CONST_INT_1,slot.rawValue());assertNull(slot.node());assertEquals(0,a.incoming());frame.close();
        }
        pb.close();pa.close();
    }

    @Test public void objectCertificateRejectsChangedPublicCarrierIdentity() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var object=new BaseObject(new ClassEntity((php.runtime.env.Context)null)) {};var other=new BaseObject(new ClassEntity((php.runtime.env.Context)null)) {};
            var memory=new ObjectMemory(object);var node=domain.object(object,new PhpOwnershipDomain.Lifecycle() {});
            var binding=PhpOwnershipValues.bindValue(domain,memory,node);assertSame(node,binding.node());
            memory.value=other;
            assertThrows(IllegalArgumentException.class,binding::rawValue);assertEquals(0,node.incoming());
        }
        participant.close();
    }

    @Test public void scalarCertificatePublishesNullNodeBeforeOldCleanup() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var frame=new PhpOwnershipFrame(domain.graph());var slot=frame.local("value");
            var node=domain.object(new Object(),new PhpOwnershipDomain.Lifecycle() {
                @Override public void destruct(PhpOwnership.Node n,Object context) {
                    assertEquals("operation",context);assertSame(Memory.CONST_INT_1,slot.rawValue());assertNull(slot.node());
                }
            });
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindValue(domain,new LongMemory(2),node));
            slot.commit(node,"old");var value=PhpOwnershipValues.bindValue(domain,Memory.CONST_INT_1,null);
            var token=value.prepareCommit(slot);token.commit();assertEquals(PhpOwnership.State.DISPOSED,node.state());frame.close();
        }
        participant.close();
    }

    @Test public void opaqueScalarSubclassAndMissingExecutionAreRejectedWithoutCallbacks() throws Throwable {
        var domain=new PhpOwnershipDomain();
        assertThrows(IllegalStateException.class,()->PhpOwnershipValues.bindValue(domain,Memory.NULL,null));
        var participant=domain.participant("operation");
        try (var execution=participant.enter()) {
            var opaque=new LongMemory(1) {
                @Override public Memory toValue() {throw new AssertionError("Opaque getter must not run");}
            };
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindValue(domain,opaque,null));
        }
        participant.close();
    }

    @Test public void actualArrayHostHandleSurvivesParticipantCloseAndReleasesWithExplicitContext() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("creator");
        var closer=domain.participant("release");PhpOwnershipDomain.Owned host;PhpOwnership.Node storage;
        try (var execution=participant.enter()) {
            var array=new ArrayMemory();array.add(7);storage=domain.storage(array.getStorageIdentity());
            host=PhpOwnershipValues.bindValue(domain,array,storage).host();assertEquals(1,storage.incoming());
            var properties=new php.runtime.memory.PhpObjectProperties();
            var propertiesNode=domain.storage(properties.getStorageIdentity());
            assertThrows(IllegalArgumentException.class,()->PhpOwnershipValues.bindValue(domain,properties,propertiesNode));
        }
        participant.close();assertFalse(host.isClosed());assertEquals(1,storage.incoming());
        assertEquals(7,((ArrayMemory)host.rawValue()).valueOfIndex(0).toLong());
        host.close(closer);assertEquals(PhpOwnership.State.DISPOSED,storage.state());closer.close();
    }
}
