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
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Actual arrays, private ReferenceMemory fields and production PHP destructor dispatch, without compiler activation. */
public class PhpOwnershipArraysTest extends JvmCompilerCase {
    private PhpOwnershipValues.Binding scalar(PhpOwnershipDomain domain, Memory value) {
        return PhpOwnershipValues.bindValue(domain,value,null);
    }
    private void publish(PhpOwnershipHeap.Publisher publisher, PhpOwnershipValues.Binding value,
                         Memory[] actual, int index) throws Throwable {
        Memory raw=value.rawValue();
        try (var token=publisher.prepare(publisher.current(),value)) {actual[index]=raw;token.commit();}
    }
    private Memory load(Environment env, String source, boolean dumped) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if (dumped) {
            var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
        return module.include(env);
    }
    private ObjectMemory object(Environment env,String source,boolean dumped) throws Throwable {
        return load(env,source,dumped).toValue(ObjectMemory.class);
    }
    public static Memory observeDestructor(Environment env,Memory[] args) {
        env.getUserValue("haOwnershipObserver",Runnable.class).run();return Memory.NULL;
    }
    private PhpOwnershipObjects.Allocation destructor(PhpOwnershipDomain domain,PhpOwnershipArrays arrays,Environment env,
                                                       String name,String source,boolean dumped,Runnable observe) throws Throwable {
        if (env.getUserValue("haOwnershipObserverRegistered",Boolean.class) == null) {
            var function=new php.runtime.reflection.FunctionEntity(new Context(""));function.setName("ha_ownership_observe");
            function.setParameters(new php.runtime.reflection.ParameterEntity[0]);
            function.setNativeMethod(getClass().getMethod("observeDestructor",Environment.class,Memory[].class));
            env.registerFunction(function);env.setUserValue("haOwnershipObserverRegistered",Boolean.TRUE);
        }
        env.setUserValue("haOwnershipObserver",observe);load(env,source,dumped);
        var objects=new PhpOwnershipObjects(domain.heap(),arrays);var entity=env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);
        var provider=objects.registerPropertiesProvider(entity,entity.getNativeClass().getConstructor(Environment.class,php.runtime.reflection.ClassEntity.class),(e,v)->{});
        return objects.allocate(provider,env,java.util.Collections.emptyMap());
    }
    private String output(Environment env, ByteArrayOutputStream output) throws Throwable {
        env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);
    }

    @Test public void addressedCowKeepsOtherSameJavaHolderAndActualDestructorSourceAndDump() throws Throwable {
        for (boolean dumped : new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=new Environment(newScope(),out);
            var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
            try (var execution=participant.enter()) {
                var arrays=new PhpOwnershipArrays(domain.heap());var frame=new PhpOwnershipFrame(domain.graph());
                var first=domain.heap().framePublisher(frame.local("first"));var second=domain.heap().framePublisher(frame.local("second"));
                Memory[] actual=new Memory[2];int[] calls={0};
                var owned=destructor(domain,arrays,env,"HACowDrop","class HACowDrop {function __destruct(){ha_ownership_observe();echo 'drop;';}}",dumped,()->{calls[0]++;assertEquals(2,((ArrayMemory)actual[0]).getByScalar(Memory.CONST_INT_0).toLong());});
                var value=owned.binding();var object=(ObjectMemory)value.rawValue();
                ArrayMemory original;
                try (var allocation=arrays.allocate(false)) {
                    allocation.append(value);original=allocation.array();publish(first,allocation.binding(),actual,0);publish(second,allocation.binding(),actual,1);
                }
                owned.close();assertSame(actual[0],actual[1]);assertEquals(1,value.node().incoming());
                try (var write=arrays.prepareWrite(first,first.current())) {
                    assertTrue(write.separated());assertNotSame(original,write.array());assertSame(original,actual[0]);assertSame(original,actual[1]);
                    assertEquals(2,value.node().incoming());
                    var raw=write.array();try (var token=write.preparePublication()) {actual[0]=raw;token.commit();}
                    write.entry(Memory.CONST_INT_0).assign(scalar(domain,new LongMemory(2)));
                }
                assertSame(original,actual[1]);assertEquals(0,calls[0]);assertSame(object,original.getByScalar(Memory.CONST_INT_0).getRawValue());
                publish(second,scalar(domain,Memory.NULL),actual,1);
                assertEquals(1,calls[0]);assertEquals("drop;",output(env,out));assertTrue(object.value.isFinalized());
                publish(first,scalar(domain,Memory.NULL),actual,0);frame.close();assertEquals(0,domain.graph().liveNodes());
            } finally {participant.close();env.doFinal();}
        }
    }
    @Test public void cowPreservesCanonicalCellAndOnePayloadEdgeAcrossRealAliases() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(domain.heap());var frame=new PhpOwnershipFrame(domain.graph());Memory[] actual=new Memory[2];
            var a=domain.heap().framePublisher(frame.local("a"));var b=domain.heap().framePublisher(frame.local("b"));
            PhpOwnershipArrays.CellCertificate cell;
            try (var canonical=arrays.allocateCell(scalar(domain,Memory.CONST_INT_1));var allocation=arrays.allocate(false)) {
                cell=canonical.cell().getOwnershipCell();allocation.append(canonical.binding());
                publish(a,allocation.binding(),actual,0);publish(b,allocation.binding(),actual,1);
            }
            try (var write=arrays.prepareWrite(a,a.current())) {
                var raw=write.array();try (var token=write.preparePublication()) {actual[0]=raw;token.commit();}
                assertSame(((ArrayMemory)actual[1]).getByScalar(Memory.CONST_INT_0).getRawValue(),write.entry(Memory.CONST_INT_0).carrier().getRawValue());
                write.entry(Memory.CONST_INT_0).assign(scalar(domain,new LongMemory(3)));
            }
            assertEquals(3,((ArrayMemory)actual[0]).getByScalar(Memory.CONST_INT_0).toLong());
            assertEquals(3,((ArrayMemory)actual[1]).getByScalar(Memory.CONST_INT_0).toLong());
            assertEquals(2,cell.node().incoming());assertEquals(1,cell.node().outgoing().size());
            publish(a,scalar(domain,Memory.NULL),actual,0);publish(b,scalar(domain,Memory.NULL),actual,1);frame.close();assertEquals(0,domain.graph().liveNodes());
        } finally {participant.close();env.doFinal();}
    }
    @Test public void privatePayloadPublicationObservesReentrantCommitAndThrowingRealDestructor() throws Throwable {
        for (boolean dumped : new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();var env=new Environment(newScope(),out);var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
            try (var execution=participant.enter()) {
                var arrays=new PhpOwnershipArrays(domain.heap());var frame=new PhpOwnershipFrame(domain.graph());Memory[] actual=new Memory[1];
                var owner=domain.heap().framePublisher(frame.local("a"));PhpOwnershipArrays.Entry[] entry={null};
                Throwable[] nested={null};var owned=destructor(domain,arrays,env,"HARawDrop","class HARawDrop {function __destruct(){ha_ownership_observe();echo 'drop;';}}",dumped,()->{
                    assertEquals(2,entry[0].carrier().toLong());assertEquals(1,entry[0].current().revision());
                    try {entry[0].assign(scalar(domain,new LongMemory(3)));} catch (Throwable failure) {nested[0]=failure;}
                });
                var value=owned.binding();var object=(ObjectMemory)value.rawValue();
                try (var allocation=arrays.allocate(false)) {entry[0]=allocation.append(value);publish(owner,allocation.binding(),actual,0);}owned.close();
                entry[0].assign(scalar(domain,new LongMemory(2)));assertNull(nested[0]);assertEquals(3,entry[0].carrier().toLong());assertEquals(2,entry[0].current().revision());
                assertEquals("drop;",output(env,out));publish(owner,scalar(domain,Memory.NULL),actual,0);frame.close();
                var throwingOwned=destructor(domain,arrays,env,"HAThrowDrop","class HAThrowDrop {function __destruct(){ha_ownership_observe();throw new \\Exception('drop failure');}}",dumped,()->{});
                var throwing=new ObjectMemory(throwingOwned.object());
                var throwFrame=new PhpOwnershipFrame(domain.graph());var throwOwner=domain.heap().framePublisher(throwFrame.local("throw"));
                var throwingValue=throwingOwned.binding();PhpOwnershipArrays.Entry target;
                try (var allocation=arrays.allocate(false)) {target=allocation.append(throwingValue);publish(throwOwner,allocation.binding(),actual,0);}throwingOwned.close();
                assertThrows(php.runtime.lang.BaseException.class,()->target.assign(scalar(domain,new LongMemory(9))));
                assertEquals(9,target.carrier().toLong());assertEquals(1,target.current().revision());assertTrue(throwing.value.isFinalized());
                publish(throwOwner,scalar(domain,Memory.NULL),actual,0);throwFrame.close();assertEquals(0,domain.graph().liveNodes());
            } finally {participant.close();env.doFinal();}
        }
    }
    @Test public void sourcePropertyGuardChecksBeforeRawPrepareAndKeepsCanonicalPayload() throws Throwable {
        for (boolean dumped : new boolean[]{false,true}) {
            var env=new Environment(newScope(),new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
            try (var execution=participant.enter()) {
                var arrays=new PhpOwnershipArrays(domain.heap());
                var typed=object(env,"class HAGuard {public int $v=1;} return new HAGuard();",dumped);
                try (var canonical=arrays.allocateCell(scalar(domain,Memory.CONST_INT_1))) {
                    typed.value.getProperties().getByScalar("v").assignRef(canonical.cell());
                    var before=canonical.value().current();
                    var error=assertThrows(BaseTypeError.class,()->canonical.value().assign(scalar(domain,new StringMemory("bad"))));
                    assertEquals("Cannot assign string to reference held by property HAGuard::$v of type int",error.getMessage(env).toString());assertSame(before,canonical.value().current());
                    assertSame(Memory.CONST_INT_1,canonical.cell().getRawValue());
                    canonical.value().assign(scalar(domain,new StringMemory("2")));
                    assertEquals(2,canonical.cell().toLong());assertEquals(Memory.Type.INT,canonical.cell().getRawValue().type);
                }
                assertEquals(0,domain.graph().liveNodes());
            } finally {participant.close();env.doFinal();}
        }
    }
    @Test public void certifiedBackendsRejectLegacyMutationsBeforeAnyRawOrEdgeChange() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("test");
        try (var execution=participant.enter();var allocation=new PhpOwnershipArrays(domain.heap()).allocate(false)) {
            var entry=allocation.append(scalar(domain,Memory.CONST_INT_1));var array=allocation.array();var before=entry.current();
            assertThrows(IllegalStateException.class,()->array.add(2));assertThrows(IllegalStateException.class,()->array.put("x",Memory.NULL));
            assertThrows(IllegalStateException.class,array::clear);assertThrows(IllegalStateException.class,array::checkCopied);
            assertThrows(IllegalStateException.class,array::shift);assertThrows(IllegalStateException.class,array::pop);
            assertThrows(IllegalStateException.class,()->array.merge(new ArrayMemory(),false,null));
            assertThrows(IllegalStateException.class,()->entry.carrier().assign(2));assertThrows(IllegalStateException.class,()->entry.carrier().setValue(Memory.NULL));
            assertThrows(IllegalStateException.class,()->entry.carrier().assignRef(new ReferenceMemory(Memory.NULL)));
            assertThrows(IllegalStateException.class,()->entry.carrier().captureIncrementCell().restore(Memory.NULL));
            assertThrows(IllegalStateException.class,()->entry.carrier().unset());assertThrows(IllegalStateException.class,()->entry.carrier().getReferenceForBinding());
            var iterator=array.iterator();iterator.next();assertThrows(IllegalStateException.class,iterator::remove);
            assertEquals(1,array.size());assertEquals(1,entry.carrier().toLong());assertSame(before,entry.current());assertEquals(1,allocation.certificate().node().outgoing().size());
            var ordinary=new ArrayMemory();ordinary.add(1);ordinary.put("x",Memory.NULL);ordinary.clear();assertEquals(0,ordinary.size());
        }
        participant.close();
    }
    @Test public void staleCowPreparationAndForeignCertificatesCannotPublishActualHolder() throws Throwable {
        var first=new PhpOwnershipDomain();var second=new PhpOwnershipDomain();var p=first.participant("first");var q=second.participant("second");
        try (var e=p.enter();var f=q.enter()) {
            var arrays=new PhpOwnershipArrays(first.heap());var other=new PhpOwnershipArrays(second.heap());
            var frame=new PhpOwnershipFrame(first.graph());var a=first.heap().framePublisher(frame.local("a"));var b=first.heap().framePublisher(frame.local("b"));Memory[] actual=new Memory[2];
            try (var foreign=other.allocate(false);var initial=arrays.allocate(false)) {
                foreign.append(scalar(second,Memory.CONST_INT_1));
                assertThrows(IllegalArgumentException.class,()->initial.append(foreign.binding()));
                assertThrows(IllegalArgumentException.class,()->arrays.certificate(foreign.array()));assertEquals(0,initial.array().size());
                initial.append(scalar(first,Memory.CONST_INT_1));publish(a,initial.binding(),actual,0);publish(b,initial.binding(),actual,1);
            }
            try (var write=arrays.prepareWrite(a,a.current())) {
                assertTrue(write.separated());publish(a,scalar(first,Memory.NULL),actual,0);
                assertThrows(IllegalArgumentException.class,write::preparePublication);assertSame(Memory.NULL,actual[0]);
            }
            publish(b,scalar(first,Memory.NULL),actual,1);frame.close();assertEquals(0,first.graph().liveNodes());
        }
        q.close();p.close();
    }
    @Test public void physicalDuplicateEntryEdgesAndActualNestedCarrierCowAreIndependent() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("test");
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(domain.heap());var frame=new PhpOwnershipFrame(domain.graph());Memory[] actual=new Memory[2];
            var outer=domain.heap().framePublisher(frame.local("outer"));var alias=domain.heap().framePublisher(frame.local("alias"));
            try (var child=arrays.allocate(true);var parent=arrays.allocate(false)) {
                child.insertBucket("x",scalar(domain,new LongMemory(4)));child.insertBucket("x",scalar(domain,Memory.CONST_INT_1));
                parent.append(child.binding());publish(outer,parent.binding(),actual,0);publish(alias,child.binding(),actual,1);
            }
            var outerArray=(ArrayMemory)actual[0];PhpOwnershipArrays.Entry field;
            try (var access=arrays.prepareWrite(outer,outer.current())) {assertFalse(access.separated());field=access.entry(Memory.CONST_INT_0);}
            var target=field.carrier();var old=(ArrayMemory)target.getRawValue();
            try (var write=arrays.prepareWrite(field,field.current())) {
                assertTrue(write.separated());assertEquals(2,write.certificate().node().outgoing().size());
                try (var token=write.preparePublication()) {target.publishOwnershipRaw((PhpOwnershipArrays.RawPublication)token);token.commit();}
                write.entry("x").assign(scalar(domain,new LongMemory(7)));
                assertEquals(7,((ArrayMemory)target.getRawValue()).getByScalar("x").toLong());assertEquals(1,old.getByScalar("x").toLong());
                assertEquals(4,((ArrayMemory)target.getRawValue()).bucketAt(0).toLong());assertEquals(1,old.bucketAt(1).toLong());
            }
            publish(outer,scalar(domain,Memory.NULL),actual,0);publish(alias,scalar(domain,Memory.NULL),actual,1);frame.close();assertEquals(0,domain.graph().liveNodes());
        }
        participant.close();
    }
    @Test public void undefinedCellInitializationAndBorrowedFetchPinDoNotInventOwners() throws Throwable {
        var domain=new PhpOwnershipDomain();var participant=domain.participant("test");
        try (var execution=participant.enter()) {
            var arrays=new PhpOwnershipArrays(domain.heap());
            try (var canonical=arrays.allocateCell(scalar(domain,Memory.UNDEFINED))) {
                assertSame(Memory.NULL,canonical.cell().getRawValue());
                assertSame(Memory.NULL,canonical.value().current().binding().rawValue());
            }
            var frame=new PhpOwnershipFrame(domain.graph());var owner=domain.heap().framePublisher(frame.local("a"));Memory[] actual=new Memory[1];
            try (var allocation=arrays.allocate(false)) {allocation.append(scalar(domain,Memory.CONST_INT_1));publish(owner,allocation.binding(),actual,0);}
            var raw=(ArrayMemory)actual[0];
            try (var borrowed=raw.pinWriteFetch();var write=arrays.prepareWrite(owner,owner.current())) {
                assertEquals(1,write.certificate().node().incoming());assertFalse(write.separated());assertSame(raw,write.array());
            }
            publish(owner,scalar(domain,Memory.NULL),actual,0);frame.close();assertEquals(0,domain.graph().liveNodes());
        }
        participant.close();
    }
}