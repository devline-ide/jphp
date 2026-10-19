package php.runtime.invoke;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.annotation.PhpNativeResultBridge;
import php.runtime.annotation.Reflection;

/** Cached physical proof of false/true wrappers forwarding to one private native implementation. */
public final class PhpNativeResultUse {
    private PhpNativeResultUse() {}
    public static final class Proof {
        private final Method semantic, physical;
        private final boolean discarded;
        private final String classSHA256;
        private Proof(Method semantic,Method physical,boolean discarded,String hash) {
            this.semantic=semantic;this.physical=physical;this.discarded=discarded;classSHA256=hash;
        }
        public Method semantic() {return semantic;}
        public Method physical() {return physical;}
        public boolean discarded() {return discarded;}
        public String classSHA256() {return classSHA256;}
    }
    private record Pair(Proof used,Proof discarded) {}
    private static final ClassValue<Map<Method,Pair>> PAIRS=new ClassValue<>() {
        protected Map<Method,Pair> computeValue(Class<?> owner) {return new java.util.concurrent.ConcurrentHashMap<>();}
    };
    public static Method select(Method semantic,boolean discarded) {return proof(semantic,discarded).physical();}
    public static Proof require(Method semantic,String owner,String name,String descriptor,boolean discarded) {
        Proof proof=proof(semantic,discarded);Method actual=proof.physical();
        if(proof.discarded()!=discarded||!owner.equals(Type.getInternalName(actual.getDeclaringClass()))
                ||!name.equals(actual.getName())||!descriptor.equals(Type.getMethodDescriptor(actual)))
            throw new IllegalArgumentException("Native result use differs from its exact physical bridge");
        return proof;
    }
    private static Proof proof(Method semantic,boolean discarded) {
        Pair pair=PAIRS.get(semantic.getDeclaringClass()).computeIfAbsent(semantic,PhpNativeResultUse::prepare);
        return discarded&&pair.discarded()!=null?pair.discarded():pair.used();
    }
    private static Pair prepare(Method semantic) {
        PhpNativeResultBridge contract=semantic.getAnnotation(PhpNativeResultBridge.class);
        if(contract==null)return new Pair(new Proof(semantic,semantic,false,null),null);
        try {
            Class<?> owner=semantic.getDeclaringClass();
            Method bridge=owner.getDeclaredMethod(contract.discarded(),semantic.getParameterTypes());
            byte[] bytes=physicalBytes(owner);
            verify(bytes,semantic,bridge);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Pair(new Proof(semantic,semantic,false,hash),new Proof(semantic,bridge,true,hash));
        }catch(ReflectiveOperationException|IOException|java.security.NoSuchAlgorithmException|java.net.URISyntaxException failure) {
            throw new IllegalArgumentException("Native result bridge lacks its closed physical pair proof",failure);
        }
    }
    private static byte[] physicalBytes(Class<?> owner)throws IOException,java.net.URISyntaxException {
        var source=owner.getProtectionDomain().getCodeSource();
        if(source==null||!"file".equals(source.getLocation().getProtocol()))
            throw new IOException("Native result bridge has no exact local CodeSource");
        var origin=java.nio.file.Path.of(source.getLocation().toURI());
        String entry=owner.getName().replace('.','/')+".class";
        if(java.nio.file.Files.isDirectory(origin))
            return java.nio.file.Files.readAllBytes(origin.resolve(entry));
        try(var jar=new java.util.jar.JarFile(origin.toFile())) {
            var member=jar.getJarEntry(entry);
            if(member==null)throw new IOException("Native bridge is absent from its actual CodeSource");
            try(var input=jar.getInputStream(member)){return input.readAllBytes();}
        }
    }
    /** Mutation tests may verify bytes; those bytes never mint an invocation capability. */
    public static void verify(byte[] bytes,Method semantic,Method bridge)throws IOException {
        PhpNativeResultBridge contract=semantic.getAnnotation(PhpNativeResultBridge.class);
        int flags=Modifier.PUBLIC|Modifier.STATIC;
        if(contract==null||!contract.discarded().equals(bridge.getName())||semantic.equals(bridge)
                ||semantic.getDeclaringClass()!=bridge.getDeclaringClass()
                ||(semantic.getModifiers()&flags)!=flags||(bridge.getModifiers()&flags)!=flags
                ||semantic.getReturnType()!=Memory.class||bridge.getReturnType()!=Memory.class
                ||!Arrays.equals(semantic.getParameterTypes(),bridge.getParameterTypes())
                ||!Arrays.equals(semantic.getExceptionTypes(),bridge.getExceptionTypes())
                ||!bridge.isAnnotationPresent(Reflection.Ignore.class))
            throw new IOException("Foreign or exposed native result bridge");
        ClassNode owner=new ClassNode();new ClassReader(bytes).accept(owner,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        if(!owner.name.equals(Type.getInternalName(semantic.getDeclaringClass())))throw new IOException("Foreign native bridge class");
        String descriptor=Type.getMethodDescriptor(semantic);
        MethodNode used=member(owner,semantic.getName(),descriptor),ignored=member(owner,bridge.getName(),descriptor);
        metadata(used,semantic);metadata(ignored,bridge);
        List<AbstractInsnNode> a=operations(used),b=operations(ignored);
        if(a.size()!=b.size()||used.maxLocals!=ignored.maxLocals||used.maxStack!=ignored.maxStack)
            throw new IOException("Native bridge changes its frame shape");
        Type[] parameters=Type.getArgumentTypes(descriptor);int booleanAt=-1,argument=0,slot=0;
        for(int i=0;i<a.size()-2;i++) {
            AbstractInsnNode op=a.get(i),other=b.get(i);
            if(op.getOpcode()==Opcodes.ICONST_0&&other.getOpcode()==Opcodes.ICONST_1) {
                if(booleanAt>=0)throw new IOException("Duplicate result-use operand");booleanAt=i;continue;
            }
            if(!(op instanceof VarInsnNode load)||!(other instanceof VarInsnNode actual)||argument>=parameters.length
                    ||load.getOpcode()!=parameters[argument].getOpcode(Opcodes.ILOAD)
                    ||actual.getOpcode()!=load.getOpcode()||load.var!=slot||actual.var!=slot)
                throw new IOException("Native bridge does not forward its exact operands");
            slot+=parameters[argument++].getSize();
        }
        if(booleanAt<0||argument!=parameters.length||used.maxLocals!=slot||a.size()!=parameters.length+3
                ||!(a.get(a.size()-2) instanceof MethodInsnNode call)
                ||!(b.get(b.size()-2) instanceof MethodInsnNode actual)
                ||call.getOpcode()!=Opcodes.INVOKESTATIC||call.itf||!call.owner.equals(owner.name)
                ||actual.getOpcode()!=call.getOpcode()||actual.itf||!actual.owner.equals(call.owner)
                ||!actual.name.equals(call.name)||!actual.desc.equals(call.desc)
                ||a.getLast().getOpcode()!=Opcodes.ARETURN||b.getLast().getOpcode()!=Opcodes.ARETURN)
            throw new IOException("Native result pair changes its closed forwarding body");
        Type[] forwarded=Type.getArgumentTypes(call.desc);
        if(forwarded.length!=parameters.length+1||!Type.getReturnType(call.desc).equals(Type.getType(Memory.class)))
            throw new IOException("Result-use flag does not belong to the forwarded signature");
        for(int i=0,j=0;i<forwarded.length;i++) {
            if(i==booleanAt) {if(!forwarded[i].equals(Type.BOOLEAN_TYPE))throw new IOException("Result-use operand is not boolean");}
            else if(!forwarded[i].equals(parameters[j++]))throw new IOException("Forwarded argument type changed");
        }
        MethodNode target=member(owner,call.name,call.desc);
        if((target.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC))!=(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC)
                ||(target.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0)
            throw new IOException("Result wrappers do not share a private implementation");
    }
    /** Relevant physical metadata must describe these actual reflected native members exactly. */
    private static void metadata(MethodNode physical,Method actual)throws IOException {
        if(physical.access!=actual.getModifiers())
            throw new IOException("Native result wrapper changed its physical access");
        List<String> exceptions=physical.exceptions==null?List.of():physical.exceptions;
        List<String> declared=Arrays.stream(actual.getExceptionTypes()).map(Type::getInternalName).toList();
        if(!exceptions.equals(declared))
            throw new IOException("Native result wrapper changed its physical exceptions");
        AnnotationNode bridge=annotation(physical,Type.getDescriptor(PhpNativeResultBridge.class));
        PhpNativeResultBridge expected=actual.getAnnotation(PhpNativeResultBridge.class);
        if(expected==null) {
            if(bridge!=null)throw new IOException("Unexpected physical native result contract");
        }else if(bridge==null||bridge.values==null||bridge.values.size()!=2
                ||!"discarded".equals(bridge.values.get(0))||!expected.discarded().equals(bridge.values.get(1)))
            throw new IOException("Physical native result contract differs from its reflected declaration");
        AnnotationNode ignored=annotation(physical,Type.getDescriptor(Reflection.Ignore.class));
        if((ignored!=null)!=actual.isAnnotationPresent(Reflection.Ignore.class)
                ||ignored!=null&&ignored.values!=null&&!ignored.values.isEmpty())
            throw new IOException("Physical native Ignore marker differs from its reflected declaration");
    }
    private static AnnotationNode annotation(MethodNode method,String descriptor)throws IOException {
        AnnotationNode found=null;
        if(method.invisibleAnnotations!=null)for(AnnotationNode annotation:method.invisibleAnnotations)
            if(annotation.desc.equals(descriptor))
                throw new IOException("Native result metadata lost its runtime visibility");
        if(method.visibleAnnotations!=null)for(AnnotationNode annotation:method.visibleAnnotations)
            if(annotation.desc.equals(descriptor)) {
                if(found!=null)throw new IOException("Duplicate physical native result metadata");
                found=annotation;
            }
        return found;
    }
    private static MethodNode member(ClassNode owner,String name,String descriptor)throws IOException {
        MethodNode found=null;
        for(MethodNode method:owner.methods)if(method.name.equals(name)&&method.desc.equals(descriptor)) {
            if(found!=null)throw new IOException("Duplicate result member");found=method;
        }
        if(found==null)throw new IOException("Missing result member");return found;
    }
    private static List<AbstractInsnNode> operations(MethodNode method)throws IOException {
        if(!method.tryCatchBlocks.isEmpty()||(method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))!=0)
            throw new IOException("Result wrapper is not closed");
        List<AbstractInsnNode> result=new ArrayList<>();
        for(AbstractInsnNode op:method.instructions)if(op.getOpcode()>=0)result.add(op);return result;
    }
}
