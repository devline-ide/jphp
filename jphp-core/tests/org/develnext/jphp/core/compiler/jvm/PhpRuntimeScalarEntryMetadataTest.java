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
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** A literal ENTRY cannot substitute another Frame, producer, numeric value, target, or publisher. */
public class PhpRuntimeScalarEntryMetadataTest extends JvmCompilerCase {
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
    @Test public void emptySchema18CannotMasqueradeAsActualSchema17Metadata()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,"$GLOBALS['copy']=null;",dump);var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);byte[] original=module.getData();
                assertEquals(Collections.emptyList(),PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported());
                var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();boolean changed=false;
                for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw) {
                    assertEquals(17,ByteBuffer.wrap(raw.bytes).getInt());byte[] data=Arrays.copyOf(raw.bytes,raw.bytes.length+4);ByteBuffer.wrap(data).putInt(18);
                    method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(data));changed=true;
                }
                assertTrue(changed);byte[] altered=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
            }finally{env.doFinal();}
        }
    }
    @Test public void originalMarkerStoreRequiresItsExactLiteralAndPhysicalEntry()throws Throwable {
        List<String> originals=new ArrayList<>();
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_warning_storage_owners.tsv")) {
            assertNotNull(input);Set<String> names=Set.of("copy_then_mutate_original","mutate_original_without_copy","copy_mutate_then_unset_copy");
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                var fields=line.split("\\t",-1);if(names.contains(fields[0]))originals.add(new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8).substring(5));
            }
        }
        assertEquals(3,originals.size());
        for(String source:originals)for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var module=compile(env,(strict?"declare(strict_types=1);":"")+source,dump);var closure=module.getClosures().iterator().next();var target=closure.findMethod("__invoke");
                String name=target.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(target.getNativeMethod());byte[] original=closure.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertEquals(Collections.emptyList(),plan.unsupported());assertEquals(1,plan.entryScalars().size());assertEquals(9,plan.entryScalars().get(0).value());
                for(int mutation=0;mutation<10;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    int at=plan.entryScalars().get(0).helper();assertEquals("entryScalar",((MethodInsnNode)ops.get(at)).name);
                    if(mutation==0)((VarInsnNode)ops.get(at-3)).var+=1;
                    else if(mutation==1)method.instructions.set(ops.get(at-2),new LdcInsnNode(((Number)((LdcInsnNode)ops.get(at-2)).cst).intValue()+1));
                    else if(mutation==2)method.instructions.set(ops.get(at-1),new LdcInsnNode(10L));
                    else if(mutation==3)((MethodInsnNode)ops.get(at)).name="globalScalar";
                    else if(mutation==4)((VarInsnNode)ops.get(at+1)).var+=1;
                    else if(mutation==5)method.instructions.set(ops.get(at+2),new LdcInsnNode(((Number)((LdcInsnNode)ops.get(at+2)).cst).intValue()+1));
                    else if(mutation==6)((MethodInsnNode)ops.get(at+3)).name="assign";
                    else if(mutation==7) {
                        var entry=plan.arrayEntries().stream().filter(e->e.producer()==plan.entryScalars().get(0).id()).findFirst().orElseThrow();((VarInsnNode)ops.get(entry.factory()-8)).var+=1;
                    } else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw) {
                        byte[] data=mutation==8?Arrays.copyOf(raw.bytes,raw.bytes.length-1):raw.bytes.clone();if(mutation==9)ByteBuffer.wrap(data).putLong(data.length-12,10L);
                        method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(data));
                    }
                    byte[] altered=write(node);assertThrows("mutation="+mutation+" debug="+debug+" dump="+dump,IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
}
