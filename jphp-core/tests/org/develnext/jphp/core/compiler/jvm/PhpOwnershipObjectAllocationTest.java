package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import static org.junit.Assert.*;

/** Native Reader reaches the real ClassEntity factory; its result transfers before temporary cleanup. */
public class PhpOwnershipObjectAllocationTest {
    private interface Check {void verify(Environment env,PhpOwnershipExecution.Session session) throws Throwable;}
    private void modes(String source,Check check) throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            env.getDefaultBuffer().setChunkSize(0);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                for (var entity:module.getClasses()) entity.initializeRuntimeState(env);
                var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
                var session=new PhpOwnershipExecution.Session(participant,env);
                try (var execution=session.enter()) {check.verify(env,session);participant.close();}
            } finally {env.doFinal();}
        }
    }
    private Memory unserialize(Environment env,PhpOwnershipExecution.Session session,PhpOwnershipExecution.CallRoots roots,String wire) throws Throwable {
        roots.send(session.value(new StringMemory(wire)));
        return roots.invokeFunction(env.fetchFunction("unserialize"),TraceInfo.UNKNOWN);
    }
    @Test public void realFactoryPublishesActualObjectAndTypedDefaultsBeforeNativeReturnTransfer() throws Throwable {
        modes("class OwnedFactory {public int $v=3;public ?string $s=null;function __construct(){throw new Exception('must-not-run');}}",(env,session)->{
            PhpOwnership.Node node;
            try (var roots=session.newCallRoots()) {
                var value=unserialize(env,session,roots,"O:12:\"OwnedFactory\":0:{}");var object=value.toValue(ObjectMemory.class).value;
                var certificate=((php.runtime.lang.BaseObject)object).getOwnershipObject();assertNotNull(certificate);node=certificate.node();
                assertSame(node,roots.result().node());assertSame(object,node.identity());assertEquals(1,node.incoming());
                assertEquals(3,object.getProperties().getByScalar("v").toLong());assertTrue(object.getProperties().getByScalar("s").isNull());
                assertTrue(object.getProperties().getByScalar("v").hasPropertySources());
            }
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void unadmittedDestructorAndUnpublishedHeapDefaultsRejectBeforeObjectAllocation() throws Throwable {
        modes("class UnknownDestructor {function __destruct(){echo 'must-not-run';}} class UnknownDefault {public $items=[1];}",(env,session)->{
            for (String wire:new String[]{"O:17:\"UnknownDestructor\":0:{}","O:14:\"UnknownDefault\":0:{}"}) {
                try (var roots=session.newCallRoots()) {
                    try {unserialize(env,session,roots,wire);fail("Unpublished callback/default admitted");} catch (IllegalArgumentException expected) {}
                    assertNull(roots.result());assertEquals(0,session.domain().graph().liveNodes());
                }
            }
        });
    }
    @Test public void actualFactoryStagingIsReleasedWhenMalformedReaderReturnsFalse() throws Throwable {
        modes("class ParseStaged {public int $v=7;}",(env,session)->{
            try (var roots=session.newCallRoots()) {
                assertSame(Memory.FALSE,unserialize(env,session,roots,"O:11:\"ParseStaged\":1:{"));
                assertNull(roots.result().node());assertEquals(0,session.domain().graph().liveNodes());
            }
        });
    }
    @Test public void actualReaderArrayProducerPublishesBeforeReturnAndCleansMalformedStage() throws Throwable {
        modes("",(env,session)->{
            PhpOwnership.Node node;
            try (var roots=session.newCallRoots()) {
                var result=unserialize(env,session,roots,"a:0:{}").toValue(ArrayMemory.class);
                var certificate=session.arrays().certificate(result);node=certificate.node();
                assertEquals(0,result.size());assertSame(node,roots.result().node());assertEquals(1,node.incoming());
            }
            assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals(0,session.domain().graph().liveNodes());
            try (var roots=session.newCallRoots()) {
                assertSame(Memory.FALSE,unserialize(env,session,roots,"a:1:{"));
                assertNull(roots.result().node());assertEquals(0,session.domain().graph().liveNodes());
            }
        });
    }
    @Test public void actualReaderPublishesNestedArraysIntoPhysicalElementsWithoutExtraStages() throws Throwable {
        modes("",(env,session)->{
            PhpOwnership.Node outer,inner;
            try (var roots=session.newCallRoots()) {
                var result=unserialize(env,session,roots,"a:2:{s:1:\"i\";i:7;s:5:\"child\";a:1:{i:0;s:1:\"v\";}}").toValue(ArrayMemory.class);
                outer=session.arrays().certificate(result).node();
                var child=result.getByScalar("child").toValue(ArrayMemory.class);inner=session.arrays().certificate(child).node();
                assertEquals(7,result.getByScalar("i").toLong());assertEquals("v",child.getByScalar(LongMemory.valueOf(0)).toString());
                assertEquals(1,outer.incoming());assertEquals(1,inner.incoming());assertSame(outer,roots.result().node());
            }
            assertEquals(PhpOwnership.State.DISPOSED,outer.state());assertEquals(PhpOwnership.State.DISPOSED,inner.state());
            assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void actualReaderPromotesPhysicalReferenceAndKeepsOnlyActualAliasAfterOverwrite() throws Throwable {
        modes("",(env,session)->{
            try (var roots=session.newCallRoots()) {
                var result=unserialize(env,session,roots,"a:3:{s:1:\"x\";i:7;s:1:\"y\";R:2;s:1:\"x\";i:9;}").toValue(ArrayMemory.class);
                assertEquals(9,result.getByScalar("x").toLong());assertEquals(7,result.getByScalar("y").toLong());
                var carrier=result.getByScalar("y");assertTrue(carrier.isReference());
                var cell=carrier.getReference();var node=session.arrays().certificate(cell).node();
                assertEquals(1,node.incoming());assertEquals(1,roots.result().node().incoming());
            }
            assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void unadmittedRestorationHookRejectsBeforeFreshObjectAndPayloadAllocation() throws Throwable {
        modes("class UnknownWake {function __wakeup(){echo 'must-not-run';}} class UnknownModern {function __unserialize($data){echo 'must-not-run';}}",(env,session)->{
            for (String wire:new String[]{"O:11:\"UnknownWake\":0:{}","O:13:\"UnknownModern\":0:{}"}) {
                try (var roots=session.newCallRoots()) {
                    try {unserialize(env,session,roots,wire);fail("Unadmitted restoration hook ran");} catch (IllegalArgumentException expected) {}
                    assertNull(roots.result());assertEquals(0,session.domain().graph().liveNodes());
                }
            }
        });
    }
    private void admitMethod(Environment env,PhpOwnershipExecution.Session session,String className,String methodName) throws Throwable {
        var clazz=env.fetchClass(className);var method=clazz.findMethod(methodName);byte[] bytes=clazz.getData();
        var plan=org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(bytes,method.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(method.getNativeMethod()));
        assertNotNull(plan);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
        var diagnostics=new StringWriter();
        org.objectweb.asm.util.CheckClassAdapter.verify(new org.objectweb.asm.ClassReader(bytes),env.scope.getClassLoader(),false,new PrintWriter(diagnostics));
        assertEquals("ASM","",diagnostics.toString());
        session.registerGenerated(method,method.getNativeMethod(),bytes,plan.runtimePlan(),(actual,member,defined)->{
            assertEquals(plan,org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.read(defined,member.getName(),PhpOwnershipExecution.descriptor(member)));
            assertTrue(actual.requiredCapabilities().stream().allMatch(c->java.util.Set.of("frame-local-publication-v1","empty-tick-handler","literal-buffered-output").contains(c)));
        });
    }
    private String text(Environment env) throws Throwable {
        env.getDefaultBuffer().flush();return ((ByteArrayOutputStream)env.getDefaultBuffer().getOutput()).toString(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test public void actualRootCellUnwrapAndSingletonSerializationMatchNativeCycleShapes() throws Throwable {
        String[][] cases={
            {"a:3:{s:1:\"x\";R:1;s:1:\"x\";i:2;s:1:\"y\";R:1;}","a:2:{s:1:\"x\";i:2;s:1:\"y\";N;}"},
            {"a:2:{i:0;R:1;i:1;R:1;}","a:2:{i:0;a:2:{i:0;R:2;i:1;R:2;}i:1;R:2;}"},
            {"a:1:{i:0;a:2:{i:0;R:2;i:1;R:2;}}","a:1:{i:0;a:2:{i:0;R:2;i:1;R:2;}}"}
        };
        modes("",(env,session)->{
            for (String[] row:cases) {
                try (var roots=session.newCallRoots()) {
                    Memory result=unserialize(env,session,roots,row[0]);
                    try (var serialize=session.newCallRoots()) {
                        serialize.send(session.value(result));
                        assertEquals(row[1],serialize.invokeFunction(env.fetchFunction("serialize"),TraceInfo.UNKNOWN).toString());
                    }
                }
                session.domain().graph().collectCycles();assertEquals(0,session.domain().graph().liveNodes());
            }
        });
    }
    @Test public void actualDuplicateCleanupRunsBeforeNewWakeupAndReleasesQueuedOwnersBeforeReturn() throws Throwable {
        modes("class ReaderD {function __destruct(){echo 'D';}} class ReaderW {function __wakeup(){echo 'W';}function __destruct(){echo 'N';}}",(env,session)->{
            admitMethod(env,session,"ReaderD","__destruct");admitMethod(env,session,"ReaderW","__wakeup");admitMethod(env,session,"ReaderW","__destruct");
            try (var roots=session.newCallRoots()) {
                var result=unserialize(env,session,roots,"a:2:{s:1:\"x\";O:7:\"ReaderD\":0:{}s:1:\"x\";O:7:\"ReaderW\":0:{}}");
                assertTrue(result.isArray());assertEquals("DW",text(env));assertEquals(1,roots.result().node().incoming());
                var child=result.toValue(ArrayMemory.class).getByScalar("x").toValue(ObjectMemory.class);
                assertEquals(1,session.value(child).node().incoming());
            }
            assertEquals("DWN",text(env));assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void actualModernPayloadMovesToDeferredHookAndDoesNotSurviveReturn() throws Throwable {
        modes("class ReaderD {function __destruct(){echo 'D';}} class ReaderM {function __unserialize($data){echo 'M';}function __destruct(){echo 'N';}}",(env,session)->{
            admitMethod(env,session,"ReaderD","__destruct");admitMethod(env,session,"ReaderM","__unserialize");admitMethod(env,session,"ReaderM","__destruct");
            try (var roots=session.newCallRoots()) {
                assertTrue(unserialize(env,session,roots,"a:2:{s:1:\"x\";O:7:\"ReaderD\":0:{}s:1:\"x\";O:7:\"ReaderM\":1:{s:1:\"v\";i:8;}}").isArray());
                assertEquals("DM",text(env));
                assertEquals(3,session.domain().graph().liveNodes()); // result STORAGE, OBJECT and its properties TABLE; payload is gone.
            }
            assertEquals("DMN",text(env));assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void actualMalformedResultIsReleasedBeforeCompletedHookQueueAndOldTemporaries() throws Throwable {
        modes("class ReaderA {function __destruct(){echo 'A';}} class ReaderB {function __destruct(){echo 'B';}} class ReaderW {function __wakeup(){echo 'W';}function __destruct(){echo 'N';}}",(env,session)->{
            for(String name:new String[]{"ReaderA","ReaderB","ReaderW"}) admitMethod(env,session,name,"__destruct");admitMethod(env,session,"ReaderW","__wakeup");
            try (var roots=session.newCallRoots()) {
                assertSame(Memory.FALSE,unserialize(env,session,roots,"a:3:{s:1:\"x\";O:7:\"ReaderA\":0:{}s:1:\"x\";O:7:\"ReaderB\":0:{}s:3:\"bad\";?}"));
                assertTrue(text(env).endsWith("BA"));assertEquals(0,session.domain().graph().liveNodes());
            }
        });
    }
    @Test public void actualTypedPropertyOverwriteDetachesOnlyReplacedSourceAndKeepsCurrentReferenceAddress() throws Throwable {
        modes("class ReaderTyped {public int $x=1;public $y;public $z;}",(env,session)->{
            try (var roots=session.newCallRoots()) {
                var result=unserialize(env,session,roots,"O:11:\"ReaderTyped\":4:{s:1:\"x\";i:1;s:1:\"y\";R:2;s:1:\"x\";i:2;s:1:\"z\";R:2;}").toValue(ObjectMemory.class).value;
                var x=result.getProperties().getByScalar("x");var y=result.getProperties().getByScalar("y");var z=result.getProperties().getByScalar("z");
                assertEquals(2,x.toLong());assertEquals(1,y.toLong());assertEquals(2,z.toLong());
                assertSame(x.getReference(),z.getReference());assertNotSame(x.getReference(),y.getReference());
                assertFalse(y.getReference().hasPropertySources());assertTrue(x.getReference().hasPropertySources());
                assertEquals(1,session.arrays().certificate(y.getReference()).node().incoming());
                assertEquals(2,session.arrays().certificate(x.getReference()).node().incoming());
            }
            assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void actualTypeErrorReleasesPartialResultBeforeFifoOldValueCleanup() throws Throwable {
        modes("class ReaderA {function __destruct(){echo 'A';}} class ReaderB {function __destruct(){echo 'B';}} class ReaderT {public int $x=1;function __destruct(){echo 'T';}}",(env,session)->{
            for(String name:new String[]{"ReaderA","ReaderB","ReaderT"}) admitMethod(env,session,name,"__destruct");
            try (var roots=session.newCallRoots()) {
                try {
                    unserialize(env,session,roots,"a:3:{s:1:\"x\";O:7:\"ReaderA\":0:{}s:1:\"x\";O:7:\"ReaderB\":0:{}s:3:\"bad\";O:7:\"ReaderT\":1:{s:1:\"x\";s:3:\"bad\";}}");
                    fail("Typed restore must fail");
                } catch(php.runtime.lang.exception.BaseTypeError failure) {
                    assertNotNull(roots.failureBinding(failure));assertNull(roots.result());
                    assertEquals("BTA",text(env));
                }
            }
            assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void actualOwnedReaderMatchesNativeDuplicateAddressMatrixIncludingRemovedDescendants() throws Throwable {
        try (var input=getClass().getResourceAsStream("/resources/type_hinting/php85_reader_addresses.tsv")) {
            assertNotNull(input);String matrix=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            modes("class ReaderO {public $n;}",(env,session)->{
                for (String line:matrix.split("\\r?\\n")) {
                    if(line.isEmpty() || line.startsWith("#")) continue;
                    String[] row=line.split("\\t",-1);assertEquals(6,row.length);
                    String wire=row[1].replace("O:8:\"stdClass\"","O:7:\"ReaderO\"");
                    String expected=row[2].replace("O:8:\"stdClass\"","O:7:\"ReaderO\"");
                    try(var roots=session.newCallRoots()) {
                        Memory result=unserialize(env,session,roots,wire);
                        try(var serialization=session.newCallRoots()) {
                            serialization.send(session.value(result));
                            assertEquals(row[0],expected,serialization.invokeFunction(env.fetchFunction("serialize"),TraceInfo.UNKNOWN).toString());
                        }
                        if(!row[4].equals("0")) assertSame(row[0],Memory.FALSE,result);
                    }
                    session.domain().graph().collectCycles();assertEquals(row[0],0,session.domain().graph().liveNodes());
                }
            });
        }
    }
    private void nestedTypeFailure(String wire,String expected) throws Throwable {
        modes("class ReaderT {public int $x=1;function __destruct(){echo 'T';}}"
                +"class ReaderD {function __destruct(){echo 'D';}}"
                +"class ReaderU {public $y;function __destruct(){echo 'U';}}"
                +"class ReaderW {function __wakeup(){echo 'W';}function __destruct(){echo 'H';}}",(env,session)->{
            for(String name:new String[]{"ReaderT","ReaderD","ReaderU","ReaderW"})
                admitMethod(env,session,name,"__destruct");
            admitMethod(env,session,"ReaderW","__wakeup");
            try(var roots=session.newCallRoots()) {
                try {unserialize(env,session,roots,wire);fail("Typed restoration must fail");}
                catch(php.runtime.lang.exception.BaseTypeError failure) {
                    assertNotNull(roots.failureBinding(failure));assertNull(roots.result());
                    assertEquals(expected,text(env));
                }
            }
            assertEquals(0,session.domain().graph().liveNodes());
        });
    }
    @Test public void actualNestedArrayCompletesBeforeTypedPropertyValidation() throws Throwable {
        nestedTypeFailure("O:7:\"ReaderT\":1:{s:1:\"x\";a:1:{i:0;O:7:\"ReaderD\":0:{}}}","DT");
    }
    @Test public void actualNestedObjectCompletesBeforeTypedPropertyValidation() throws Throwable {
        nestedTypeFailure("O:7:\"ReaderT\":1:{s:1:\"x\";O:7:\"ReaderU\":1:{s:1:\"y\";O:7:\"ReaderD\":0:{}}}","UDT");
    }
    @Test public void completedNestedWakeupIsSuppressedWhenContainingPropertyTypeFails() throws Throwable {
        nestedTypeFailure("O:7:\"ReaderT\":1:{s:1:\"x\";a:1:{i:0;O:7:\"ReaderW\":0:{}}}","T");
    }
    @Test public void pendingTypedArrayPromotionKeepsActualCanonicalReferenceAndFinalSource() throws Throwable {
        modes("class ReaderP {public array $x;public $y;}",(env,session)->{
            try(var roots=session.newCallRoots()) {
                var result=unserialize(env,session,roots,"O:7:\"ReaderP\":2:{s:1:\"x\";a:1:{i:0;R:2;}s:1:\"y\";R:2;}");
                var properties=result.toValue(ObjectMemory.class).value.getProperties();
                var x=properties.getByScalar("x").getReference();var y=properties.getByScalar("y").getReference();
                assertSame(x,y);assertTrue(x.hasPropertySources());
                assertSame(x,x.toValue(ArrayMemory.class).getByScalar(LongMemory.CONST_INT_0).getReference());
                assertEquals(3,session.arrays().certificate(x).node().incoming());
                try(var serialization=session.newCallRoots()) {
                    serialization.send(session.value(result));
                    assertEquals("O:7:\"ReaderP\":2:{s:1:\"x\";a:1:{i:0;R:2;}s:1:\"y\";R:2;}",serialization.invokeFunction(env.fetchFunction("serialize"),TraceInfo.UNKNOWN).toString());
                }
            }
            session.domain().graph().collectCycles();assertEquals(0,session.domain().graph().liveNodes());
        });
    }
}
