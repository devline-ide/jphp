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

/** Named global admission binds the table import, actual source LOAD and physical keyed write. */
public class PhpRuntimeGlobalMetadataTest extends JvmCompilerCase {
    private static final class RawAttribute extends Attribute {
        final byte[] bytes;
        RawAttribute(byte[] bytes){super("JPHP_Runtime_Frame_v1");this.bytes=bytes;}
        protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(bytes,0,bytes.length);}
        protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RawAttribute(data);}
    }
    private static ClassNode read(byte[] bytes){var node=new ClassNode();new ClassReader(bytes).accept(node,new Attribute[]{new RawAttribute(new byte[0])},0);return node;}
    private static byte[] write(ClassNode node){var writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    @Test public void literalTableCandidateMustReachItsDeclaredActualCv()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("$GLOBALS['copy']=[];return 7;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();
                var imported=PhpRuntimeOwnership.readRuntimePlan(original,name,desc).globalImports().getFirst();
                for(int mutation=0;mutation<4;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    if(mutation==0)((VarInsnNode)ops.get(imported.end()+24)).var+=1;
                    else if(mutation==1)((VarInsnNode)ops.get(imported.end()+19)).var+=1;
                    else if(mutation==2)((MethodInsnNode)ops.get(imported.end()+26)).name="storeDirect";
                    else ((VarInsnNode)ops.get(imported.helper()-2)).var+=1;
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void capturedSourceLoadMustFeedTheExactReferenceRhsAndKey()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("$a=[];$f=function()use(&$a){$GLOBALS['alias']=&$a;};$f();return 7;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);var closure=module.getClosures().iterator().next();
                var target=closure.findMethod("__invoke");String name=target.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(target.getNativeMethod());byte[] original=closure.getData();
                var access=PhpRuntimeOwnership.readRuntimePlan(original,name,desc).globalAccesses().getFirst();assertTrue(access.read()>access.index());
                witness(debug,"original",original);
                for(int mutation=0;mutation<5;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    MethodInsnNode export=referenceExport(original,node,method,ops,access,name,desc);
                    if(mutation==0)((VarInsnNode)ops.get(access.read())).var+=1;
                    else if(mutation==1)method.instructions.set(export,new InsnNode(Opcodes.POP));
                    else if(mutation==2)export.name="valueOf";
                    else if(mutation==3)method.instructions.set(ops.get(access.index()-1),new LdcInsnNode("different"));
                    else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw)method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(Arrays.copyOf(raw.bytes,raw.bytes.length-1)));
                    byte[] changed=write(node);witness(debug,"mutation-"+mutation,changed);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
    /** Locate the actual attested RHS export, including the complete CELL metadata envelope. */
    private static MethodInsnNode referenceExport(byte[] original,ClassNode node,MethodNode method,List<AbstractInsnNode> ops,PhpRuntimeOwnership.GlobalAccess access,String name,String desc)throws IOException {
        assertEquals(1,access.mode());assertEquals("alias",access.key());assertTrue(access.sourceLocal()>=0);
        assertTrue(ops.get(access.read()) instanceof VarInsnNode load&&load.getOpcode()==Opcodes.ALOAD);
        MethodInsnNode found=null;int position=-1;
        for(int i=access.read()+1;i<access.assignment()-4;i++)if(ops.get(i) instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&!call.itf) {
            boolean plain=call.owner.equals("php/runtime/memory/ReferenceMemory")&&call.name.equals("exportReference")&&call.desc.equals("(Lphp/runtime/Memory;)Lphp/runtime/Memory;");
            boolean cell=call.owner.equals("php/runtime/PhpPrimitiveCellRuntime")&&call.name.equals("export")&&call.desc.equals("(Lphp/runtime/Memory;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/Memory;");
            if(plain||cell){assertNull("Multiple reference exports in the actual GLOBAL RHS",found);found=call;position=i;}
        }
        assertNotNull("Missing exact actual reference export in the GLOBAL RHS",found);
        if(found.owner.equals("php/runtime/memory/ReferenceMemory"))assertEquals(access.read()+1,position);
        else {
            int metadata=position-4;assertEquals(access.read()+1,metadata);
            assertTrue(ops.get(metadata) instanceof LdcInsnNode owner&&Type.getObjectType(node.name).equals(owner.cst));
            assertTrue(ops.get(metadata+1) instanceof LdcInsnNode member&&name.equals(member.cst));
            assertTrue(ops.get(metadata+2) instanceof LdcInsnNode descriptor&&desc.equals(descriptor.cst));
            assertTrue(ops.get(metadata+3) instanceof LdcInsnNode site&&site.cst instanceof Integer);
            int site=(Integer)((LdcInsnNode)ops.get(metadata+3)).cst;
            PhpPrimitiveCellPlan.validateExportSite(original,node.name,method.name,method.desc,site,access.sourceLocal());
        }
        return found;
    }
    private static void witness(boolean debug,String label,byte[] bytes)throws IOException {
        String directory=System.getProperty("jphp.global.metadata.witnesses");if(directory==null)return;
        var root=java.nio.file.Path.of(directory);java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.write(root.resolve("captured-global-debug-"+debug+"-"+label+".class"),bytes,java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    @Test public void globalReadResultRoleRootKeyAndHelperArePhysicalBeforeAdmission()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("$GLOBALS['x']=[];$r=$GLOBALS['x'];return $r;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();
                var fetch=PhpRuntimeOwnership.readRuntimePlan(original,name,desc).globalReads().getFirst();assertFalse(fetch.cell());
                for(int mutation=0;mutation<5;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    if(mutation==0)((VarInsnNode)ops.get(fetch.read())).var+=1;
                    else if(mutation==1)method.instructions.set(ops.get(fetch.helper()-6),new LdcInsnNode("different"));
                    else if(mutation==2)method.instructions.set(ops.get(fetch.helper()-5),new InsnNode(Opcodes.ICONST_1));
                    else if(mutation==3)((MethodInsnNode)ops.get(fetch.helper())).name="globalUnset";
                    else for(int i=0;i<method.attrs.size();i++)if(method.attrs.get(i) instanceof RawAttribute raw)method.attrs.set(i,new OwnershipFrameCompiler.RuntimeFrameAttribute(Arrays.copyOf(raw.bytes,raw.bytes.length-1)));
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void inactiveSessionReadRequiresTheActualNullGuardEnvironmentAndNativeJoin()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("$GLOBALS['x']=[];return count($GLOBALS['x']);");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                String name=module.getNativeMethod().getName(),desc=PhpOwnershipExecution.descriptor(module.getNativeMethod());byte[] original=module.getData();assertTrue(PhpRuntimeOwnership.readRuntimePlan(original,name,desc).unsupported().isEmpty());
                for(int mutation=0;mutation<4;mutation++) {
                    var node=read(original);var method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();var ops=code(method);
                    int entry=-1,factory=-1;for(int i=0;i<ops.size();i++)if(ops.get(i) instanceof MethodInsnNode call) {
                        if(call.owner.equals("php/runtime/PhpOwnershipExecution")&&call.name.equals("enterGenerated"))entry=i;
                        if(call.owner.equals("php/runtime/PhpRuntimeOwnership")&&call.name.equals("nativeCall"))factory=i;
                    }
                    assertTrue(entry>=4&&factory>=6);var jump=(JumpInsnNode)ops.get(factory-7);int guard=-1;
                    for(int i=0;i<factory-7;i++)if(ops.get(i) instanceof JumpInsnNode branch&&branch.getOpcode()==Opcodes.IFNULL) {
                        var next=branch.label.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();if(next==ops.get(factory-6))guard=i;
                    }
                    assertTrue(guard>=1);
                    if(mutation==0)((JumpInsnNode)ops.get(guard)).setOpcode(Opcodes.IFNONNULL);
                    else if(mutation==1)((VarInsnNode)ops.get(entry-4)).var+=1;
                    else if(mutation==2)jump.label=((JumpInsnNode)ops.get(guard)).label;
                    else ((VarInsnNode)ops.get(guard-1)).var+=1;
                    byte[] changed=write(node);assertThrows(IOException.class,()->PhpRuntimeOwnership.readRuntimePlan(changed,name,desc));
                }
            }finally{env.doFinal();}
        }
    }
}
