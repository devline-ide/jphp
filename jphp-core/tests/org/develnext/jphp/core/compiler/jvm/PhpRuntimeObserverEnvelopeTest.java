package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.PhpOwnershipExecution;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Cold reader proof for real joint FIELD and typed NEW/CV observer envelopes. */
public class PhpRuntimeObserverEnvelopeTest extends JvmCompilerCase implements Opcodes {
    private static final String TYPED="class MetadataField {public int $v=1;} $a=new MetadataField;$r=&$a->v;unset($a);$r='free';return $r;";
    private static final String FIELD="class RInt {public int $v=1;} $a=new RInt;$ref=&$a->v;$ref='bad';return 7;";
    @FunctionalInterface private interface Check {void run(byte[] bytes,String name,String descriptor)throws Throwable;}
    private void modes(String source,Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var method=module.getNativeMethod();check.run(module.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));
                assertEquals("Cold proof must not run PHP effects","",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static MethodNode method(ClassNode node,String name,String descriptor){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static int call(List<AbstractInsnNode> code,String owner,String name){for(int i=0;i<code.size();i++)if(code.get(i) instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))return i;throw new AssertionError("Missing actual helper "+owner+"."+name);}
    private static IOException rejected(byte[] bytes,String name,String descriptor)throws Throwable {
        try{PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);fail("Mutated connected envelope was admitted");return null;}catch(IOException expected){return expected;}
    }
    @Test public void realJointObserversRetainClosedRuntimeProof()throws Throwable {
        for(String source:List.of(TYPED,FIELD))modes(source,(bytes,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);
            assertNotNull(plan);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            assertEquals(1,plan.objectCvs().size());assertEquals(1,plan.propertyReferences().size());
            assertEquals(plan.propertyReferences().getFirst().helper()+14,plan.propertyReferences().getFirst().resultPublication());
        });
    }
    @Test public void fieldExportRequiresExactMemberAndRecordedSite()throws Throwable {
        modes(FIELD,(bytes,name,descriptor)->{
            assertTrue(PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor).unsupported().isEmpty());
            for(int mutation=0;mutation<5;mutation++) {
                var node=read(bytes);var m=method(node,name,descriptor);var ops=code(m);int at=call(ops,"php/runtime/PhpPrimitiveCellRuntime","export");
                if(mutation==0)((LdcInsnNode)ops.get(at-4)).cst=org.objectweb.asm.Type.getObjectType("java/lang/Object");
                else if(mutation==1)((LdcInsnNode)ops.get(at-3)).cst="foreign";
                else if(mutation==2)((LdcInsnNode)ops.get(at-2)).cst="()V";
                else if(mutation==3)((LdcInsnNode)ops.get(at-1)).cst=1000000;
                else ((MethodInsnNode)ops.get(at)).desc="(Lphp/runtime/Memory;)Lphp/runtime/Memory;";
                rejected(write(node),name,descriptor);
            }
        });
    }
    @Test public void typedCreatedRequiresSameCompleteProofAndAddress()throws Throwable {
        modes(TYPED,(bytes,name,descriptor)->{
            assertTrue(PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor).unsupported().isEmpty());
            for(int mutation=0;mutation<7;mutation++) {
                var node=read(bytes);var m=method(node,name,descriptor);var ops=code(m);int at=call(ops,"php/runtime/PhpTypedSourceLifetime","created");
                if(mutation==0)((LdcInsnNode)ops.get(at-6)).cst=org.objectweb.asm.Type.getObjectType("java/lang/Object");
                else if(mutation==1)((LdcInsnNode)ops.get(at-5)).cst=org.objectweb.asm.Type.getObjectType("java/lang/Object");
                else if(mutation==2)((LdcInsnNode)ops.get(at-4)).cst="foreign";
                else if(mutation==3)((LdcInsnNode)ops.get(at-3)).cst="()V";
                else if(mutation==4)((LdcInsnNode)ops.get(at-2)).cst=1000000;
                else if(mutation==5)((LdcInsnNode)ops.get(at-1)).cst=1000000;
                else {int published=call(ops,"php/runtime/PhpTypedSourceLifetime","published");((MethodInsnNode)ops.get(published)).name="created";}
                rejected(write(node),name,descriptor);
            }
        });
    }
    @Test public void foreignBranchCannotEnterFieldExportOperands()throws Throwable {
        modes(FIELD,(bytes,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);assertTrue(plan.unsupported().isEmpty());
            var node=read(bytes);var m=method(node,name,descriptor);var ops=code(m);int exported=call(ops,"php/runtime/PhpPrimitiveCellRuntime","export");
            int discard=call(ops.subList(exported+1,ops.size()),"php/runtime/PhpPrimitiveCellRuntime","discard")+exported+1;
            assertEquals(DUP,ops.get(discard-5).getOpcode());
            var ingress=new LabelNode();m.instructions.insertBefore(ops.get(exported-3),ingress);
            m.instructions.set(ops.get(discard-5),new JumpInsnNode(GOTO,ingress));
            var failure=rejected(write(node),name,descriptor);
            assertTrue(failure.toString(),failure.getMessage().contains("physical transport envelope"));
        });
    }
}