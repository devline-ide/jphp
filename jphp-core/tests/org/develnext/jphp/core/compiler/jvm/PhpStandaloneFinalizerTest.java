package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.loader.StandaloneLoader;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import static org.junit.Assert.*;

public class PhpStandaloneFinalizerTest extends JvmCompilerCase {
    private static final ThreadLocal<AtomicInteger> DTORS=new ThreadLocal<>();
    public static final class ProbeExtension extends Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends FunctionsContainer {
        public static Memory standalone_dtor_probe(Environment env) {
            assertSame(env,Environment.current());DTORS.get().incrementAndGet();return Memory.NULL;
        }
        public static Memory standalone_native_probe(Environment env) {
            assertSame(env,Environment.current());return php.runtime.memory.LongMemory.valueOf(7);
        }
    }
    private static final class Loader extends StandaloneLoader {
        Loader(ClassLoader parent){super(parent);getScope().registerExtension(new ProbeExtension());}
        void define(ModuleEntity module){loadModule(module);}
    }
    private static final class Resources extends ClassLoader {
        final Map<String,byte[]> entries;int opened,closed;final Set<String> generated;
        Resources(ClassLoader parent,Map<String,byte[]> entries){super(parent);this.entries=entries;generated=new HashSet<>();for(String name:entries.keySet())if(name.endsWith(".class"))generated.add(name.substring(0,name.length()-6).replace('/','.'));}
        @Override public InputStream getResourceAsStream(String name) {
            byte[] bytes=entries.get(name);if(bytes==null)return super.getResourceAsStream(name);opened++;
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {public void close()throws IOException{closed++;super.close();}};
        }
        @Override public Class<?> loadClass(String name)throws ClassNotFoundException {
            if(generated.contains(name))throw new AssertionError("Generated PHP class escaped to the resource parent: "+name);
            return super.loadClass(name);
        }
    }
    private Path fixture(String branch,PhpLanguageProfile profile,boolean debug)throws Exception {
        var url=getClass().getClassLoader().getResource("resources/standalone_finalizer_"+branch+"_"+profile+"_"+debug+".jar");assertNotNull(url);return Path.of(url.toURI());
    }
    private static Map<String,byte[]> entries(Path archive)throws Exception {
        var result=new HashMap<String,byte[]>();try(var jar=new JarFile(archive.toFile())){var entries=jar.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(!entry.isDirectory())try(var input=jar.getInputStream(entry)){result.put(entry.getName(),input.readAllBytes());}}}return result;
    }
    private static void worker(php.runtime.lang.IObject object)throws Exception {
        var entry=object.getClass().getDeclaredMethod("finalize");var executor=Executors.newSingleThreadExecutor();
        try{executor.submit(()->{try{entry.invoke(object);}catch(Exception failure){throw new AssertionError(failure);}}).get(5,TimeUnit.SECONDS);}
        finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private void checkPackage(ClassLoader resources,PhpLanguageProfile profile,boolean debug,Map<String,Class<?>> preloaded)throws Throwable {
        var loader=new Loader(resources);var env=loader.getScopeEnvironment();var calls=new AtomicInteger();DTORS.set(calls);
        loader.getScope().setDebugMode(debug);loader.getScope().setPhpLanguageProfile(profile);
        try {
            loader.loadExtensions();loader.loadLibrary();var module=loader.fetchModule("JPHP-INF/.bootstrap.php");assertNotNull(module);
            var result=module.include(env);for(int i=1;i<6;i++)assertEquals(new long[]{7,3,9,42,17}[i-1],result.valueOfIndex(i).toLong());
            var object=((ObjectMemory)result.valueOfIndex(0).toValue()).value;
            env.pushCall(TraceInfo.UNKNOWN,null,new Memory[0],"standalone-host-frame",null,null);worker(object);
            assertEquals(0,calls.get());assertFalse(object.isFinalized());assertEquals(1,env.getCallStackTop());
            env.runPendingFinalizers();assertEquals(1,calls.get());assertTrue(object.isFinalized());assertEquals(1,env.getCallStackTop());
            worker(object);env.runPendingFinalizers();env.finalizeObjects();assertEquals(1,calls.get());env.popCall();
            assertSame(loader.getScope().getClassLoader(),object.getClass().getClassLoader());
            assertSame(object.getClass().getClassLoader(),object.getClass().getSuperclass().getClassLoader());
            assertTrue(loader.getScope().getClassLoader().matchesDefinedBytecode(object.getClass(),object.getReflection().getData()));
            for(var item:preloaded.entrySet())if(item.getKey().equals(object.getClass().getName()))assertNotSame(item.getValue(),object.getClass());
            assertSame(Memory.class,resources.loadClass(Memory.class.getName()));assertSame(ProbeFunctions.class,resources.loadClass(ProbeFunctions.class.getName()));
            if(resources instanceof Resources tracked){assertTrue(tracked.opened>0);assertEquals(tracked.opened,tracked.closed);}
        }finally{try{env.doFinal();}finally{DTORS.remove();loader.getScope().getClassLoader().close();}}
    }

    @Test public void physicalOldStandaloneArchivesPreserveSymbolsExtensionsAndFinalizerDelivery()throws Throwable {
        for(String branch:new String[]{"IFEQ","IFNE"})for(boolean debug:new boolean[]{false,true}) {
            var profile=branch.equals("IFEQ")?PhpLanguageProfile.JPHP_LEGACY:PhpLanguageProfile.PHP_8_5;
            checkPackage(new Resources(getClass().getClassLoader(),entries(fixture(branch,profile,debug))),profile,debug,Map.of());
        }
    }
    @Test public void parentPreloadedGeneratedClassesDoNotOverrideScopedAdaptedDefinitions()throws Throwable {
        var path=fixture("IFNE",PhpLanguageProfile.PHP_8_5,true);var entries=entries(path);
        try(var parent=new URLClassLoader(new URL[]{path.toUri().toURL()},getClass().getClassLoader())) {
            var preloaded=new HashMap<String,Class<?>>();for(String name:entries.keySet())if(name.endsWith(".class")){
                String binary=name.substring(0,name.length()-6).replace('/','.');preloaded.put(binary,parent.loadClass(binary));
            }
            checkPackage(parent,PhpLanguageProfile.PHP_8_5,true,preloaded);
        }
    }
    private static byte[] stale(byte[] original) {
        var node=new ClassNode();new ClassReader(original).accept(node,0);int changes=0;
        for(var method:node.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call
                && call.owner.equals("php/runtime/PhpRuntimeOwnership") && call.name.equals("nativeBorrowSend")) {
            assertEquals("(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZZ)Lphp/runtime/Memory;",call.desc);
            method.instructions.remove(call.getPrevious());call.desc="(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZ)Lphp/runtime/Memory;";changes++;
        }
        assertTrue(changes>0);var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }
    @Test public void resourcePreflightClosesStreamsAndRejectsMissingOrStaleMembersBeforeDefinition()throws Throwable {
        var compiler=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var context=new Context("function standalone_gate($n){return count([$n]);}echo 'effect';return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var original=new JvmCompiler(compiler,context).compile(false);var function=original.getFunctions().iterator().next();
            var dumper=new ModuleDumper(context,compiler,true);dumper.setIncludeData(false);var header=new ByteArrayOutputStream();dumper.save(original,header);
            for(boolean missing:new boolean[]{false,true}) {
                var data=new HashMap<String,byte[]>();data.put(original.getInternalName()+".class",original.getData());
                if(!missing)data.put(function.getInternalName()+".class",stale(function.getData()));
                var resources=new Resources(getClass().getClassLoader(),data);var loader=new Loader(resources);var env=loader.getScopeEnvironment();
                try {
                    var module=new ModuleDumper(context,env,true).load(new ByteArrayInputStream(header.toByteArray()));
                    byte[] before=module.getData();
                    if(missing){var failure=assertThrows(RuntimeException.class,()->loader.define(module));assertTrue(failure.getCause() instanceof IOException);assertSame(before,module.getData());}
                    else assertThrows(LinkageError.class,()->loader.define(module));
                    assertNull(module.getNativeClazz());for(var member:module.getFunctions())assertNull(member.getNativeClazz());
                    assertEquals(resources.opened,resources.closed);assertEquals("",env.getDefaultBuffer().getContents().toString());
                }finally{env.doFinal();loader.getScope().getClassLoader().close();}
            }
        }finally{compiler.doFinal();}
    }
    @Test public void embeddedModuleBytesUseTheSameScopedDefinitionWithoutClassResources()throws Throwable {
        var compiler=new Environment(newScope(),new ByteArrayOutputStream());var resources=new Resources(getClass().getClassLoader(),new HashMap<>());var loader=new Loader(resources);var env=loader.getScopeEnvironment();
        try {
            var context=new Context("class EmbeddedStandalone{public $value=9;}return new EmbeddedStandalone();");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(compiler,context).compile(false);loader.define(module);loader.getScope().addUserModule(module);env.registerModule(module);
            var object=((ObjectMemory)module.include(env)).value;assertEquals(9,object.getProperties().valueOfIndex("value").toLong());assertEquals(0,resources.opened);
            assertSame(loader.getScope().getClassLoader(),object.getClass().getClassLoader());
        }finally{env.doFinal();loader.getScope().getClassLoader().close();compiler.doFinal();}
    }
    @Test public void lazyScopedConstantsRetainCaseAndDoNotAutoloadBuiltins()throws Throwable {
        var resources=new Resources(getClass().getClassLoader(),entries(fixture("IFNE",PhpLanguageProfile.PHP_8_5,false)));var loader=new Loader(resources);var env=loader.getScopeEnvironment();var fetches=new AtomicInteger();
        try {
            loader.loadExtensions();loader.loadLibrary();loader.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            loader.getScope().addConstantEntityFetchHandler(new php.runtime.env.handler.EntityFetchHandler(){public void fetch(CompileScope scope,String origin,String lower){fetches.incrementAndGet();}});
            assertNotNull(env.findConstant("PHP_VERSION"));assertEquals(0,fetches.get());
            assertEquals(17,env.findConstant("STANDALONE_VALUE").toLong());assertEquals(1,fetches.get());
            assertNull(env.findConstant("standalone_value"));assertEquals(17,env.findConstant("STANDALONE_VALUE").toLong());assertEquals(1,fetches.get());
            assertEquals(resources.opened,resources.closed);
        }finally{env.doFinal();loader.getScope().getClassLoader().close();}
    }
    @Test public void libraryDumpStreamClosesAfterSuccessfulAndFailedDecode()throws Throwable {
        byte[] library=entries(fixture("IFNE",PhpLanguageProfile.PHP_8_5,false)).get("JPHP-INF/library.dump");
        for(boolean broken:new boolean[]{false,true}) {
            var resources=new Resources(getClass().getClassLoader(),Map.of("JPHP-INF/library.dump",broken?new byte[]{0}:library));var loader=new Loader(resources);
            try{if(broken)assertThrows(IOException.class,loader::loadLibrary);else loader.loadLibrary();assertEquals(1,resources.opened);assertEquals(1,resources.closed);}
            finally{loader.getScopeEnvironment().doFinal();loader.getScope().getClassLoader().close();}
        }
    }
}
