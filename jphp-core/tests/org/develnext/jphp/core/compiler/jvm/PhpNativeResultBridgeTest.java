package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.annotation.PhpNativeResultBridge;
import php.runtime.annotation.Reflection;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Actual CodeSource proof, physical mutations and passive native forwarding; no invented owned capabilities. */
public class PhpNativeResultBridgeTest extends JvmCompilerCase {
    public static final class ThirdExtension extends Extension {
        public Status getStatus() {return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope) {registerFunctions(new ThirdFunctions());}
    }
    public static final class ThirdFunctions extends FunctionsContainer {
        @PhpNativeResultBridge(discarded="third_ignored")
        public static Memory third_dispatch(Environment env,TraceInfo trace,Memory callable,Memory argument)throws Throwable {
            return forward(env,trace,callable,argument,false);
        }
        @Reflection.Ignore
        public static Memory third_ignored(Environment env,TraceInfo trace,Memory callable,Memory argument)throws Throwable {
            return forward(env,trace,callable,argument,true);
        }
        public static Memory third_unmarked(Environment env,TraceInfo trace,Memory callable,Memory argument)throws Throwable {
            return forward(env,trace,callable,argument,false);
        }
        private static Memory forward(Environment env,TraceInfo trace,Memory callable,Memory argument,boolean discarded)throws Throwable {
            Invoker target=Invoker.valueOf(env,null,callable);
            if(target==null)throw new IllegalArgumentException("Fixture callable is invalid");
            target.setTrace(trace);
            try(var frame=PhpCallFrame.resolvedInvoker(env,trace,target,1)) {
                Memory[] passed={argument};
                return discarded?frame.invokePreparedDiscarded(passed):frame.invokePrepared(passed);
            }
        }
        private static Memory alternateForward(Environment env,TraceInfo trace,Memory callable,Memory argument,boolean discarded)throws Throwable {
            return LongMemory.valueOf(99);
        }
    }
    public static final class ExposedBridge {
        @PhpNativeResultBridge(discarded="ignored")
        public static Memory used(Memory value) {return body(value,false);}
        public static Memory ignored(Memory value) {return body(value,true);}
        private static Memory body(Memory value,boolean ignored) {return value;}
    }
    public static final class MissingBridge {
        @PhpNativeResultBridge(discarded="absent")
        public static Memory used(Memory value) {return value;}
    }

    private static Method third(String name)throws Exception {
        return ThirdFunctions.class.getDeclaredMethod(name,Environment.class,TraceInfo.class,Memory.class,Memory.class);
    }
    private static String descriptor(Method member) {return Type.getMethodDescriptor(member);}
    private static PhpNativeResultUse.Proof require(Method semantic,Method physical,boolean discarded) {
        return PhpNativeResultUse.require(semantic,Type.getInternalName(physical.getDeclaringClass()),
                physical.getName(),descriptor(physical),discarded);
    }
    private static byte[] physicalBytes(Class<?> owner)throws Exception {
        var code=owner.getProtectionDomain().getCodeSource();
        assertNotNull("Fixture must have a real physical CodeSource",code);
        assertEquals("file",code.getLocation().getProtocol());
        Path origin=Path.of(code.getLocation().toURI());
        String entry=owner.getName().replace('.','/')+".class";
        if(Files.isDirectory(origin))return Files.readAllBytes(origin.resolve(entry));
        try(var jar=new java.util.jar.JarFile(origin.toFile())) {
            var member=jar.getJarEntry(entry);assertNotNull(member);
            try(var input=jar.getInputStream(member)){return input.readAllBytes();}
        }
    }
    private static MethodNode method(ClassNode owner,String name,String desc) {
        return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();
    }
    private static List<AbstractInsnNode> ops(MethodNode member) {
        var result=new ArrayList<AbstractInsnNode>();
        for(var op:member.instructions)if(op.getOpcode()>=0)result.add(op);
        return result;
    }
    private static byte[] mutated(byte[] original,Consumer<ClassNode> mutation) {
        var owner=new ClassNode();new ClassReader(original).accept(owner,0);mutation.accept(owner);
        var writer=new ClassWriter(0);owner.accept(writer);return writer.toByteArray();
    }
    private static void rejected(byte[] original,Consumer<ClassNode> mutation)throws Exception {
        Method canonical=third("third_dispatch"),bridge=third("third_ignored");
        byte[] changed=mutated(original,mutation);
        assertThrows(IOException.class,()->PhpNativeResultUse.verify(changed,canonical,bridge));
        assertEquals("Mutation bytes do not enter the cold cache",bridge,PhpNativeResultUse.select(canonical,true));
        assertEquals("Actual CodeSource bytes remain authoritative",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),
                require(canonical,bridge,true).classSHA256());
    }

    @Test public void thirdFamilySelectAndRequireUseActualCodeSourcePairAndReusableProof()throws Throwable {
        Method canonical=third("third_dispatch"),bridge=third("third_ignored");
        byte[] bytes=physicalBytes(ThirdFunctions.class);
        PhpNativeResultUse.verify(bytes,canonical,bridge);
        assertEquals(canonical,PhpNativeResultUse.select(canonical,false));
        assertEquals(bridge,PhpNativeResultUse.select(canonical,true));
        var used=require(canonical,canonical,false);var ignored=require(canonical,bridge,true);
        assertFalse(used.discarded());assertTrue(ignored.discarded());
        assertEquals(canonical,ignored.semantic());assertEquals(bridge,ignored.physical());
        assertSame("Cold proof is reused",ignored,require(canonical,bridge,true));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(hash,used.classSHA256());assertEquals(hash,ignored.classSHA256());
    }

    @Test public void unmarkedMemberAndInvalidMetadataCannotAcquireDiscardedBridge()throws Throwable {
        Method canonical=third("third_dispatch"),bridge=third("third_ignored"),unmarked=third("third_unmarked");
        assertEquals(unmarked,PhpNativeResultUse.select(unmarked,false));
        assertEquals(unmarked,PhpNativeResultUse.select(unmarked,true));
        assertFalse(require(unmarked,unmarked,false).discarded());
        assertThrows(IllegalArgumentException.class,()->require(unmarked,unmarked,true));
        byte[] bytes=physicalBytes(ThirdFunctions.class);
        assertThrows(IOException.class,()->PhpNativeResultUse.verify(bytes,unmarked,bridge));
        assertThrows(IOException.class,()->PhpNativeResultUse.verify(bytes,canonical,unmarked));
        assertThrows(IllegalArgumentException.class,()->require(canonical,bridge,false));
        assertThrows(IllegalArgumentException.class,()->require(canonical,unmarked,true));
        assertThrows(IllegalArgumentException.class,()->PhpNativeResultUse.require(canonical,
                "foreign/ThirdFunctions",bridge.getName(),descriptor(bridge),true));
        assertThrows(IllegalArgumentException.class,()->PhpNativeResultUse.require(canonical,
                Type.getInternalName(ThirdFunctions.class),bridge.getName(),"(Lphp/runtime/Memory;)Lphp/runtime/Memory;",true));
        Method exposed=ExposedBridge.class.getDeclaredMethod("used",Memory.class);
        assertThrows(IllegalArgumentException.class,()->PhpNativeResultUse.select(exposed,true));
        assertThrows(IOException.class,()->PhpNativeResultUse.verify(physicalBytes(ExposedBridge.class),
                exposed,ExposedBridge.class.getDeclaredMethod("ignored",Memory.class)));
        assertThrows(IOException.class,()->PhpNativeResultUse.verify(bytes,canonical,
                ExposedBridge.class.getDeclaredMethod("ignored",Memory.class)));
        Method missing=MissingBridge.class.getDeclaredMethod("used",Memory.class);
        assertThrows(IllegalArgumentException.class,()->PhpNativeResultUse.select(missing,true));
    }

    @Test public void physicalFlagOpcodeStoreHandlerArgumentCalleeDescriptorAndOwnerMutationsReject()throws Throwable {
        Method canonical=third("third_dispatch"),bridge=third("third_ignored");
        byte[] original=physicalBytes(ThirdFunctions.class);String desc=descriptor(canonical);
        rejected(original,c->{var m=method(c,bridge.getName(),desc);var flag=ops(m).stream().filter(o->o.getOpcode()==Opcodes.ICONST_1).findFirst().orElseThrow();m.instructions.set(flag,new InsnNode(Opcodes.ICONST_0));});
        rejected(original,c->{var m=method(c,canonical.getName(),desc);var flag=ops(m).stream().filter(o->o.getOpcode()==Opcodes.ICONST_0).findFirst().orElseThrow();m.instructions.set(flag,new InsnNode(Opcodes.ICONST_1));});
        rejected(original,c->method(c,bridge.getName(),desc).instructions.insert(new InsnNode(Opcodes.NOP)));
        rejected(original,c->{var m=method(c,bridge.getName(),desc);var extra=new InsnList();extra.add(new InsnNode(Opcodes.ACONST_NULL));extra.add(new VarInsnNode(Opcodes.ASTORE,m.maxLocals++));m.instructions.insert(extra);});
        rejected(original,c->{var m=method(c,bridge.getName(),desc);var start=new LabelNode();var end=new LabelNode();var handler=new LabelNode();m.instructions.insert(start);m.instructions.add(end);m.instructions.add(handler);m.instructions.add(new InsnNode(Opcodes.ATHROW));m.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,"java/lang/Throwable"));});
        rejected(original,c->{var m=method(c,bridge.getName(),desc);var loads=ops(m).stream().filter(o->o instanceof VarInsnNode).map(o->(VarInsnNode)o).toList();int first=loads.get(0).var;loads.get(0).var=loads.get(1).var;loads.get(1).var=first;});
        rejected(original,c->{var m=method(c,bridge.getName(),desc);var call=(MethodInsnNode)ops(m).stream().filter(o->o instanceof MethodInsnNode).findFirst().orElseThrow();call.name="alternateForward";});
        rejected(original,c->method(c,bridge.getName(),desc).desc="(Lphp/runtime/Memory;)Lphp/runtime/Memory;");
        rejected(original,c->c.name="foreign/PhysicalOwner");
    }

    @Test public void physicalMetadataMustAgreeWithReflectedNativePairContract()throws Throwable {
        Method canonical=third("third_dispatch"),bridge=third("third_ignored");
        byte[] original=physicalBytes(ThirdFunctions.class);String desc=descriptor(canonical);
        rejected(original,c->method(c,canonical.getName(),desc).visibleAnnotations.removeIf(a->a.desc.equals(Type.getDescriptor(PhpNativeResultBridge.class))));
        rejected(original,c->method(c,bridge.getName(),desc).visibleAnnotations.removeIf(a->a.desc.equals(Type.getDescriptor(Reflection.Ignore.class))));
        rejected(original,c->{var a=method(c,canonical.getName(),desc).visibleAnnotations.stream().filter(x->x.desc.equals(Type.getDescriptor(PhpNativeResultBridge.class))).findFirst().orElseThrow();int at=a.values.indexOf("discarded");a.values.set(at+1,"third_unmarked");});
        rejected(original,c->method(c,bridge.getName(),desc).access=(method(c,bridge.getName(),desc).access&~Opcodes.ACC_PUBLIC)|Opcodes.ACC_PRIVATE);
    }

    private void execute(String source,boolean generic,String expected)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            if(generic)scope.registerExtension(new ThirdExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            env.getDefaultBuffer().setChunkSize(0);
            try {
                assertNull("Passive lane has no invented ownership authority",php.runtime.PhpRuntimeOwnership.current(env));
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                module.include(env);env.getDefaultBuffer().flush();
                assertEquals("debug="+debug+" PHB="+phb,expected,output.toString(StandardCharsets.UTF_8));
                assertNull(php.runtime.PhpRuntimeOwnership.current(env));
                assertNull(env.getErrorHandler());
            }finally{env.doFinal();}
        }
    }
    private static final String PRELUDE="set_error_handler(function($n,$s){echo 'W;';return true;});"
            +"#[NoDiscard] function bridge_value($n){echo 'B',$n,';';return $n;}";

    @Test public void passiveThirdNativeFamilyForwardsDiscardedUsedAndExplicitVoidSourceAndPhb()throws Throwable {
        execute(PRELUDE+"function third_run(){third_dispatch('bridge_value',1);$used=third_dispatch('bridge_value',2);"
                +"(void)third_dispatch('bridge_value',3);echo 'R',$used,';';}third_run();restore_error_handler();",
                true,"W;B1;B2;B3;R2;");
    }

    @Test public void passiveCufAndCufaNullPacketForwardDiscardedUsedAndExplicitVoidSourceAndPhb()throws Throwable {
        execute(PRELUDE+"function native_run(){call_user_func('bridge_value',1);$a=call_user_func('bridge_value',2);"
                +"(void)call_user_func('bridge_value',3);call_user_func_array('bridge_value',[4]);"
                +"$b=call_user_func_array('bridge_value',[5]);(void)call_user_func_array('bridge_value',[6]);"
                +"echo 'R',$a,'/',$b,';';}native_run();restore_error_handler();",
                false,"W;B1;B2;B3;W;B4;B5;B6;R2/5;");
    }
}
