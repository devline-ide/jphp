package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Named FUNCTION transport binds acquisition, source CV, SEND and callee argument import. */
public class PhpRuntimeNamedFunctionMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(data);}
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    @Test public void namedPacketAndReferenceImportCannotClaimDifferentActualOperands()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("function consume(&$v){}$x=5;consume($x);return 7;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();var fetch=PhpRuntimeOwnership.readRuntimePlan(original,name,desc).functionCalls().getFirst();
                for(int mutation=0;mutation<9;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    if(mutation==0)((VarInsnNode)ops.get(fetch.read())).var+=1;
                    else if(mutation==1)method.instructions.set(ops.get(fetch.factory()-2),new LdcInsnNode("different"));
                    else if(mutation==2)((MethodInsnNode)ops.get(fetch.send())).name="invokeToEval";
                    else if(mutation==3)method.instructions.set(ops.get(fetch.invoke()-1),new LdcInsnNode(fetch.id()+1));
                    else if(mutation==4)((VarInsnNode)ops.get(fetch.factory()-4)).var+=1;
                    else if(mutation==5)method.instructions.set(ops.get(fetch.send()-1),new LdcInsnNode(fetch.local()+1));
                    else if(mutation==6)((JumpInsnNode)ops.get(fetch.start()-1)).setOpcode(Opcodes.IFNONNULL);
                    else if(mutation==7)((VarInsnNode)ops.get(fetch.invoke()-2)).var+=1;
                    else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw)method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(Arrays.copyOf(raw.bytes,raw.bytes.length-1)));
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
                var function=module.getFunctions().iterator().next();String targetName=function.getNativeMethod().getName(),targetDesc=PhpOwnershipExecution.descriptor(function.getNativeMethod());byte[] target=function.getData();assertNotNull(PhpRuntimeOwnership.readRuntimePlan(target,targetName,targetDesc));
                for(int mutation=0;mutation<4;mutation++) {
                    var node=read(target);var method=node.methods.stream().filter(m->m.name.equals(targetName)&&m.desc.equals(targetDesc)).findFirst().orElseThrow();var ops=code(method);int at=-1;
                    for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof MethodInsnNode call&&call.owner.equals("php/runtime/PhpRuntimeOwnership$Frame")&&call.name.equals("importArgument"))at=i;assertTrue(at>=5);
                    if(mutation==0)method.instructions.set(ops.get(at-1),new InsnNode(Opcodes.ICONST_0));
                    else if(mutation==1)method.instructions.set(ops.get(at-4),new InsnNode(Opcodes.ICONST_1));
                    else if(mutation==2)((VarInsnNode)ops.get(at-2)).var+=1;
                    else ((VarInsnNode)ops.get(at-5)).var+=1;
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,targetName,targetDesc));
                }
            }finally{env.doFinal();}
        }
    }
}
