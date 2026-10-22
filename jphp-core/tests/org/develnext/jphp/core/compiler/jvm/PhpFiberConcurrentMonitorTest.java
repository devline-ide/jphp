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

/** Additional native autoload and direct host-monitor assertions. No accepted fixtures are rewritten. */
public class PhpFiberConcurrentMonitorTest {
    private static Path ROOT;
    @BeforeClass public static void origins()throws Exception {
        ROOT = PhpPortableTestInputs.fixture("qa.concurrent", "fiber-concurrent");
        Path expected=PhpPortableTestInputs.runtimeOrigin();
        for(Class<?> type:List.of(PhpFiber.class,Environment.class,ConcurrentEnvironment.class,php.runtime.output.OutputBuffer.class))
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

    private static void nativeAutoload(String name)throws Throwable {nativeProgram("native-autoload-r1",name,true);}
    private static void nativeProgram(String group,String name,boolean restoreMask)throws Throwable {
        for(boolean concurrent:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();Environment env=concurrent?new ConcurrentEnvironment(scope(debug),out):new Environment(scope(debug),out);
            env.getDefaultBuffer().setImplicitFlush(true);var stack=env.getCallStack();var buffers=env.getOutputBuffers();int mask=env.getErrorFlags();
            try {
                String source=Files.readString(ROOT.resolve(group+"/"+name+".php"),StandardCharsets.UTF_8).replaceFirst("<\\?php\\s*","");
                compile(env,source,phb).include(env);env.flushAll();
                assertArrayEquals(name+"/"+concurrent+"/"+debug+"/"+phb,Files.readAllBytes(ROOT.resolve(group+"/"+name+".stdout")),out.toByteArray());
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());if(restoreMask)assertEquals(mask,env.getErrorFlags());assertNull(PhpFiber.current(env));assertTrue(env.getDefaultBuffer().isImplicitFlush());
            }finally{env.doFinal();}
        }
    }
    @Test(timeout=60000) public void otherClassLoadsWhileFirstAutoloaderIsParked()throws Throwable {nativeAutoload("autoload-park-main-other");}
    @Test(timeout=60000) public void sameClassDoesNotReenterAParkedAutoloader()throws Throwable {nativeAutoload("autoload-park-main-same");}
    @Test(timeout=60000) public void nestedSeparateAutoloadersDoNotRetainHostMonitors()throws Throwable {nativeAutoload("autoload-nested-fibers-separate");}
    @Test(timeout=60000) public void loaderAddedDuringCallbackIsStillVisited()throws Throwable {nativeAutoload("autoload-loader-added-during-call");}
    @Test(timeout=60000) public void generatorMaskChangesReachFiberButNotMain()throws Throwable {nativeProgram("native-state-r1","generator-mask-handoff",false);}
    @Test(timeout=60000) public void generatorSuppressionRestoresEveryCarrier()throws Throwable {nativeProgram("native-state-r1","generator-silent-handoff",false);}
    private static void monitorCase(String mode)throws Throwable {
        for(boolean concurrent:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();Environment env=concurrent?new ConcurrentEnvironment(scope(debug),out):new Environment(scope(debug),out);env.getDefaultBuffer().setImplicitFlush(true);
            var field=Environment.class.getDeclaredField("autoloadLocks");field.setAccessible(true);Object autoloadLock=field.get(env);
            var count=new java.util.concurrent.atomic.AtomicInteger();var held=new java.util.concurrent.atomic.AtomicReference<String>();
            env.getGlobals().refOfIndex("monitor_probe").assign(php.runtime.invoke.RunnableInvoker.make(env,new php.runtime.common.Callback<Memory,Memory[]>() {
                @Override public Memory call(Memory[] ignored) {
                    count.incrementAndGet();
                    if(Thread.holdsLock(env))held.compareAndSet(null,"Environment");
                    if(Thread.holdsLock(autoloadLock))held.compareAndSet(null,"autoload metadata");
                    if(Thread.holdsLock(env.scope))held.compareAndSet(null,"compile scope");
                    if(Thread.holdsLock(env.getDefaultBuffer()))held.compareAndSet(null,"root output sink");
                    return Memory.NULL;
                }
            }));
            env.getGlobals().refOfIndex("host_operation").assign(php.runtime.invoke.RunnableInvoker.make(env,new php.runtime.common.Callback<Memory,Memory[]>() {
                @Override public Memory call(Memory[] arguments) {
                    if(mode.equals("config")){env.setConfigValue("qa.fiber.config",arguments[0]);return Memory.NULL;}
                    try {
                        var context=new Context("class ImportedFiberChild extends ImportedFiberParent {}");context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                        env.importModule(context);return Memory.NULL;
                    }catch(Throwable failure){return raise(failure);}
                }
            }));
            try {
                String source=switch(mode) {
                    case "autoload" -> "spl_autoload_register(function($name){($GLOBALS['monitor_probe'])();Fiber::suspend('park');eval('class '.$name.' {}');});return new Fiber(function(){return new MonitoredFiberClass;});";
                    case "config" -> "class FiberConfigValue{public function __toString(){($GLOBALS['monitor_probe'])();Fiber::suspend('park');return '9';}}return new Fiber(function(){($GLOBALS['host_operation'])(new FiberConfigValue);return 17;});";
                    case "import" -> "spl_autoload_register(function($name){($GLOBALS['monitor_probe'])();Fiber::suspend('park');eval('class '.$name.' {}');});return new Fiber(function(){($GLOBALS['host_operation'])();return 17;});";
                    default -> "return new Fiber(function(){ob_start(function($value){if($value!==''){($GLOBALS['monitor_probe'])();Fiber::suspend('park');}return $value;},1);echo 'F';ob_end_flush();return 17;});";
                };
                PhpFiber fiber=(PhpFiber)compile(env,source,phb).include(env).toValue(php.runtime.memory.ObjectMemory.class).value;
                var stack=env.getCallStack();var buffers=env.getOutputBuffers();int mask=env.getErrorFlags();
                assertEquals("park",fiber.start(env).toString());assertEquals(1,count.get());assertNull(held.get());
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());assertEquals(mask,env.getErrorFlags());
                if(mode.equals("config"))env.setConfigValue("qa.fiber.main",Memory.TRUE);
                fiber.resume(env,Memory.NULL);assertEquals(Memory.TRUE,fiber.isTerminated(env));assertEquals(1,count.get());assertNull(held.get());
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());assertTrue(env.getDefaultBuffer().isImplicitFlush());assertNull(PhpFiber.current(env));
                if(mode.equals("chunk")){assertEquals(17,fiber.getReturn(env).toInteger());env.flushAll();assertEquals("F",out.toString(StandardCharsets.UTF_8));}
                if(mode.equals("config")){assertEquals("9",env.getConfigValue("qa.fiber.config").toString());assertEquals(17,fiber.getReturn(env).toInteger());}
                if(mode.equals("import"))assertEquals(17,fiber.getReturn(env).toInteger());
            }finally{env.doFinal();}
        }
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> Memory raise(Throwable failure)throws T {throw (T)failure;}
    @Test(timeout=60000) public void mappedConfigConversionRunsOutsideHostMonitor()throws Throwable {monitorCase("config");}
    @Test(timeout=60000) public void legacyCompilerAutoloadRunsOutsideHostAndScopeMonitors()throws Throwable {monitorCase("import");}
    @Test(timeout=60000) public void autoloadCallbackRunsOutsideEnvironmentAndMetadataMonitors()throws Throwable {monitorCase("autoload");}
    @Test(timeout=60000) public void chunkHandlerRunsOutsideEnvironmentAndLeafSinkMonitors()throws Throwable {monitorCase("chunk");}


    @org.junit.AfterClass public static void releasePortableFixtures() throws Exception {
        Path owned = ROOT;ROOT = null;PhpPortableTestInputs.release(owned);
    }
}
