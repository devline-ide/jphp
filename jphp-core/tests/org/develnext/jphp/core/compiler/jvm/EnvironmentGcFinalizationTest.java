package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.BaseObject;
import php.runtime.lang.IObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;

import java.io.*;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.Assert.*;

/** GC registry publication, destructor allocation, reentry and failure recovery in ordinary execution. */
public class EnvironmentGcFinalizationTest extends JvmCompilerCase {
    @Test public void destructorAllocationAtShutdownSourceDumpAndDebug() throws Throwable {
        String source="class A{function __destruct(){$GLOBALS['children'][]=new B();echo 'A;';}}"
                +"class B{function __destruct(){$GLOBALS['leaves'][]=new C();echo 'B;';}}"
                +"class C{function __destruct(){echo 'C;';}}$objects=[];$children=[];$leaves=[];"
                +"for($i=0;$i<8;$i++){$objects[]=new A();}";
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.doFinal();
                assertEquals("debug="+debug+" dump="+dump,"A;".repeat(8)+"B;".repeat(8)+"C;".repeat(8),output.toString(StandardCharsets.UTF_8));
                env.doFinal();assertEquals(48,output.size());
            }finally{env.doFinal();}
        }
    }

    @FunctionalInterface private interface Callback {void run(IObject object,Environment env) throws Throwable;}
    private ClassEntity type(Callback callback) {
        var context=new Context("");var type=new ClassEntity(context);type.setName("GcHostFixture");
        var method=new MethodEntity(context) {
            @Override public Memory invokeDynamic(IObject object,Environment env,TraceInfo trace,Memory... args) throws Throwable {
                callback.run(object,env);return Memory.NULL;
            }
        };
        method.setName("__destruct");method.setClazz(type);type.methodDestruct=method;return type;
    }
    private IObject object(Environment env,ClassEntity type) {return new BaseObject(env,type) {};}
    private int registered(Environment env) throws Exception {
        var field=Environment.class.getDeclaredField("gcObjects");field.setAccessible(true);return ((Collection<?>)field.get(env)).size();
    }

    @Test public void concurrentRegistrationsRemainAvailableAtFinalization() throws Throwable {
        var env=new ConcurrentEnvironment(newScope(),new ByteArrayOutputStream());var count=new AtomicInteger();
        var type=type((object,active)->count.incrementAndGet());int workers=8,perWorker=2000;var objects=new IObject[workers*perWorker];
        var executor=Executors.newFixedThreadPool(workers);var start=new CountDownLatch(1);
        try {
            var futures=new Future<?>[workers];
            for(int worker=0;worker<workers;worker++) {
                final int base=worker*perWorker;
                futures[worker]=executor.submit(()->{
                    try{assertTrue(start.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new AssertionError(error);}
                    for(int index=0;index<perWorker;index++){var object=object(env,type);objects[base+index]=object;env.registerObjectInGC(object);}
                });
            }
            start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
            assertEquals(objects.length,registered(env));env.finalizeObjects();assertEquals(objects.length,count.get());
            assertEquals(0,registered(env));Reference.reachabilityFence(objects);
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));env.doFinal();}
    }

    @Test public void callbacksPublishFromAnotherThreadAndCanReenter() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var started=new AtomicBoolean();var count=new AtomicInteger();
        var added=new AtomicReference<IObject>();var type=new ClassEntity[1];
        type[0]=type((object,active)->{
            count.incrementAndGet();
            if(started.compareAndSet(false,true)) {
                var child=object(active,type[0]);added.set(child);var failure=new AtomicReference<Throwable>();
                var thread=new Thread(()->{try{active.registerObjectInGC(child);}catch(Throwable error){failure.set(error);}},"gc-publication-qa");
                thread.setDaemon(true);thread.start();thread.join(3000);assertFalse("Registry monitor held during callback",thread.isAlive());
                if(failure.get()!=null)throw failure.get();active.finalizeObjects();
            }
        });
        var first=object(env,type[0]);var second=object(env,type[0]);env.registerObjectInGC(first);env.registerObjectInGC(second);
        try {
            env.finalizeObjects();assertEquals(3,count.get());assertTrue(first.isFinalized());assertTrue(second.isFinalized());assertTrue(added.get().isFinalized());
            env.finalizeObjects();assertEquals(3,count.get());assertEquals(0,env.getCallStack().getTop());
        }finally{env.doFinal();}
    }

    @Test public void failedDestructorPreservesOtherPendingObjectsAndOriginalThrowable() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var failOnce=new AtomicBoolean();var count=new AtomicInteger();
        var expected=new AssertionError("destructor-failure");var type=type((object,active)->{count.incrementAndGet();if(failOnce.compareAndSet(false,true))throw expected;});
        var objects=new IObject[5];for(int i=0;i<objects.length;i++){objects[i]=object(env,type);env.registerObjectInGC(objects[i]);}
        try {
            try{env.finalizeObjects();fail("Destructor must propagate its failure");}catch(AssertionError actual){assertSame(expected,actual);}
            assertEquals(4,registered(env));assertEquals(0,env.getCallStack().getTop());env.finalizeObjects();assertEquals(5,count.get());
            for(var object:objects)assertTrue(object.isFinalized());assertEquals(0,registered(env));Reference.reachabilityFence(objects);
        }finally{env.doFinal();}
    }

    @Test public void queuedReferencesKeepIndicesValidAcrossSwapRemovalAndReentry() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());env.setGcEnabled(false);
        var count=new AtomicInteger();var publish=new AtomicBoolean();var references=new Reference<?>[4];
        var child=new AtomicReference<IObject>();var type=new ClassEntity[1];
        type[0]=type((object,active)->{
            count.incrementAndGet();
            if(publish.compareAndSet(false,true)) {
                references[1].clear();assertTrue(references[1].enqueue());
                var added=object(active,type[0]);child.set(added);active.registerObjectInGC(added);active.finalizeObjects();
            }
        });
        var objects=new IObject[4];for(int i=0;i<4;i++){objects[i]=object(env,type[0]);env.registerObjectInGC(objects[i]);}
        var field=Environment.class.getDeclaredField("gcObjects");field.setAccessible(true);
        for(Object entry:(Collection<?>)field.get(env))for(int i=0;i<4;i++) {
            var reference=(Reference<?>)entry;if(reference.get()==objects[i])references[i]=reference;
        }
        for(var reference:references)assertNotNull(reference);
        try {
            // Deterministic queue injection exercises registry metadata, not JVM reachability.
            references[2].clear();assertTrue(references[2].enqueue());
            var extra=object(env,type[0]);env.registerObjectInGC(extra);assertEquals(4,registered(env));
            env.finalizeObjects();assertEquals(4,count.get());assertEquals(0,registered(env));
            assertTrue(objects[0].isFinalized());assertTrue(objects[3].isFinalized());assertTrue(extra.isFinalized());assertTrue(child.get().isFinalized());
            references[0].clear();assertTrue(references[0].enqueue());
            var last=object(env,type[0]);env.registerObjectInGC(last);assertEquals(1,registered(env));
            assertEquals(0,env.collectGarbage());assertEquals(1,registered(env));
            env.finalizeObjects();assertEquals(5,count.get());assertTrue(last.isFinalized());
            Reference.reachabilityFence(objects);Reference.reachabilityFence(extra);Reference.reachabilityFence(last);
        }finally{env.doFinal();}
    }
    @Test public void unpublishedQueueEntryDoesNotRemoveAnExistingRegistration() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());var count=new AtomicInteger();
        var type=type((object,active)->count.incrementAndGet());var registered=object(env,type);env.registerObjectInGC(registered);
        try {
            var queueField=Environment.class.getDeclaredField("gcObjectRefQueue");queueField.setAccessible(true);
            Class<?> referenceType=null;
            for(var nested:Environment.class.getDeclaredClasses())if(nested.getSimpleName().equals("GcObjectReference"))referenceType=nested;
            assertNotNull(referenceType);
            var constructor=referenceType.getDeclaredConstructor(IObject.class,java.lang.ref.ReferenceQueue.class);constructor.setAccessible(true);
            // Represents a constructed weak entry whose registry insertion did not complete.
            var unpublished=(Reference<?>)constructor.newInstance(object(env,type),queueField.get(env));unpublished.clear();assertTrue(unpublished.enqueue());
            assertEquals(0,env.collectGarbage());assertEquals(1,registered(env));env.finalizeObjects();assertEquals(1,count.get());
            assertTrue(registered.isFinalized());Reference.reachabilityFence(registered);
        }finally{env.doFinal();}
    }
}
