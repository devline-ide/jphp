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

/** FIELD reference proof is bound to actual NEW, CV publication and generated operands. */
public class PhpRuntimePropertyReferenceMetadataTest extends JvmCompilerCase {
    private static final String SOURCE="class MetadataField {public int $v=1;} $a=new MetadataField;$r=&$a->v;unset($a);$r='free';return $r;";
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(data);}
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static int publication(List<AbstractInsnNode> ops,int producer){
        for(int i=4;i<ops.size();i++)if(ops.get(i) instanceof MethodInsnNode call
                &&call.owner.equals("php/runtime/PhpRuntimeOwnership$Frame")&&call.name.equals("assignLocalFromEval")
                &&ops.get(i-4) instanceof LdcInsnNode id&&Integer.valueOf(producer).equals(id.cst))return i;
        throw new AssertionError("Missing actual producer-to-CV publication: "+producer);
    }
    @FunctionalInterface private interface Check {void run(byte[] original,String name,String descriptor)throws Throwable;}
    private void modes(Check check)throws Throwable {modes(SOURCE,check);}
    private void modes(String source,Check check)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var out=new ByteArrayOutputStream();dumper.save(module,out);module=dumper.load(new ByteArrayInputStream(out.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                check.run(module.getData(),module.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(module.getNativeMethod()));
                assertEquals("Metadata checks must not run PHP effects","",env.getDefaultBuffer().getContents().toString());
            }finally{env.doFinal();}
        }
    }
    private static MethodNode method(ClassNode node,String name,String descriptor){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static void rejected(byte[] changed,String name,String descriptor,String label,List<String> accepted)throws Throwable {
        try{PhpRuntimeOwnership.readRuntimePlan(changed,name,descriptor);accepted.add(label);}catch(IOException expected){}
    }
    @Test public void fieldOperandsAndResultAreBoundToActualCode()throws Throwable {
        modes((original,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);
            assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertEquals(1,plan.propertyReferences().size());
            var field=plan.propertyReferences().getFirst();assertEquals(field.helper()-6,field.read());
            assertTrue(plan.producers().stream().anyMatch(p->p.id()==field.id()&&p.kind().equals("PROPERTY_REF_RESULT")&&p.consumers().contains("CV")));
            var accepted=new ArrayList<String>();
            String[] labels={"frame","site","root-id","actual-root-read","key","environment","helper-owner","helper-name","helper-descriptor","result-consumer"};
            for(int mutation=0;mutation<labels.length;mutation++){
                var node=read(original);var m=method(node,name,descriptor);var ops=code(m);int at=field.helper();
                if(mutation==0)((VarInsnNode)ops.get(at-9)).var++;
                else if(mutation==1)m.instructions.set(ops.get(at-8),new LdcInsnNode(field.id()+1));
                else if(mutation==2)m.instructions.set(ops.get(at-7),new LdcInsnNode(field.root()+1));
                else if(mutation==3)((VarInsnNode)ops.get(field.read())).var++;
                else if(mutation==4)m.instructions.set(ops.get(at-5),new LdcInsnNode("other"));
                else if(mutation==5)((VarInsnNode)ops.get(at-4)).var++;
                else if(mutation==6)((MethodInsnNode)ops.get(at)).owner="php/runtime/invoke/ObjectInvokeHelper";
                else if(mutation==7)((MethodInsnNode)ops.get(at)).name="globalRead";
                else if(mutation==8)((MethodInsnNode)ops.get(at)).desc="(Lphp/runtime/Memory;)Lphp/runtime/Memory;";
                else {int result=publication(ops,field.id());m.instructions.set(ops.get(result-4),new LdcInsnNode(field.id()+1));}
                rejected(write(node),name,descriptor,labels[mutation],accepted);
            }
            assertTrue("Accepted FIELD byte mutations: "+accepted,accepted.isEmpty());
        });
    }
    private static byte[] fieldTail(PhpRuntimeOwnership.RuntimePlan plan,int delta)throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(plan.objectCvs().size());
        for(var cv:plan.objectCvs()){out.writeInt(cv.producer());out.writeInt(cv.local());out.writeUTF(cv.owner());out.writeInt(cv.publication()+delta);}
        out.writeInt(plan.propertyReferences().size());
        for(var field:plan.propertyReferences()){
            out.writeInt(field.id());out.writeInt(field.root());out.writeInt(field.producer());out.writeUTF(field.key());
            out.writeInt(field.helper()+delta);out.writeInt(field.read()+delta);out.writeInt(field.resultLocal());out.writeInt(field.resultPublication()+delta);
        }
        return bytes.toByteArray();
    }
    private static void rebaseFieldTail(MethodNode method,PhpRuntimeOwnership.RuntimePlan plan,int delta)throws IOException {
        assertEquals("Only the exact single-source/single-FIELD fixture is rebased",1,plan.objectCvs().size());
        assertEquals(1,plan.propertyReferences().size());byte[] old=fieldTail(plan,0),next=fieldTail(plan,delta);
        assertEquals(old.length,next.length);boolean replaced=false;
        for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw){
            int start=raw.bytes.length-old.length;assertTrue(start>=0);
            assertArrayEquals("Rebase must match the complete existing schema14 tail",old,Arrays.copyOfRange(raw.bytes,start,raw.bytes.length));
            byte[] payload=raw.bytes.clone();System.arraycopy(next,0,payload,start,next.length);method.attrs.set(i,new RawAttribute(payload));replaced=true;
        }
        assertTrue("Missing exact schema14 method attribute",replaced);
    }
    @Test public void fieldRequiresTheActualNewCvPublicationAndAllocationGuard()throws Throwable {
        modes((original,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);assertEquals(1,plan.objectNews().size());var allocation=plan.objectNews().getFirst();
            var accepted=new ArrayList<String>();String[] labels={"new-frame","new-site","new-class","new-helper","new-guard","cv-producer","cv-local","cv-raw-read","cv-mode","cv-guard","cv-conversion-cast"};
            for(int mutation=0;mutation<labels.length;mutation++){
                var node=read(original);var m=method(node,name,descriptor);var ops=code(m);int at=allocation.helper(),cv=publication(ops,allocation.id());
                if(mutation==0)((VarInsnNode)ops.get(at-3)).var++;
                else if(mutation==1)m.instructions.set(ops.get(at-2),new LdcInsnNode(allocation.id()+1));
                else if(mutation==2)m.instructions.set(ops.get(at-1),new LdcInsnNode("OtherClass"));
                else if(mutation==3)((MethodInsnNode)ops.get(at)).name="evalHolder";
                else if(mutation==4)((JumpInsnNode)ops.get(at-4)).label=((JumpInsnNode)ops.get(at+1)).label;
                else if(mutation==5)m.instructions.set(ops.get(cv-4),new LdcInsnNode(allocation.id()+1));
                else if(mutation==6)m.instructions.set(ops.get(cv-3),new LdcInsnNode(plan.propertyReferences().getFirst().root()+1));
                else if(mutation==7)((VarInsnNode)ops.get(cv-2)).var++;
                else if(mutation==8)m.instructions.set(ops.get(cv-1),new InsnNode(Opcodes.ICONST_1));
                else if(mutation==9)((JumpInsnNode)ops.get(cv-6)).label=((JumpInsnNode)ops.get(cv+1)).label;
                else {
                    // Keep a valid twin: the added conversion is accepted with the same rebased metadata.
                    var conversion=new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"php/runtime/Memory","toImmutable","()Lphp/runtime/Memory;",false);
                    int conversionAt=allocation.end();
                    if(ops.get(conversionAt).getOpcode()==Opcodes.DUP) {
                        assertTrue("Complete existing typed-created observer must precede the CV conversion",conversionAt+8<ops.size());
                        assertTrue(ops.get(conversionAt+7) instanceof MethodInsnNode);
                        var observer=(MethodInsnNode)ops.get(conversionAt+7);
                        assertEquals(Opcodes.INVOKESTATIC,observer.getOpcode());
                        assertEquals("php/runtime/PhpTypedSourceLifetime",observer.owner);assertEquals("created",observer.name);
                        assertEquals("(Lphp/runtime/Memory;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;II)V",observer.desc);
                        // Preserve the issuer's exact fresh-EVAL boundary; convert its unchanged result afterward.
                        conversionAt+=8;
                    }
                    m.instructions.insertBefore(ops.get(conversionAt),conversion);
                    rebaseFieldTail(m,plan,1);
                    var twin=PhpRuntimeOwnership.readRuntimePlan(write(node),name,descriptor);
                    assertTrue("Valid immutable-conversion twin must remain admitted",twin.unsupported().isEmpty());
                    m.instructions.set(conversion,new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/String"));
                }
                rejected(write(node),name,descriptor,labels[mutation],accepted);
            }
            assertTrue("Accepted NEW/CV lineage byte mutations: "+accepted,accepted.isEmpty());
        });
    }
    @Test public void receiverMutationMustMatchTheActualSourceLocal()throws Throwable {
        for(String marker:new String[]{"7","new MetadataField"}) {
        String source=SOURCE.replace("$r=&$a->v","$marker="+marker+";unset($marker);$r=&$a->v");
        modes(source,(original,name,descriptor)->{
            var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,descriptor);
            assertTrue("Unchanged marker assignment/unset must be admitted: "+plan.unsupported(),plan.unsupported().isEmpty());
            var field=plan.propertyReferences().getFirst();var origin=plan.objectCvs().getFirst();
            var declared=PhpOwnershipExecution.readFramePlan(original,name,descriptor);
            int receiverSlot=declared.locals().stream().filter(local->local.id()==field.root()).findFirst().orElseThrow().jvmSlot();
            var accepted=new ArrayList<String>();
            for(int mutation=0;mutation<3;mutation++){
                var node=read(original);var m=method(node,name,descriptor);var ops=code(m);int assignment=-1,unset=-1,unsetLocal=-1,unsetCarrier=-1;
                for(int i=origin.publication()+1;i<field.read();i++)if(ops.get(i) instanceof MethodInsnNode call){
                    if(call.owner.equals("php/runtime/PhpRuntimeOwnership$Frame")
                            &&call.name.equals(marker.equals("7")?"assignLocal":"assignLocalFromEval")
                            &&call.desc.equals(marker.equals("7")?"(ILphp/runtime/Memory;I)Lphp/runtime/Memory;":"(IILphp/runtime/Memory;I)Lphp/runtime/Memory;"))assignment=i;
                    if(call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("unsetLocal")
                            &&call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;)V")) {
                        unset=i;unsetLocal=i-3;unsetCarrier=i-2;
                    }else if(call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals("php/runtime/PhpPrimitiveCellRuntime")&&call.name.equals("unset")
                            &&call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)V")) {
                        assertTrue("Complete existing CELL unset operands",i>=8);
                        assertEquals(Type.getObjectType(node.name),((LdcInsnNode)ops.get(i-4)).cst);
                        assertEquals(name,((LdcInsnNode)ops.get(i-3)).cst);assertEquals(descriptor,((LdcInsnNode)ops.get(i-2)).cst);
                        assertTrue("Recorded existing CELL site",((LdcInsnNode)ops.get(i-1)).cst instanceof Integer site&&site>=0);
                        unset=i;unsetLocal=i-7;unsetCarrier=i-6;
                    }
                }
                assertTrue("Missing actual intervening marker publication",assignment>=0);
                assertTrue("Missing actual intervening marker unset",unset>=0);
                if(mutation==0)m.instructions.set(ops.get(assignment-3),new LdcInsnNode(field.root()));
                else {
                    m.instructions.set(ops.get(unsetLocal),new LdcInsnNode(field.root()));
                    if(mutation==2)((VarInsnNode)ops.get(unsetCarrier)).var=receiverSlot;
                }
                rejected(write(node),name,descriptor,"receiver-mutation-"+mutation,accepted);
            }
            assertTrue("Accepted actual receiver mutation before FIELD: "+accepted,accepted.isEmpty());
        });
        }
    }
    @Test public void malformedPropertyTailRejectsWithoutRunningTheBody()throws Throwable {
        modes((original,name,descriptor)->{
            var accepted=new ArrayList<String>();
            for(int mutation=0;mutation<3;mutation++){
                var node=read(original);var m=method(node,name,descriptor);
                for(int i=0;i<m.attrs.size();i++)if(m.attrs.get(i) instanceof RawAttribute raw){
                    byte[] payload=mutation==2?Arrays.copyOf(raw.bytes,raw.bytes.length+1):Arrays.copyOf(raw.bytes,raw.bytes.length-(mutation==0?1:4));
                    m.attrs.set(i,new RawAttribute(payload));
                }
                rejected(write(node),name,descriptor,"tail-"+mutation,accepted);
            }
            assertTrue("Accepted malformed FIELD tails: "+accepted,accepted.isEmpty());
        });
    }
}
