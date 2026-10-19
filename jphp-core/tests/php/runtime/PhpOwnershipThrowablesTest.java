package php.runtime;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real generated return failures retain the current PHP trace arguments until failure custody ends. */
public class PhpOwnershipThrowablesTest extends JvmCompilerCase {
    private final class Fixture implements AutoCloseable {
        final Environment env;final ModuleEntity module;final PhpOwnershipDomain domain=new PhpOwnershipDomain();
        final PhpOwnershipDomain.Participant participant;final PhpOwnershipExecution.Session session;final PhpOwnershipExecution.Scope scope;
        Fixture(boolean debug,boolean dump,String source)throws Throwable {
            var compileScope=newScope();compileScope.setDebugMode(debug);env=new Environment(compileScope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var compiled=new JvmCompiler(env,context).compile(false);
            if(dump){var dumper=new ModuleDumper(context,env,true);var output=new ByteArrayOutputStream();dumper.save(compiled,output);compiled=dumper.load(new ByteArrayInputStream(output.toByteArray()));}
            module=compiled;env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);for(var clazz:module.getClasses())clazz.initializeRuntimeState(env);
            participant=domain.participant(env);session=new PhpOwnershipExecution.Session(participant,env);scope=session.enter();
        }
        void admit(Object entity,java.lang.reflect.Method member,byte[] bytes)throws Throwable {
            var plan=OwnershipFrameCompiler.read(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));assertNotNull(plan);assertEquals(plan.unsupported().toString(),List.of(),plan.unsupported());session.registerGenerated(entity,member,bytes,plan.runtimePlan(),(p,m,b)->{});
        }
        FunctionEntity function(String name)throws Throwable {var function=module.findFunction(name);admit(function,function.getNativeMethod(),function.getData());return function;}
        PhpOwnershipObjects.Allocation object()throws Throwable {
            var clazz=env.fetchClass("TraceOwned",true,PhpLanguageProfile.PHP_8_5);admit(clazz.methodDestruct,clazz.methodDestruct.getNativeMethod(),clazz.getData());
            var provider=session.objects().registerPropertiesProvider(clazz,clazz.getNativeClass().getConstructor(Environment.class,ClassEntity.class),(c,e)->{});
            return session.objects().allocate(provider,env,new IdentityHashMap<>());
        }
        String output()throws IOException{return env.getDefaultBuffer().getContents().toString();}
        public void close()throws Exception {scope.close();if(!participant.isClosed())try(var execution=participant.enter()){participant.close();}try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}}
    }
    private static final String OBJECT="class TraceOwned {function __destruct(){echo 'D;';}} ";
    private BaseTypeError returnedFailure(PhpOwnershipExecution.CallRoots roots,FunctionEntity function)throws Throwable {try{roots.invokeFunction(function,TraceInfo.UNKNOWN);fail("Expected TypeError");return null;}catch(BaseTypeError error){assertNotNull(error.getOwnershipThrowable());assertNotNull(roots.failureBinding(error));assertNull(roots.result());return error;}}

    @Test public void exactNativeFailureRetainsCurrentTraceArgumentSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,OBJECT+"function traceBad($a):int{return 'bad';}")) {
            var function=f.function("traceBad");var allocation=f.object();var object=allocation.object();var node=allocation.binding().node();PhpOwnership.Node exceptionNode;
            try(var roots=f.session.newCallRoots()) {roots.send(0,allocation.binding());allocation.close();var error=returnedFailure(roots,function);exceptionNode=roots.failureBinding(error).node();
                assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals("",f.output());
                var row=error.getTrace(f.env).valueOfIndex(0);assertTrue(row.valueOfIndex("object").isUndefined());assertSame(object,row.valueOfIndex("args").valueOfIndex(0).toValue(ObjectMemory.class).value);
                var javaTrace=error.getCallStack();if(javaTrace.length>0){javaTrace[0].args[0]=Memory.NULL;assertSame(object,error.getTrace(f.env).valueOfIndex(0).valueOfIndex("args").valueOfIndex(0).toValue(ObjectMemory.class).value);}
            }
            assertEquals(PhpOwnership.State.DISPOSED,exceptionNode.state());assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("D;",f.output());
        }
    }
    @Test public void traceUsesReplacedArgumentCvAndDoesNotKeepConsumedSendSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,OBJECT+"function traceBad($a):int{$a=null;return 'bad';}")) {
            var function=f.function("traceBad");var allocation=f.object();var node=allocation.binding().node();
            try(var roots=f.session.newCallRoots()) {roots.send(0,allocation.binding());allocation.close();var error=returnedFailure(roots,function);assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("D;",f.output());assertTrue(error.getTrace(f.env).valueOfIndex(0).valueOfIndex("args").valueOfIndex(0).isNull());}
            assertEquals("D;",f.output());
        }
    }
    @Test public void explicitHostFailureOwnerOutlivesPacketAndRejectsForeignDomain()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,OBJECT+"function traceBad($a):int{return 'bad';}")) {
            var function=f.function("traceBad");var allocation=f.object();var node=allocation.binding().node();PhpOwnershipDomain.Owned retained;BaseTypeError error;
            try(var roots=f.session.newCallRoots()) {roots.send(0,allocation.binding());allocation.close();error=returnedFailure(roots,function);retained=roots.failureBinding(error).host();}
            assertEquals(PhpOwnership.State.LIVE,node.state());assertEquals("",f.output());assertNotNull(error.getMessage(f.env));
            var foreign=new PhpOwnershipDomain();var participant=foreign.participant(f.env);try(var scope=participant.enter()) {var opaque=foreign.object(error,null);try{PhpOwnershipValues.bindValue(foreign,new ObjectMemory(error),opaque).requireCertifiedObject();fail("Foreign native origin admitted");}catch(IllegalArgumentException expected){}}
            retained.close();assertEquals(PhpOwnership.State.DISPOSED,node.state());assertEquals("D;",f.output());
        }
    }
    @Test public void actualHostFailureCanReleaseInAnotherParticipantAfterOriginParticipantCloses()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,OBJECT+"function traceBad($a):int{return 'bad';}")) {
            var function=f.function("traceBad");var allocation=f.object();var child=allocation.binding().node();PhpOwnershipDomain.Owned host;BaseTypeError error;
            try(var roots=f.session.newCallRoots()){roots.send(0,allocation.binding());allocation.close();error=returnedFailure(roots,function);host=roots.failureBinding(error).host();}
            f.scope.close();try(var context=f.participant.enter()){f.participant.close();}
            var other=new Environment(f.env.getScope(),new ByteArrayOutputStream());other.getDefaultBuffer().setChunkSize(0);
            var participant=f.domain.participant(other);var session=new PhpOwnershipExecution.Session(participant,other);
            try(var context=session.enter()) {
                var destructor=f.module.findClass("TraceOwned").methodDestruct;var member=destructor.getNativeMethod();
                var plan=OwnershipFrameCompiler.read(destructor.getClazz().getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                session.registerGenerated(destructor,member,destructor.getClazz().getData(),plan.runtimePlan(),(p,m,b)->{});
                assertSame(error.getOwnershipThrowable().node(),session.value(new ObjectMemory(error)).node());
                host.close();assertEquals(PhpOwnership.State.DISPOSED,child.state());assertEquals("D;",other.getDefaultBuffer().getContents().toString());assertEquals("",f.output());
            } finally {try(var context=participant.enter()){participant.close();}other.doFinal();}
        }
    }
    @Test public void unknownOuterHeapTraceRejectsBeforeGeneratedEffectsSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(debug,dump,OBJECT+"function traceSafe(){echo 'B;';return 1;}")) {
            var function=f.function("traceSafe");var allocation=f.object();var node=allocation.binding().node();
            f.env.pushCall(TraceInfo.UNKNOWN,null,new Memory[]{allocation.binding().rawValue()},"untracked",null,null);
            try(var roots=f.session.newCallRoots()){try{roots.invokeFunction(function,TraceInfo.UNKNOWN);fail("Unknown outer trace origin admitted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("trace"));}assertEquals("",f.output());assertNull(roots.result());assertEquals(PhpOwnership.State.LIVE,node.state());}
            finally{f.env.popCall();allocation.close();}
            assertEquals("D;",f.output());
        }
    }
}
