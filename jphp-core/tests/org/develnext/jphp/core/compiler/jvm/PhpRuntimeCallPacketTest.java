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

/** Actual generated lexical body execution through sequential caller CALL custody. */
public class PhpRuntimeCallPacketTest {
    private static final class Fixture implements AutoCloseable {
        final Environment env;final PhpRuntimeOwnership.Authority authority;final PhpOwnershipDomain.Execution execution;final PhpRuntimeOwnership.Frame frame;
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final ObjectMemory closure;final ReferenceMemory cv=new ReferenceMemory();
        Fixture(boolean debug,boolean dump,String source)throws Throwable {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());execution=authority.enter();frame=authority.frame();
            var entity=module.getClosures().iterator().next();authority.registerModule(module,module.getData(),(plan,member,bytes)->{});authority.registerMethod(entity.findMethod("__invoke"),entity.getData(),(plan,member,bytes)->{});
            try(var p=frame.prepareLocal(1,"f",cv,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){p.afterLocalStore();p.commit();}
            try(var result=authority.include(module)){closure=(ObjectMemory)result.raw();cv.assign(closure);}
        }
        @Override public void close()throws Exception{frame.close();authority.close();execution.close();try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    @Test public void cellReturnIsTransferredIntoCallerEvalBeforeReceiverAndCalleeRelease()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"return function &(){static $x=5;return $x;};")) {
            var descriptor=((php.runtime.lang.Closure)f.closure.value).ownershipClosure();var object=descriptor.objectCertificate().node();
            assertEquals(1,object.incoming());
            try(var packet=f.frame.callable(4_100_000,f.cv,1,-1,TraceInfo.UNKNOWN,0)) {
                assertEquals(2,object.incoming());f.cv.assign(Memory.NULL);assertEquals(1,object.incoming());
                var cell=(ReferenceMemory)packet.invokeToEval(4_100_000);
                assertSame(cell,f.frame.evalHolder(4_100_000).raw());assertEquals(5,cell.toLong());
                var cellNode=cell.getRuntimeCell().node();assertEquals("Released callable drops physical static owner after RESULT transfer",1,cellNode.incoming());
                assertEquals(PhpOwnership.State.DISPOSED,object.state());f.frame.releaseEval(4_100_000);assertEquals(PhpOwnership.State.DISPOSED,cellNode.state());
            }
        }
    }
    @Test public void sequentialSendImportsActualArrayProducerWithoutSecondArgumentOwner()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"return function($a){$v=$a;$a=null;return $v;};")) {
            var allocation=f.authority.arrays().allocate(true);var node=allocation.certificate().node();var array=(ArrayMemory)f.frame.stageAllocation(4_200_000,allocation);
            try(var packet=f.frame.callable(4_200_001,f.cv,1,-1,TraceInfo.UNKNOWN,1)) {
                packet.send(0,array,4_200_000);assertTrue(f.frame.evalHolder(4_200_000).raw().isUndefined());assertEquals(1,node.incoming());
                Memory result=packet.invokeToEval(4_200_001);assertTrue(result instanceof ArrayMemory);assertEquals(1,node.incoming());
                f.frame.releaseEval(4_200_001);assertEquals(PhpOwnership.State.DISPOSED,node.state());
            }
        }
    }
    @Test public void cellResultMovesToReferenceCvAndByValueCvBeforeProducerRelease()throws Throwable {
        for(boolean reference:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"return function &(){static $x=5;return $x;};")) {
            ReferenceMemory destination=new ReferenceMemory();try(var p=f.frame.prepareLocal(2,"r",destination,PhpRuntimeOwnership.Role.BORROW_ADDRESS)){p.afterLocalStore();p.commit();}
            try(var packet=f.frame.callable(4_400_000,f.cv,1,-1,TraceInfo.UNKNOWN,0)) {
                var cell=(ReferenceMemory)packet.invokeToEval(4_400_000);var node=cell.getRuntimeCell().node();assertEquals(2,node.incoming());
                f.frame.assignLocalFromEval(4_400_000,2,reference?cell:cell.getRawValue(),reference?1:0);
                assertTrue(f.frame.evalHolder(4_400_000).raw().isUndefined());assertEquals(5,destination.toLong());assertEquals(reference?2:1,node.incoming());
                if(reference){assertSame(cell,destination.getRawValue());destination.manualUnset(f.env);assertEquals(1,node.incoming());}else assertFalse(destination.getRawValue() instanceof ReferenceMemory);
            }
        }
    }
    @Test public void standaloneCallerCannotClaimDiscardedResultWithoutPhysicalInvocation()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,"return #[\\NoDiscard] function(){echo 'body';return 7;};")) {
            var object=((php.runtime.lang.Closure)f.closure.value).ownershipClosure().objectCertificate().node();
            try(var packet=f.frame.callable(4_500_000,f.cv,1,-1,TraceInfo.UNKNOWN,0)) {
                int live=f.authority.domain().graph().liveNodes();assertEquals(2,object.incoming());
                try{packet.invokeToEvalDiscarded(4_500_000);fail("Standalone caller claimed a physical discarded CALL");}
                catch(IllegalArgumentException expected){assertEquals("CALL has no actual caller invocation",expected.getMessage());}
                assertEquals(live,f.authority.domain().graph().liveNodes());assertEquals(2,object.incoming());assertNull(f.frame.evalHolder(4_500_000));
                f.env.getDefaultBuffer().flush();assertEquals("",f.output.toString("UTF-8"));
                Memory result=packet.invokeToEval(4_500_000);assertEquals(7,result.toLong());assertSame(result,f.frame.evalHolder(4_500_000).raw());
                f.env.getDefaultBuffer().flush();assertEquals("body",f.output.toString("UTF-8"));
                assertEquals(1,object.incoming());f.frame.releaseEval(4_500_000);
            }
        }
    }
    @Test public void unknownCallableAddressRejectsBeforeReceiverOwnershipChanges()throws Throwable {
        try(var f=new Fixture(false,false,"return function(){return 7;};")) {
            var node=((php.runtime.lang.Closure)f.closure.value).ownershipClosure().objectCertificate().node();long before=node.incoming();
            try{f.frame.callable(4_300_000,new ReferenceMemory(f.closure),1,-1,TraceInfo.UNKNOWN,0);fail("Forged CV admitted");}catch(IllegalArgumentException expected){}
            assertEquals(before,node.incoming());assertSame(f.closure.value,((ObjectMemory)f.cv.getRawValue()).value);
        }
    }
}
