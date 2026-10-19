package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PhpLegacyJvmFinalizerTest extends JvmCompilerCase {
    private static final ThreadLocal<AtomicInteger> CALLS=new ThreadLocal<>();
    public static final class ProbeExtension extends Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends FunctionsContainer {
        public static Memory old_finalizer_probe(Environment env) {
            assertSame(env,Environment.current());CALLS.get().incrementAndGet();return Memory.NULL;
        }
    }
    private String resource(String branch,PhpLanguageProfile profile,boolean debug) {
        return "resources/legacy_finalizer_"+branch+"_"+profile+"_"+debug+".phb";
    }
    private ModuleEntity fixture(Environment env,String branch,PhpLanguageProfile profile,boolean debug)throws Exception {
        try(var input=getClass().getClassLoader().getResourceAsStream(resource(branch,profile,debug))) {
            assertNotNull(input);return new ModuleDumper(new Context("return 0;"),env,true).load(input);
        }
    }
    private static byte[] adapted(byte[] data)throws Exception {
        var type=Class.forName("php.runtime.loader.PhpJvmFinalizerBytecode");var method=type.getDeclaredMethod("rewrite",byte[].class);method.setAccessible(true);
        return (byte[])method.invoke(null,(Object)data);
    }
    private static Map<String,String> methods(byte[] data) {
        var node=new ClassNode();new ClassReader(data).accept(node,0);var result=new TreeMap<String,String>();
        for(var method:node.methods)if(!method.name.equals("finalize") || !method.desc.equals("()V")) {
            var text=new Textifier();method.accept(new TraceMethodVisitor(text));var buffer=new StringWriter();text.print(new PrintWriter(buffer));result.put(method.name+method.desc,buffer.toString());
        }
        return result;
    }
    private static byte[] changedBranch(byte[] original) {
        byte[] changed=original.clone();int changes=0;
        for(int i=0;i+8<original.length;i++)if((original[i]&255)==42 && (original[i+1]&255)==Opcodes.INVOKEVIRTUAL
                && ((original[i+4]&255)==Opcodes.IFEQ || (original[i+4]&255)==Opcodes.IFNE)
                && (original[i+7]&255)==42 && (original[i+8]&255)==Opcodes.DUP) {
            changed[i+4]=(byte)((original[i+4]&255)==Opcodes.IFEQ?Opcodes.IFNE:Opcodes.IFEQ);changes++;
        }
        assertEquals(1,changes);return changed;
    }

    @Test public void physicalLegacyPhbPublishesAndPreservesPhpBodiesAcrossProfilesAndDebug()throws Throwable {
        for(String branch:new String[]{"IFEQ","IFNE"})for(var profile:PhpLanguageProfile.values())for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.registerExtension(new ProbeExtension());scope.setDebugMode(debug);scope.setPhpLanguageProfile(profile);
            var env=new Environment(scope,new ByteArrayOutputStream());var calls=new AtomicInteger();CALLS.set(calls);
            try {
                var module=fixture(env,branch,profile,debug);var originals=new IdentityHashMap<ClassEntity,byte[]>();
                for(var entity:module.getClasses())originals.put(entity,entity.getData().clone());
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var object=((ObjectMemory)module.include(env)).value;var entry=object.getClass().getDeclaredMethod("finalize");
                var worker=Executors.newSingleThreadExecutor();
                try{worker.submit(()->{try{entry.invoke(object);}catch(Exception failure){throw new AssertionError(failure);}}).get(5,TimeUnit.SECONDS);}
                finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
                assertEquals(0,calls.get());assertFalse(object.isFinalized());assertEquals(0,env.getCallStackTop());
                env.runPendingFinalizers();assertEquals(1,calls.get());assertTrue(object.isFinalized());assertEquals(0,env.getCallStackTop());
                entry.invoke(object);env.runPendingFinalizers();env.finalizeObjects();assertEquals(1,calls.get());
                for(var pair:originals.entrySet()) {
                    var entity=pair.getKey();byte[] original=pair.getValue(),definition=adapted(original);
                    assertArrayEquals(original,entity.getData());assertFalse(Arrays.equals(original,definition));assertArrayEquals(definition,adapted(definition));
                    assertEquals(methods(original),methods(definition));assertTrue(scope.getClassLoader().matchesDefinedBytecode(entity.getNativeClass(),original));
                }
            }finally{env.doFinal();CALLS.remove();}
        }
    }

    @Test public void adaptedDefinitionsStillRejectDifferentInputsAndAllowExactCachedReuse()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var module=fixture(env,"IFNE",PhpLanguageProfile.PHP_8_5,false);var loader=env.scope.getClassLoader();loader.loadModule(module,true);
            var entity=module.getClasses().iterator().next();byte[] original=entity.getData();Class<?> actual=entity.getNativeClass();byte[] different=changedBranch(original);
            assertFalse(Arrays.equals(original,different));assertArrayEquals(adapted(original),adapted(different));
            assertTrue(loader.matchesDefinedBytecode(actual,original));assertFalse(loader.matchesDefinedBytecode(actual,different));
            entity.setData(different);assertThrows(LinkageError.class,()->loader.loadClass(entity,true));entity.setData(original);assertSame(actual,loader.loadClass(entity,true));
            var repeated=fixture(env,"IFNE",PhpLanguageProfile.PHP_8_5,false);assertTrue(loader.loadModule(repeated,true));
            assertSame(module.getNativeClazz(),repeated.getNativeClazz());assertEquals("",env.getDefaultBuffer().getContents().toString());
        }finally{env.doFinal();}
    }

    @Test public void currentClassesAndUnrecognizedJavaFinalizersAreNotRewritten()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var context=new Context("class CurrentFinalizer{function __destruct(){}}return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);byte[] current=module.getClasses().iterator().next().getData();assertSame(current,adapted(current));
            var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"UnrelatedFinalizer",null,"java/lang/Object",null);
            var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"finalize","()V",null,null);method.visitCode();method.visitInsn(Opcodes.RETURN);method.visitMaxs(0,1);method.visitEnd();writer.visitEnd();
            byte[] unrelated=writer.toByteArray();assertSame(unrelated,adapted(unrelated));
            var legacy=fixture(env,"IFEQ",PhpLanguageProfile.PHP_8_5,true).getClasses().iterator().next().getData();
            var node=new ClassNode();new ClassReader(legacy).accept(node,0);
            for(var body:node.methods)if(body.name.equals("finalize"))body.instructions.insert(new InsnNode(Opcodes.NOP));
            writer=new ClassWriter(0);node.accept(writer);byte[] unknown=writer.toByteArray();assertSame(unknown,adapted(unknown));
        }finally{env.doFinal();}
    }

    @Test public void linkageValidationStillPrecedesLegacyDefinitionAndAllModuleEffects()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var module=fixture(env,"IFNE",PhpLanguageProfile.PHP_8_5,false);var entity=module.getClasses().iterator().next();
            var node=new ClassNode();new ClassReader(entity.getData()).accept(node,0);
            var method=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"stale","()V",null,null);
            method.instructions.add(new LdcInsnNode(new Handle(Opcodes.H_INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZ)Lphp/runtime/Memory;",false)));
            method.instructions.add(new InsnNode(Opcodes.POP));method.instructions.add(new InsnNode(Opcodes.RETURN));method.maxStack=1;node.methods.add(method);
            var writer=new ClassWriter(0);node.accept(writer);entity.setData(writer.toByteArray());
            assertThrows(LinkageError.class,()->env.scope.getClassLoader().loadModule(module,true));assertNull(entity.getNativeClass());assertNull(module.getNativeClazz());
            assertEquals("",env.getDefaultBuffer().getContents().toString());
        }finally{env.doFinal();}
    }
}
