package php.runtime;

import org.junit.Test;
import php.runtime.env.*;
import php.runtime.lang.BaseObject;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.Assert.*;

/** Actual live views must reject certified unlink before raw storage or owner edges change. */
public class PhpOwnershipKeySetPerimeterTest {
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain d,long value) {
        return PhpOwnershipValues.bindValue(d,new LongMemory(value),null);
    }
    private void rejectMutations(Set<Object> view,Runnable unchanged) {
        assertThrows(IllegalStateException.class,()->view.remove("x"));unchanged.run();
        assertThrows(IllegalStateException.class,view::clear);unchanged.run();
        Iterator<Object> iterator=view.iterator();assertTrue(iterator.hasNext());iterator.next();
        assertThrows(IllegalStateException.class,iterator::remove);unchanged.run();
        assertThrows(IllegalStateException.class,()->view.removeAll(Collections.singleton("x")));unchanged.run();
        assertThrows(IllegalStateException.class,()->view.retainAll(Collections.emptySet()));unchanged.run();
    }
    private php.runtime.memory.support.ArrayMemoryMap actualMap(ArrayMemory array) throws Exception {
        var field=ArrayMemory.class.getDeclaredField("map");field.setAccessible(true);
        return (php.runtime.memory.support.ArrayMemoryMap)field.get(array);
    }
    private void rejectMapViews(ArrayMemory array,Runnable unchanged) throws Exception {
        var map=actualMap(array);var entry=map.getEntry("x");
        assertThrows(IllegalStateException.class,()->map.entrySet().remove(entry));unchanged.run();
        assertThrows(IllegalStateException.class,()->map.entrySet().clear());unchanged.run();
        var entries=map.entrySet().iterator();entries.next();assertThrows(IllegalStateException.class,entries::remove);unchanged.run();
        assertThrows(IllegalStateException.class,()->map.values().clear());unchanged.run();
        var values=map.values().iterator();values.next();assertThrows(IllegalStateException.class,values::remove);unchanged.run();
        assertThrows(IllegalStateException.class,()->map.remove("x"));unchanged.run();
    }
    @Test public void certifiedArrayKeySetRejectsBeforeRawAndEdgeMutationIncludingPinnedBacking() throws Throwable {
        var d=new PhpOwnershipDomain();var p=d.participant("array");
        try (var execution=p.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(true)) {
            var x=allocation.insertBucket("x",scalar(d,1));allocation.insertBucket("y",scalar(d,2));
            var array=allocation.array();var before=x.current();int edges=allocation.certificate().node().outgoing().size(),nodes=d.graph().liveNodes();
            Runnable unchanged=()->{
                assertEquals(new HashSet<>(Arrays.asList("x","y")),array.keySet());assertEquals(2,array.size());
                assertSame(before,x.current());assertEquals(1,x.carrier().toLong());
                new PhpOwnershipArrays(d.heap()).certificate(array);
                assertEquals(edges,allocation.certificate().node().outgoing().size());assertEquals(nodes,d.graph().liveNodes());
            };
            rejectMutations(array.keySet(),unchanged);rejectMapViews(array,unchanged);
            try (var lease=array.pinWriteFetch()) {rejectMutations(array.keySet(),unchanged);}
        } finally {p.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    /** This exact shell exposes only a borrowed Java key view, with no hidden PHP VALUE holder. */
    public static final class ViewShell extends BaseObject {
        public ViewShell(Environment env,ClassEntity entity) {
            super(env,entity);var table=getOwnershipProperties();
            table.putAsKeyString("temporary",Memory.CONST_INT_1);table.removeByScalar("temporary");
            env.setUserValue("preCertificateKeyView",table.keySet());
        }
    }
    @Test public void actualTableAndViewRetainedBeforeCertificationRejectUnlink() throws Throwable {
        var env=new Environment(new CompileScope(),new ByteArrayOutputStream());var d=new PhpOwnershipDomain();var p=d.participant(env);
        try (var execution=p.enter()) {
            var arrays=new PhpOwnershipArrays(d.heap());var objects=new PhpOwnershipObjects(d.heap(),arrays);
            var entity=new ClassEntity(new Context(""));entity.setName("OwnershipViewShell");entity.setInternal(true);entity.setNativeClazz(ViewShell.class);
            var provider=objects.registerPropertiesProvider(entity,ViewShell.class.getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
            try (var allocation=objects.allocate(provider,env,Collections.emptyMap())) {
                var certificate=allocation.certificate().properties();var x=certificate.insertBucket("x",scalar(d,1));certificate.insertBucket("y",scalar(d,2));
                var table=certificate.table();var before=x.ownerPublisher().current();int edges=certificate.node().outgoing().size(),nodes=d.graph().liveNodes();
                Runnable unchanged=()->{
                    certificate.validateAll();assertEquals(new HashSet<>(Arrays.asList("x","y")),table.keySet());assertEquals(2,table.size());
                    assertSame(before,x.ownerPublisher().current());assertEquals(1,x.payloadPublisher().carrier().toLong());
                    assertEquals(edges,certificate.node().outgoing().size());assertEquals(nodes,d.graph().liveNodes());
                };
                rejectMutations(table.keySet(),unchanged);rejectMapViews(table,unchanged);
                Set<Object> retained=env.getUserValue("preCertificateKeyView",Set.class);
                assertEquals(table.keySet(),retained);rejectMutations(retained,unchanged);
            }
        } finally {p.close();env.doFinal();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void ordinaryMapRetainedViewKeepsLegacyMutationSemantics() {
        var array=new ArrayMemory(true);array.put("x",Memory.CONST_INT_1);array.put("y",Memory.CONST_INT_1);
        var keys=array.keySet();assertSame(keys,array.keySet());assertTrue(keys.remove("x"));assertNull(array.getByScalar("x"));assertEquals(Collections.singleton("y"),keys);
        array.put("z",Memory.CONST_INT_1);assertEquals(new HashSet<>(Arrays.asList("y","z")),keys);
        var iterator=keys.iterator();Object first=iterator.next();iterator.remove();assertNull(array.getByScalar(first));assertEquals(1,keys.size());
        keys.clear();assertTrue(keys.isEmpty());assertTrue(array.keySet().isEmpty());
    }
}
