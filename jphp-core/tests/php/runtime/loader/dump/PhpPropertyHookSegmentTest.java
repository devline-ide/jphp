package php.runtime.loader.dump;

import java.io.*;
import java.util.*;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.develnext.jphp.core.compiler.jvm.*;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.io.*;
import php.runtime.reflection.*;
import static org.junit.Assert.*;

/** Real compiler-derived nested hook method bytes; malformed payloads never publish a partial property. */
public class PhpPropertyHookSegmentTest extends JvmCompilerCase {
    private static List<AbstractInsnNode> physicalOpcodes(MethodNode method) {
        List<AbstractInsnNode> code=new ArrayList<>();for(AbstractInsnNode instruction:method.instructions)if(instruction.getOpcode()>=0)code.add(instruction);
        return code;
    }
    /** Keep actual instruction objects while indexing the stable semantic prefix across ABI29/30. */
    private static List<AbstractInsnNode> hookPrefix(MethodNode method) {
        List<AbstractInsnNode> code=physicalOpcodes(method);
        if(code.size()>6 && code.get(6) instanceof MethodInsnNode enter && enter.owner.equals("php/runtime/PhpPropertyWriteContext")
                && enter.name.equals("enter") && enter.desc.equals("(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;ZI)Lphp/runtime/PhpPropertyWriteContext$Frame;")) {
            assertTrue(code.get(5) instanceof LdcInsnNode literal && literal.cst instanceof Integer id && (id==0||id==1));
            code.remove(5);
        }
        return code;
    }
    private static LdcInsnNode numericProfileLiteral(MethodNode method) {
        List<AbstractInsnNode> code=physicalOpcodes(method);
        assertTrue("Current ABI emits an explicit numeric profile",code.get(5) instanceof LdcInsnNode && code.get(6) instanceof MethodInsnNode enter
                && enter.owner.equals("php/runtime/PhpPropertyWriteContext") && enter.name.equals("enter") && enter.desc.contains(";ZI)"));
        return (LdcInsnNode)code.get(5);
    }
    private ClassEntity compile(Environment env)throws Throwable {return compile(env,false);}
    private ClassEntity compile(Environment env,boolean strict)throws Throwable {
        Context context=new Context((strict?"declare(strict_types=1);":"")+"class SegmentHook {public int $x=1 {set=>$value;get=>$this->x;}}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        for(ClassEntity klass:module.getDeferredClasses())if(klass.getName().equals("SegmentHook"))return klass;
        ClassEntity klass=module.findClass("SegmentHook");assertNotNull(klass);return klass;
    }
    private PropertyEntity target(Context context){PropertyEntity p=new PropertyEntity(context);p.setName("x");p.setDefault(true);p.setDefaultValue(Memory.CONST_INT_1);return p;}
    private void reject(byte[] bytes,MethodDumper methods)throws IOException {
        PropertyEntity p=target(methods.context);
        try {PropertyDeclarationDumper.load(p,bytes,methods,true,true);fail("Corrupt nested hook payload accepted");}
        catch(IOException expected){assertFalse(p.isFinal());assertFalse(p.isPromoted());assertNull(p.getDeclaredType());assertNull(p.getPhpHooks());assertSame(Memory.CONST_INT_1,p.getDefaultValue());assertTrue(p.isDefault());}
    }
    @Test public void validCompilerDerivedSegmentRebindsEveryHookToActualFreshProperty()throws Throwable {
        var env=new Environment(newScope());try {ClassEntity klass=compile(env);PropertyEntity source=klass.findProperty("x");var methods=new MethodDumper(klass.getContext(),env,true);
            byte[] bytes=PropertyDeclarationDumper.save(source,methods,true);PropertyEntity copy=target(klass.getContext());PropertyDeclarationDumper.load(copy,bytes,methods,true,true);
            assertEquals(2,copy.getPhpHooks().declarationOrder().size());assertFalse(copy.getPhpHooks().isVirtual());assertEquals("int",copy.getDeclaredType().getErrorSignature());
            for(var hook:copy.getPhpHooks().declarationOrder()){assertSame(copy,hook.declaration());assertNull(hook.method().getClazz());assertNull(hook.method().getNativeMethod());}
            try{PropertyDeclarationDumper.load(target(klass.getContext()),bytes,methods,true,false);fail("ABI21 nested reader accepted hook segment");}catch(DumpException expected){assertEquals("Property hooks require compiler ABI22",expected.getMessage());}
        }finally{env.doFinal();}
    }
    @Test public void truncationTrailingBytesInvalidFlagsAndKindsLeavePropertyUnchanged()throws Throwable {
        var env=new Environment(newScope());try {ClassEntity klass=compile(env);var methods=new MethodDumper(klass.getContext(),env,true);byte[] valid=PropertyDeclarationDumper.save(klass.findProperty("x"),methods,true);
            for(int i=1;i<valid.length;i++)reject(Arrays.copyOf(valid,i),methods);reject(Arrays.copyOf(valid,valid.length+1),methods);
            byte[] flag=valid.clone();flag[4]=2;reject(flag,methods);
            var in=new DumpInputStream(new ByteArrayInputStream(valid),true);in.readInt();in.readBoolean();in.readBoolean();in.readRawData();in.readBoolean();in.readInt();int kind=valid.length-in.available();
            byte[] invalidKind=valid.clone();invalidKind[kind]=3;reject(invalidKind,methods);
            for(int size:new int[]{-1,Integer.MAX_VALUE}){byte[] huge=valid.clone();java.nio.ByteBuffer.wrap(huge,kind+1,4).putInt(size);reject(huge,methods);}
        }finally{env.doFinal();}
    }
    @Test public void oversizedNestedParameterCountRejectsBeforeAllocatingAnyParameterArray()throws Throwable {
        var env=new Environment(newScope());try {ClassEntity klass=compile(env);var methods=new MethodDumper(klass.getContext(),env,true);byte[] valid=PropertyDeclarationDumper.save(klass.findProperty("x"),methods,true);
            var in=new DumpInputStream(new ByteArrayInputStream(valid),true);in.readInt();in.readBoolean();in.readBoolean();in.readRawData();in.readBoolean();in.readInt();in.readByte();int payloadLength=in.readInt();int payload=valid.length-in.available();
            byte[] method=Arrays.copyOfRange(valid,payload,payload+payloadLength);var m=new DumpInputStream(new ByteArrayInputStream(method),true);
            m.readUTF();for(int i=0;i<5;i++)m.readBoolean();m.readMemory();m.readBoolean();m.readBoolean();m.readHintType();m.readName();m.readBoolean();m.readBoolean();m.readModifier();m.readName();m.readName();m.readTrace(klass.getContext());
            int countOffset=method.length-m.available();byte[] huge=valid.clone();java.nio.ByteBuffer.wrap(huge,payload+countOffset,4).putInt(Integer.MAX_VALUE);reject(huge,methods);
        }finally{env.doFinal();}
    }
    @Test public void actualColdClassRejectsForeignBackingHeaderAndWrongJvmDescriptor()throws Throwable {
        var env=new Environment(newScope());try {ClassEntity klass=compile(env);byte[] original=klass.getData();
            for(boolean descriptor:new boolean[]{false,true}) {
                ClassNode node=new ClassNode();new ClassReader(original).accept(node,0);String member=klass.findProperty("x").getPhpHooks().get().method().getInternalName();
                MethodNode method=node.methods.stream().filter(m->m.name.equals(member)).findFirst().orElseThrow();
                if(descriptor)method.desc="(Lphp/runtime/env/Environment;)Lphp/runtime/Memory;";
                else {boolean changed=false;for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof LdcInsnNode literal&&"x".equals(literal.cst)){literal.cst="foreign";changed=true;break;}assertTrue(changed);}
                var writer=new ClassWriter(0);node.accept(writer);klass.setData(writer.toByteArray());
                try {var bytes=new ByteArrayOutputStream();var dumper=new ClassDumper(klass.getContext(),klass.getModule(),env,true);dumper.save(klass,bytes);dumper.setDeferLinking(true);
                    try {dumper.load(new ByteArrayInputStream(bytes.toByteArray()));fail("Cold physical hook mismatch accepted");}catch(DumpException expected){assertTrue(expected.getMessage().contains("Physical hook"));}
                }finally{klass.setData(original);}
            }
        }finally{env.doFinal();}
    }

    @Test public void mandatoryCompilerProloguesAreAcceptedAndCorruptPrefixesAreRejectedBothStrictAndDebugModes()throws Throwable {
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);var env=new Environment(scope);
            try {ClassEntity klass=compile(env,strict);PropertyEntity property=klass.findProperty("x");byte[] original=klass.getData();
                PhpPropertyHookDumper.validateDecodedClass(klass,List.of(property),klass.getOwnedMethods());
                // The historical three-argument entry remains a valid header as well.
                ClassNode oldNode=new ClassNode();new ClassReader(original).accept(oldNode,0);
                MethodNode oldMethod=oldNode.methods.stream().filter(m->m.name.equals(property.getPhpHooks().get().method().getInternalName())).findFirst().orElseThrow();
                List<AbstractInsnNode> oldCode=physicalOpcodes(oldMethod);
                if(oldCode.get(6) instanceof MethodInsnNode enter && enter.desc.contains(";ZI)")) {
                    oldMethod.instructions.remove(oldCode.get(5));enter.desc=enter.desc.replace(";ZI)",";Z)");
                    PhpPropertyHookDumper.validateBodyHeader(oldNode,oldMethod,physicalOpcodes(oldMethod),property,property.getPhpHooks().get(),strict);
                }
                for(int variant=0;variant<28;variant++) {
                    ClassNode node=new ClassNode();new ClassReader(original).accept(node,0);
                    String member=property.getPhpHooks().get().method().getInternalName();MethodNode method=node.methods.stream().filter(m->m.name.equals(member)).findFirst().orElseThrow();
                    List<AbstractInsnNode> code=hookPrefix(method);
                    assertTrue("Real compiler has the mandatory prefix before hook binding",code.size()>=26);
                    switch(variant) {
                        case 0 -> ((VarInsnNode)code.get(0)).var=0;
                        case 1 -> ((FieldInsnNode)code.get(1)).owner="foreign/Owner";
                        case 2 -> ((FieldInsnNode)code.get(1)).name="$FOREIGN";
                        case 3 -> ((FieldInsnNode)code.get(1)).desc="[Ljava/lang/Object;";
                        case 4 -> ((LdcInsnNode)code.get(2)).cst=Integer.MAX_VALUE;
                        case 5 -> method.instructions.set(code.get(4),new InsnNode(strict?Opcodes.ICONST_0:Opcodes.ICONST_1));
                        case 6 -> ((MethodInsnNode)code.get(5)).name="foreignEnter";
                        case 7 -> ((VarInsnNode)code.get(6)).var=1;
                        case 8 -> ((LdcInsnNode)code.get(8)).cst=Type.getObjectType("foreign/Owner");
                        case 9 -> ((LdcInsnNode)code.get(9)).cst="foreignMember";
                        case 10 -> ((LdcInsnNode)code.get(10)).cst="()V";
                        case 11 -> ((MethodInsnNode)code.get(11)).owner="php/runtime/PhpRuntimeOwnership";
                        case 12 -> ((VarInsnNode)code.get(12)).var=((VarInsnNode)code.get(6)).var;
                        case 13 -> ((VarInsnNode)code.get(13)).var=0;
                        case 14 -> ((LdcInsnNode)code.get(14)).cst=Type.getObjectType("foreign/Owner");
                        case 15 -> ((LdcInsnNode)code.get(15)).cst="foreignMember";
                        case 16 -> ((MethodInsnNode)code.get(17)).desc="()V";
                        case 17 -> ((MethodInsnNode)code.get(17)).itf=true;
                        case 18 -> ((VarInsnNode)code.get(18)).var=method.maxLocals;
                        case 19 -> ((VarInsnNode)code.get(25)).var=((VarInsnNode)code.get(18)).var;
                        case 20 -> method.instructions.insertBefore(code.get(0),new InsnNode(Opcodes.NOP));
                        case 21 -> method.instructions.remove(code.get(7));
                        case 22 -> ((MethodInsnNode)code.get(11)).setOpcode(Opcodes.INVOKEVIRTUAL);
                        case 23 -> node.fields.stream().filter(f->f.name.equals("$TRC")).findFirst().orElseThrow().access=Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC;
                        case 24 -> {MethodNode clinit=node.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();AbstractInsnNode first=clinit.instructions.getFirst();while(first.getOpcode()<0)first=first.getNext();clinit.instructions.set(first,new InsnNode(Opcodes.ICONST_0));}
                        case 25 -> ((LdcInsnNode)code.get(2)).cst=-1;
                        case 26 -> numericProfileLiteral(method).cst=99;
                        case 27 -> numericProfileLiteral(method).cst="fast";
                        default -> throw new AssertionError("Uncovered prefix mutation");
                    }
                    var writer=new ClassWriter(0);node.accept(writer);klass.setData(writer.toByteArray());
                    try {var bytes=new ByteArrayOutputStream();var dumper=new ClassDumper(klass.getContext(),klass.getModule(),env,true);dumper.save(klass,bytes);dumper.setDeferLinking(true);
                        try {dumper.load(new ByteArrayInputStream(bytes.toByteArray()));fail("Corrupt mandatory prefix accepted variant="+variant+" strict="+strict+" debug="+debug);}
                        catch(DumpException expected){assertTrue(expected.getMessage(),expected.getMessage().startsWith("Physical hook"));}
                    }finally{klass.setData(original);}
                }
            }finally{env.doFinal();}
        }
    }
}
