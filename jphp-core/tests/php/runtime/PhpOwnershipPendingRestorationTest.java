package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Explicit pending parser permits; these checks do not activate the Reader or compiler adapter. */
public class PhpOwnershipPendingRestorationTest extends JvmCompilerCase {
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
        Fixture(boolean dump) throws Throwable {
            env=new Environment(newScope(),new ByteArrayOutputStream());
            var context=new Context("class PendingArray {public array $child;public array|string $alias;} class PendingInt {public int $child;} class PendingLeaf {}");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if (dump) {var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
            env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            participant=domain.participant(env);execution=participant.enter();arrays=new PhpOwnershipArrays(domain.heap());objects=new PhpOwnershipObjects(domain.heap(),arrays);
        }
        PhpOwnershipObjects.Allocation object(String name) throws Throwable {
            var clazz=env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);clazz.initializeRuntimeState(env);
            var provider=objects.registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(e,v)->{});
            var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
            for (var p:provider.properties()) defaults.put(p,value(p.getDefaultValue(env).toImmutable()));
            return objects.allocate(provider,env,defaults);
        }
        PhpOwnershipValues.Binding value(Memory value) {return PhpOwnershipValues.bindValue(domain,value,null);}
        @Override public void close() throws Exception {
            participant.close();execution.close();try {env.doFinal();} catch (Exception e) {throw e;} catch (Error e) {throw e;} catch (Throwable e) {throw new AssertionError(e);}
            assertEquals(0,domain.graph().liveNodes());
        }
    }
    private PhpOwnershipFramePublisher stage(Fixture f,FrameHandle frame,PhpOwnershipArrays.Allocation child) throws Throwable {
        var source=f.domain.heap().framePublisher(frame.temporary("child"));
        f.domain.graph().deferDestruction(()->{try (var p=child.preparePublication(source,source.current())) {p.commit();}child.close();});return source;
    }
    private PhpOwnershipFramePublisher stage(Fixture f,FrameHandle frame,PhpOwnershipObjects.Allocation child) throws Throwable {
        var source=f.domain.heap().framePublisher(frame.temporary("object"));
        f.domain.graph().deferDestruction(()->{try (var p=child.preparePublication(source,source.current())) {p.commit();}child.close();});return source;
    }
    private PhpOwnershipArrays.RestorationCompletion pending(PhpOwnershipArrays.RestorationAddress address,PhpOwnershipFramePublisher source,PhpOwnershipArrays.RestorationAssembly child) throws Throwable {
        try (var p=address.preparePendingMoveFrom(address.current(),source,source.current(),child)) {p.store();p.commit();return p.pendingCompletion();}
    }
    private void complete(PhpOwnershipArrays.RestorationAddress address,PhpOwnershipArrays.RestorationCompletion completion) throws Throwable {
        try (var p=completion.prepare(address.current())) {p.store();p.commit();}
    }
    @Test public void physicalEdgePrecedesCompletionAndPromotionGetsOnlyCompletedSource() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) try (var f=new Fixture(dump);var frame=new FrameHandle(f.domain);var parent=f.object("PendingArray");var table=parent.restoration()) {
            var child=f.arrays.allocate(true);var childAssembly=child.restoration();var source=stage(f,frame,child);var node=source.current().binding().node();var x=table.address("child");
            var completion=pending(x,source,childAssembly);assertNull(source.current().binding());assertEquals(1,node.incoming());assertSame(node,x.current().binding().node());
            assertFalse(x.resolve().ownerPublisher().carrier().hasPropertySources());
            try (var p=x.preparePromotion(x.current())) {p.store();p.commit();}
            assertFalse(x.resolve().ownerPublisher().carrier().hasPropertySources());
            var alias=table.address("alias");try (var p=alias.prepareReference(alias.current(),x.current().binding())) {p.store();p.commit();}
            var nested=childAssembly.address("nested");try (var p=nested.preparePut(nested.current(),f.value(Memory.CONST_INT_1))) {p.store();p.commit();}
            complete(x,completion);assertTrue(x.resolve().ownerPublisher().carrier().hasPropertySources());
            var strict=PhpPropertyWriteContext.enter(f.env,f.env.trace(),true);
            try {assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->x.resolve().payloadPublisher().assign(f.value(new StringMemory("bad"))));}
            finally {PhpPropertyWriteContext.leave(strict);}
            assertEquals(1,parent.object().getProperties().valueOfIndex("child").valueOfIndex("nested").toLong());
            assertThrows(IllegalArgumentException.class,()->completion.prepare(x.current()));
            try (var p=f.arrays.prepareTableUnset(parent.certificate().properties(),"child")) {p.store();p.commit();}
            alias.resolve().payloadPublisher().assign(f.value(new StringMemory("other-source")));
            strict=PhpPropertyWriteContext.enter(f.env,f.env.trace(),true);
            try {assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->alias.resolve().payloadPublisher().assign(f.value(Memory.CONST_INT_1)));}
            finally {PhpPropertyWriteContext.leave(strict);}
            assertEquals("other-source",parent.object().getProperties().valueOfIndex("alias").toString());childAssembly.close();
        }
    }
    @Test public void invalidCompletedArrayAndObjectBecomeUndefinedBeforeParentCleanup() throws Throwable {
        for (boolean dump:new boolean[]{false,true}) for (boolean objectValue:new boolean[]{false,true}) for (boolean promoted:new boolean[]{false,true})
            try (var f=new Fixture(dump);var frame=new FrameHandle(f.domain);var parent=f.object("PendingInt");var table=parent.restoration()) {
                PhpOwnershipFramePublisher source;PhpOwnershipArrays.RestorationAssembly childAssembly;
                if (objectValue) {var child=f.object("PendingLeaf");childAssembly=child.restoration();source=stage(f,frame,child);}
                else {var child=f.arrays.allocate(true);childAssembly=child.restoration();source=stage(f,frame,child);}
                var node=source.current().binding().node();var x=table.address("child");var ordinal=parent.object().getProperties().bucketOrdinal(x.resolve().ownerPublisher().carrier());
                var completion=pending(x,source,childAssembly);assertEquals(PhpOwnership.State.LIVE,node.state());
                if (promoted) try (var p=x.preparePromotion(x.current())) {p.store();p.commit();}
                assertThrows(php.runtime.lang.exception.BaseTypeError.class,()->completion.prepare(x.current()));
                try (var p=completion.prepareUnset(x.current())) {p.store();p.commit();}
                assertTrue(x.current().binding().rawValue().isUndefined());assertEquals(PhpOwnership.State.DISPOSED,node.state());
                assertEquals(ordinal,parent.object().getProperties().bucketOrdinal(x.resolve().ownerPublisher().carrier()));
                try (var p=x.preparePut(x.current(),f.value(new LongMemory(4)))) {p.store();p.commit();}
                assertTrue(x.resolve().ownerPublisher().carrier().hasPropertySources());childAssembly.close();
            }
    }
    @Test public void malformedAbortPreservesPartialRawAndRestoresSourceOnlyAfterPromotion() throws Throwable {
        for (boolean promoted:new boolean[]{false,true}) try (var f=new Fixture(false);var frame=new FrameHandle(f.domain);var parent=f.object("PendingInt");var table=parent.restoration()) {
            var child=f.arrays.allocate(true);var raw=child.array();var childAssembly=child.restoration();var source=stage(f,frame,child);var x=table.address("child");var completion=pending(x,source,childAssembly);
            if (promoted) try (var p=x.preparePromotion(x.current())) {p.store();p.commit();}
            completion.abort(x.current());assertSame(raw,x.resolve().payloadPublisher().current().binding().rawValue());
            assertEquals(promoted,x.resolve().ownerPublisher().carrier().hasPropertySources());childAssembly.close();
        }
    }
    @Test public void pendingPermitRejectsWrongFreshAssemblyAndStaleProducerWithoutRawMutation() throws Throwable {
        try (var f=new Fixture(false);var frame=new FrameHandle(f.domain);var parent=f.object("PendingArray");var table=parent.restoration()) {
            var child=f.arrays.allocate(true);var childAssembly=child.restoration();var source=stage(f,frame,child);var x=table.address("child");
            try (var foreign=f.arrays.allocate(true);var wrong=foreign.restoration()) {
                assertThrows(IllegalArgumentException.class,()->x.preparePendingMoveFrom(x.current(),source,source.current(),wrong));
            }
            var p=x.preparePendingMoveFrom(x.current(),source,source.current(),childAssembly);
            var nested=childAssembly.address("later");try (var edit=nested.preparePut(nested.current(),f.value(Memory.CONST_INT_1))) {edit.store();edit.commit();}
            try (p) {assertThrows(IllegalArgumentException.class,p::store);}
            assertTrue(x.current().binding().rawValue().isUndefined());assertNotNull(source.current().binding());childAssembly.close();
            var foreign=new PhpOwnershipDomain();var participant=foreign.participant("foreign");
            try (var execution=participant.enter();var otherFrame=new FrameHandle(foreign);var otherChild=new PhpOwnershipArrays(foreign.heap()).allocate(true);var otherAssembly=otherChild.restoration()) {
                var otherSource=foreign.heap().framePublisher(otherFrame.temporary("foreign-child"));
                try (var publication=otherChild.preparePublication(otherSource,otherSource.current())) {publication.commit();}
                assertThrows(IllegalArgumentException.class,()->x.preparePendingMoveFrom(x.current(),otherSource,otherSource.current(),otherAssembly));
                assertTrue(x.current().binding().rawValue().isUndefined());
            } finally {participant.close();}
            assertEquals(0,foreign.graph().liveNodes());
        }
    }
    @Test public void ordinaryHandleDefersTypeCheckAndHandlesPartialReferenceAbort() throws Throwable {
        try (var f=new Fixture(false)) {
            var property=f.env.fetchClass("PendingInt",true,PhpLanguageProfile.PHP_8_5).findProperty("child");
            var origin=new ReferenceMemory(Memory.UNDEFINED);origin.attachPropertySource(f.env,property);
            var child=new ArrayMemory();var pending=origin.beginPropertyRestoration(Memory.NULL);assertTrue(origin.isNull());pending.store(child);
            assertSame(child,origin.toValue());assertFalse(origin.hasPropertySources());
            assertThrows(php.runtime.lang.exception.BaseTypeError.class,pending::complete);assertTrue(origin.isUndefined());
            origin.attachPropertySource(f.env,property);pending=origin.beginPropertyRestoration(Memory.NULL);pending.store(child);
            var canonical=new ReferenceMemory(child).getReferenceForBinding();pending.store(canonical);pending.abort();
            assertSame(child,origin.toValue());assertTrue(origin.hasPropertySources());
        }
    }
}
