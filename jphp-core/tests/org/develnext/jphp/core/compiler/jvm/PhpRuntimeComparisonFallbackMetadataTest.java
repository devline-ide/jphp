package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

/** The direct ordinary comparison must stay behind its exact Frame-null guard. */
public class PhpRuntimeComparisonFallbackMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(data);}
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static int call(List<AbstractInsnNode> ops,String owner,String name){
        for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof MethodInsnNode selected&&selected.owner.equals(owner)&&selected.name.equals(name))return i;
        throw new AssertionError("Missing call "+owner+"."+name);
    }
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void booleanAndMemoryDirectLanesRequireTheirExactGuardOperandsAndJoin()throws Throwable {
        for(String operator:new String[]{"==","<=>"})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var module=compile(env,"$flag=false;if($flag){echo 'unexpected;';}$a=2;$b=3;echo 'early;';$r=$a"+operator+"$b;$a++;return $r;",dump);
                var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);byte[] original=module.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertEquals(1,plan.nativeMembers().size());
                String selected=operator.equals("==")?"equal":"spaceshipCompare";
                for(int mutation=0;mutation<20;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    int at=call(ops,operator.equals("==")?"php/runtime/PhpComparison":"php/runtime/PhpComparisonCalls",selected),jump=at+1,borrow=call(ops,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend");
                    if(mutation==0)((VarInsnNode)ops.get(at-8)).var++;
                    else if(mutation==1){var guard=(JumpInsnNode)ops.get(at-7);method.instructions.set(guard,new JumpInsnNode(Opcodes.IFNULL,guard.label));}
                    else if(mutation==2||mutation==3){var entry=new LabelNode();method.instructions.insertBefore(ops.get(at-6),entry);((JumpInsnNode)ops.get(mutation==2?at-7:jump)).label=entry;}
                    else if(mutation==4)((VarInsnNode)ops.get(at-6)).var++;
                    else if(mutation==5)((VarInsnNode)ops.get(at-5)).var++;
                    else if(mutation==6)((VarInsnNode)ops.get(at-4)).var++;
                    else if(mutation==7)((FieldInsnNode)ops.get(at-3)).name="$OTHER";
                    else if(mutation==8)method.instructions.set(ops.get(at-2),new LdcInsnNode(-1));
                    else if(mutation==9)method.instructions.set(ops.get(at-1),new InsnNode(Opcodes.NOP));
                    else if(mutation==10)((VarInsnNode)ops.get(borrow-8)).var++;
                    else if(mutation==11)((VarInsnNode)ops.get(at-9)).var++;
                    else if(mutation==12)((MethodInsnNode)ops.get(at)).name="notEqual";
                    else if(mutation==13)((MethodInsnNode)ops.get(at)).owner="php/runtime/PhpArithmetic";
                    else if(mutation==14){var entry=new LabelNode();method.instructions.insertBefore(ops.get(at-6),entry);ops.stream().filter(i->i.getOpcode()==Opcodes.IFEQ||i.getOpcode()==Opcodes.IFNE).map(i->(JumpInsnNode)i).findFirst().orElseThrow().label=entry;}
                    else if(mutation==15){var entry=new LabelNode();method.instructions.insertBefore(ops.get(at-6),entry);method.tryCatchBlocks.getLast().handler=entry;}
                    else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw) {
                        assertEquals(22,ByteBuffer.wrap(raw.bytes).getInt());byte[] bytes=raw.bytes.clone();
                        if(mutation==16)bytes=Arrays.copyOf(bytes,bytes.length-1);
                        else if(mutation==17)ByteBuffer.wrap(bytes).putInt(bytes.length-8,plan.nativeMembers().getFirst().id()+1);
                        else if(mutation==18)ByteBuffer.wrap(bytes).putInt(bytes.length-4,at+1);
                        else {bytes=Arrays.copyOf(bytes,bytes.length-12);ByteBuffer.wrap(bytes).putInt(21);}
                        method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(bytes));
                    }
                    byte[] altered=write(node);assertThrows("operator="+operator+" mutation="+mutation,IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));assertEquals(0,output.size());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void inactiveLegacyArrayComparisonsRequireTheirRealEntryGuard()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var module=compile(env,"$flag=false;if($flag){echo 'unexpected;';}$a=2;$b=3;echo 'early;';echo serialize([$a==$b]);",dump);
                var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);byte[] original=module.getData();assertTrue(PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported().isEmpty());
                for(int mutation=0;mutation<4;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    int entered=call(ops,"php/runtime/PhpOwnershipExecution","enterGenerated"),entry=((VarInsnNode)ops.get(entered+1)).var,comparison=call(ops,"php/runtime/PhpComparison","equal"),guard=-1;
                    for(int at=1;at<comparison;at++)if(ops.get(at) instanceof JumpInsnNode branch&&branch.getOpcode()==Opcodes.IFNULL&&ops.get(at-1) instanceof VarInsnNode read&&read.var==entry&&method.instructions.indexOf(branch.label)>method.instructions.indexOf(ops.get(comparison)))guard=at;
                    assertTrue("Actual legacy ARRAY comparison must have its Entry guard",guard>=0);
                    if(mutation==0){var branch=(JumpInsnNode)ops.get(guard);method.instructions.set(branch,new JumpInsnNode(Opcodes.IFNONNULL,branch.label));}
                    else if(mutation==1)((VarInsnNode)ops.get(guard-1)).var++;
                    else if(mutation==2){method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));method.instructions.add(new VarInsnNode(Opcodes.ASTORE,entry));}
                    else {var entryLabel=new LabelNode();method.instructions.insertBefore(ops.get(comparison),entryLabel);ops.stream().filter(i->i.getOpcode()==Opcodes.IFEQ||i.getOpcode()==Opcodes.IFNE).map(i->(JumpInsnNode)i).findFirst().orElseThrow().label=entryLabel;}
                    byte[] altered=write(node);assertThrows("Session comparison mutation="+mutation,IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));assertEquals(0,output.size());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void emptySchema22CannotUpgradeALegitimateLocalIncrementBody()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,"$a=2;$a++;return $a;",dump);var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);
                var original=module.getData();assertTrue(PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported().isEmpty());var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();
                for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw){assertEquals(21,ByteBuffer.wrap(raw.bytes).getInt());byte[] bytes=Arrays.copyOf(raw.bytes,raw.bytes.length+4);ByteBuffer.wrap(bytes).putInt(22);method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(bytes));}
                byte[] altered=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
            }finally{env.doFinal();}
        }
    }
}
