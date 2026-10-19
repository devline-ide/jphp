package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.helper.ClosureEntity;
import java.io.*;
import static org.junit.Assert.*;

/** Actual source/dump shell and physical holders. Whole ordinary PHP invocation is a separate gate. */
public class PhpRuntimeClosureAllocationTest {
    private final class Fixture implements AutoCloseable {
        final Environment env;
        final php.runtime.reflection.ModuleEntity module;
        final ClosureEntity closure,captureClosure;
        final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpRuntimeOwnership.Authority authority;
        final PhpOwnershipDomain.Execution execution;
        final PhpRuntimeOwnership.Frame frame;
        Fixture(boolean debug,boolean dumped,String source) throws Throwable {this(debug,dumped,source,false);}
        Fixture(boolean debug,boolean dumped,String source,boolean executePassive) throws Throwable {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            env=new Environment(scope,new ByteArrayOutputStream());var context=new Context(source);
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
            if (dumped) {var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            this.module=module;scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);closure=module.getClosures().iterator().next();
            captureClosure=module.getClosures().stream().filter(entity->!entity.getOwnershipCapturePlan().entries().isEmpty()).findFirst().orElse(null);
            for (byte[] bytes:new byte[][]{module.getData(),closure.getData()}) {
                var diagnostics=new StringWriter();CheckClassAdapter.verify(new ClassReader(bytes),scope.getClassLoader(),false,new PrintWriter(diagnostics));
                assertEquals("ASM", "",diagnostics.toString());
            }
            if (executePassive)assertEquals("Pinned native module result",7,module.include(env).toLong());
            authority=new PhpRuntimeOwnership.Authority(env,domain);execution=authority.enter();frame=authority.frame();
        }
        ReferenceMemory address(int id,String name) throws Throwable {
            var raw=new ReferenceMemory();
            try (var publication=frame.prepareLocal(id,name,raw,PhpRuntimeOwnership.Role.BORROW_ADDRESS)) {publication.afterLocalStore();publication.commit();}
            return raw;
        }
        @Override public void close() throws Exception {frame.close();authority.close();execution.close();try {env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    @Test public void actualCompilerModuleReturnTransfersFreshClosureToCallerBeforeFrameClose() throws Throwable {
        for (boolean debug:new boolean[]{false,true})for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"return function(){return 7;};")) {
                f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->assertEquals(PhpOwnershipExecution.EntryKind.MODULE,plan.entryKind()));
                var invoke=f.closure.findMethod("__invoke");
                assertSame("Source and dump invocation owner",f.closure,invoke.getClazz());
                var bodyPlan=PhpOwnershipExecution.readFramePlan(f.closure.getData(),invoke.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(invoke.getNativeMethod()));
                assertNotNull("Actual generated invocation frame plan",bodyPlan);
                assertEquals("Actual invocation entry kind",PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE,bodyPlan.entryKind());
                f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->assertEquals(PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE,plan.entryKind()));
                PhpOwnership.Node node;
                try (var result=f.authority.include(f.module)) {
                    var raw=(ObjectMemory)result.raw();var descriptor=((php.runtime.lang.Closure)raw.value).ownershipClosure();node=descriptor.objectCertificate().node();
                    assertEquals("Only the actual caller result remains after module return",1,node.incoming());
                    try (var returned=f.authority.invoke(invoke,raw.value,TraceInfo.UNKNOWN)) {assertEquals(7,returned.raw().toLong());}
                    assertEquals(1,node.incoming());assertEquals(PhpOwnership.State.LIVE,node.state());
                }
                assertEquals(PhpOwnership.State.DISPOSED,node.state());
            }
    }
    @Test public void actualGeneratedCaptureImportsRetainDeclaredValueAndCellModes() throws Throwable {
        for (String source:new String[]{"$a=8;return function()use($a){return $a;};","$a=8;return function()use(&$a){return $a;};"})
            for (boolean debug:new boolean[]{false,true})for (boolean dumped:new boolean[]{false,true})
                try (var f=new Fixture(debug,dumped,source)) {
                    f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->{});
                    var invoke=f.closure.findMethod("__invoke");assertSame(f.closure,invoke.getClazz());
                    f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->{});
                    try (var result=f.authority.include(f.module)) {
                        var raw=(ObjectMemory)result.raw();var closure=(php.runtime.lang.Closure)raw.value;
                        boolean cell=f.closure.getOwnershipCapturePlan().entries().get(0).mode()==ClosureEntity.CaptureMode.CELL;
                        PhpOwnership.Node node=cell?((ReferenceMemory)closure.getUses()[0]).getRuntimeCell().node():null;
                        if(cell)assertEquals("Physical capture and persistent module GLOBAL remain",2,node.incoming());
                        try(var returned=f.authority.invoke(invoke,raw.value,TraceInfo.UNKNOWN)){assertEquals(8,returned.raw().toLong());}
                        if(cell)assertEquals("Capture CV release removes its extra CELL owner",2,node.incoming());
                    }
                }
    }
    @Test public void actualStaticAddressBorrowsStorageAndReferenceReturnOwnsOnlyItsCallerRoot() throws Throwable {
        for (boolean reference:new boolean[]{false,true})for (boolean debug:new boolean[]{false,true})for (boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,"return function "+(reference?"&":"")+"(){static $x=0;$x=5;return $x;};")) {
                f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->{});
                var invoke=f.closure.findMethod("__invoke");
                f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->{});
                try(var result=f.authority.include(f.module)) {
                    var raw=(ObjectMemory)result.raw();var closure=(php.runtime.lang.Closure)raw.value;PhpOwnership.Node cell=null;
                    try(var returned=f.authority.invoke(invoke,raw.value,TraceInfo.UNKNOWN)) {
                        assertEquals(5,returned.raw().toLong());
                        var staticSlot=(ReferenceMemory)closure.getStatic("x");assertNotNull(staticSlot.getRuntimeHolder());
                        if(reference) {
                            var canonical=staticSlot.getReferenceForBinding();cell=canonical.getRuntimeCell().node();
                            assertSame("Actual caller result is the exported static CELL",canonical,returned.raw());
                            assertEquals("Physical static and real caller RETURN",2,cell.incoming());
                        } else assertFalse("Value-only static stays a VALUE slot",staticSlot.getRawValue() instanceof ReferenceMemory);
                    }
                    if(reference)assertEquals("Released caller leaves the single physical static owner",1,cell.incoming());
                }
            }
    }
    @Test public void actualStaticCvReferenceRebindDetachesWithoutChangingItsPhysicalStaticSlot() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,"return function(){static $x=0;$z=7;$x=&$z;$x=9;return $x;};")) {
                f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->{});
                var invoke=f.closure.findMethod("__invoke");f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->{});
                try(var result=f.authority.include(f.module)) {
                    var raw=(ObjectMemory)result.raw();var closure=(php.runtime.lang.Closure)raw.value;
                    try(var returned=f.authority.invoke(invoke,raw.value,TraceInfo.UNKNOWN)){assertEquals(9,returned.raw().toLong());}
                    var slot=(ReferenceMemory)closure.getStatic("x");assertEquals("Pinned native detached static",0,slot.toLong());
                    assertFalse("Static table did not acquire the detached local CELL",slot.getRawValue() instanceof ReferenceMemory);
                }
            }
    }
    @Test public void actualStaticCvUnsetDetachesWithoutChangingItsPhysicalStaticSlot() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,"return function(){static $x=0;unset($x);$x=9;return $x;};")) {
                f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->{});
                var invoke=f.closure.findMethod("__invoke");f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->{});
                try(var result=f.authority.include(f.module)) {
                    var raw=(ObjectMemory)result.raw();var closure=(php.runtime.lang.Closure)raw.value;
                    try(var returned=f.authority.invoke(invoke,raw.value,TraceInfo.UNKNOWN)){assertEquals(9,returned.raw().toLong());}
                    var slot=(ReferenceMemory)closure.getStatic("x");assertEquals("Pinned native unset detaches the CV",0,slot.toLong());
                    assertFalse("Physical static remains its VALUE",slot.getRawValue() instanceof ReferenceMemory);
                }
            }
    }
    @Test public void originalReleasedStaticCloneHasIndependentCellsAfterActualCallCvUnsetSourceDump() throws Throwable {
        String source = "$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo 'released-ref-static:',$t,',',$s,\"\\n\";";
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,(strict?"declare(strict_types=1);":"")+source)) {
                f.env.getDefaultBuffer().setChunkSize(0);
                var invoke=f.closure.findMethod("__invoke");f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->{});
                f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->{});
                try(var result=f.authority.include(f.module)) {result.raw();}
                assertEquals("Pinned native original unchanged", "released-ref-static:5,9\n",f.env.getDefaultBuffer().getContents().toString());
                var original=f.authority.globalAddress("f").toObject(php.runtime.lang.Closure.class);
                var clone=f.authority.globalAddress("g").toObject(php.runtime.lang.Closure.class);
                var originalCell=((ReferenceMemory)original.getStatic("x")).getReferenceForBinding();
                var clonedCell=((ReferenceMemory)clone.getStatic("x")).getReferenceForBinding();
                assertNotSame("Released r leaves clone an independent static",originalCell,clonedCell);
                assertEquals(5,originalCell.toLong());assertEquals(9,clonedCell.toLong());
                assertEquals("Original static and final t CV",2,originalCell.getRuntimeCell().node().incoming());
                assertEquals("Cloned static and s CV",2,clonedCell.getRuntimeCell().node().incoming());
            }
    }
    @Test public void actualGlobalClosureCloneAcceptsOnlyItsPhysicalBucketPublisher() throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})
            try(var f=new Fixture(debug,dumped,(strict?"declare(strict_types=1);":"")+"$f=function(){return 7;};return $f;")) {
                f.authority.registerModule(f.module,f.module.getData(),(plan,member,bytes)->{});
                var invoke=f.closure.findMethod("__invoke");f.authority.registerMethod(invoke,f.closure.getData(),(plan,member,bytes)->{});
                try(var original=f.authority.include(f.module)) {
                    var address=f.authority.globalAddress("f");assertEquals(php.runtime.memory.support.ArrayMapEntryMemory.class,address.getClass());
                    var raw=(ObjectMemory)original.raw();var sourceNode=((php.runtime.lang.Closure)raw.value).ownershipClosure().objectCertificate().node();
                    long incoming=sourceNode.incoming();int nodes=f.domain.graph().liveNodes();
                    var forged=new php.runtime.memory.support.ArrayMapEntryMemory(null,0,"forged",raw);
                    try {PhpOwnershipClosures.cloneRuntime(f.frame,200,forged,f.env,TraceInfo.UNKNOWN);fail("Unregistered bucket must not acquire clone capability");}
                    catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("exact same-domain publisher"));}
                    assertEquals("Rejection precedes new shell allocation",nodes,f.domain.graph().liveNodes());
                    assertEquals("Rejection creates no source owner",incoming,sourceNode.incoming());
                    var clone=(ObjectMemory)PhpOwnershipClosures.cloneRuntime(f.frame,201,address,f.env,TraceInfo.UNKNOWN);
                    assertNotSame(raw.value,clone.value);var cloneNode=((php.runtime.lang.Closure)clone.value).ownershipClosure().objectCertificate().node();
                    assertEquals("Fresh clone belongs to its actual EVAL",1,cloneNode.incoming());
                    try(var returned=f.authority.invoke(invoke,clone.value,TraceInfo.UNKNOWN)){assertEquals("Pinned native clone invocation",7,returned.raw().toLong());}
                    assertEquals("Borrowing the source CV added no owner",incoming,sourceNode.incoming());
                    f.frame.releaseEval(201);assertEquals(PhpOwnership.State.DISPOSED,cloneNode.state());assertEquals(PhpOwnership.State.LIVE,sourceNode.state());
                }
            }
    }
    @Test public void passiveCloneCvAndImmediateLiteralPreserveNativeResultAndBytecode() throws Throwable {
        for (String source:new String[]{"$c=function(){return 7;};$d=clone $c;return $d();","$d=clone (function(){return 7;});return $d();"})
            for (boolean strict:new boolean[]{false,true})for (boolean debug:new boolean[]{false,true})for (boolean dumped:new boolean[]{false,true})
                try (var ignored=new Fixture(debug,dumped,(strict ? "declare(strict_types=1);" : "")+source,true)) {}
    }
    @Test public void actualEmptyShellEvalPrecedesCaptureAndOwnsCellUntilClosureRelease() throws Throwable {
        for (boolean debug:new boolean[]{false,true})for (boolean dumped:new boolean[]{false,true})
            try (var f=new Fixture(debug,dumped,"$a=8;return function()use(&$a){return $a;};")) {
                var address=f.address(1,"a");address.assign(8);var cell=address.getReferenceForBinding();var cellNode=cell.getRuntimeCell().node();
                assertEquals(1,cellNode.incoming());
                var producer=PhpOwnershipClosures.prepareRuntime(f.frame,100,f.closure);
                var shell=(ObjectMemory)f.frame.evalHolder(100).raw();var object=((php.runtime.lang.support.IPhpOwnershipObject)shell.value).getOwnershipObject();
                assertNotNull(object);var objectNode=object.node();assertNull(object.closure());assertEquals(1,objectNode.incoming());
                producer.self(Memory.NULL,null);producer.capture(0,cell);
                assertEquals("Actual capture EVAL precedes descriptor publication",2,cellNode.incoming());
                var value=(ObjectMemory)producer.complete();assertSame(shell.value,value.value);
                assertSame(cell,((php.runtime.lang.Closure)value.value).getUses()[0]);
                assertEquals("Temporary capture transferred to its physical closure edge",2,cellNode.incoming());
                f.frame.releaseEval(100);assertEquals(PhpOwnership.State.DISPOSED,objectNode.state());assertEquals(1,cellNode.incoming());
                address.unset();assertEquals(PhpOwnership.State.DISPOSED,cellNode.state());
            }
    }
    @Test public void actualStaticCloneSharesOnlyCellWithAnotherPhysicalOwner() throws Throwable {
        for (boolean debug:new boolean[]{false,true})for (boolean dumped:new boolean[]{false,true})for (String operation:new String[]{"live","unset","rebind","capture-retained","capture-released"})
            try (var f=new Fixture(debug,dumped,"$f=function &(){static $x=0;return $x;};$h=function()use(&$r){};return $f;")) {
                var producer=PhpOwnershipClosures.prepareRuntime(f.frame,100,f.closure);producer.self(Memory.NULL,null);
                var original=(ObjectMemory)producer.complete();var closure=(php.runtime.lang.Closure)original.value;
                var slot=(ReferenceMemory)closure.getOrCreateStatic("x",LongMemory.valueOf(0));var alias=f.address(1,"r");alias.assignRef(slot);alias.assign(5);
                var cell=slot.getReferenceForBinding();assertEquals(2,cell.getRuntimeCell().node().incoming());
                if (operation.startsWith("capture-")) {
                    var capture=PhpOwnershipClosures.prepareRuntime(f.frame,102,f.captureClosure);
                    capture.self(Memory.NULL,null);capture.capture(0,alias.getReferenceForBinding());capture.complete();
                    alias.unset();
                    if (operation.equals("capture-released"))f.frame.releaseEval(102);
                }
                if (operation.equals("unset"))alias.unset();
                if (operation.equals("rebind")){var other=f.address(2,"z");other.assign(7);alias.assignRef(other);}
                assertEquals((operation.equals("live")||operation.equals("capture-retained"))?2:1,cell.getRuntimeCell().node().incoming());
                var copy=(ObjectMemory)PhpOwnershipClosures.cloneRuntime(f.frame,101,original,f.env,TraceInfo.UNKNOWN);
                assertNotSame(original.value,copy.value);
                ((php.runtime.lang.Closure)copy.value).getOrCreateStatic("x",Memory.NULL).assign(9);
                assertEquals((operation.equals("live")||operation.equals("capture-retained"))?9:5,slot.toLong());
                assertEquals(9,((php.runtime.lang.Closure)copy.value).getOrCreateStatic("x",Memory.NULL).toLong());
            }
    }
}
