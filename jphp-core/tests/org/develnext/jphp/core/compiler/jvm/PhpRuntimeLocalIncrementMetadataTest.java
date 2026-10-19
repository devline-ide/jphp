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

/** Local unary opcodes require their exact addressed operands before body effects. */
public class PhpRuntimeLocalIncrementMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(data);}
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    private static void replaceInt(MethodNode method,AbstractInsnNode old,int value){method.instructions.set(old,new LdcInsnNode(value));}
    @Test public void localOpcodeRequiresItsActualFrameTargetOperationEnvironmentAndResult()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var module=compile(env,"$a=9;$flag=false;if($flag){echo 'unexpected';}$b=$a++;echo 'effect';",dump);
                var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);byte[] original=module.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertEquals(1,plan.localIncrements().size());var site=plan.localIncrements().get(0);
                for(int mutation=0;mutation<14;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);int at=site.helper();
                    if(mutation==0)((VarInsnNode)ops.get(at-10)).var++;
                    else if(mutation==1)replaceInt(method,ops.get(at-9),site.id()+1);
                    else if(mutation==2)replaceInt(method,ops.get(at-8),site.root()+1);
                    else if(mutation==3)((VarInsnNode)ops.get(at-7)).var++;
                    else if(mutation==4)replaceInt(method,ops.get(at-6),site.decrement()?0:1);
                    else if(mutation==5)replaceInt(method,ops.get(at-5),site.post()?0:1);
                    else if(mutation==6)((VarInsnNode)ops.get(at-4)).var++;
                    else if(mutation==7)((FieldInsnNode)ops.get(at-3)).name="$OTHER";
                    else if(mutation==8)replaceInt(method,ops.get(at-2),-1);
                    else if(mutation==9)((MethodInsnNode)ops.get(at)).name="arrayIncrement";
                    else if(mutation==10) {
                        var branch=ops.stream().filter(i->i.getOpcode()==Opcodes.IFEQ||i.getOpcode()==Opcodes.IFNE).map(i->(JumpInsnNode)i).findFirst().orElseThrow();
                        var destination=new LabelNode();method.instructions.insertBefore(ops.get(at-7),destination);branch.label=destination;
                    } else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw) {
                        byte[] bytes=mutation==11?Arrays.copyOf(raw.bytes,raw.bytes.length-1):raw.bytes.clone();
                        if(mutation==12)ByteBuffer.wrap(bytes).putInt(bytes.length-8,site.helper()+1);
                        if(mutation==13)ByteBuffer.wrap(bytes).putInt(bytes.length-18,site.id()+1);
                        method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(bytes));
                    }
                    byte[] altered=write(node);assertThrows("local increment mutation="+mutation,IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
                    assertEquals(0,output.size());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void emptySchema21CannotUpgradeAValidSchema20Body()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,"$v=9;$GLOBALS['copy']=(object)['p'=>&$v];",dump);var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);byte[] original=module.getData();
                assertTrue(PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported().isEmpty());var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();boolean changed=false;
                for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw){assertEquals(20,ByteBuffer.wrap(raw.bytes).getInt());byte[] bytes=Arrays.copyOf(raw.bytes,raw.bytes.length+4);ByteBuffer.wrap(bytes).putInt(21);method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(bytes));changed=true;}
                assertTrue(changed);byte[] altered=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
            }finally{env.doFinal();}
        }
    }
}
