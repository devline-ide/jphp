package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.ConfigChangeHandler;
import php.runtime.invoke.RunnableInvoker;
import php.runtime.lang.PhpFiber;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Source-only r6 controls. Run in supervised isolated JVMs; r5 is expected to fail/timeout. */
public class PhpFiberConcurrentTransitiveTest {
    private static Path ROOT;
    @BeforeClass public static void origins()throws Exception {
        ROOT = PhpPortableTestInputs.fixture("qa.concurrent", "fiber-concurrent");
        Path expected=PhpPortableTestInputs.runtimeOrigin();
        for(Class<?> type:List.of(PhpFiber.class,Environment.class,ConcurrentEnvironment.class,ModuleManager.class))
            assertEquals(type.getName(),expected,Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug) {
        var scope=new CompileScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    private static Environment environment(boolean concurrent,boolean debug) {
        Environment env=concurrent?new ConcurrentEnvironment(scope(debug),new ByteArrayOutputStream()):new Environment(scope(debug),new ByteArrayOutputStream());
        env.getDefaultBuffer().setImplicitFlush(true);return env;
    }
    private static Path directory(String name)throws IOException {
        Path parent=ROOT.resolve("run-transitive-r6");Files.createDirectories(parent);return Files.createTempDirectory(parent,name+"-");
    }
    private static Context context(String source,Path file) {
        var context=new Context(source,file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);return context;
    }
    private static ModuleEntity compile(Environment env,String source,Path file,boolean phb)throws Throwable {
        Context context=context(source,file);ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(phb) {
            var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,env.scope.isDebugMode());codec.save(module,bytes);
            assertTrue(bytes.size()>0);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private static Path fixture(Environment env,Path directory,String name,String source,boolean phb)throws Throwable {
        Path file=directory.resolve(name+(phb?".phb":".php"));
        if(phb) {
            Context context=context(source,file);ModuleEntity module=new JvmCompiler(env,context).compile(false);
            var bytes=new ByteArrayOutputStream();new ModuleDumper(context,env,env.scope.isDebugMode()).save(module,bytes);Files.write(file,bytes.toByteArray());
        }else Files.writeString(file,source,StandardCharsets.UTF_8);
        return file;
    }
    private static void nativeCase(String name)throws Throwable {
        Path oracle=ROOT.resolve("native-transitive-r6").resolve(name);
        for(boolean concurrent:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var out=new ByteArrayOutputStream();Environment env=concurrent?new ConcurrentEnvironment(scope(debug),out):new Environment(scope(debug),out);
            env.getDefaultBuffer().setImplicitFlush(true);Path dir=directory(name);List<Path> files;
            try(var paths=Files.list(oracle)){files=paths.filter(p->p.getFileName().toString().endsWith(".php")).sorted().toList();}
            var stack=env.getCallStack();var buffers=env.getOutputBuffers();int mask=env.getErrorFlags();
            try {
                // Original native files are immutable. Only the owned PHB transport copy changes .php references.
                for(Path source:files) {
                    String code=Files.readString(source,StandardCharsets.UTF_8);if(phb)code=code.replace(".php'",".phb'");
                    String base=source.getFileName().toString().replaceFirst("\\.php$","");fixture(env,dir,base,code,phb);
                }
                Path main=dir.resolve("main"+(phb?".phb":".php"));
                ModuleEntity module=phb?env.importCompiledModule(new Context(main.toFile()),debug):env.importModule(context(Files.readString(main,StandardCharsets.UTF_8),main));
                assertEquals(phb,module.isCompiledArtifact());env.scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                module.include(env);env.flushAll();
                assertArrayEquals(name+"/"+concurrent+"/"+debug+"/"+phb,Files.readAllBytes(oracle.resolve("main.stdout")),out.toByteArray());
                for(Path source:files)if(!source.getFileName().toString().equals("main.php")) {
                    String file=source.getFileName().toString().replaceFirst("\\.php$",phb?".phb":".php");
                    ModuleEntity included=env.getModuleManager().findModule(dir.resolve(file).toString());assertNotNull(file,included);assertEquals(phb,included.isCompiledArtifact());
                }
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());assertEquals(mask,env.getErrorFlags());
                assertNull(PhpFiber.current(env));assertTrue(env.getDefaultBuffer().isImplicitFlush());
            }finally{env.doFinal();}
        }
    }
    @Test(timeout=60000) public void includeOtherWhileBodyIsParked()throws Throwable {nativeCase("include-park-main-other");}
    @Test(timeout=60000) public void includeOnceSeesInProgressBody()throws Throwable {nativeCase("include-once-park-main-same");}
    @Test(timeout=60000) public void ordinaryIncludeMayExecuteSameParkedBodyAgain()throws Throwable {nativeCase("include-park-main-same");}
    @Test(timeout=60000) public void nestedIncludedFibersLeaveCallerFreeToInclude()throws Throwable {nativeCase("include-nested-fibers");}
    @Test(timeout=60000) public void classConstantInitializerMayAutoloadThenPark()throws Throwable {nativeCase("classinit-autoload-park-main-other");}
    @Test(timeout=60000) public void sameClassInitializerDoesNotWaitOnItsParkedDependency()throws Throwable {nativeCase("classinit-autoload-park-main-same");}
    @Test(timeout=60000) public void interfaceBindingAutoloadMayPark()throws Throwable {nativeCase("declaration-interface-autoload-park");}
    @Test(timeout=60000) public void failedIncludeRunsFinallyAndKeepsNativeOnceState()throws Throwable {nativeCase("include-throw-once-cleanup");}
    @Test(timeout=60000) public void failedInitializerReleasesReservationForRetry()throws Throwable {nativeCase("classinit-throw-retry");}
    @Test(timeout=60000) public void ordinaryGeneratorInAutoloaderInheritsAdmissionLineage()throws Throwable {nativeCase("ordinary-generator-in-autoloader");}
    @Test(timeout=60000) public void ordinaryGeneratorInIncludeMayIncludeAnotherModule()throws Throwable {nativeCase("ordinary-generator-in-included-body");}

    private static String quoted(Path path){return "'"+path.toString().replace("\\","/").replace("'","\\'")+"'";}
    private static void transitiveMonitor(boolean include)throws Throwable {
        for(boolean concurrent:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            Environment env=environment(concurrent,debug);env.getDefaultBuffer().setImplicitFlush(true);Path dir=directory("monitor");var probes=new AtomicInteger();
            env.getGlobals().refOfIndex("probe").assign(RunnableInvoker.make(env,new php.runtime.common.Callback<Memory,Memory[]>() {
                @Override public Memory call(Memory[] ignored) {
                    assertFalse("classMap retained across PHP",Thread.holdsLock(env.classMap));
                    assertFalse("moduleManager retained across PHP",Thread.holdsLock(env.getModuleManager()));
                    assertFalse("Environment retained across PHP",Thread.holdsLock(env));
                    assertFalse("CompileScope retained across PHP",Thread.holdsLock(env.scope));probes.incrementAndGet();return Memory.NULL;
                }
            }));
            try {
                PhpFiber fiber;
                if(include) {
                    Path parked=fixture(env,dir,"parked","<?php ($GLOBALS['probe'])();Fiber::suspend('park');return 7;",phb);
                    Path other=fixture(env,dir,"other","<?php ($GLOBALS['probe'])();return 8;",phb);
                    String source="<?php return new Fiber(function(){return include "+quoted(parked)+";});";
                    fiber=(PhpFiber)compile(env,source,dir.resolve("driver.php"),phb).include(env).toValue(php.runtime.memory.ObjectMemory.class).value;
                    assertEquals("park",fiber.start(env).toString());assertEquals(8,env.__include(other.toString()).toInteger());
                }else {
                    env.getGlobals().refOfIndex("fetch").assign(RunnableInvoker.make(env,new php.runtime.common.Callback<Memory,Memory[]>() {
                        @Override public Memory call(Memory[] args){assertNotNull(env.fetchClass(args[0].toString(),true,PhpLanguageProfile.PHP_8_5));return Memory.NULL;}
                    }));
                    String source="<?php spl_autoload_register(function($name){($GLOBALS['probe'])();if($name==='ParkedFetchClass')Fiber::suspend('park');eval('class '.$name.' {}');});return new Fiber(function(){($GLOBALS['fetch'])('ParkedFetchClass');return 7;});";
                    fiber=(PhpFiber)compile(env,source,dir.resolve("driver.php"),phb).include(env).toValue(php.runtime.memory.ObjectMemory.class).value;
                    assertEquals("park",fiber.start(env).toString());assertNotNull(env.fetchClass("OtherFetchClass",true,PhpLanguageProfile.PHP_8_5));
                }
                assertEquals(2,probes.get());fiber.resume(env,Memory.NULL);assertEquals(7,fiber.getReturn(env).toInteger());assertNull(PhpFiber.current(env));
            }finally{env.doFinal();}
        }
    }
    @Test(timeout=60000) public void realFetchClassAutoloaderOwnsNoTransitiveHostMonitor()throws Throwable {transitiveMonitor(false);}
    @Test(timeout=60000) public void realIncludeBodyOwnsNoTransitiveHostMonitor()throws Throwable {transitiveMonitor(true);}

    private static final class ReadProbe extends InputStream {
        final ByteArrayInputStream data;final CountDownLatch firstRead=new CountDownLatch(1),release=new CountDownLatch(1),parallelRead=new CountDownLatch(1);
        final AtomicBoolean first=new AtomicBoolean(true);final AtomicInteger bytes=new AtomicInteger();volatile Thread reader;
        ReadProbe(byte[] bytes){data=new ByteArrayInputStream(bytes);}
        private void before()throws IOException {
            if(first.compareAndSet(true,false)) {
                reader=Thread.currentThread();firstRead.countDown();
                try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("read release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            }else if(reader!=Thread.currentThread()&&release.getCount()!=0)parallelRead.countDown();
        }
        @Override public int read()throws IOException {before();int value=data.read();if(value>=0)bytes.incrementAndGet();return value;}
        @Override public int read(byte[] target,int offset,int length)throws IOException {before();int n=data.read(target,offset,length);if(n>0)bytes.addAndGet(n);return n;}
    }
    private static void moduleReservation(boolean sameContext)throws Throwable {
        for(boolean separateEnvironments:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            CompileScope scope=scope(debug);Environment first=new ConcurrentEnvironment(scope,new ByteArrayOutputStream());
            Environment second=separateEnvironments?new ConcurrentEnvironment(scope,new ByteArrayOutputStream()):first;
            first.getDefaultBuffer().setImplicitFlush(true);second.getDefaultBuffer().setImplicitFlush(true);
            Path dir=directory("module-reservation");byte[] content;
            if(phb) {
                Context source=context("<?php return 41;",dir.resolve("source.php"));ModuleEntity template=new JvmCompiler(first,source).compile(false);
                var bytes=new ByteArrayOutputStream();new ModuleDumper(source,first,debug).save(template,bytes);content=bytes.toByteArray();
            }else content="<?php return 41;".getBytes(StandardCharsets.UTF_8);
            var input=new ReadProbe(content);var otherInput=new ReadProbe(content);otherInput.release.countDown();
            String name=dir.resolve(phb?"shared.phb":"shared.php").toString();
            Context context=new Context(input,name,StandardCharsets.UTF_8);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            Context other=sameContext?context:new Context(otherInput,name,StandardCharsets.UTF_8);other.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity[] result=new ModuleEntity[2];var failure=new AtomicReference<Throwable>();var attempted=new CountDownLatch(1);Thread[] threads=new Thread[2];
            try {
                threads[0]=worker("cache-first",()->{try(var binding=first.bindCurrentThread()){result[0]=phb?first.importCompiledModule(context,debug):first.importModule(context);}},failure);
                assertTrue(input.firstRead.await(5,TimeUnit.SECONDS));
                threads[1]=worker("cache-second",()->{try(var binding=second.bindCurrentThread()){attempted.countDown();result[1]=phb?second.importCompiledModule(other,debug):second.importModule(other);}},failure);
                assertTrue(attempted.await(5,TimeUnit.SECONDS));
                boolean premature=sameContext?input.parallelRead.await(200,TimeUnit.MILLISECONDS):otherInput.firstRead.await(200,TimeUnit.MILLISECONDS);
                assertFalse("Duplicate parse/read before cache owner publication",premature);input.release.countDown();join(threads);
                assertNull(failure.get());assertNotNull(result[0]);assertSame(result[0],result[1]);assertEquals(41,result[0].include(first).toInteger());assertEquals(content.length,input.bytes.get());
                if(!sameContext)assertEquals(0,otherInput.bytes.get());
            }finally{input.release.countDown();join(threads);if(second!=first)second.doFinal();first.doFinal();}
        }
    }
    @Test(timeout=60000) public void sameNamedContextIsConsumedOnceAcrossImporters()throws Throwable {moduleReservation(true);}
    @Test(timeout=60000) public void distinctContextsWithSameCacheKeyDoNotRepeatCompilerEffects()throws Throwable {moduleReservation(false);}

    @FunctionalInterface private interface Work {void run()throws Throwable;}
    private static Thread worker(String name,Work work,AtomicReference<Throwable> failure) {
        Thread thread=new Thread(()->{try{work.run();}catch(Throwable problem){failure.compareAndSet(null,problem);}},"jphp-r6-"+name);thread.setDaemon(true);thread.start();return thread;
    }
    private static void join(Thread[] threads)throws InterruptedException {for(Thread thread:threads)if(thread!=null){thread.join(5000);assertFalse("Owned worker did not terminate: "+thread.getName(),thread.isAlive());}}
    private static void ordinarySerialization(boolean include)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var env=new ConcurrentEnvironment(scope(debug),new ByteArrayOutputStream());Path dir=directory("ordinary-serialization");
            env.getDefaultBuffer().setImplicitFlush(true);
            var firstEntry=new CountDownLatch(1);var secondEntry=new CountDownLatch(1);var attempted=new CountDownLatch(1);var release=new CountDownLatch(1);
            var failure=new AtomicReference<Throwable>();Thread[] threads=new Thread[2];
            env.getGlobals().refOfIndex("entry").assign(RunnableInvoker.make(env,new php.runtime.common.Callback<Memory,Memory[]>() {
                @Override public Memory call(Memory[] args) {
                    if(args[0].toString().equals("A")) {firstEntry.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
                    else secondEntry.countDown();return Memory.NULL;
                }
            }));
            try {
                Path a=fixture(env,dir,"a","<?php ($GLOBALS['entry'])('A');return 1;",phb),b=fixture(env,dir,"b","<?php ($GLOBALS['entry'])('B');return 2;",phb);
                if(!include)compile(env,"<?php spl_autoload_register(function($name){($GLOBALS['entry'])($name==='OrdinaryA'?'A':'B');eval('class '.$name.' {}');});",dir.resolve("driver.php"),phb).include(env);
                threads[0]=worker("serial-first",()->{try(var binding=env.bindCurrentThread()){if(include)assertEquals(1,env.__include(a.toString()).toInteger());else assertNotNull(env.fetchClass("OrdinaryA",true,PhpLanguageProfile.PHP_8_5));}},failure);
                assertTrue(firstEntry.await(5,TimeUnit.SECONDS));
                threads[1]=worker("serial-second",()->{try(var binding=env.bindCurrentThread()){attempted.countDown();if(include)assertEquals(2,env.__include(b.toString()).toInteger());else assertNotNull(env.fetchClass("OrdinaryB",true,PhpLanguageProfile.PHP_8_5));}},failure);
                assertTrue(attempted.await(5,TimeUnit.SECONDS));assertFalse("Independent host callbacks lost baseline serialization",secondEntry.await(200,TimeUnit.MILLISECONDS));
                release.countDown();join(threads);assertNull(failure.get());assertEquals(0,secondEntry.getCount());
            }finally{release.countDown();join(threads);env.doFinal();}
        }
    }
    @Test(timeout=60000) public void unrelatedConcurrentIncludesStillSerializeBodies()throws Throwable {ordinarySerialization(true);}
    @Test(timeout=60000) public void unrelatedConcurrentFetchesStillSerializeAutoloaders()throws Throwable {ordinarySerialization(false);}

    @Test(timeout=20000) public void configHandlerApplicationAndPublishedValueStayCoherent()throws Throwable {
        var env=new ConcurrentEnvironment(scope(false),new ByteArrayOutputStream());ConfigChangeHandler original=Environment.configurationHandler.get("include_path");
        env.getDefaultBuffer().setImplicitFlush(true);
        var applied=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);var bApplied=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();Thread[] threads=new Thread[2];
        Environment.configurationHandler.put("include_path",new ConfigChangeHandler() {
            @Override public void onChange(Environment target,Memory value) {
                original.onChange(target,value);
                if(value.toString().equals("qa-path-A")) {applied.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
                else bApplied.countDown();
            }
        });
        try {
            threads[0]=worker("config-A",()->{try(var binding=env.bindCurrentThread()){env.setConfigValue("include_path",php.runtime.memory.StringMemory.valueOf("qa-path-A"));}},failure);
            assertTrue(applied.await(5,TimeUnit.SECONDS));
            threads[1]=worker("config-B",()->{try(var binding=env.bindCurrentThread()){attempted.countDown();env.setConfigValue("include_path",php.runtime.memory.StringMemory.valueOf("qa-path-B"));}},failure);
            assertTrue(attempted.await(5,TimeUnit.SECONDS));assertFalse("Handler executed before previous config publication",bApplied.await(200,TimeUnit.MILLISECONDS));release.countDown();join(threads);
            assertNull(failure.get());assertEquals("qa-path-B",env.getConfigValue("include_path").toString());assertEquals(Set.of("qa-path-B"),env.getIncludePaths());
        }finally{release.countDown();join(threads);Environment.configurationHandler.put("include_path",original);env.doFinal();}
    }


    @org.junit.AfterClass public static void releasePortableFixtures() throws Exception {
        Path owned = ROOT;ROOT = null;PhpPortableTestInputs.release(owned);
    }
}
