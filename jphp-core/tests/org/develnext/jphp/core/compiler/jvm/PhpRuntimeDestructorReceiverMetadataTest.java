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

/** Lifecycle entry/import authority is bound to actual generated receiver operands and CFG. */
public class PhpRuntimeDestructorReceiverMetadataTest extends JvmCompilerCase {
    private static final String RUNTIME="JPHP_Runtime_Frame_v1", FRAME="JPHP_Ownership_Frame_v1";
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(String name,byte[] bytes){super(name);this.bytes=bytes;}
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(type,data);}
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(RUNTIME,new byte[0]),new RawAttribute(FRAME,new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static MethodNode method(ClassNode node,String name,String descriptor){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static RawAttribute attribute(MethodNode method,String name){return (RawAttribute)method.attrs.stream().filter(a->a.type.equals(name)).findFirst().orElseThrow();}
    private static void replace(MethodNode method,String name,byte[] bytes){for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i).type.equals(name)){method.attrs.set(i,new RawAttribute(name,bytes));return;}throw new AssertionError("Missing actual "+name);}
    @FunctionalInterface private interface Check {void run(byte[] bytes,String name,String descriptor,php.runtime.reflection.MethodEntity entity,Environment env)throws Throwable;}
    private void modes(String body,Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            try{
                var context=new Context((strict?"declare(strict_types=1);":"")+"class ReceiverMetadata {public int $v=7;function other(){echo 'other';}function __destruct(){"+body+"}}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var d=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();d.save(module,out);module=d.load(new ByteArrayInputStream(out.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var clazz=module.getClasses().stream().filter(c->c.getName().equals("ReceiverMetadata")).findFirst().orElseThrow();var entity=clazz.methodDestruct;var member=entity.getNativeMethod();
                check.run(clazz.getData(),member.getName(),PhpOwnershipExecution.descriptor(member),entity,env);
                assertEquals("Data/preflight checks must not run PHP effects","",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    private static void rejected(byte[] bytes,String name,String descriptor,String label,List<String> accepted)throws Throwable {
        try{PhpRuntimeOwnership.readRuntimePlan(bytes,name,descriptor);accepted.add(label);}catch(IOException expected){}
    }
    private static byte[] lifecycleTail(boolean flag,List<PhpRuntimeOwnership.LifecycleReceiver> receivers,List<PhpRuntimeOwnership.LifecycleDiscard> discards)throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBoolean(flag);out.writeInt(receivers.size());
        for(var r:receivers){out.writeInt(r.local());out.writeInt(r.read());out.writeInt(r.boxing());out.writeInt(r.helper());out.writeInt(r.store());out.writeInt(r.start());out.writeInt(r.fallback());out.writeInt(r.end());}out.writeInt(discards.size());for(var d:discards){out.writeInt(d.local());out.writeInt(d.read());out.writeInt(d.branch());out.writeInt(d.exception());out.writeInt(d.pop());}return bytes.toByteArray();
    }
    private static void tail(MethodNode method,PhpRuntimeOwnership.RuntimePlan plan,boolean flag,List<PhpRuntimeOwnership.LifecycleReceiver> rows)throws IOException {
        var old=lifecycleTail(plan.lifecycle(),plan.lifecycleReceivers(),plan.lifecycleDiscards());var raw=attribute(method,RUNTIME);int at=raw.bytes.length-old.length;
        assertTrue(at>=0);assertArrayEquals("Only exact existing schema16 tail may be replaced",old,Arrays.copyOfRange(raw.bytes,at,raw.bytes.length));
        var out=new ByteArrayOutputStream();out.write(raw.bytes,0,at);out.write(lifecycleTail(flag,rows,plan.lifecycleDiscards()));replace(method,RUNTIME,out.toByteArray());
    }
    private static void discardTail(MethodNode method,PhpRuntimeOwnership.RuntimePlan plan,List<PhpRuntimeOwnership.LifecycleDiscard> rows)throws IOException {
        var old=lifecycleTail(plan.lifecycle(),plan.lifecycleReceivers(),plan.lifecycleDiscards());var raw=attribute(method,RUNTIME);int at=raw.bytes.length-old.length;assertArrayEquals(old,Arrays.copyOfRange(raw.bytes,at,raw.bytes.length));
        var out=new ByteArrayOutputStream();out.write(raw.bytes,0,at);out.write(lifecycleTail(plan.lifecycle(),plan.lifecycleReceivers(),rows));replace(method,RUNTIME,out.toByteArray());
    }
    @Test public void receiverOperandsAndGuardBindTheActualBorrowedImport()throws Throwable {
        modes("$this;echo 'd';",(original,name,descriptor,entity,env)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);assertTrue(plan.lifecycle());assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertEquals(1,plan.lifecycleReceivers().size());
            var r=plan.lifecycleReceivers().getFirst();assertEquals(r.helper()-2,r.read());assertEquals(r.helper()-1,r.boxing());assertEquals(r.helper()+1,r.store());
            assertEquals(plan.lifecycleReceivers(),PhpRuntimeOwnership.readRuntimePlan(write(read(original)),name,descriptor).lifecycleReceivers());
            String[] labels={"guard-frame","actual-frame","local-id","receiver-read","box-owner","box-name","box-descriptor","helper-owner","helper-name","helper-descriptor","actual-store","guard-opcode","guard-target","join-target"};var accepted=new ArrayList<String>();
            for(int mutation=0;mutation<labels.length;mutation++){
                var node=read(original);var m=method(node,name,descriptor);var ops=code(m);int h=r.helper();
                if(mutation==0)((VarInsnNode)ops.get(h-6)).var++;
                else if(mutation==1)((VarInsnNode)ops.get(h-4)).var++;
                else if(mutation==2)m.instructions.set(ops.get(h-3),new LdcInsnNode(r.local()+1));
                else if(mutation==3)((VarInsnNode)ops.get(r.read())).var=1;
                else if(mutation==4)((MethodInsnNode)ops.get(r.boxing())).owner="php/runtime/memory/ReferenceMemory";
                else if(mutation==5)((MethodInsnNode)ops.get(r.boxing())).name="valueOfRef";
                else if(mutation==6)((MethodInsnNode)ops.get(r.boxing())).desc="(Lphp/runtime/Memory;)Lphp/runtime/Memory;";
                else if(mutation==7)((MethodInsnNode)ops.get(h)).owner="php/runtime/PhpOwnershipExecution$Entry";
                else if(mutation==8)((MethodInsnNode)ops.get(h)).name="prepareLocal";
                else if(mutation==9)((MethodInsnNode)ops.get(h)).desc="(ILphp/runtime/Memory;I)Lphp/runtime/Memory;";
                else if(mutation==10)((VarInsnNode)ops.get(r.store())).var++;
                else if(mutation==11)((JumpInsnNode)ops.get(h-5)).setOpcode(Opcodes.IFNONNULL);
                else if(mutation==12)((JumpInsnNode)ops.get(h-5)).label=((JumpInsnNode)ops.get(h+2)).label;
                else ((JumpInsnNode)ops.get(h+2)).label=((JumpInsnNode)ops.get(h-5)).label;
                rejected(write(node),name,descriptor,labels[mutation],accepted);
            }
            assertTrue("Accepted lifecycle operand mutations: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void lifecycleTailAndDeclaredSelfStorageCannotLaunderAuthority()throws Throwable {
        modes("$this;echo 'd';",(original,name,descriptor,entity,env)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);var r=plan.lifecycleReceivers().getFirst();var accepted=new ArrayList<String>();
            String[] labels={"false-flag","missing-row","duplicate-row","foreign-local","foreign-read","foreign-box","foreign-helper","foreign-store","foreign-start","foreign-fallback","foreign-end","static-member","declared-storage","declared-jvm-alias","declared-import"};
            for(int mutation=0;mutation<labels.length;mutation++){
                var node=read(original);var m=method(node,name,descriptor);
                if(mutation==0)tail(m,plan,false,plan.lifecycleReceivers());
                else if(mutation==1)tail(m,plan,true,List.of());
                else if(mutation==2)tail(m,plan,true,List.of(r,r));
                else if(mutation>=3&&mutation<=10){int[] v={r.local(),r.read(),r.boxing(),r.helper(),r.store(),r.start(),r.fallback(),r.end()};v[mutation-3]++;tail(m,plan,true,List.of(new PhpRuntimeOwnership.LifecycleReceiver(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7])));}
                else if(mutation==11)m.access|=Opcodes.ACC_STATIC;
                else replaceSelfFrame(m,r.local(),mutation-12);
                rejected(write(node),name,descriptor,labels[mutation],accepted);
            }
            var raw=attribute(method(read(original),name,descriptor),RUNTIME);
            for(int extra=0;extra<2;extra++){var node=read(original);var m=method(node,name,descriptor);byte[] payload=extra==0?Arrays.copyOf(raw.bytes,raw.bytes.length-1):Arrays.copyOf(raw.bytes,raw.bytes.length+1);replace(m,RUNTIME,payload);rejected(write(node),name,descriptor,extra==0?"truncated-tail":"trailing-tail",accepted);}
            var d=plan.lifecycleDiscards().getFirst();for(int mutation=0;mutation<7;mutation++){
                var node=read(original);var m=method(node,name,descriptor);if(mutation==0)discardTail(m,plan,List.of());else if(mutation==1)discardTail(m,plan,List.of(d,d));else{int[] v={d.local(),d.read(),d.branch(),d.exception(),d.pop()};v[mutation-2]++;discardTail(m,plan,List.of(new PhpRuntimeOwnership.LifecycleDiscard(v[0],v[1],v[2],v[3],v[4])));}rejected(write(node),name,descriptor,"discard-tail-"+mutation,accepted);
            }
            assertTrue("Accepted lifecycle storage/tail mutations: "+accepted,accepted.isEmpty());
        });
    }
    private static void replaceSelfFrame(MethodNode method,int selfId,int mutation)throws IOException {
        var raw=attribute(method,FRAME);var in=new DataInputStream(new ByteArrayInputStream(raw.bytes));var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(in.readInt());for(int i=0;i<4;i++)out.writeUTF(in.readUTF());int count=in.readInt();out.writeInt(count);boolean found=false;
        for(int i=0;i<count;i++){int id=in.readInt(),slot=in.readInt();String name=in.readUTF(),storage=in.readUTF(),kind=in.readUTF();int index=in.readInt();String mode=in.readUTF();if(id==selfId){found=true;if(mutation==0)storage="VALUE";else if(mutation==1)slot=0;else{kind="ARGUMENT";index=0;}}out.writeInt(id);out.writeInt(slot);out.writeUTF(name);out.writeUTF(storage);out.writeUTF(kind);out.writeInt(index);out.writeUTF(mode);}assertTrue(found);out.write(in.readAllBytes());replace(method,FRAME,bytes.toByteArray());
    }
    @Test public void borrowedSelfAllowsOnlyItsExactDiscardAndLiteralEntryNeedsItsFlag()throws Throwable {
        modes("$this;echo 'd';",(original,name,descriptor,entity,env)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);var r=plan.lifecycleReceivers().getFirst();assertEquals(1,plan.lifecycleDiscards().size());var d=plan.lifecycleDiscards().getFirst();
            assertEquals(d.read()+3,d.branch());assertEquals(d.read()+11,d.exception());assertEquals(d.read()+12,d.pop());
            var accepted=new ArrayList<String>();String[] labels={"read","dup","isNull-owner","isNull-name","isNull-desc","branch-opcode","branch-target","env","trace-owner","trace-name","trace-desc","trace-index","array-read","error-class","message","null-args","exception-owner","exception-name","exception-desc","pop","self-store"};
            for(int mutation=0;mutation<labels.length;mutation++){var node=read(original);var m=method(node,name,descriptor);var ops=code(m);int at=d.read();
                switch(mutation){
                    case 0->((VarInsnNode)ops.get(at)).var=0;
                    case 1->m.instructions.set(ops.get(at+1),new InsnNode(Opcodes.POP));
                    case 2->((MethodInsnNode)ops.get(at+2)).owner="php/runtime/memory/ObjectMemory";
                    case 3->((MethodInsnNode)ops.get(at+2)).name="isNotNull";
                    case 4->((MethodInsnNode)ops.get(at+2)).desc="()I";
                    case 5->((JumpInsnNode)ops.get(at+3)).setOpcode(Opcodes.IFNE);
                    case 6->((JumpInsnNode)ops.get(at+3)).label=((JumpInsnNode)ops.get(r.helper()-5)).label;
                    case 7->((VarInsnNode)ops.get(at+4)).var=2;
                    case 8->((FieldInsnNode)ops.get(at+5)).owner="foreign/Receiver";
                    case 9->((FieldInsnNode)ops.get(at+5)).name="$MEM";
                    case 10->((FieldInsnNode)ops.get(at+5)).desc="[Lphp/runtime/Memory;";
                    case 11->m.instructions.set(ops.get(at+6),new InsnNode(Opcodes.ICONST_M1));
                    case 12->m.instructions.set(ops.get(at+7),new InsnNode(Opcodes.POP));
                    case 13->((LdcInsnNode)ops.get(at+8)).cst=Type.getType("Lphp/runtime/lang/exception/BaseException;");
                    case 14->((LdcInsnNode)ops.get(at+9)).cst="foreign diagnostic";
                    case 15->m.instructions.set(ops.get(at+10),new InsnNode(Opcodes.ICONST_0));
                    case 16->((MethodInsnNode)ops.get(at+11)).owner="foreign/Environment";
                    case 17->((MethodInsnNode)ops.get(at+11)).name="error";
                    case 18->((MethodInsnNode)ops.get(at+11)).desc="()V";
                    case 19->m.instructions.set(ops.get(at+12),new InsnNode(Opcodes.ARETURN));
                    default->m.instructions.set(ops.get(at),new VarInsnNode(Opcodes.ASTORE,r.local()));
                }rejected(write(node),name,descriptor,"self-discard-"+labels[mutation],accepted);
            }assertTrue("Accepted self escape/diagnostic mutations: "+accepted,accepted.isEmpty());
        });
        modes("echo 'd';",(original,name,descriptor,entity,env)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);assertTrue(plan.lifecycle());assertTrue(plan.lifecycleReceivers().isEmpty());var node=read(original);var m=method(node,name,descriptor);tail(m,plan,false,List.of());var accepted=new ArrayList<String>();rejected(write(node),name,descriptor,"literal-unmarked-entry",accepted);assertTrue(accepted.toString(),accepted.isEmpty());
        });
    }
    private static LabelNode labelBefore(MethodNode method,AbstractInsnNode instruction){var label=new LabelNode();method.instructions.insertBefore(instruction,label);return label;}
    @Test public void externalControlFlowCannotEnterReceiverOrDiscardAuthority()throws Throwable {
        modes("$this;echo 'd';",(original,name,descriptor,entity,env)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);var r=plan.lifecycleReceivers().getFirst();var d=plan.lifecycleDiscards().getFirst();var accepted=new ArrayList<String>();
            // Insert after all recorded lifecycle ordinals; matching switch twin executes the same next instruction.
            for(int variant=0;variant<3;variant++)for(int target:new int[]{r.start()+1,r.read(),r.fallback(),r.fallback()+2,d.read()+1,d.read()+4,d.pop()}){
                var node=read(original);var m=method(node,name,descriptor);var ops=code(m);var next=ops.get(d.pop()+1);var safe=labelBefore(m,next);var bad=labelBefore(m,ops.get(target));
                if(variant==0){m.instructions.insertBefore(next,new JumpInsnNode(Opcodes.GOTO,safe));}
                else{m.instructions.insertBefore(next,new InsnNode(Opcodes.ICONST_0));m.instructions.insertBefore(next,variant==1?new TableSwitchInsnNode(0,0,safe,safe):new LookupSwitchInsnNode(safe,new int[]{0},new LabelNode[]{safe}));}
                byte[] twin=write(node);assertTrue("Actual safe external branch twin",PhpRuntimeOwnership.readRuntimePlan(twin,name,descriptor).unsupported().isEmpty());
                AbstractInsnNode branch=next.getPrevious();if(branch instanceof JumpInsnNode jump)jump.label=bad;else if(branch instanceof TableSwitchInsnNode jump)jump.labels.set(0,bad);else ((LookupSwitchInsnNode)branch).labels.set(0,bad);
                rejected(write(node),name,descriptor,"external-"+variant+"-target-"+target,accepted);
            }
            // Duplicate an existing cleanup handler as the unchanged twin, then redirect only its handler label.
            for(int target:new int[]{r.read(),r.fallback(),d.read()+4}){
                var node=read(original);var m=method(node,name,descriptor);var ops=code(m);assertFalse(m.tryCatchBlocks.isEmpty());var old=m.tryCatchBlocks.getFirst();var extra=new TryCatchBlockNode(old.start,old.end,old.handler,old.type);m.tryCatchBlocks.add(extra);
                assertTrue(PhpRuntimeOwnership.readRuntimePlan(write(node),name,descriptor).unsupported().isEmpty());extra.handler=labelBefore(m,ops.get(target));rejected(write(node),name,descriptor,"external-handler-"+target,accepted);
            }
            assertTrue("Accepted lifecycle control-flow bypass: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void changedLoadedBytesRejectBeforeProtocolOrBody()throws Throwable {
        modes("$this;echo 'd';",(original,name,descriptor,entity,env)->{
            var domain=new PhpOwnershipDomain();var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter();try{
                int[] validations={0};authority.registerMethod(entity,original,(p,m,b)->validations[0]++);assertEquals(1,validations[0]);assertNull(authority.currentFrame());
                var node=read(original);node.sourceDebug="different-defined-bytes";byte[] changed=write(node);assertTrue(PhpRuntimeOwnership.readRuntimePlan(changed,name,descriptor).lifecycle());
                int[] changedValidations={0};try{authority.registerMethod(entity,changed,(p,m,b)->changedValidations[0]++);fail("Accepted different actual loaded bytecode");}catch(IllegalArgumentException expected){}
                assertEquals("Reject before arbitrary host protocol",0,changedValidations[0]);assertEquals(0,domain.graph().liveNodes());assertNull(authority.currentFrame());
            }finally{try{authority.close();}finally{execution.close();}}
        });
    }
}
