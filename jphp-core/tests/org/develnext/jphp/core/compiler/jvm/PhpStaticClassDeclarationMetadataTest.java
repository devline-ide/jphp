package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.PhpOwnershipExecution;
import php.runtime.PhpPrimitiveCellPlan;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;
import static org.junit.Assert.*;

/** Cold physical admission for the final check-only class declaration bridge. Never executes PHP. */
public class PhpStaticClassDeclarationMetadataTest extends JvmCompilerCase implements Opcodes {
    private static final String SOURCE="class BridgeMetadataCell {public int $v=1;} $a=new BridgeMetadataCell;$r=&$a->v;$r='bad';return 7;";
    @FunctionalInterface private interface Check {void run(byte[] bytes,String name,String descriptor)throws Throwable;}
    private void modes(Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(SOURCE);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var method=module.getNativeMethod();check.run(module.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));
                assertEquals("Cold proof must not execute PHP","",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static MethodNode member(ClassNode node,String name,String descriptor){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static int bridge(List<AbstractInsnNode> ops){for(int at=0;at<ops.size();at++)if(ops.get(at) instanceof MethodInsnNode call&&call.name.equals("__checkClassDeclaration"))return at;throw new AssertionError("Missing actual class declaration bridge");}
    private static void rejected(byte[] bytes,String name,String descriptor)throws Throwable {
        try{PhpPrimitiveCellPlan.validateClassDeclarationChecks(bytes,name,descriptor);fail("Changed class declaration prefix admitted");}
        catch(IOException expected){assertTrue(expected.toString(),expected.getMessage().contains("physical module operands or control flow"));}
    }
    private static boolean cellWitness(byte[] bytes,String name,String descriptor)throws Throwable {
        var model=ClassFile.of().parse(bytes);
        var method=model.methods().stream().filter(m->m.methodName().equalsString(name)&&m.methodType().equalsString(descriptor)).findFirst().orElseThrow();
        var code=method.code().orElseThrow();var ops=code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
        int at=-1;for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof InvokeInstruction call&&call.name().equalsString("__checkClassDeclaration")){at=i;break;}
        assertTrue(at>=0);
        var check=PhpPrimitiveCellPlan.class.getDeclaredMethod("classDeclarationCheck",String.class,MethodModel.class,CodeModel.class,List.class,int.class);
        check.setAccessible(true);return (Boolean)check.invoke(null,model.thisClass().asInternalName(),method,code,ops,at);
    }
    @Test public void realSourceAndPhbRetainIndependentRuntimeAndCellProof()throws Throwable {
        assertTrue("Effectful/nonfinal predecessor must remain inadmissible",java.lang.reflect.Modifier.isFinal(
                Environment.class.getMethod("__checkClassDeclaration",TraceInfo.class,String.class,String.class).getModifiers()));
        modes((bytes,name,descriptor)->{
            PhpPrimitiveCellPlan.validateClassDeclarationChecks(bytes,name,descriptor);assertTrue(cellWitness(bytes,name,descriptor));
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);
            assertNotNull(plan);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            assertEquals(1,plan.objectCvs().size());assertEquals(1,plan.propertyReferences().size());
        });
    }
    @Test public void changedOperandsAndInteriorControlFlowFailCold()throws Throwable {
        modes((bytes,name,descriptor)->{
            for(int mutation=0;mutation<20;mutation++) {
                var tree=read(bytes);var method=member(tree,name,descriptor);var ops=code(method);int at=bridge(ops);
                if(mutation==0)((VarInsnNode)ops.get(at-6)).var=1;
                else if(mutation==1)((FieldInsnNode)ops.get(at-5)).owner="java/lang/Object";
                else if(mutation==2)((FieldInsnNode)ops.get(at-5)).name="$MEM";
                else if(mutation==3)((FieldInsnNode)ops.get(at-5)).desc="[Lphp/runtime/Memory;";
                else if(mutation==4)method.instructions.set(ops.get(at-4),new InsnNode(ICONST_M1));
                else if(mutation==5)method.instructions.set(ops.get(at-3),new InsnNode(NOP));
                else if(mutation==6)((LdcInsnNode)ops.get(at-2)).cst="foreign/module";
                else if(mutation==7)((LdcInsnNode)ops.get(at-1)).cst="";
                else if(mutation==8)((LdcInsnNode)ops.get(at-1)).cst=1;
                else if(mutation==9)((MethodInsnNode)ops.get(at)).setOpcode(INVOKESTATIC);
                else if(mutation==10)((MethodInsnNode)ops.get(at)).owner="java/lang/Object";
                else if(mutation==11)((MethodInsnNode)ops.get(at)).desc="(Lphp/runtime/env/TraceInfo;Ljava/lang/String;)V";
                else if(mutation==12)method.access&=~ACC_STATIC;
                else if(mutation==13)method.name="__invoke";
                else if(mutation==14){var stores=new InsnList();stores.add(new InsnNode(ACONST_NULL));stores.add(new VarInsnNode(ASTORE,0));method.instructions.insertBefore(ops.get(at-6),stores);}
                else {
                    var inside=new LabelNode();method.instructions.insertBefore(ops.get(at-1),inside);
                    if(mutation==15)method.instructions.insertBefore(ops.get(at-6),new JumpInsnNode(GOTO,inside));
                    else if(mutation==16)method.instructions.insertBefore(ops.get(at-6),new TableSwitchInsnNode(0,0,inside,inside));
                    else if(mutation==17)method.instructions.insertBefore(ops.get(at-6),new LookupSwitchInsnNode(inside,new int[]{0},new LabelNode[]{inside}));
                    else {
                        var start=new LabelNode();var end=new LabelNode();var handler=new LabelNode();
                        method.instructions.insertBefore(ops.get(at-6),start);method.instructions.insert(ops.get(at),end);
                        method.instructions.add(handler);method.instructions.add(new InsnNode(ATHROW));
                        method.tryCatchBlocks.add(new TryCatchBlockNode(mutation==19?inside:start,end,mutation==18?inside:handler,"java/lang/Throwable"));
                    }
                }
                byte[] changed=write(tree);rejected(changed,method.name,descriptor);
                assertFalse("CELL must independently reject mutation "+mutation,cellWitness(changed,method.name,descriptor));
                if(mutation<12)try{PhpRuntimeOwnership.readRuntimePlan(changed,name,descriptor);fail("Runtime plan trusted changed bridge operands "+mutation);}catch(IOException expected){}
            }
        });
    }
    @Test public void aBranchMayTargetTheWholeOperandPrefix()throws Throwable {
        modes((bytes,name,descriptor)->{
            var tree=read(bytes);var method=member(tree,name,descriptor);var ops=code(method);int at=bridge(ops);
            var start=new LabelNode();method.instructions.insertBefore(ops.get(at-6),start);
            method.instructions.insertBefore(start,new JumpInsnNode(GOTO,start));
            byte[] changed=write(tree);PhpPrimitiveCellPlan.validateClassDeclarationChecks(changed,name,descriptor);
            assertTrue(cellWitness(changed,name,descriptor));
        });
    }
}
