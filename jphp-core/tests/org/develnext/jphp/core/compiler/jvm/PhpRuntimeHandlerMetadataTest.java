package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Rejects changed physical schema6 instructions before admission, with schema5 decoding preserved. */
public class PhpRuntimeHandlerMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){
            byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(data);
        }
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}

    @Test public void exactHandlerSendSourceAndSchemaTailCannotBeForged()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("set_error_handler(function(){return true;});return 7;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertEquals(1,plan.handlers().size());var handler=plan.handlers().getFirst();
                for(int mutation=0;mutation<4;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var instructions=code(method);
                    if(mutation==0)method.instructions.set(instructions.get(handler.send()-3),new LdcInsnNode(handler.producer()+1));
                    else if(mutation==1)((MethodInsnNode)instructions.get(handler.send())).name="nativeSend";
                    for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw) {
                        byte[] payload=raw.bytes.clone();if(mutation==2)payload=Arrays.copyOf(payload,payload.length-1);if(mutation==3)payload[3]=5;
                        method.attrs.set(i,new org.develnext.jphp.core.compiler.jvm.statement.OwnershipFrameCompiler.RuntimeFrameAttribute(payload));
                    }
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }

    @Test public void exactArraySiteAndActualFrameOperandsRejectChanges()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("$a=[];$a[]=[1];return 7;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();
                var site=PhpRuntimeOwnership.readRuntimePlan(original,name,desc).arrayEntries().getFirst();
                for(int mutation=0;mutation<4;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var instructions=code(method);
                    if(mutation==0)method.instructions.set(instructions.get(site.factory()-1),new LdcInsnNode(site.local()+1));
                    else if(mutation==1)((VarInsnNode)instructions.get(site.assignment()-2)).var+=1;
                    else if(mutation==2)((MethodInsnNode)instructions.get(site.step())).name="index";
                    else ((VarInsnNode)instructions.get(site.factory()-8)).var+=1;
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
}
