package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Explicit native-parser allocation/placement operations; no Reader or global compiler activation. */
public class PhpOwnershipRestorationPlacementTest extends JvmCompilerCase {
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain d,Memory value) {return PhpOwnershipValues.bindValue(d,value,null);}
    private void put(PhpOwnershipArrays.RestorationAddress address,PhpOwnershipValues.Binding value) throws Throwable {
        try (var p=address.preparePut(address.current(),value)) {p.store();p.commit();}
    }
    private void ref(PhpOwnershipArrays.RestorationAddress address,PhpOwnershipValues.Binding value) throws Throwable {
        try (var p=address.prepareReference(address.current(),value)) {p.store();p.commit();}
    }
    private PhpOwnershipValues.Binding promote(PhpOwnershipArrays.RestorationAddress address) throws Throwable {
        try (var p=address.preparePromotion(address.current())) {var cell=p.binding();p.store();p.commit();return cell;}
    }
    private PhpOwnershipFramePublisher stage(PhpOwnershipDomain d,FrameHandle frame,PhpOwnershipArrays.Allocation allocation) throws Throwable {
        var publisher=d.heap().framePublisher(frame.temporary("array"));
        d.graph().deferDestruction(()->{try (var p=allocation.preparePublication(publisher,publisher.current())) {p.commit();}allocation.close();});return publisher;
    }
    private static final class FrameHandle implements AutoCloseable {
        final PhpOwnershipFrame frame;
        FrameHandle(PhpOwnershipDomain domain) {frame=new PhpOwnershipFrame(domain.graph());}
        PhpOwnershipFrame.Slot temporary(String name) {return frame.temporary(name);}
        @Override public void close() throws Exception {try {frame.close();} catch (Exception e) {throw e;} catch (Error e) {throw e;} catch (Throwable e) {throw new AssertionError(e);}}
    }
    private final class Fixture implements AutoCloseable {
        final Environment env;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;
        final PhpOwnershipDomain.Execution execution;
        final PhpOwnershipArrays arrays;
        final PhpOwnershipObjects objects;
        Fixture(boolean debug,boolean dump,String source) throws Throwable {this(debug,dump,source,new ByteArrayOutputStream());}
        Fixture(boolean debug,boolean dump,String source,ByteArrayOutputStream output) throws Throwable {
            var scope=newScope();scope.setDebugMode(debug);env=new Environment(scope,output);
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if (dump) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            participant=domain.participant(env);execution=participant.enter();arrays=new PhpOwnershipArrays(domain.heap());objects=new PhpOwnershipObjects(domain.heap(),arrays);
        }
        PhpOwnershipObjects.Allocation object(String name) throws Throwable {
            var clazz=env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);clazz.initializeRuntimeState(env);
            var provider=objects.registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for (var p:provider.properties()) defaults.put(p,scalar(domain,p.getDefaultValue(env).toImmutable()));
            return objects.allocate(provider,env,defaults);
        }
        @Override public void close() throws Exception {participant.close();execution.close();try {env.doFinal();} catch (Exception e) {throw e;} catch (Error e) {throw e;} catch (Throwable e) {throw new AssertionError(e);}}
    }
    @Test public void publishedArrayAcceptsIntegerAndStringKeysWithNoAddressOwner() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter();var frame=new FrameHandle(d)) {
            var arrays=new PhpOwnershipArrays(d.heap());var allocation=arrays.allocate(false);var raw=allocation.array();var assembly=allocation.restoration();
            var producer=stage(d,frame,allocation);var node=producer.current().binding().node();assertEquals(1,node.incoming());assertFalse(raw.isList());
            for (int i=0;i<40;i++) put(assembly.address(new LongMemory(i*2L)),scalar(d,new LongMemory(i)));
            put(assembly.address("name"),scalar(d,new StringMemory("value")));
            assertEquals(41,raw.size());assertEquals(1,node.incoming());assertEquals(41,node.outgoing().size());
            assertEquals(39,raw.valueOfIndex(78).toLong());assertEquals("value",raw.valueOfIndex("name").toString());
            var borrowed=raw.toImmutable();put(assembly.address("tail"),scalar(d,Memory.CONST_INT_1));
            assertEquals(1,borrowed.valueOfIndex("tail").toLong());assembly.close();
            assertThrows(IllegalStateException.class,()->assembly.address("new"));
        } finally {participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void overwriteReplacesOwnerAndRPromotesCurrentAddressWithoutChangingOldAliases() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter();var allocation=new PhpOwnershipArrays(d.heap()).allocate(true);var assembly=allocation.restoration()) {
            var x=assembly.address("x");var y=assembly.address("y");var z=assembly.address("z");
            put(x,scalar(d,Memory.CONST_INT_1));var old=promote(x);ref(y,old);put(x,scalar(d,new LongMemory(2)));ref(z,promote(x));
            assertEquals(1,y.resolve().payloadPublisher().current().binding().rawValue().toLong());
            y.resolve().payloadPublisher().assign(scalar(d,new LongMemory(99)));
            assertEquals(2,allocation.array().valueOfIndex("x").toLong());assertEquals(2,allocation.array().valueOfIndex("z").toLong());
            z.resolve().payloadPublisher().assign(scalar(d,new LongMemory(88)));assertEquals(88,allocation.array().valueOfIndex("x").toLong());
        } finally {participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void exactEvalTransfersToPhysicalEdgeAndAbortKeepsProducerAndRawKeys() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter();var frame=new FrameHandle(d);var target=new PhpOwnershipArrays(d.heap()).allocate(true);var assembly=target.restoration()) {
            var arrays=new PhpOwnershipArrays(d.heap());var child=arrays.allocate(true);var childRaw=child.array();var source=stage(d,frame,child);var node=source.current().binding().node();var address=assembly.address("child");
            int edges=target.certificate().node().outgoing().size();
            try (var p=address.prepareMoveFrom(address.current(),source,source.current())) {}
            assertSame(childRaw,source.current().binding().rawValue());assertEquals(0,target.array().size());assertEquals(edges,target.certificate().node().outgoing().size());
            try (var p=address.prepareMoveFrom(address.current(),source,source.current())) {p.store();p.commit();}
            assertNull(source.current().binding());assertEquals(1,node.incoming());assertSame(childRaw,target.array().valueOfIndex("child").toValue());
            assertEquals(1,target.certificate().node().outgoing().size());
        } finally {participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void staleForeignAndConsumedPermitsRejectBeforeChangingPublishedBacking() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("test");
        try (var execution=participant.enter();var frame=new FrameHandle(d);var allocation=new PhpOwnershipArrays(d.heap()).allocate(true);var assembly=allocation.restoration()) {
            var x=assembly.address("x");var pending=x.preparePut(x.current(),scalar(d,Memory.CONST_INT_1));put(assembly.address("other"),scalar(d,new LongMemory(2)));
            try (pending) {assertThrows(IllegalArgumentException.class,pending::store);}assertNull(allocation.array().getByScalar("x"));
            var source=d.heap().framePublisher(frame.temporary("source"));try (var p=source.prepare(source.current(),scalar(d,new LongMemory(3)))) {p.commit();}
            var expected=source.current();var p=x.prepareMoveFrom(x.current(),source,expected);
            try (var change=source.prepare(source.current(),scalar(d,new LongMemory(4)))) {change.commit();}
            try (p) {assertThrows(IllegalArgumentException.class,p::store);}assertNull(allocation.array().getByScalar("x"));assertEquals(4,source.current().binding().rawValue().toLong());
            try (var done=x.preparePut(x.current(),scalar(d,Memory.CONST_INT_1))) {done.store();done.commit();assertThrows(IllegalStateException.class,done::store);}
            assertThrows(IllegalArgumentException.class,()->assembly.address(Integer.valueOf(5)));
            var foreign=new PhpOwnershipDomain();var visitor=foreign.participant("foreign");
            try(var foreignExecution=visitor.enter()) {
                var before=x.current();int size=allocation.array().size();
                assertThrows(IllegalArgumentException.class,()->x.preparePut(before,scalar(foreign,new LongMemory(9))));
                assertSame(before.binding().rawValue(),x.current().binding().rawValue());assertEquals(size,allocation.array().size());
            } finally {visitor.close();}
        } finally {participant.close();}
        assertEquals(0,d.graph().liveNodes());
    }
    @Test public void typedTableReplacementDetachesOnlyThisPropertySourceAndKeepsDeclaredOrdinalSourceDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dump:new boolean[]{false,true})
            try (var f=new Fixture(debug,dump,"class RPType {public int $x=1;public int $y=0;public int $z=0;}" );var object=f.object("RPType");var assembly=object.restoration()) {
                var x=assembly.address("x");var y=assembly.address("y");var z=assembly.address("z");var old=promote(x);ref(y,old);
                int ordinal=object.object().getProperties().bucketOrdinal(x.resolve().ownerPublisher().carrier());
                var strict=PhpPropertyWriteContext.enter(f.env,f.env.trace(),true);
                try {put(x,scalar(f.domain,new LongMemory(2)));ref(z,promote(x));} finally {PhpPropertyWriteContext.leave(strict);}
                var oldCell=(ReferenceMemory)old.rawValue();assertTrue(oldCell.hasPropertySources());
                assertEquals(ordinal,object.object().getProperties().bucketOrdinal(x.resolve().ownerPublisher().carrier()));
                y.resolve().payloadPublisher().assign(scalar(f.domain,new LongMemory(99)));assertEquals(2,object.object().getProperties().valueOfIndex("x").toLong());
                assertEquals(99,oldCell.toLong());assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->y.resolve().payloadPublisher().assign(scalar(f.domain,new StringMemory("bad"))));
                assertEquals(99,oldCell.toLong());put(x,scalar(f.domain,new LongMemory(7)));assertEquals(2,object.object().getProperties().valueOfIndex("z").toLong());
                assertEquals(3,object.object().getProperties().size());
            }
    }
    @Test public void typedOverwriteReleasesOnlyItsConstraintWhileOtherReferenceSourceSurvivesSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true}) try(var f=new Fixture(false,dump,"class RPExactSource {public int $x=1;public int|string $y=0;}");var object=f.object("RPExactSource");var assembly=object.restoration()) {
            var x=assembly.address("x");var y=assembly.address("y");ref(y,promote(x));put(x,scalar(f.domain,new LongMemory(2)));
            var strict=PhpPropertyWriteContext.enter(f.env,f.env.trace(),true);
            try {
                y.resolve().payloadPublisher().assign(scalar(f.domain,new StringMemory("free")));
                assertEquals("free",object.object().getProperties().valueOfIndex("y").toString());
                assertEquals(2,object.object().getProperties().valueOfIndex("x").toLong());
                assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->y.resolve().payloadPublisher().assign(scalar(f.domain,new DoubleMemory(1.2))));
                assertEquals("free",object.object().getProperties().valueOfIndex("y").toString());
            } finally {PhpPropertyWriteContext.leave(strict);}
        }
    }
    @Test public void tableFreshOriginAndStrictRebindFailureLeaveBothSourcesAndStoreUnchangedSourceDump() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) try (var f=new Fixture(false,dump,"class RPStrict {public int $x=1;}" );var object=f.object("RPStrict");var assembly=object.restoration();var bad=f.arrays.allocateCell(scalar(f.domain,new StringMemory("bad")))) {
            assertThrows(IllegalStateException.class,object::restoration);var x=assembly.address("x");var before=x.current();
            var strict=PhpPropertyWriteContext.enter(f.env,f.env.trace(),true);
            try {assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->x.preparePut(before,scalar(f.domain,new StringMemory("2"))));
                assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->x.prepareReference(before,bad.binding()));}
            finally {PhpPropertyWriteContext.leave(strict);}
            assertEquals(1,object.object().getProperties().valueOfIndex("x").toLong());assertFalse(bad.cell().hasPropertySources());
            assertSame(before.binding().rawValue(),x.current().binding().rawValue());
        }
    }
    @Test public void actualDestructorReentrySeesNewRawAndEmptySourceBeforeDrainSourceDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dump:new boolean[]{false,true}) {
            Runnable[] observe={null};var output=new ByteArrayOutputStream(){public synchronized void write(byte[] b,int o,int n){super.write(b,o,n);if(observe[0]!=null){var run=observe[0];observe[0]=null;run.run();}}};
            try (var f=new Fixture(debug,dump,"class RPDrop {function __destruct(){echo 'drop;';flush();}}",output);var frame=new FrameHandle(f.domain);var target=f.arrays.allocate(true);var assembly=target.restoration()) {
                var object=f.object("RPDrop");var x=assembly.address("x");put(x,object.binding());object.close();
                var source=f.domain.heap().framePublisher(frame.temporary("source"));try (var p=source.prepare(source.current(),scalar(f.domain,new LongMemory(2)))) {p.commit();}
                int[] seen={0};observe[0]=()->{assertNull(source.current().binding());assertEquals(2,target.array().valueOfIndex("x").toLong());seen[0]++;try{put(assembly.address("reentry"),scalar(f.domain,new LongMemory(3)));}catch(Throwable e){throw new AssertionError(e);}};
                try(var p=x.prepareMoveFrom(x.current(),source,source.current())){p.store();p.commit();}
                assertEquals(1,seen[0]);assertEquals(3,target.array().valueOfIndex("reentry").toLong());
            }
        }
    }
    @Test public void actualThrowingDestructorLeavesTransferredStoreCommittedSourceDump() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) try (var f=new Fixture(false,dump,"class RPThrow {function __destruct(){throw new Exception('restoration-dtor');}}" );var frame=new FrameHandle(f.domain);var target=f.arrays.allocate(true);var assembly=target.restoration()) {
            var object=f.object("RPThrow");var x=assembly.address("x");put(x,object.binding());object.close();
            var source=f.domain.heap().framePublisher(frame.temporary("source"));try(var p=source.prepare(source.current(),scalar(f.domain,new LongMemory(8)))){p.commit();}
            try(var p=x.prepareMoveFrom(x.current(),source,source.current())){p.store();try{p.commit();fail("destructor throw lost");}catch(php.runtime.lang.BaseException e){assertEquals("restoration-dtor",e.getMessage(f.env).toString());}assertTrue(p.isCommitted());}
            assertNull(source.current().binding());assertEquals(8,target.array().valueOfIndex("x").toLong());assertEquals(8,x.current().binding().rawValue().toLong());
        }
    }
    @Test public void cleanupTransferPreservesOldCanonicalAliasesAndRejectsStaleOrNonemptyTemps() throws Throwable {
        var d=new PhpOwnershipDomain();var participant=d.participant("cleanup");
        try(var execution=participant.enter();var frame=new FrameHandle(d);var allocation=new PhpOwnershipArrays(d.heap()).allocate(true);var assembly=allocation.restoration()) {
            var x=assembly.address("x");var y=assembly.address("y");put(x,scalar(d,Memory.CONST_INT_1));var cell=promote(x);ref(y,cell);
            var cleanup=d.heap().framePublisher(frame.temporary("overwritten"));var before=x.current();var empty=cleanup.current();
            try(var aborted=x.prepareClearTo(before,cleanup,empty)) {}
            assertSame(cell.rawValue(),x.current().binding().rawValue());assertSame(empty,cleanup.current());
            try(var clearing=x.prepareClearTo(before,cleanup,empty)) {clearing.store();clearing.commit();}
            assertTrue(allocation.array().valueOfIndex("x").isNull());assertSame(cell.rawValue(),cleanup.current().binding().rawValue());
            assertEquals(2,cell.node().incoming());assertEquals(1,allocation.array().valueOfIndex("y").toLong());
            put(x,scalar(d,new LongMemory(2)));ref(assembly.address("z"),promote(x));
            assertEquals(1,allocation.array().valueOfIndex("y").toLong());assertEquals(2,allocation.array().valueOfIndex("z").toLong());
            assertThrows(IllegalArgumentException.class,()->x.prepareClearTo(x.current(),cleanup,cleanup.current()));
            try(var release=cleanup.prepare(cleanup.current(),scalar(d,Memory.NULL))) {release.commit();}
            var other=d.heap().framePublisher(frame.temporary("stale"));var stale=x.prepareClearTo(x.current(),other,other.current());put(x,scalar(d,new LongMemory(3)));
            try(stale) {assertThrows(IllegalArgumentException.class,stale::store);}assertNull(other.current().binding());assertEquals(3,allocation.array().valueOfIndex("x").toLong());
        } finally {participant.close();}
    }
    @Test public void typedCleanupPublishesNullWithoutDroppingOtherConstraintsAndChecksFinalValueSourceDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) for(boolean dump:new boolean[]{false,true})
            try(var f=new Fixture(debug,dump,"class RPCleanupType {public int $x=1;public int|string $y=0;}");var frame=new FrameHandle(f.domain);var object=f.object("RPCleanupType");var assembly=object.restoration()) {
                var x=assembly.address("x");var y=assembly.address("y");ref(y,promote(x));var cleanup=f.domain.heap().framePublisher(frame.temporary("old-reference"));
                var ordinal=object.object().getProperties().bucketOrdinal(x.resolve().ownerPublisher().carrier());
                try(var p=x.prepareClearTo(x.current(),cleanup,cleanup.current())) {p.store();p.commit();}
                assertTrue(object.object().getProperties().valueOfIndex("x").isNull());assertEquals(ordinal,object.object().getProperties().bucketOrdinal(x.resolve().ownerPublisher().carrier()));
                var strict=PhpPropertyWriteContext.enter(f.env,f.env.trace(),true);
                try {
                    y.resolve().payloadPublisher().assign(scalar(f.domain,new StringMemory("free")));
                    assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->x.preparePut(x.current(),scalar(f.domain,new StringMemory("2"))));
                    assertTrue(object.object().getProperties().valueOfIndex("x").isNull());put(x,scalar(f.domain,new LongMemory(7)));
                    assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->x.resolve().payloadPublisher().assign(scalar(f.domain,new StringMemory("bad"))));
                    assertEquals("free",object.object().getProperties().valueOfIndex("y").toString());
                } finally {PhpPropertyWriteContext.leave(strict);}
                assertEquals(7,object.object().getProperties().valueOfIndex("x").toLong());
            }
    }
    @Test public void overwrittenObjectDestructorWaitsForCleanupReleaseAndSeesFinalStoreSourceDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) for(boolean dump:new boolean[]{false,true}) {
            Runnable[] observe={null};var output=new ByteArrayOutputStream(){public synchronized void write(byte[] b,int o,int n){super.write(b,o,n);if(observe[0]!=null){var run=observe[0];observe[0]=null;run.run();}}};
            try(var f=new Fixture(debug,dump,"class RPCleanupDrop {function __destruct(){echo 'cleanup-drop;';flush();}}",output);var frame=new FrameHandle(f.domain);var target=f.arrays.allocate(true);var assembly=target.restoration()) {
                var object=f.object("RPCleanupDrop");var x=assembly.address("x");put(x,object.binding());object.close();var cleanup=f.domain.heap().framePublisher(frame.temporary("old-object"));
                int[] seen={0};observe[0]=()->{assertTrue(cleanup.current().binding().rawValue().isNull());assertEquals(8,target.array().valueOfIndex("x").toLong());seen[0]++;try {put(assembly.address("dtor-reentry"),scalar(f.domain,new LongMemory(9)));}catch(Throwable e){throw new AssertionError(e);}};
                try(var p=x.prepareClearTo(x.current(),cleanup,cleanup.current())) {p.store();p.commit();}
                assertEquals(0,seen[0]);assertTrue(target.array().valueOfIndex("x").isNull());assertEquals(1,cleanup.current().binding().node().incoming());
                put(x,scalar(f.domain,new LongMemory(8)));assertEquals(0,seen[0]);
                try(var release=cleanup.prepare(cleanup.current(),scalar(f.domain,Memory.NULL))) {release.commit();}
                assertEquals(1,seen[0]);assertEquals(9,target.array().valueOfIndex("dtor-reentry").toLong());
            }
        }
    }
    @Test public void cleanupDestructorFailureKeepsFinalPlacementAndCleanupMirrorCommittedSourceDump() throws Throwable {
        for(boolean dump:new boolean[]{false,true}) try(var f=new Fixture(false,dump,"class RPCleanupThrow {function __destruct(){throw new Exception('cleanup-dtor');}}");var frame=new FrameHandle(f.domain);var target=f.arrays.allocate(true);var assembly=target.restoration()) {
            var object=f.object("RPCleanupThrow");var x=assembly.address("x");put(x,object.binding());object.close();var cleanup=f.domain.heap().framePublisher(frame.temporary("old-object"));
            try(var p=x.prepareClearTo(x.current(),cleanup,cleanup.current())) {p.store();p.commit();}put(x,scalar(f.domain,new LongMemory(8)));
            try(var release=cleanup.prepare(cleanup.current(),scalar(f.domain,Memory.NULL))) {try{release.commit();fail("lost cleanup destructor");}catch(php.runtime.lang.BaseException e){assertEquals("cleanup-dtor",e.getMessage(f.env).toString());}assertTrue(release.isCommitted());}
            assertTrue(cleanup.current().binding().rawValue().isNull());assertEquals(8,target.array().valueOfIndex("x").toLong());
        }
    }
}
