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
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** NULL must be the actual declared scalar EVAL consumed by the original callback's physical GLOBAL STORE. */
public class PhpRuntimeNullGlobalStoreMetadataTest extends JvmCompilerCase {
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
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void emptySchema17CannotMasqueradeAsActualSchema16Metadata()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,"class EmptyNullSchema {function __destruct(){echo 'd';}}",dump);
                module.include(env);
                var entity=env.fetchClass("EmptyNullSchema",true,PhpLanguageProfile.PHP_8_5);
                var actual=entity.methodDestruct.getNativeMethod();
                String name=actual.getName(),desc=PhpOwnershipExecution.descriptor(actual);
                byte[] original=entity.getData();assertEquals(Collections.emptyList(),PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported());
                var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();
                boolean changed=false;
                for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw) {
                    assertEquals(16,java.nio.ByteBuffer.wrap(raw.bytes).getInt());
                    byte[] data=Arrays.copyOf(raw.bytes,raw.bytes.length+4);java.nio.ByteBuffer.wrap(data).putInt(17);
                    method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(data));changed=true;
                }
                assertTrue(changed);byte[] altered=write(node);
                assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
            }finally{env.doFinal();}
        }
    }
    @Test public void originalNullStoreRequiresItsExactFrameProducerValueModeAndTarget()throws Throwable {
        String source=null;
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_warning_storage_owners.tsv")) {
            assertNotNull(input);
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R"))
                if(line.startsWith("global_copy_replaced\t"))source=new String(Base64.getDecoder().decode(line.split("\\t",-1)[1]),StandardCharsets.UTF_8).substring(5);
        }
        assertNotNull(source);
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,(strict?"declare(strict_types=1);":"")+source,dump);var closure=module.getClosures().iterator().next();var target=closure.findMethod("__invoke");
                String name=target.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(target.getNativeMethod());byte[] original=closure.getData();
                assertEquals(Collections.emptyList(),PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported());
                for(int mutation=0;mutation<9;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    int at=-1;for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof MethodInsnNode call&&call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("globalNull")){assertEquals(-1,at);at=i;}
                    assertTrue(at>=4);
                    if(mutation==0)((VarInsnNode)ops.get(at-2)).var+=1;
                    else if(mutation==1)method.instructions.set(ops.get(at-1),new LdcInsnNode(((Number)((LdcInsnNode)ops.get(at-1)).cst).intValue()+1));
                    else if(mutation==2)((MethodInsnNode)ops.get(at)).name="globalScalar";
                    else if(mutation==3)((VarInsnNode)ops.get(at+1)).var+=1;
                    else if(mutation==4)method.instructions.set(ops.get(at+2),new LdcInsnNode(((Number)((LdcInsnNode)ops.get(at+2)).cst).intValue()+1));
                    else if(mutation==5)method.instructions.set(ops.get(at+3),new InsnNode(Opcodes.ICONST_0));
                    else if(mutation==6)method.instructions.set(ops.get(at+4),new InsnNode(Opcodes.ICONST_1));
                    else if(mutation==7)method.instructions.set(ops.get(at-4),new LdcInsnNode("different"));
                    else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw)method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(Arrays.copyOf(raw.bytes,raw.bytes.length-1)));
                    byte[] changed=write(node);assertThrows("mutation="+mutation+" debug="+debug+" dump="+dump,IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
}
