package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

/** Unshipped helper epochs reject before definition, including fresh and cached loading routes. */
public class PhpRuntimeHelperLinkageTest extends JvmCompilerCase {
    private static byte[] staleBorrow(byte[] original){
        var node=new ClassNode();new ClassReader(original).accept(node,new Attribute[]{new OwnershipFrameCompiler.FrameAttribute(new byte[0]),new OwnershipFrameCompiler.RuntimeFrameAttribute(new byte[0])},0);int changed=0;
        for(var method:node.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("nativeBorrowSend")){
            assertEquals("(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZZ)Lphp/runtime/Memory;",call.desc);
            assertTrue(call.getPrevious().getOpcode()==Opcodes.ICONST_0||call.getPrevious().getOpcode()==Opcodes.ICONST_1);method.instructions.remove(call.getPrevious());
            call.desc="(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZ)Lphp/runtime/Memory;";changed++;
        }
        assertTrue("Actual ABI11 five-argument predecessor witness",changed>0);var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
    }
    private static Context context(String source){var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);return context;}
    @Test public void staleFiveArgumentNativeHelperRejectsFreshAndLoadedModuleBeforeEffects()throws Throwable {
        for(boolean debug:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var module=new JvmCompiler(env,context("$n=$_GET;echo 'effect';return count([$n]);")).compile(false);assertEquals(ModuleDumper.COMPILER_ABI_VERSION,module.getCompilerProvenance().getCompilerAbiVersion());
                byte[] original=module.getData();module.setData(staleBorrow(original));
                var failure=assertThrows(LinkageError.class,()->scope.getClassLoader().loadModule(module,true));assertTrue(failure.getCause().getMessage(),failure.getCause().getMessage().contains("nativeBorrowSend"));
                assertNull(module.getNativeMethod());assertNull(module.getNativeClazz());assertEquals("",env.getDefaultBuffer().getContents().toString());
                module.setData(original);assertTrue(scope.getClassLoader().loadModule(module,true));
                module.setData(staleBorrow(original));assertThrows(LinkageError.class,()->scope.getClassLoader().loadModule(module,true));assertEquals("",env.getDefaultBuffer().getContents().toString());
                module.setData(original);assertFalse(scope.getClassLoader().loadModule(module,true));assertEquals(1,module.include(env).toLong());assertEquals("effect",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    @Test public void childFunctionClassAndClosureHelpersArePreflightedBeforeAnyModuleDefinition()throws Throwable {
        String[] sources={"function f($n){return count([$n]);}echo 'effect';return 0;","class C{function f($n){return count([$n]);}}echo 'effect';return 0;","$f=function($n){return count([$n]);};echo 'effect';return 0;"};
        for(String source:sources){var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var module=new JvmCompiler(env,context(source)).compile(false);byte[] original;
                if(!module.getFunctions().isEmpty()){var entity=module.getFunctions().iterator().next();original=entity.getData();entity.setData(staleBorrow(original));}
                else if(!module.getClasses().isEmpty()){var entity=module.getClasses().iterator().next();original=entity.getData();entity.setData(staleBorrow(original));}
                else{var entity=module.getClosures().iterator().next();original=entity.getData();entity.setData(staleBorrow(original));}
                assertThrows(LinkageError.class,()->scope.getClassLoader().loadModule(module,true));assertNull(module.getNativeClazz());assertNull(module.getNativeMethod());assertEquals("",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    private static final class ExposedLoader extends php.runtime.loader.RuntimeClassLoader {
        ExposedLoader(ClassLoader parent){super(parent);}
        Class<?> function(php.runtime.reflection.FunctionEntity entity)throws Exception{return loadFunction(entity,true);}
        Class<?> closure(php.runtime.reflection.helper.ClosureEntity entity)throws Exception{return loadClosure(entity,true);}
    }
    @Test public void directFunctionClassAndClosureDefinitionsValidateCachedReuse()throws Throwable {
        for(int route=0;route<3;route++){
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var env=new Environment(scope,new ByteArrayOutputStream());
            try(var loader=new ExposedLoader(scope.getClassLoader())){
                String source=route==0?"function f($n){return count([$n]);}return 0;":route==1?"class C{function f($n){return count([$n]);}}return 0;":"$f=function($n){return count([$n]);};return 0;";
                var module=new JvmCompiler(env,context(source)).compile(false);
                if(route==0){
                    var entity=module.getFunctions().iterator().next();byte[] original=entity.getData();entity.setData(staleBorrow(original));assertThrows(LinkageError.class,()->loader.function(entity));assertNull(entity.getNativeClazz());
                    entity.setData(original);Class<?> defined=loader.function(entity);entity.setData(staleBorrow(original));assertThrows(LinkageError.class,()->loader.function(entity));entity.setData(original);assertSame(defined,loader.function(entity));
                }else if(route==1){
                    var entity=module.getClasses().iterator().next();byte[] original=entity.getData();entity.setData(staleBorrow(original));assertThrows(LinkageError.class,()->loader.loadClass(entity,true));assertNull(entity.getNativeClass());
                    entity.setData(original);Class<?> defined=loader.loadClass(entity,true);entity.setData(staleBorrow(original));assertThrows(LinkageError.class,()->loader.loadClass(entity,true));entity.setData(original);assertSame(defined,loader.loadClass(entity,true));
                }else{
                    var entity=module.getClosures().iterator().next();byte[] original=entity.getData();entity.setData(staleBorrow(original));assertThrows(LinkageError.class,()->loader.closure(entity));assertNull(entity.getNativeClass());
                    entity.setData(original);Class<?> defined=loader.closure(entity);entity.setData(staleBorrow(original));assertThrows(LinkageError.class,()->loader.closure(entity));entity.setData(original);assertSame(defined,loader.closure(entity));
                }
                assertEquals("",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    @Test public void nestedHelperOpcodePrivacyAndUnknownOwnersRejectBeforeDefinition()throws Throwable {
        String key="devline.qa.helper-linkage-init";assertNull(System.getProperty(key));
        for(int route=0;route<3;route++)try(var loader=new ExposedLoader(getClass().getClassLoader())){
            String name="HelperLinkageNegative"+route;var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
            var init=writer.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);init.visitCode();init.visitLdcInsn(key);init.visitLdcInsn("effect");init.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false);init.visitInsn(Opcodes.POP);init.visitInsn(Opcodes.RETURN);init.visitMaxs(0,0);init.visitEnd();
            var body=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"run","()V",null,null);body.visitCode();body.visitInsn(Opcodes.ACONST_NULL);
            if(route==0)body.visitMethodInsn(Opcodes.INVOKEINTERFACE,"php/runtime/PhpRuntimeOwnership$Frame","close","()V",true);
            else if(route==1){body.visitInsn(Opcodes.ICONST_0);body.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","producerEval","(I)Lphp/runtime/PhpRuntimeOwnership$Holder;",false);body.visitInsn(Opcodes.POP);}
            else body.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$UnknownHelper","close","()V",false);
            body.visitInsn(Opcodes.RETURN);body.visitMaxs(0,0);body.visitEnd();writer.visitEnd();
            var entity=new php.runtime.reflection.ClassEntity(new Context(""));entity.setName(name);entity.setInternalName(name);entity.setData(writer.toByteArray());
            assertThrows(LinkageError.class,()->loader.loadClass(entity,true));assertNull(entity.getNativeClass());assertNull(System.getProperty(key));
        }
    }
    @Test public void staleHelperHandlesInLdcAndBootstrapRejectBeforeDefinition()throws Throwable {
        for(boolean bootstrap:new boolean[]{false,true})try(var loader=new ExposedLoader(getClass().getClassLoader())){
            String name="StaleHelperHandle"+bootstrap;var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
            var body=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"run","()V",null,null);body.visitCode();
            var stale=new Handle(Opcodes.H_INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZ)Lphp/runtime/Memory;",false);
            if(bootstrap)body.visitInvokeDynamicInsn("invalid","()Ljava/lang/Object;",stale);else body.visitLdcInsn(stale);
            body.visitInsn(Opcodes.POP);body.visitInsn(Opcodes.RETURN);body.visitMaxs(0,0);body.visitEnd();writer.visitEnd();
            var entity=new php.runtime.reflection.ClassEntity(new Context(""));entity.setName(name);entity.setInternalName(name);entity.setData(writer.toByteArray());
            var failure=assertThrows(LinkageError.class,()->loader.loadClass(entity,true));assertTrue(failure.getCause().getMessage(),failure.getCause().getMessage().contains("nativeBorrowSend"));assertNull(entity.getNativeClass());
        }
    }
    @Test public void officialPhysicalAbiTenLoadsAlongsideZeroThroughNineProvenanceControls()throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("resources/compiler_ownership_schema1_abi10.phb")){
            assertNotNull(input);var module=new ModuleDumper(new Context("return 0;"),env,true).load(input);assertEquals(10,module.getCompilerProvenance().getCompilerAbiVersion());
            env.scope.loadModule(module);assertNotNull(module.getNativeClazz());assertNotNull(module.getNativeMethod());assertNotNull(module.include(env));
        }finally{env.doFinal();}
    }
}
