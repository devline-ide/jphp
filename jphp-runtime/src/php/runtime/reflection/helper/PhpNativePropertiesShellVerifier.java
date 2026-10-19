package php.runtime.reflection.helper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Physical native shell proof. It admits no PHP body, callback, instance field or class initializer. */
public final class PhpNativePropertiesShellVerifier {
    private PhpNativePropertiesShellVerifier(){}
    public static byte[] require(Constructor<?> constructor)throws IOException {
        Class<?> owner=constructor.getDeclaringClass();byte[] bytes;
        try(var input=owner.getResourceAsStream('/'+owner.getName().replace('.','/')+".class")) {
            if(input==null)throw new IOException("Native shell lacks its physical class resource");bytes=input.readAllBytes();
        }
        require(bytes,owner.getName());return bytes;
    }
    public static void require(byte[] bytes,String binaryName)throws IOException {
        ClassNode type=new ClassNode();new ClassReader(bytes).accept(type,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        if(!type.name.equals(binaryName.replace('.','/'))||!type.superName.equals("php/runtime/lang/BaseObject")
                ||(type.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))!=0)
            throw new IOException("Native properties shell has a foreign or effectful parent");
        for(FieldNode field:type.fields)if((field.access&(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL))!=(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL)
                ||field.value==null||!java.util.Set.of("Z","B","C","S","I","J","F","D","Ljava/lang/String;").contains(field.desc))
            throw new IOException("Native properties shell has additional heap fields");
        MethodNode constructor=null;
        for(MethodNode method:type.methods) {
            if(method.name.equals("<clinit>"))throw new IOException("Native properties shell has an effectful initializer");
            if(method.name.equals("<init>")) {
                if(constructor!=null||!method.desc.equals("(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;)V")
                        ||method.access!=Opcodes.ACC_PUBLIC)throw new IOException("Native properties shell constructor is not exact");
                constructor=method;
            }
        }
        if(constructor==null||!constructor.tryCatchBlocks.isEmpty()||constructor.maxStack!=3||constructor.maxLocals!=3)
            throw new IOException("Native properties shell lacks its closed constructor proof");
        var operations=new java.util.ArrayList<AbstractInsnNode>();
        for(AbstractInsnNode op:constructor.instructions)if(op.getOpcode()>=0)operations.add(op);
        if(operations.size()!=5)throw new IOException("Native properties shell constructor has additional instructions");
        for(int i=0;i<3;i++)if(!(operations.get(i) instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=i)
            throw new IOException("Native properties shell constructor changed its actual arguments");
        if(!(operations.get(3) instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESPECIAL||call.itf
                ||!call.owner.equals(type.superName)||!call.name.equals("<init>")||!call.desc.equals(constructor.desc)
                ||operations.get(4).getOpcode()!=Opcodes.RETURN)
            throw new IOException("Native properties shell constructor has effects");
    }
}
