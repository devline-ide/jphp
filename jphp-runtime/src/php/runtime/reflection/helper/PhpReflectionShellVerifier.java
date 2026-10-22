package php.runtime.reflection.helper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import php.runtime.env.Environment;
import php.runtime.ext.core.reflection.Reflection;
import php.runtime.ext.core.reflection.ReflectionClass;
import php.runtime.ext.core.reflection.ReflectionObject;
import php.runtime.ext.core.reflection.ReflectionProperty;
import php.runtime.reflection.ClassEntity;

/** Exact metadata-bearing Reflection shells, separate from the field-free native-shell protocol. */
public final class PhpReflectionShellVerifier {
    private PhpReflectionShellVerifier() {}
    public static boolean supports(Class<?> type) {
        return type==ReflectionClass.class||type==ReflectionObject.class||type==ReflectionProperty.class;
    }
    public record Proof(Constructor<?> constructor,List<String> physicalClasses) {}
    private static final ClassValue<Proof> PROOFS=new ClassValue<>() {
        @Override protected Proof computeValue(Class<?> type) {
            try {return inspect(type);}catch(ReflectiveOperationException|IOException failure) {
                throw new IllegalArgumentException("Reflection shell lacks its exact physical constructor/metadata proof",failure);
            }
        }
    };
    public static Proof require(ClassEntity entity) {
        if(entity==null||!entity.isInternal()||!supports(entity.getNativeClass()))
            throw new IllegalArgumentException("Unsupported native Reflection allocation");
        return PROOFS.get(entity.getNativeClass());
    }
    public static Proof require(Class<?> type) {
        if(!supports(type))throw new IllegalArgumentException("Unsupported native Reflection receiver");
        return PROOFS.get(type);
    }
    private static Proof inspect(Class<?> type)throws ReflectiveOperationException,IOException {
        if(!supports(type))throw new IllegalArgumentException("Foreign Reflection shell");
        var names=new ArrayList<String>();
        for(Class<?> current=type;current!=php.runtime.lang.BaseObject.class;current=current.getSuperclass()) {
            if(current!=ReflectionClass.class&&current!=ReflectionObject.class&&current!=ReflectionProperty.class&&current!=Reflection.class)
                throw new IOException("Reflection constructor crosses an unsupported parent");
            ClassNode node=new ClassNode();
            try(var input=current.getResourceAsStream('/'+current.getName().replace('.','/')+".class")) {
                if(input==null)throw new IOException("Missing actual Reflection class resource");
                new ClassReader(input.readAllBytes()).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            }
            if(!node.name.equals(current.getName().replace('.','/'))||!node.superName.equals(current.getSuperclass().getName().replace('.','/')))
                throw new IOException("Foreign Reflection class hierarchy");
            for(FieldNode field:node.fields) {
                if((field.access&Opcodes.ACC_STATIC)!=0) {
                    if((field.access&Opcodes.ACC_FINAL)==0||field.value==null||!java.util.Set.of("Z","B","C","S","I","J","F","D","Ljava/lang/String;").contains(field.desc))
                        throw new IOException("Reflection shell has an unproved static holder");
                    continue;
                }
                boolean entity=current==ReflectionClass.class&&field.name.equals("entity")&&field.desc.equals("Lphp/runtime/reflection/ClassEntity;")
                        ||current==ReflectionProperty.class&&field.name.equals("entity")&&field.desc.equals("Lphp/runtime/reflection/PropertyEntity;");
                boolean access=current==ReflectionProperty.class&&field.name.equals("hackAccess")&&field.desc.equals("Z");
                if(field.access!=Opcodes.ACC_PROTECTED||!entity&&!access)
                    throw new IOException("Reflection shell has an unaccounted instance holder");
            }
            MethodNode constructor=null;
            for(MethodNode method:node.methods) {
                if(method.name.equals("<clinit>"))throw new IOException("Reflection shell has an effectful class initializer");
                if(method.name.equals("<init>")&&method.desc.equals("(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;)V")) {
                    if(constructor!=null||method.access!=Opcodes.ACC_PUBLIC)throw new IOException("Reflection constructor is not exact");
                    constructor=method;
                }
            }
            if(constructor==null||!constructor.tryCatchBlocks.isEmpty())throw new IOException("Missing straight-line Reflection constructor");
            var ops=new ArrayList<AbstractInsnNode>();for(var op:constructor.instructions)if(op.getOpcode()>=0)ops.add(op);
            int expected=current==ReflectionProperty.class?8:5;
            if(ops.size()!=expected)throw new IOException("Reflection constructor has additional effects");
            for(int i=0;i<3;i++)if(!(ops.get(i)instanceof VarInsnNode load)||load.getOpcode()!=Opcodes.ALOAD||load.var!=i)
                throw new IOException("Reflection constructor changed its actual arguments");
            if(!(ops.get(3)instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKESPECIAL||call.itf||!call.owner.equals(node.superName)||!call.name.equals("<init>")||!call.desc.equals(constructor.desc))
                throw new IOException("Reflection constructor calls another body");
            if(current==ReflectionProperty.class) {
                if(!(ops.get(4)instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0||ops.get(5).getOpcode()!=Opcodes.ICONST_0
                        ||!(ops.get(6)instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.PUTFIELD||!field.owner.equals(node.name)||!field.name.equals("hackAccess")||!field.desc.equals("Z"))
                    throw new IOException("Reflection access metadata has another initializer");
            }
            if(ops.get(expected-1).getOpcode()!=Opcodes.RETURN)throw new IOException("Reflection constructor has no exact return");
            names.add(current.getName());
        }
        return new Proof(type.getConstructor(Environment.class,ClassEntity.class),List.copyOf(names));
    }
}
