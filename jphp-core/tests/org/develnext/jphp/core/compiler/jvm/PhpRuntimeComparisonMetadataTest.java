package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Comparison admission binds the selected native member and rejects unknown callbacks before effects. */
public class PhpRuntimeComparisonMetadataTest extends JvmCompilerCase {
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
        throw new AssertionError("Missing actual call "+owner+"."+name);
    }
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    private void rejectBeforeEffects(String source,String reason)throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var domain=new PhpOwnershipDomain();var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                var module=compile(env,(strict?"declare(strict_types=1);":"")+source,dump);
                var target=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),target.getName(),PhpOwnershipExecution.descriptor(target));
                assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
                int[] argumentChecks={0};
                for(var dependency:plan.nativeMembers()) {
                    var owner=Class.forName(dependency.owner().replace('/','.'));
                    var selected=Arrays.stream(owner.getDeclaredMethods()).filter(m->m.getName().equals(dependency.name())&&PhpOwnershipExecution.descriptor(m).equals(dependency.descriptor())).findFirst().orElseThrow();
                    authority.registerNative(selected,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(selected,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> actual){argumentChecks[0]++;assertEquals(2,actual.size());}
                    });
                }
                var clazz=module.getClasses().iterator().next();boolean initialized=clazz.isRuntimeStateInitialized(env);
                authority.registerModule(module,module.getData(),(p,m,b)->{});
                try(var result=authority.include(module)){fail("Unproved callback reached module BODY");}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(reason));}
                assertEquals(0,argumentChecks[0]);assertEquals(initialized,clazz.isRuntimeStateInitialized(env));
                env.getDefaultBuffer().flush();assertEquals("",output.toString(StandardCharsets.UTF_8));assertEquals(0,domain.graph().liveNodes());
                }finally{env.doFinal();authority.close();domain.collectCycles();}
            }
        }
    }
    @Test public void knownStringConversionRejectsBeforeEarlyEchoAndNewAllocation()throws Throwable {
        rejectBeforeEffects("class C{function __toString(){echo 'hook;';return '1';}}echo 'early;';$a=new C;$r=$a==1;","complete callback protocol");
    }
    @Test public void literalNewRequiresItsAlreadyRegisteredDestructorBody()throws Throwable {
        rejectBeforeEffects("class C{function __destruct(){echo 'd;';}}echo 'early;';$a=new C;","registered nonowning body protocol");
    }
    @Test public void comparisonFactoryBodyBorrowAndBooleanResultRequireExactBytecode()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var module=compile(env,"$a=2;$b=3;echo 'early;';$r=$a==$b;echo 'late;';",dump);
                var target=module.getNativeMethod();String name=target.getName(),desc=PhpOwnershipExecution.descriptor(target);byte[] original=module.getData();
                var plan=PhpRuntimeOwnership.readRuntimePlan(original,name,desc);assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());assertEquals(1,plan.nativeMembers().size());
                var dependency=plan.nativeMembers().getFirst();assertEquals("php/runtime/PhpComparisonCalls",dependency.owner());assertEquals("equal",dependency.name());assertEquals(2,dependency.argc());
                for(int mutation=0;mutation<11;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    int factory=call(ops,"php/runtime/PhpRuntimeOwnership","nativeCall"),body=call(ops,dependency.owner(),dependency.name());
                    int borrow=call(ops,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend"),box=call(ops,"php/runtime/memory/TrueMemory","valueOf"),result=call(ops,"php/runtime/PhpRuntimeOwnership","nativeResult");
                    if(mutation==0)method.instructions.set(ops.get(factory-5),new LdcInsnNode(dependency.id()+1));
                    else if(mutation==1)method.instructions.set(ops.get(factory-4),new LdcInsnNode(Type.getObjectType("php/runtime/PhpComparison")));
                    else if(mutation==2)method.instructions.set(ops.get(factory-3),new LdcInsnNode("notEqual"));
                    else if(mutation==3)method.instructions.set(ops.get(factory-1),new LdcInsnNode(1));
                    else if(mutation==4)((MethodInsnNode)ops.get(body)).name="notEqual";
                    else if(mutation==5)((MethodInsnNode)ops.get(body)).owner="php/runtime/PhpComparison";
                    else if(mutation==6)method.instructions.set(ops.get(borrow-5),new LdcInsnNode(2));
                    else if(mutation==7)method.instructions.set(ops.get(borrow-1),new InsnNode(Opcodes.ICONST_1));
                    else if(mutation==8)((MethodInsnNode)ops.get(box)).name="otherValueOf";
                    else if(mutation==9)method.instructions.set(ops.get(result-1),new LdcInsnNode(dependency.id()+1));
                    else ((MethodInsnNode)ops.get(result)).name="evalHolder";
                    byte[] altered=write(node);assertThrows("comparison mutation="+mutation,IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(altered,name,desc));
                    assertEquals(0,output.size());
                }
            }finally{env.doFinal();}
        }
    }
}
