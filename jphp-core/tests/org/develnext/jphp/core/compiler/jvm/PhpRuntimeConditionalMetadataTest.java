package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Conditional ownership is tied to actual branch, scratch, quiet read and selected value code. */
public class PhpRuntimeConditionalMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        @Override protected ByteVector write(ClassWriter w,byte[] c,int n,int s,int l){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        @Override protected Attribute read(ClassReader r,int o,int n,char[] b,int c,Label[] l){byte[] data=new byte[n];for(int i=0;i<n;i++)data[i]=(byte)r.readByte(o+i);return new RawAttribute(data);}
    }
    @FunctionalInterface private interface Check {void run(byte[] bytes,String name,String descriptor)throws Throwable;}
    private void modes(Check check)throws Throwable {
        modes("return $GLOBALS['missing'] ?? 'absent';",check);
    }
    private void modes(String source,Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                check.run(module.getData(),module.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(module.getNativeMethod()));
                assertEquals("Byte checks must not execute PHP effects",0,output.size());
            }finally{env.doFinal();}
        }
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static MethodNode method(ClassNode node,String name,String descriptor){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> ops(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(n->n.getOpcode()>=0).toList();}
    private static void reject(byte[] bytes,String name,String descriptor,String label,List<String> accepted)throws Throwable {
        try{PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);accepted.add(label);}catch(IOException expected){}
    }
    @Test public void testAndSelectedArmsNeedTheirExactSourceScratchAndJumps()throws Throwable {
        modes((bytes,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertEquals(1,plan.conditionals().size());
            var site=plan.conditionals().getFirst();var accepted=new ArrayList<String>();
            for(int change=0;change<16;change++) {
                var node=read(bytes);var m=method(node,name,descriptor);var code=ops(m);int test=site.test(),yes=site.trueResult(),no=site.falseResult();
                switch(change) {
                    case 0: ((VarInsnNode)code.get(test-6)).var++;break;
                    case 1: m.instructions.set(code.get(test-5),new LdcInsnNode(site.id()+1));break;
                    case 2: m.instructions.set(code.get(test-4),new LdcInsnNode(site.source()+1));break;
                    case 3: ((VarInsnNode)code.get(test-3)).var++;break;
                    case 4: m.instructions.set(code.get(test-2),new InsnNode(Opcodes.ICONST_1));break;
                    case 5: m.instructions.set(code.get(test-1),new InsnNode(Opcodes.ICONST_0));break;
                    case 6: ((JumpInsnNode)code.get(site.branch())).label=((JumpInsnNode)code.get(site.jump())).label;break;
                    case 7: m.instructions.set(code.get(yes-5),new LdcInsnNode(site.id()+1));break;
                    case 8: m.instructions.set(code.get(yes-4),new LdcInsnNode(site.id()+1));break;
                    case 9: m.instructions.set(code.get(yes-3),new LdcInsnNode(site.source()+1));break;
                    case 10: ((VarInsnNode)code.get(yes-2)).var++;break;
                    case 11: m.instructions.set(code.get(yes-1),new InsnNode(Opcodes.ICONST_0));break;
                    case 12: ((JumpInsnNode)code.get(site.jump())).label=((JumpInsnNode)code.get(site.branch())).label;break;
                    case 13: m.instructions.set(code.get(no-3),new LdcInsnNode(site.source()));break;
                    case 14: ((VarInsnNode)code.get(no-7)).var++;break;
                    default: m.instructions.set(code.get(no-1),new InsnNode(Opcodes.ICONST_1));
                }
                reject(write(node),name,descriptor,"branch-"+change,accepted);
            }
            assertTrue("Accepted conditional mutations: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void quietReadAndScalarArmCannotClaimAnotherActualValue()throws Throwable {
        modes((bytes,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);var site=plan.conditionals().getFirst();var read=plan.globalReads().getFirst();assertTrue(plan.quietGlobalReads().contains(read.id()));
            var accepted=new ArrayList<String>();
            for(int change=0;change<5;change++) {
                var node=read(bytes);var m=method(node,name,descriptor);var code=ops(m);
                if(change==0)((MethodInsnNode)code.get(read.helper())).name="globalRead";
                else if(change==1)m.instructions.set(code.get(read.helper()-6),new LdcInsnNode("other"));
                else if(change==2)m.instructions.set(code.get(site.falseResult()-9),new LdcInsnNode("changed"));
                else if(change==3)((MethodInsnNode)code.get(site.falseResult()-8)).owner="php/runtime/memory/ObjectMemory";
                else ((VarInsnNode)code.get(site.trueResult()-8)).var++;
                reject(write(node),name,descriptor,"quiet-or-literal-"+change,accepted);
            }
            assertTrue("Accepted quiet/scalar mutations: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void oldSchemaAndTruncatedTailCannotHideTheActualJoin()throws Throwable {
        modes((bytes,name,descriptor)->{
            var accepted=new ArrayList<String>();
            for(int change=0;change<3;change++) {
                var node=read(bytes);var m=method(node,name,descriptor);
                for(int i=0;i<m.attrs.size();i++)if(m.attrs.get(i) instanceof RawAttribute raw) {
                    byte[] payload=change==0?raw.bytes.clone():Arrays.copyOf(raw.bytes,raw.bytes.length-(change==1?1:4));
                    if(change==0){payload[0]=0;payload[1]=0;payload[2]=0;payload[3]=14;}
                    m.attrs.set(i,new RawAttribute(payload));
                }
                reject(write(node),name,descriptor,"tail-"+change,accepted);
            }
            assertTrue("Accepted hidden conditional tails: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void scalarNativeTransportNeedsActualLiteralConversionAndBodyResult()throws Throwable {
        for(String source:new String[]{"return error_reporting(0);","return trigger_error('alternate');"})modes(source,(bytes,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            var original=ops(method(read(bytes),name,descriptor));
            int borrow=-1,box=-1;
            for(int i=0;i<original.size();i++)if(original.get(i) instanceof MethodInsnNode call) {
                if(call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("nativeBorrowSend"))borrow=i;
                if(call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("nativeResult"))box=i-5;
            }
            assertTrue(borrow>=9&&box>=0);var accepted=new ArrayList<String>();
            for(int change=0;change<13;change++) {
                var node=read(bytes);var m=method(node,name,descriptor);var code=ops(m);
                switch(change) {
                    case 0: m.instructions.set(code.get(borrow-9),new LdcInsnNode(source.contains("reporting")?"foreign":Long.valueOf(7)));break;
                    case 1: ((MethodInsnNode)code.get(borrow-8)).owner="php/runtime/memory/ObjectMemory";break;
                    case 2: ((MethodInsnNode)code.get(borrow-8)).desc="(I)Lphp/runtime/Memory;";break;
                    case 3: ((VarInsnNode)code.get(borrow-7)).var++;break;
                    case 4: ((VarInsnNode)code.get(borrow-4)).var++;break;
                    case 5: m.instructions.set(code.get(borrow-3),new LdcInsnNode(4_000_099));break;
                    case 6: ((MethodInsnNode)code.get(borrow+1)).owner="php/runtime/memory/ObjectMemory";break;
                    case 7: ((MethodInsnNode)code.get(borrow+1)).name="toDouble";break;
                    case 8: if(source.contains("reporting"))m.instructions.set(code.get(borrow+2),new InsnNode(Opcodes.L2F));else ((MethodInsnNode)code.get(borrow+1)).desc="()Ljava/lang/Object;";break;
                    case 9: ((MethodInsnNode)code.get(box)).owner="php/runtime/memory/ObjectMemory";break;
                    case 10: ((MethodInsnNode)code.get(box)).desc="(J)Lphp/runtime/Memory;";break;
                    case 11: ((VarInsnNode)code.get(box+2)).var++;break;
                    default: ((VarInsnNode)code.get(box+3)).var++;
                }
                reject(write(node),name,descriptor,"native-scalar-"+change,accepted);
            }
            assertTrue("Accepted scalar transport mutations: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void addressedCvTestNeedsItsActualReadAndTemporaryProducer()throws Throwable {
        modes("$a=[];unset($a);return $a??'absent';",(bytes,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            assertEquals(1,plan.conditionalLocals().size());var local=plan.conditionalLocals().getFirst();var site=plan.conditionals().getFirst();
            var accepted=new ArrayList<String>();
            for(int change=0;change<9;change++) {
                var node=read(bytes);var m=method(node,name,descriptor);var code=ops(m);int at=local.helper();
                switch(change) {
                    case 0: ((VarInsnNode)code.get(at-4)).var++;break;
                    case 1: m.instructions.set(code.get(at-3),new LdcInsnNode(local.id()+1));break;
                    case 2: m.instructions.set(code.get(at-2),new LdcInsnNode(local.local()+1));break;
                    case 3: ((VarInsnNode)code.get(at-1)).var++;break;
                    case 4: ((MethodInsnNode)code.get(at)).owner="php/runtime/PhpOwnershipArrays";break;
                    case 5: ((MethodInsnNode)code.get(at)).name="nativeResult";break;
                    case 6: ((MethodInsnNode)code.get(at)).desc="(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;)Ljava/lang/Object;";break;
                    case 7: m.instructions.set(code.get(site.test()-4),new LdcInsnNode(local.id()+1));break;
                    default: ((VarInsnNode)code.get(site.test()-7)).var++;
                }
                reject(write(node),name,descriptor,"conditional-CV-"+change,accepted);
            }
            assertTrue("Accepted conditional CV mutations: "+accepted,accepted.isEmpty());
        });
    }
}
