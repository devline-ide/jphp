package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.PhpFiber;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Fail-first host boundary tests; source-only preparation, execute under root process supervision. */
public class PhpFiberConcurrentHostTest {
    private static Path ROOT;
    @BeforeClass public static void origins()throws Exception {
        ROOT = PhpPortableTestInputs.fixture("qa.concurrent", "fiber-concurrent");
        Path expected=PhpPortableTestInputs.runtimeOrigin();
        for(Class<?> type:List.of(PhpFiber.class,Environment.class,ConcurrentEnvironment.class))
            assertEquals(type.getName(),expected,Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    private static php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean phb)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,env.scope.isDebugMode());codec.save(module,bytes);assertTrue(bytes.size()>0);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static void nativeCase(String name)throws Throwable {
        for(boolean concurrent:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();Environment env=concurrent?new ConcurrentEnvironment(scope(debug),out):new Environment(scope(debug),out);
            env.getDefaultBuffer().setImplicitFlush(true); // Exact Launcher behavior; never disable concurrency/implicitFlush.
            var stack=env.getCallStack();var buffers=env.getOutputBuffers();int mask=env.getErrorFlags();
            try {
                String code=Files.readString(ROOT.resolve("native-r2/"+name+".php"),StandardCharsets.UTF_8).replaceFirst("<\\?php\\s*","");
                compile(env,code,phb).include(env);env.flushAll();
                assertArrayEquals(name+"/concurrent="+concurrent+"/debug="+debug+"/phb="+phb,Files.readAllBytes(ROOT.resolve("native-r2/"+name+".stdout")),out.toByteArray());
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());assertEquals(1,buffers.size());
                assertTrue(env.getDefaultBuffer().isImplicitFlush());assertNull(PhpFiber.current(env));
            }finally{env.doFinal();}
        }
    }
    @Test(timeout=60000) public void sharedOutputStack()throws Throwable {nativeCase("output-stack-sharing");}
    @Test(timeout=60000) public void isolatedErrorMask()throws Throwable {nativeCase("error-reporting-isolation");}
    @Test(timeout=60000) public void suppressionRestoration()throws Throwable {nativeCase("suppression-suspend-isolation");}
    @Test(timeout=60000) public void exceptionPreservesSharedBuffers()throws Throwable {nativeCase("buffer-exception-isolation");}
    @Test(timeout=60000) public void generatorUsesCallerOutputLineage()throws Throwable {nativeCase("generator-buffer-delegation");}
    @Test(timeout=60000) public void outputHandlerMaySuspendWithoutHostMonitorDeadlock()throws Throwable {nativeCase("output-handler-suspend-context");}
    @Test(timeout=60000) public void autoloadMaySuspendThenMainMayEcho()throws Throwable {nativeCase("autoload-suspend-main-echo");}
    @Test(timeout=60000) public void nestedFiberOutputAndMasks()throws Throwable {nativeCase("nested-fiber-output-mask");}

    @Test(timeout=60000) public void ordinaryConcurrentThreadsRetainIndependentStacksAndBuffers()throws Throwable {
        var out=new ByteArrayOutputStream();var env=new ConcurrentEnvironment(scope(false),out);
        env.getDefaultBuffer().setImplicitFlush(true);var mainStack=env.getCallStack();var mainBuffers=env.getOutputBuffers();
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var stacks=new java.util.concurrent.ConcurrentHashMap<Integer,CallStack>();
        var buffers=new java.util.concurrent.ConcurrentHashMap<Integer,java.util.Stack<php.runtime.output.OutputBuffer>>();
        Thread[] threads=new Thread[2];
        try {
            for(int index=0;index<threads.length;index++) {final int id=index;
                threads[index]=new Thread(()->{
                    try(var binding=env.bindCurrentThread()) {
                        stacks.put(id,env.getCallStack());buffers.put(id,env.getOutputBuffers());
                        env.pushOutputBuffer(null,0,true);entered.countDown();
                        assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                        env.echo((id==0?"A":"B").repeat(1000));env.flushAll();
                    }catch(Throwable problem){failure.compareAndSet(null,problem);}
                },"jphp-concurrent-baseline-"+id);threads[index].setDaemon(true);threads[index].start();
            }
            assertTrue("Independent ordinary threads serialized before the barrier",entered.await(5,java.util.concurrent.TimeUnit.SECONDS));release.countDown();
            for(Thread thread:threads){thread.join(5000);assertFalse(thread.isAlive());}
            assertNull(failure.get());assertNotSame(stacks.get(0),stacks.get(1));assertNotSame(mainStack,stacks.get(0));assertNotSame(mainStack,stacks.get(1));
            assertNotSame(buffers.get(0),buffers.get(1));assertNotSame(mainBuffers,buffers.get(0));assertNotSame(mainBuffers,buffers.get(1));
            assertSame(mainStack,env.getCallStack());assertSame(mainBuffers,env.getOutputBuffers());assertEquals(1,mainBuffers.size());
            String text=out.toString(StandardCharsets.UTF_8);assertEquals(2000,text.length());assertEquals(1000,text.chars().filter(c->c=='A').count());assertEquals(1000,text.chars().filter(c->c=='B').count());
            assertTrue(env.getDefaultBuffer().isImplicitFlush());
        }finally{release.countDown();for(Thread thread:threads)if(thread!=null)thread.join(5000);env.doFinal();}
    }

    private static final class UnknownEnvironment extends Environment {UnknownEnvironment(CompileScope scope,OutputStream out){super(scope,out);}}
    private static final class UnknownConcurrent extends ConcurrentEnvironment {UnknownConcurrent(CompileScope scope,OutputStream out){super(scope,out);}}
    @Test(timeout=60000) public void unknownSubclassesRejectBeforeCallbackOrStateMutation()throws Throwable {
        for(boolean concurrent:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();Environment env=concurrent?new UnknownConcurrent(scope(debug),out):new UnknownEnvironment(scope(debug),out);
            env.getDefaultBuffer().setImplicitFlush(true);
            try {
                compile(env,"$GLOBALS['called']=0;$GLOBALS['autoloaded']=0;spl_autoload_register(function($name){$GLOBALS['autoloaded']++;});",phb).include(env);
                for(String callback:new String[]{"function(){$GLOBALS['called']++;}","'UnknownFiberCallback::missing'"}) {
                var module=compile(env,"return new Fiber("+callback+");",phb);
                var stack=env.getCallStack();int top=stack.getTop();var buffers=env.getOutputBuffers();int depth=buffers.size();int mask=env.getErrorFlags();
                Throwable caught=null;try{module.include(env);}catch(Throwable failure){caught=failure;}
                assertNotNull(caught);assertTrue("Expected FiberError, got "+caught,caught instanceof php.runtime.lang.exception.PhpFiberError);
                assertEquals(0,env.getGlobals().valueOfIndex("called").toInteger());assertEquals(0,env.getGlobals().valueOfIndex("autoloaded").toInteger());
                assertNull(env.getFiberScheduler(false));assertNull(PhpFiber.current(env));
                assertSame(stack,env.getCallStack());assertEquals(top,stack.getTop());assertSame(buffers,env.getOutputBuffers());assertEquals(depth,buffers.size());
                assertEquals(mask,env.getErrorFlags());assertTrue(env.getDefaultBuffer().isImplicitFlush());assertEquals(0,out.size());
                }
            }finally{env.doFinal();}
        }
    }
    @Test(timeout=60000) public void foreignCallerCannotResumeTheOwnedActiveSegment()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var env=new ConcurrentEnvironment(scope(debug),new ByteArrayOutputStream());env.getDefaultBuffer().setImplicitFlush(true);
            try {
                var memory=compile(env,"return new Fiber(function(){Fiber::suspend('park');return 17;});",phb).include(env);
                PhpFiber fiber=(PhpFiber)memory.toValue(php.runtime.memory.ObjectMemory.class).value;
                fiber.start(env);var stack=env.getCallStack();int mask=env.getErrorFlags();var buffers=env.getOutputBuffers();
                try(var lease=env.getFiberScheduler(false).enterCaller()) {
                    var failed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                    Thread foreign=new Thread(()->{try(var binding=env.bindCurrentThread()){fiber.resume(env,Memory.NULL);}catch(Throwable failure){failed.set(failure);}},"jphp-fiber-foreign-concurrent-qa");
                    foreign.setDaemon(true);foreign.start();foreign.join(5000);assertFalse("Foreign caller blocked",foreign.isAlive());
                    assertTrue("Foreign active handoff not rejected: "+failed.get(),failed.get() instanceof IllegalStateException);
                    assertEquals(Memory.TRUE,fiber.isSuspended(env));assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());assertEquals(mask,env.getErrorFlags());
                }
                fiber.resume(env,Memory.NULL);assertEquals(Memory.TRUE,fiber.isTerminated(env));assertEquals(17,fiber.getReturn(env).toInteger());
            }finally{env.doFinal();}
        }
    }


    @org.junit.AfterClass public static void releasePortableFixtures() throws Exception {
        Path owned = ROOT;ROOT = null;PhpPortableTestInputs.release(owned);
    }
}
