package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.*;
import php.runtime.lang.*;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PhpJvmFinalizerDispatchTest extends JvmCompilerCase {
    private static final ThreadLocal<AtomicInteger> CALLS=new ThreadLocal<>();
    private static final ThreadLocal<IObject> QUEUED=new ThreadLocal<>();
    public static final class ProbeExtension extends Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends FunctionsContainer {
        public static Memory runtime_finalizer_probe(Environment env) {
            assertSame(env,Environment.current());CALLS.get().incrementAndGet();return Memory.NULL;
        }
        public static Memory queue_finalizer_probe(Environment env) {
            env.enqueueJvmFinalizer(QUEUED.get());return Memory.NULL;
        }
    }
    @FunctionalInterface interface Callback {void run(Environment env)throws Throwable;}
    private IObject fixture(Environment env,Callback callback) {
        var context=new Context("");var type=new ClassEntity(context);type.setName("PendingJvmFixture");
        var method=new MethodEntity(context) {
            @Override public Memory invokeDynamic(IObject object,Environment active,TraceInfo trace,Memory... args)throws Throwable {
                callback.run(active);return Memory.NULL;
            }
        };
        method.setName("__destruct");method.setClazz(type);type.methodDestruct=method;return new BaseObject(env,type) {};
    }
    private ModuleEntity module(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private void worker(Runnable task)throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try{executor.submit(task).get(5,TimeUnit.SECONDS);}
        finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private void hostFrame(Environment env) {env.pushCall(TraceInfo.UNKNOWN,null,new Memory[0],"active-host-frame",null,null);}
    private void intact(Environment env) {assertEquals(1,env.getCallStackTop());assertEquals("active-host-frame",env.peekCall(0).function);}

    @Test public void foreignGeneratedEntryPublishesWithoutPhpAndRestoresCallerContextSourceDumpDebug()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.registerExtension(new ProbeExtension());scope.setDebugMode(debug);
            var previous=Environment.current();var env=new Environment(scope,new ByteArrayOutputStream());var calls=new AtomicInteger();CALLS.set(calls);
            try {
                var object=((ObjectMemory)module(env,"function runtime_finalizer_user(){runtime_finalizer_probe();}class PendingParent{function __destruct(){runtime_finalizer_user();}}class PendingChild extends PendingParent{}return new PendingChild();",dump).include(env)).value;
                var entry=object.getClass().getDeclaredMethod("finalize");env.releaseThreadSupport();assertSame(previous,Environment.current());hostFrame(env);
                worker(()->{try{entry.invoke(object);}catch(Exception error){throw new AssertionError(error);}});
                assertEquals(0,calls.get());assertFalse(object.isFinalized());intact(env);
                env.runPendingFinalizers();assertEquals(1,calls.get());assertTrue(object.isFinalized());intact(env);assertSame(previous,Environment.current());
                env.runPendingFinalizers();env.finalizeObjects();assertEquals(1,calls.get());intact(env);env.popCall();
            }finally{env.doFinal();CALLS.remove();}
        }
    }
    @Test public void failureKeepsOtherPendingReceiversAndPreservesThrowableIdentity()throws Throwable {
        var scope=newScope();var previous=Environment.current();var env=new Environment(scope,new ByteArrayOutputStream());env.releaseThreadSupport();hostFrame(env);
        var first=new AtomicInteger();var second=new AtomicInteger();var expected=new AssertionError("pending-finalizer-failure");
        var a=fixture(env,active->{assertSame(active,Environment.current());first.incrementAndGet();throw expected;});
        var b=fixture(env,active->{assertSame(active,Environment.current());second.incrementAndGet();});
        try {
            worker(()->{env.enqueueJvmFinalizer(a);env.enqueueJvmFinalizer(a);env.enqueueJvmFinalizer(b);});
            assertEquals(0,first.get());assertEquals(0,second.get());intact(env);
            try{env.runPendingFinalizers();fail("Original destructor failure expected");}catch(AssertionError actual){assertSame(expected,actual);}
            assertEquals(1,first.get());assertEquals(0,second.get());intact(env);assertSame(previous,Environment.current());
            env.runPendingFinalizers();assertEquals(1,first.get());assertEquals(1,second.get());intact(env);
            worker(()->{env.enqueueJvmFinalizer(a);env.enqueueJvmFinalizer(b);});
            env.runPendingFinalizers();assertEquals(1,first.get());assertEquals(1,second.get());env.popCall();
        }finally{env.doFinal();}
    }
    @Test public void callbackCanPublishFromAnotherThreadAndReenterFinalization()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var calls=new AtomicInteger();
        var child=fixture(env,active->{assertSame(active,Environment.current());calls.incrementAndGet();});
        var parent=fixture(env,active->{calls.incrementAndGet();worker(()->active.enqueueJvmFinalizer(child));active.finalizeObjects();});
        try{env.enqueueJvmFinalizer(parent);env.runPendingFinalizers();assertEquals(2,calls.get());assertEquals(0,env.getCallStackTop());env.runPendingFinalizers();assertEquals(2,calls.get());}
        finally{env.doFinal();}
    }
    @Test public void failedShutdownClearsPendingReceiversAndRejectsLateNotifications()throws Throwable {
        var scope=newScope();var previous=Environment.current();var env=new Environment(scope,new ByteArrayOutputStream());var calls=new AtomicInteger();var expected=new AssertionError("shutdown-finalizer-failure");
        var first=fixture(env,active->{calls.incrementAndGet();throw expected;});
        var second=fixture(env,active->calls.incrementAndGet());
        env.enqueueJvmFinalizer(first);env.enqueueJvmFinalizer(second);
        try{env.doFinal();fail("Shutdown failure expected");}catch(AssertionError actual){assertSame(expected,actual);}
        assertEquals(1,calls.get());assertSame(previous,Environment.current());
        var field=Environment.class.getDeclaredField("pendingJvmFinalizers");field.setAccessible(true);assertNull(field.get(env));
        worker(()->env.enqueueJvmFinalizer(second));env.runPendingFinalizers();assertNull(field.get(env));assertEquals(1,calls.get());
    }
    @Test public void disabledLogicalGcAndOrdinaryCallModuleAndHostEntriesDeliverNotifications()throws Throwable {
        var scope=newScope();scope.registerExtension(new ProbeExtension());var env=new Environment(scope,new ByteArrayOutputStream());var calls=new AtomicInteger();
        var counter=fixture(env,active->{assertSame(active,Environment.current());active.getRuntimeGlobalsTable().refOfIndex("released").assign(LongMemory.valueOf(calls.incrementAndGet()));});
        try {
            env.setGcEnabled(false);env.enqueueJvmFinalizer(counter);env.collectGarbageIfNeeded();assertEquals(1,calls.get());
            module(env,"function pending_target(){return 7;}class PendingTarget{function run(){return 7;}}",false).include(env);
            var next=fixture(env,active->calls.incrementAndGet());env.enqueueJvmFinalizer(next);
            assertEquals(7,PhpCallFrame.function(env,TraceInfo.UNKNOWN,"pending_target","pending_target",null,0,0).invoke().toLong());assertEquals(2,calls.get());
            var target=((ObjectMemory)module(env,"return new PendingTarget();",false).include(env)).value;
            var third=fixture(env,active->calls.incrementAndGet());env.enqueueJvmFinalizer(third);
            assertEquals(7,target.getReflection().findMethod("run").invokeDynamic(target,env,TraceInfo.UNKNOWN).toLong());assertEquals(3,calls.get());
            var fourth=fixture(env,active->{active.getRuntimeGlobalsTable().refOfIndex("released").assign(LongMemory.valueOf(calls.incrementAndGet()));});env.enqueueJvmFinalizer(fourth);
            assertEquals(4,module(env,"return $GLOBALS['released'];",false).include(env).toLong());assertEquals(4,calls.get());
            var fifth=fixture(env,active->{active.getRuntimeGlobalsTable().refOfIndex("released").assign(LongMemory.valueOf(calls.incrementAndGet()));});QUEUED.set(fifth);
            assertEquals(4,module(env,"queue_finalizer_probe();return $GLOBALS['released'];",false).include(env).toLong());assertEquals(5,calls.get());
            assertEquals(5,env.getRuntimeGlobalsTable().valueOfIndex("released").toLong());assertEquals(0,env.getCallStackTop());
        }finally{QUEUED.remove();env.doFinal();}
    }
    @Test(timeout=5000) public void activeOwnedSessionDefersRawCallbacksWithoutSpinning()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var calls=new AtomicInteger();env.setGcEnabled(false);
        var object=fixture(env,active->calls.incrementAndGet());var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
        var session=new PhpOwnershipExecution.Session(participant,env);
        try {
            try(var owned=session.enter()) {
                env.enqueueJvmFinalizer(object);env.runPendingFinalizers();env.finalizeObjects();env.collectGarbageIfNeeded();
                assertEquals(0,calls.get());assertFalse(object.isFinalized());
            }
            env.runPendingFinalizers();assertEquals(1,calls.get());assertTrue(object.isFinalized());
        }finally{try(var execution=participant.enter()){participant.close();}env.doFinal();}
    }
    @Test public void failedOrAbandonedConstructionDoesNotAcquireALateDestructorSourceDumpDebug()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.registerExtension(new ProbeExtension());scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var calls=new AtomicInteger();CALLS.set(calls);
            try {
                var failed=((ObjectMemory)module(env,"class BrokenCtor{function __construct(){$GLOBALS['saved']=$this;throw new \\Exception('construction-failure');}function __destruct(){runtime_finalizer_probe();}}try{new BrokenCtor();}catch(\\Throwable $e){}return $GLOBALS['saved'];",dump).include(env)).value;
                assertTrue(failed.isFinalized());failed.getClass().getDeclaredMethod("finalize").invoke(failed);env.runPendingFinalizers();
                assertFalse(PhpObjectLifecycle.destruct(failed,env,PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE));assertEquals(0,calls.get());
                try{failed.getReflection().newObject(env,TraceInfo.UNKNOWN,true);fail("Host constructor failure expected");}
                catch(Throwable failure){if(failure instanceof AssertionError)throw failure;}
                var hostFailed=((ObjectMemory)env.getRuntimeGlobalsTable().valueOfIndex("saved").toValue()).value;
                assertTrue(hostFailed.isFinalized());hostFailed.getClass().getDeclaredMethod("finalize").invoke(hostFailed);env.runPendingFinalizers();assertEquals(0,calls.get());
                module(env,"class ArgCtor{function __construct(int $v){}function __destruct(){runtime_finalizer_probe();}}class NoCtor{function __destruct(){runtime_finalizer_probe();}}",dump).include(env);
                var field=PhpCallFrame.class.getDeclaredField("constructedObject");field.setAccessible(true);
                var typed=PhpCallFrame.construct(new StringMemory("ArgCtor"),env,TraceInfo.UNKNOWN,null,0,1);
                var typedObject=((ObjectMemory)field.get(typed)).value;
                try{typed.invokePrepared(new Memory[]{new StringMemory("invalid-int")});fail("Argument type error expected");}
                catch(Throwable failure){if(failure instanceof AssertionError)throw failure;}
                assertTrue(typedObject.isFinalized());typedObject.getClass().getDeclaredMethod("finalize").invoke(typedObject);env.runPendingFinalizers();assertEquals(0,calls.get());
                var abandoned=PhpCallFrame.construct(new StringMemory("NoCtor"),env,TraceInfo.UNKNOWN,null,0,1);
                var abandonedObject=((ObjectMemory)field.get(abandoned)).value;
                abandonedObject.getClass().getDeclaredMethod("finalize").invoke(abandonedObject);env.runPendingFinalizers();
                assertFalse(PhpObjectLifecycle.destruct(abandonedObject,env,PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE));assertEquals(0,calls.get());
                var success=(ObjectMemory)PhpCallFrame.construct(new StringMemory("NoCtor"),env,TraceInfo.UNKNOWN,null,0,0).invoke();
                success.value.getClass().getDeclaredMethod("finalize").invoke(success.value);env.runPendingFinalizers();assertEquals(1,calls.get());
            }finally{env.doFinal();CALLS.remove();}
        }
    }
}

