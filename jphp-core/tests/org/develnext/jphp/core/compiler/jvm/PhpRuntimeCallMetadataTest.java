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

/** Closed ordinary CALL transport preserves the physical native schema3 proof. */
public class PhpRuntimeCallMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] payload;
        RawAttribute(byte[] payload){super("JPHP_Runtime_Frame_v1");this.payload=payload;}
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){
            byte[] bytes=new byte[length];for(int i=0;i<length;i++)bytes[i]=(byte)reader.readByte(offset+i);return new RawAttribute(bytes);
        }
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    @Test public void nativeSchema3ProofSurvivesSchema4TailAndTruncationRejects()throws Throwable {
        for(boolean debug:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var context=new Context("function native_metadata($n){return count([$n]);}return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                var function=env.fetchFunction("native_metadata");String name=function.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(function.getNativeMethod());
                byte[] original=function.getData();var baseline=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertTrue(baseline.unsupported().toString(),baseline.unsupported().isEmpty());assertEquals(1,baseline.nativeMembers().size());
                var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();
                byte[] payload=((RawAttribute)method.attrs.stream().filter(a->a instanceof RawAttribute).findFirst().orElseThrow()).payload;
                assertEquals(3,new DataInputStream(new ByteArrayInputStream(payload)).readInt());
                var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(4);out.write(payload,4,payload.length-4);out.writeInt(0);out.writeInt(0);out.writeInt(0);
                byte[] complete=bytes.toByteArray();
                for(int cut:new int[]{0,1,4,8}){
                    byte[] changed=cut==0?complete:Arrays.copyOf(complete,complete.length-cut);
                    method.attrs.removeIf(a->a.type.equals("JPHP_Runtime_Frame_v1"));method.attrs.add(new OwnershipFrameCompiler.RuntimeFrameAttribute(changed));byte[] actual=write(node);
                    if(cut==0){var upgraded=PhpRuntimeOwnership.readRuntimePlan(actual,name,desc);assertEquals(baseline.nativeMembers(),upgraded.nativeMembers());assertEquals(baseline.producers(),upgraded.producers());assertTrue(upgraded.calls().isEmpty());}
                    else assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(actual,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void cloneSourcePublicationGuardAndSchemaEpochAreBoundToActualCode()throws Throwable {
        for(boolean debug:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var context=new Context("$f=function(){return 9;};$r=$f();$g=clone $f;$s=$g();return $s;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertEquals(1,plan.clones().size());assertEquals(2,plan.calls().size());var clone=plan.clones().getFirst();
                for(int mutation=0;mutation<4;mutation++){
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var instructions=code(method);
                    if(mutation==0)((VarInsnNode)instructions.get(clone.start()-2)).var+=1;
                    else if(mutation==1)method.instructions.set(instructions.get(clone.helper()-4),new LdcInsnNode(clone.local()+1));
                    else if(mutation==2)((MethodInsnNode)instructions.get(clone.helper())).name="cloneRuntime";
                    for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw){
                        byte[] payload=raw.payload;
                        if(mutation==3){
                            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);out.writeInt(clone.id());out.writeInt(clone.local());out.writeInt(clone.producer());out.writeUTF(clone.closure());out.writeInt(clone.helper());out.writeInt(clone.start());out.writeInt(clone.fallback());out.writeInt(clone.end());
                            payload=Arrays.copyOf(payload,payload.length-bytes.size());payload[3]=4;
                        }
                        method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(payload));
                    }
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void valueReturnUsedAsReferenceRejectsBeforeCallerOutputAndAllocation()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            try{
                var context=new Context("echo 'effect';$f=function(){return 9;};$ref=&$f();unset($ref);return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var bytes=new ByteArrayOutputStream();var dumper=new php.runtime.loader.dump.ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);var domain=new PhpOwnershipDomain();
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()){
                    var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),module.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(module.getNativeMethod()));
                    assertEquals(1,plan.calls().size());assertTrue(plan.calls().getFirst().requiresCellResult());
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,bytes)->{});
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});var actual=module;
                    var failure=assertThrows(IllegalArgumentException.class,()->authority.include(actual));assertTrue(failure.getMessage(),failure.getMessage().contains("warning protocol"));
                    assertEquals("",env.getDefaultBuffer().getContents().toString());assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void literalCastFrameAndCallGuardAreBoundToActualCode()throws Throwable {
        for(boolean debug:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try{
                var context=new Context("$f=function(){return 9;};$r=$f();$f=null;return $r;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertEquals(1,plan.literals().size());assertEquals(1,plan.calls().size());
                for(int mutation=0;mutation<3;mutation++){
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var instructions=code(method);
                    if(mutation==0)((TypeInsnNode)instructions.get(plan.literals().getFirst().lookup()+1)).desc="java/lang/String";
                    else if(mutation==1)((VarInsnNode)instructions.get(plan.literals().getFirst().lookup()-5)).var+=1;
                    else ((VarInsnNode)instructions.get(plan.calls().getFirst().start()-2)).var+=1;
                    for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw)method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(raw.payload));
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
}
