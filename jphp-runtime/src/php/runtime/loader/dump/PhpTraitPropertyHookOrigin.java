package php.runtime.loader.dump;

import java.io.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.reflection.support.PhpPropertyHooks;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.reflection.*;
import php.runtime.env.Environment;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.common.PhpClassNames;
import php.runtime.loader.dump.io.DumpException;
import java.security.MessageDigest;
import php.runtime.loader.RuntimeClassLoader;

/** Cold compiler copy of an exact hidden trait hook. Never enters the ordinary PHP method table. */
public final class PhpTraitPropertyHookOrigin {
    private static final String DESC="(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
    private static final String BIND="(Lphp/runtime/lang/IObject;Lphp/runtime/env/Environment;Ljava/lang/String;ILjava/lang/String;)Lphp/runtime/reflection/support/PhpPropertyHooks$Hook;";
    private static final String STATIC_SITES="JPHP_Trait_Static_Sites_v1";
    private static final String CELL="JPHP_Primitive_Cell_Plan_v1";
    private static final String FRAME="JPHP_Ownership_Frame_v1",RUNTIME="JPHP_Runtime_Frame_v1";
    private static final Set<String> PROTOCOLS=Set.of(FRAME,RUNTIME,CELL);
    public record TraceOrigin(String owner,int index) {}
    public static final String ATTRIBUTE="JPHP_Trait_Property_Hook_Origin_v1";
    private PhpTraitPropertyHookOrigin() {}
    public record Source(ClassNode physical,MethodNode method,PhpPropertyHooks.Hook hook,byte[] bytes,boolean strict,String protocolOwner,
                         TraceOrigin trace,int traceCapacity,PhpRuntimeOwnershipMode ownershipMode) {}
    private record Admission(boolean strict,TraceOrigin trace,int capacity,PhpRuntimeOwnershipMode ownershipMode) {}
    private record Origin(String module,String trait,String owner,String member,String hash,boolean strict,int sourceTrace,int targetTrace,
                          int mode,String traceOwner,String targetTemplate,String targetMember) {
        Origin(String module,String trait,String owner,String member,String hash,boolean strict,int sourceTrace,int targetTrace) {
            this(module,trait,owner,member,hash,strict,sourceTrace,targetTrace,1,owner,"","");
        }
    }
    public static ClassNode readClass(byte[] bytes) {
        var physical=new ClassNode();
        new ClassReader(bytes).accept(physical,new Attribute[]{
                new ProtocolAttribute(FRAME,new byte[0]),new ProtocolAttribute(RUNTIME,new byte[0]),
                new ProtocolAttribute(CELL,new byte[0]),new ProtocolAttribute(STATIC_SITES,new byte[0]),
                new OriginAttribute(new byte[0])},0);
        return physical;
    }
    /** Active execution checks the exact physical donor, never merely the consumer module mode. */
    public static void requireOwnershipAdmission(PhpPropertyHooks.Hook hook,Environment env) {
        var owner=hook.method().getClazz();
        php.runtime.PhpOwnershipExecution.requireOwnershipAdmission(owner.getModule());
        try {
            ClassNode physical=readClass(owner.getData());
            var matches=physical.methods.stream().filter(method->method.name.equals(hook.method().getInternalName())).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("Hook lacks its exact physical ownership source");
            Admission actual=admit(owner,physical,matches.getFirst(),hook.declaration(),hook,env,null,new HashSet<>());
            if(!actual.ownershipMode().supportsAdmission())throw new IllegalArgumentException("Physical OFF trait hook cannot enter ownership execution");
        }catch(IOException|LinkageError invalid) {
            throw new IllegalArgumentException("Hook ownership source cannot be admitted",invalid);
        }
    }
    public static Source source(PhpPropertyHooks.Hook hook,Environment env) {
        return source(hook,env,new HashSet<>());
    }


    private static Source source(PhpPropertyHooks.Hook hook,Environment env,Set<String> visited) {
        return source(hook,env,null,visited);
    }
    private static Source source(PhpPropertyHooks.Hook hook,Environment env,RuntimeGraph graph,Set<String> visited) {
        hook.validate();
        var donor=hook.method().getClazz();
        if(donor==null || !donor.isTrait() || PhpPropertyHooks.ownedMethod(donor,hook.method())!=hook)
            throw new IllegalStateException("Copied hook is not an exact owned trait declaration");
        byte[] bytes=graph!=null ? graph.bytes(donor) : donor.getNativeClass()==null ? donor.getData()
                : env==null ? null : env.getScope().getClassLoader().traitHookDefinition(donor);
        if(bytes==null || bytes.length==0)throw new IllegalStateException("Missing actual trait hook bytes");
        if(donor.getNativeClass()!=null) {
            var member=hook.method().getNativeMethod();
            if(member==null || member.getDeclaringClass()!=donor.getNativeClass() || !member.getName().equals(hook.method().getInternalName())
                    || member.getReturnType()!=php.runtime.Memory.class
                    || !Arrays.equals(member.getParameterTypes(),new Class<?>[]{Environment.class,php.runtime.Memory[].class}))
                throw new LinkageError("Hook metadata is not its exact already-defined native member");
        }
        ClassNode physical=readClass(bytes);
        String owner=donor.getNativeClass()==null?donor.getCompiledInternalName().replace('.','/'):donor.getNativeClass().getName().replace('.','/');
        if(!physical.name.equals(owner))throw new IllegalStateException("Trait hook physical class identity changed");
        List<MethodNode> methods=physical.methods.stream().filter(item->item.name.equals(hook.method().getInternalName())).toList();
        if(methods.size()!=1)throw new IllegalStateException("Missing or ambiguous exact trait hook JVM member");
        MethodNode actual=methods.get(0);
        if(!DESC.equals(actual.desc) || actual.access!=(Opcodes.ACC_PRIVATE|(hook.method().isFinal()?Opcodes.ACC_FINAL:0))
                || hook.method().isImmutable() || hook.method().getImmutableResult()!=null)
            throw new IllegalStateException("Trait hook physical callable contract changed");
        List<AbstractInsnNode> code=opcodes(actual);
        Admission admitted;
        try {
            admitted=admit(donor,physical,actual,hook.declaration(),hook,env,graph,visited);
            PhpPropertyHookDumper.validateBodyHeader(physical,actual,code,hook.declaration(),hook,admitted.strict(),
                    admitted.trace().owner(),admitted.trace().index(),admitted.capacity(),admitted.ownershipMode());
        }catch(IOException malformed){throw new IllegalStateException("Invalid actual donor hook protocol",malformed);}
        return new Source(physical,actual,hook,bytes.clone(),admitted.strict(),donor.getCompiledInternalName().replace('.','/'),
                admitted.trace(),admitted.capacity(),admitted.ownershipMode());
    }

    /** Only attested entry/CELL/binder operands and the header trace-table operand change; opcode ordinals stay intact. */
    public static TraceOrigin rebind(MethodNode node,Source source,String targetOwner,String targetMethod,int targetTrace) {
        return rebind(node,source,targetOwner,targetOwner,targetMethod,targetTrace,false);
    }
    private static TraceOrigin rebind(MethodNode node,Source source,String targetOwner,String targetProtocolTemplate,String targetMethod,int targetTrace,boolean retained) {
        PhpPropertyHooks.Hook hook=source.hook();
        String sourceOwner=source.physical().name;
        String sourceMethod=hook.method().getInternalName();
        targetOwner=targetOwner.replace('.','/');
        List<AbstractInsnNode> code=opcodes(node);
        List<Integer> before=code.stream().map(AbstractInsnNode::getOpcode).toList();
        boolean owned=source.ownershipMode().supportsAdmission();
        int body=owned?19:7;
        if(code.size()<body+7 || !load(code.get(0),1) || !(code.get(1) instanceof FieldInsnNode trace)
                || trace.getOpcode()!=Opcodes.GETSTATIC || !trace.owner.equals(source.trace().owner())
                || !trace.name.equals("$TRC") || !trace.desc.equals("[Lphp/runtime/env/TraceInfo;")
                || !(code.get(2) instanceof LdcInsnNode site) || !(site.cst instanceof Integer sourceTrace) || sourceTrace!=source.trace().index()
                || code.get(3).getOpcode()!=Opcodes.AALOAD
                || code.get(4).getOpcode()!=(source.strict()?Opcodes.ICONST_1:Opcodes.ICONST_0)
                || !call(code.get(5),"php/runtime/PhpPropertyWriteContext","enter",
                    "(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Z)Lphp/runtime/PhpPropertyWriteContext$Frame;"))
            throw new IllegalStateException("Copied trait hook property-write header changed");
        int write=store(code.get(6),node.maxLocals);
        int scoped=owned?entry(code,7,node,sourceOwner,sourceMethod,targetOwner,targetMethod,
                "php/runtime/PhpOwnershipExecution","php/runtime/PhpOwnershipExecution$Entry"):-1;
        int runtime=owned?entry(code,13,node,sourceOwner,sourceMethod,targetOwner,targetMethod,
                "php/runtime/PhpRuntimeOwnership","php/runtime/PhpRuntimeOwnership$Frame"):-2;
        int kind=hook.kind()==PhpPropertyHooks.Kind.GET?1:2;
        if(!load(code.get(body+0),0) || !load(code.get(body+1),1) || !constant(code.get(body+2),hook.declaration().getName())
                || integer(code.get(body+3))!=kind || !constant(code.get(body+4),sourceMethod)
                || !call(code.get(body+5),"php/runtime/PhpPropertyHookBodyAccess","bindGeneratedMethod",BIND))
            throw new IllegalStateException("Copied trait hook exact property/kind/member binding changed");
        int binding=store(code.get(body+6),node.maxLocals);
        if(owned ? Set.of(write,scoped,runtime,binding).size()!=4 : write==binding)
            throw new IllegalStateException("Copied trait hook mandatory protocol locals overlap");
        int binders=0,entries=0;
        for(int at=0;at<code.size();at++)if(code.get(at) instanceof MethodInsnNode invoke) {
            if(invoke.owner.equals("php/runtime/PhpPropertyHookBodyAccess") && invoke.name.equals("bindGeneratedMethod"))binders++;
            if(invoke.name.equals("enterGenerated") && (invoke.owner.equals("php/runtime/PhpOwnershipExecution")
                    || invoke.owner.equals("php/runtime/PhpRuntimeOwnership")))entries++;
            if(invoke.owner.equals("php/runtime/PhpPrimitiveCellRuntime")) {
                if(at<4 || invoke.getOpcode()!=Opcodes.INVOKESTATIC || invoke.itf
                        || !(code.get(at-4) instanceof LdcInsnNode clazz) || !(clazz.cst instanceof Type type)
                        || type.getSort()!=Type.OBJECT || !type.getInternalName().equals(sourceOwner)
                        || !constant(code.get(at-3),sourceMethod) || !constant(code.get(at-2),DESC)
                        || !(code.get(at-1) instanceof LdcInsnNode id) || !(id.cst instanceof Integer))
                    throw new IllegalStateException("Copied trait hook CELL helper has a foreign physical tuple");
                clazz.cst=Type.getObjectType(targetOwner);((LdcInsnNode)code.get(at-3)).cst=targetMethod;
            }
        }
        if(binders!=1 || entries!=(owned?2:0))throw new IllegalStateException("Copied trait hook has duplicate or missing mandatory entries");
        Set<String> found=new HashSet<>();
        if(node.attrs!=null)for(int at=0;at<node.attrs.size();at++) {
            Attribute attribute=node.attrs.get(at);
            if(PROTOCOLS.contains(attribute.type)) {
                if(!(attribute instanceof ProtocolAttribute protocol) || !found.add(attribute.type))
                    throw new IllegalStateException("Copied trait hook has ambiguous physical protocol metadata");
                node.attrs.set(at,protocol.rebind(source.protocolOwner(),sourceMethod,targetProtocolTemplate,targetMethod,DESC));
            }
        }
        if(owned ? !found.contains(FRAME) || !found.contains(RUNTIME) : found.contains(FRAME) || found.contains(RUNTIME))
            throw new IllegalStateException("Copied trait hook lacks its actual ownership protocol metadata");
        if(!retained) {trace.owner=targetOwner;site.cst=targetTrace;}
        ((LdcInsnNode)code.get(body+4)).cst=targetMethod;
        if(retained && node.localVariables!=null) for(LocalVariableNode local:node.localVariables)
            if(local.index==0 && local.name.equals("~this"))local.desc="L"+targetOwner+";";
        if(!before.equals(opcodes(node).stream().map(AbstractInsnNode::getOpcode).toList()))
            throw new IllegalStateException("Copied trait hook changed protocol instruction ordinals");
        rebindStaticSites(node,source.protocolOwner(),sourceMethod,targetProtocolTemplate,targetMethod);
        if(node.attrs==null)node.attrs=new ArrayList<>();
        node.attrs.removeIf(attribute->attribute.type.equals(ATTRIBUTE));
        Origin witness=!retained && source.trace().owner().equals(sourceOwner)
                ? new Origin(hook.method().getClazz().getModule().getInternalName(),hook.method().getClazz().getName(),
                    sourceOwner,sourceMethod,hash(source.bytes()),source.strict(),sourceTrace,targetTrace)
                : new Origin(hook.method().getClazz().getModule().getInternalName(),hook.method().getClazz().getName(),
                    sourceOwner,sourceMethod,hash(source.bytes()),source.strict(),sourceTrace,retained?sourceTrace:targetTrace,
                    retained?2:1,source.trace().owner(),targetProtocolTemplate,targetMethod);
        node.attrs.add(new OriginAttribute(encode(witness)));
        return source.trace();
    }

    private static int entry(List<AbstractInsnNode> code,int at,MethodNode node,String sourceOwner,String sourceMethod,
            String targetOwner,String targetMethod,String helper,String result) {
        if(!load(code.get(at),1) || !(code.get(at+1) instanceof LdcInsnNode clazz) || !(clazz.cst instanceof Type type)
                || type.getSort()!=Type.OBJECT || !type.getInternalName().equals(sourceOwner)
                || !constant(code.get(at+2),sourceMethod) || !constant(code.get(at+3),DESC)
                || !call(code.get(at+4),helper,"enterGenerated",
                    "(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)L"+result+";"))
            throw new IllegalStateException("Copied trait hook has a foreign mandatory ownership tuple");
        clazz.cst=Type.getObjectType(targetOwner);((LdcInsnNode)code.get(at+2)).cst=targetMethod;
        return store(code.get(at+5),node.maxLocals);
    }
    private static int traceCapacity(ClassNode physical) {
        List<FieldNode> fields=physical.fields.stream().filter(item->item.name.equals("$TRC")).toList();
        List<MethodNode> initializers=physical.methods.stream().filter(item->item.name.equals("<clinit>")).toList();
        if(fields.size()!=1 || fields.get(0).access!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)
                || !fields.get(0).desc.equals("[Lphp/runtime/env/TraceInfo;") || fields.get(0).value!=null
                || initializers.size()!=1 || initializers.get(0).access!=Opcodes.ACC_STATIC || !initializers.get(0).desc.equals("()V"))
            throw new IllegalStateException("Copied trait hook trace initializer identity changed");
        List<AbstractInsnNode> code=opcodes(initializers.get(0));int capacity=code.isEmpty()?-1:integer(code.get(0));
        if(capacity<0 || code.size()<3 || !(code.get(1) instanceof TypeInsnNode array)
                || array.getOpcode()!=Opcodes.ANEWARRAY || !array.desc.equals("php/runtime/env/TraceInfo"))
            throw new IllegalStateException("Copied trait hook trace allocation changed");
        long writes=code.stream().filter(item->item instanceof FieldInsnNode field && field.getOpcode()==Opcodes.PUTSTATIC
                && field.owner.equals(physical.name) && field.name.equals("$TRC") && field.desc.equals("[Lphp/runtime/env/TraceInfo;")).count();
        if(writes!=1)throw new IllegalStateException("Copied trait hook trace table has ambiguous stores");
        return capacity;
    }
    private static List<AbstractInsnNode> opcodes(MethodNode node) {
        var result=new ArrayList<AbstractInsnNode>();for(AbstractInsnNode item:node.instructions)if(item.getOpcode()>=0)result.add(item);
        return PhpPropertyHookDumper.propertyContextHeaderView(result);
    }
    private static boolean load(AbstractInsnNode node,int slot) {return node instanceof VarInsnNode v && v.getOpcode()==Opcodes.ALOAD && v.var==slot;}
    private static int store(AbstractInsnNode node,int maximum) {
        if(!(node instanceof VarInsnNode v) || v.getOpcode()!=Opcodes.ASTORE || v.var<3 || v.var>=maximum)
            throw new IllegalStateException("Copied trait hook has an invalid mandatory protocol local");
        return v.var;
    }
    private static boolean constant(AbstractInsnNode node,String value) {return node instanceof LdcInsnNode c && value.equals(c.cst);}
    private static boolean call(AbstractInsnNode node,String owner,String name,String desc) {
        return node instanceof MethodInsnNode m && m.getOpcode()==Opcodes.INVOKESTATIC && !m.itf
                && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(desc);
    }
    private static int integer(AbstractInsnNode node) {
        int opcode=node.getOpcode();if(opcode>=Opcodes.ICONST_M1 && opcode<=Opcodes.ICONST_5)return opcode-Opcodes.ICONST_0;
        if(node instanceof IntInsnNode v && (opcode==Opcodes.BIPUSH || opcode==Opcodes.SIPUSH))return v.operand;
        return node instanceof LdcInsnNode c && c.cst instanceof Integer value?value:-1;
    }
    /** These payloads contain no constant-pool indices; the complete suffix is preserved byte for byte. */
    private static final class ProtocolAttribute extends Attribute {
        private final byte[] payload;
        ProtocolAttribute(String name,byte[] bytes) {super(name);payload=bytes.clone();}
        ProtocolAttribute rebind(String sourceOwner,String sourceMethod,String owner,String method,String descriptor) {
            try {
                var in=new DataInputStream(new ByteArrayInputStream(payload));
                int version=in.readInt();String oldOwner=in.readUTF(),oldMethod=in.readUTF(),oldDescriptor=in.readUTF();
                if(version<1 || !oldOwner.equals(sourceOwner) || !oldMethod.equals(sourceMethod) || !oldDescriptor.equals(descriptor))
                    throw new IllegalStateException("Copied trait hook protocol header changed");
                var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
                out.writeInt(version);out.writeUTF(owner);out.writeUTF(method);out.writeUTF(descriptor);out.write(in.readAllBytes());
                out.flush();return new ProtocolAttribute(type,bytes.toByteArray());
            } catch(IOException malformed) {throw new IllegalStateException("Malformed copied trait hook protocol",malformed);}
        }
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals) {
            return new ByteVector().putByteArray(payload,0,payload.length);
        }
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] chars,int codeOffset,Label[] labels) {
            byte[] bytes=new byte[length];for(int at=0;at<length;at++)bytes[at]=(byte)reader.readByte(offset+at);
            return new ProtocolAttribute(type,bytes);
        }
    }
    private static void rebindStaticSites(MethodNode node,String sourceOwner,String sourceMethod,String targetOwner,String targetMethod) {
        if(node.attrs==null)return;
        int found=-1;
        for(int at=0;at<node.attrs.size();at++)if(STATIC_SITES.equals(node.attrs.get(at).type)) {
            if(found>=0)throw new IllegalStateException("Duplicate copied trait static sites");found=at;
        }
        if(found<0)return;
        if(!(node.attrs.get(found) instanceof ProtocolAttribute attribute))throw new IllegalStateException("Copied trait static sites need their real attribute prototype");
        sourceOwner=sourceOwner.replace('.','/');targetOwner=targetOwner.replace('.','/');
        try {
            var in=new DataInputStream(new ByteArrayInputStream(attribute.payload));
            if(in.readInt()!=1||!in.readUTF().equals(sourceOwner)||!in.readUTF().equals(sourceMethod)||!in.readUTF().equals(node.desc))
                throw new IllegalStateException("Copied trait static source header mismatch");
            var instructions=new ArrayList<AbstractInsnNode>();for(AbstractInsnNode item:node.instructions)if(item.getOpcode()>=0)instructions.add(item);
            var changes=new IdentityHashMap<LdcInsnNode,String>();var claimed=new HashSet<Integer>();
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(1);out.writeUTF(targetOwner);out.writeUTF(targetMethod);out.writeUTF(node.desc);
            int count=in.readInt();if(count<1||count>65535)throw new IllegalStateException("Invalid copied trait static site count");out.writeInt(count);
            for(int site=0;site<count;site++) {
                String local=in.readUTF(),key=in.readUTF();int[] at=new int[15];for(int i=0;i<at.length;i++){at[i]=in.readInt();if(at[i]<0||at[i]>=instructions.size())throw new IllegalStateException("Copied trait static site outside body");}
                String prefix=sourceOwner+"\0"+local+"\0";
                if(local.isEmpty()||local.indexOf('\0')>=0||!key.startsWith(prefix)||key.length()==prefix.length()||key.substring(prefix.length()).indexOf('\0')>=0)
                    throw new IllegalStateException("Copied trait static key owner/local mismatch");
                if(at[2]!=at[1]+1||at[3]!=at[2]+1||at[4]!=at[3]+1||at[7]!=at[6]+1||at[8]!=at[7]+1||at[9]!=at[8]+1
                        ||at[11]!=at[10]+1||at[12]!=at[11]+1||at[13]!=at[12]+1||at[14]!=at[13]+1)
                    throw new IllegalStateException("Copied trait static producer/consumer shape mismatch");
                checkSelf(instructions,at[1]);
                checkSelf(instructions,at[6]);
                checkCall(instructions.get(at[3]),"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;");
                checkCall(instructions.get(at[8]),"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;");
                checkCall(instructions.get(at[4]),"php/runtime/env/Environment","getStatic","(Ljava/lang/String;)Lphp/runtime/Memory;");
                checkCall(instructions.get(at[14]),"php/runtime/env/Environment","getOrCreateStatic","(Ljava/lang/String;Lphp/runtime/Memory;)Lphp/runtime/Memory;");
                int env=checkStore(instructions.get(at[5])),scratch=checkStore(instructions.get(at[9])),value=checkStore(instructions.get(at[10]));
                if(env==scratch||env==value||scratch==value)throw new IllegalStateException("Copied trait static scratch overlap");
                checkLoad(instructions.get(at[11]),env);checkLoad(instructions.get(at[12]),scratch);checkLoad(instructions.get(at[13]),value);
                for(int slot:new int[]{env,scratch,value}) {
                    long writes=instructions.stream().filter(item->item instanceof VarInsnNode var&&var.var==slot&&var.getOpcode()==Opcodes.ASTORE).count();
                    long reads=instructions.stream().filter(item->item instanceof VarInsnNode var&&var.var==slot&&var.getOpcode()==Opcodes.ALOAD).count();
                    if(writes!=1||reads!=1)throw new IllegalStateException("Copied trait static scratch reused");
                }
                if(!claimed.add(at[4])||!claimed.add(at[14]))throw new IllegalStateException("Duplicate copied static helper site");
                String replacement=targetOwner+"\0"+local+"\0"+targetMethod;
                for(int index:new int[]{at[2],at[7]}) {
                    if(!(instructions.get(index) instanceof LdcInsnNode literal)||!key.equals(literal.cst)||changes.put(literal,replacement)!=null)
                        throw new IllegalStateException("Copied trait static key literal mismatch");
                }
                out.writeUTF(local);out.writeUTF(replacement);for(int ordinal:at)out.writeInt(ordinal);
            }
            if(in.available()!=0)throw new IllegalStateException("Trailing copied trait static site data");
            for(int at=0;at<instructions.size();at++)if(instructions.get(at) instanceof MethodInsnNode call&&call.owner.equals("php/runtime/env/Environment")
                    &&(call.name.equals("getStatic")||call.name.equals("getOrCreateStatic"))&&!claimed.contains(at))throw new IllegalStateException("Unclaimed copied trait static helper");
            out.flush();changes.forEach((literal,value)->literal.cst=value);node.attrs.set(found,new ProtocolAttribute(STATIC_SITES,bytes.toByteArray()));
        }catch(IOException failure){throw new IllegalStateException("Malformed copied trait static sites",failure);}
    }
    private static void checkSelf(List<AbstractInsnNode> instructions,int at) {
        AbstractInsnNode node=instructions.get(at);
        if(node instanceof MethodInsnNode call && call.owner.equals("php/runtime/env/Environment")) {
            checkCall(node,"php/runtime/env/Environment","__getSelf","(Lphp/runtime/env/TraceInfo;)Ljava/lang/String;");
        } else {
            // Legacy traits obtain lexical self through the existing macro-class Memory conversion.
            checkCall(node,"php/runtime/Memory","toString","()Ljava/lang/String;");
            if(at<2)throw new IllegalStateException("Missing copied legacy trait self producer");
            checkCall(instructions.get(at-1),"php/runtime/env/Environment","__getMacroClass","()Lphp/runtime/Memory;");
            if(!(instructions.get(at-2) instanceof VarInsnNode env) || env.getOpcode()!=Opcodes.ALOAD)
                throw new IllegalStateException("Copied legacy trait self environment changed");
        }
    }
    private static void checkCall(AbstractInsnNode node,String owner,String name,String descriptor) {
        if(!(node instanceof MethodInsnNode call)||call.getOpcode()!=Opcodes.INVOKEVIRTUAL||!call.owner.equals(owner)||!call.name.equals(name)||!call.desc.equals(descriptor))
            throw new IllegalStateException("Copied trait static helper changed");
    }
    private static int checkStore(AbstractInsnNode node) {
        if(!(node instanceof VarInsnNode store)||store.getOpcode()!=Opcodes.ASTORE)throw new IllegalStateException("Copied trait static scratch store changed");return store.var;
    }
    private static void checkLoad(AbstractInsnNode node,int slot) {
        if(!(node instanceof VarInsnNode read)||read.getOpcode()!=Opcodes.ALOAD||read.var!=slot)throw new IllegalStateException("Copied trait static scratch read changed");
    }

    public static boolean strictContext(ClassEntity owner,ClassNode physical,MethodNode target,
            PropertyEntity property,PhpPropertyHooks.Hook hook,Environment env)throws IOException {
        try {return admit(owner,physical,target,property,hook,env,null,new HashSet<>()).strict();}
        catch(RuntimeException|LinkageError invalid) {
            var failure=new DumpException("Invalid exact trait hook source witness: "+invalid.getClass().getSimpleName()+": "+invalid.getMessage());
            failure.initCause(invalid);throw failure;
        }
    }
    public static void validateHeader(ClassEntity owner,ClassNode physical,MethodNode target,PropertyEntity property,
            PhpPropertyHooks.Hook hook,Environment env)throws IOException {
        if(origin(target)==null) {
            var code=opcodes(target);
            if(code.size()>2 && code.get(2) instanceof LdcInsnNode index && index.cst instanceof Integer slot
                    && importedTraceSlots(physical,slot)>0)
                throw new DumpException("Imported trait hook trace requires its exact source witness");
            // Ordinary bodies retain the original physical-prefix gate and its diagnostics.
            PhpPropertyHookDumper.validateBodyHeader(physical,target,code,property,hook,owner.getModule().isStrictTypes(),owner.getModule().getOwnershipMode());
            return;
        }
        try {
            Admission admitted=admit(owner,physical,target,property,hook,env,null,new HashSet<>());
            PhpPropertyHookDumper.validateBodyHeader(physical,target,opcodes(target),property,hook,admitted.strict(),
                    admitted.trace().owner(),admitted.trace().index(),admitted.capacity(),admitted.ownershipMode());
        }catch(RuntimeException|LinkageError invalid) {
            var failure=new DumpException("Invalid exact trait hook body/source witness: "+invalid.getClass().getSimpleName()+": "+invalid.getMessage());
            failure.initCause(invalid);throw failure;
        }
    }
    private static Admission admit(ClassEntity owner,ClassNode physical,MethodNode target,
            PropertyEntity property,PhpPropertyHooks.Hook hook,Environment env,RuntimeGraph graph,Set<String> visited)throws IOException {
        Origin origin=origin(target);
        if(origin==null) {
            var code=opcodes(target);
            if(code.size()>2 && code.get(2) instanceof LdcInsnNode index && index.cst instanceof Integer slot
                    && importedTraceSlots(physical,slot)>0)
                throw new DumpException("Imported trait hook trace requires its exact source witness");
            int site=code.size()>2?integer(code.get(2)):-1;
            return new Admission(owner.getModule().isStrictTypes(),new TraceOrigin(physical.name,site),traceCapacity(physical),owner.getModule().getOwnershipMode());
        }
        if(visited.size()>=1024)throw new DumpException("Trait hook source witness graph exceeds its bounded depth");
        if(!origin.targetTemplate().isEmpty() && (!origin.targetTemplate().equals(owner.getCompiledInternalName().replace('.','/'))
                || !origin.targetMember().equals(target.name)))
            throw new DumpException("Trait hook target differs from its exact declaration template/member");
        String key=origin.owner()+"\0"+origin.member();
        if(!visited.add(key))throw new DumpException("Cyclic trait hook source witness");
        try {
            ClassEntity donor=graph!=null ? graph.resolve(origin.module(),origin.trait())
                    : origin.module().equals(owner.getModule().getInternalName())?owner.getModule().findClass(origin.trait()):null;
            if(donor==null && graph==null && env!=null)donor=env.getLoadedClasses().get(PhpClassNames.canonicalKey(origin.trait()));
            if(donor==null || donor==owner || !donor.isTrait() || donor.getModule()==null
                    || !donor.getModule().getInternalName().equals(origin.module())
                    || donor.getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)
                throw new DumpException("Missing exact already-registered trait hook source");
            PropertyEntity declaration=donor.findProperty(property.getName());
            var catalog=declaration==null?null:declaration.getPhpHooks();
            var selected=catalog==null?null:catalog.declared(hook.kind());
            if(declaration==null || declaration.getClazz()!=donor || selected==null || selected.declaration()!=declaration
                    || !selected.method().getInternalName().equals(origin.member()))
                throw new DumpException("Trait origin does not select its exact owned hook");
            Source source=source(selected,env,graph,visited);
            if(!source.physical().name.equals(origin.owner()) || !hash(source.bytes()).equals(origin.hash()) || source.strict()!=origin.strict())
                throw new DumpException("Trait hook source class bytes or strict context changed");
            validateMetadata(selected,hook);
            List<AbstractInsnNode> actual=opcodes(target);
            if(actual.size()<(source.ownershipMode().supportsAdmission()?26:14) || !(actual.get(2) instanceof LdcInsnNode at) || !Integer.valueOf(origin.targetTrace()).equals(at.cst))
                throw new DumpException("Trait hook consumer trace site differs from its exact copy");
            MethodNode expected=cloneMethod(source.method());expected.name=target.name;
            String targetProtocolTemplate=origin.targetTemplate().isEmpty()?physical.name:origin.targetTemplate();
            TraceOrigin trace=rebind(expected,source,physical.name,targetProtocolTemplate,target.name,origin.targetTrace(),origin.mode()==2);
            if(!trace.owner().equals(origin.traceOwner()) || trace.index()!=origin.sourceTrace())
                throw new DumpException("Trait hook source trace site changed");
            if(origin.mode()==1)verifyTraceOrigin(physical,origin);
            byte[] expectedBody=origin.mode()==2?runtimeExpectedBody(expected):canonical(expected),actualBody=canonical(target);
            if(!MessageDigest.isEqual(expectedBody,actualBody))
                throw new DumpException("Trait hook differs from its exact source body/protocol copy: "
                        +origin.owner()+"#"+origin.member()+" -> "+physical.name+"#"+target.name
                        +"; expected-sha256="+hash(expectedBody)+"; actual-sha256="+hash(actualBody)
                        +"; expected-instructions="+opcodes(expected).size()+"; actual-instructions="+opcodes(target).size()
                        +"; expected-handlers="+expected.tryCatchBlocks.size()+"; actual-handlers="+target.tryCatchBlocks.size());
            return origin.mode()==2
                    ? new Admission(origin.strict(),source.trace(),source.traceCapacity(),source.ownershipMode())
                    : new Admission(origin.strict(),new TraceOrigin(physical.name,origin.targetTrace()),traceCapacity(physical),source.ownershipMode());
        } finally {visited.remove(key);}
    }
    private static void validateMetadata(PhpPropertyHooks.Hook source,PhpPropertyHooks.Hook target)throws IOException {
        var from=source.method();var to=target.method();
        if(source.kind()!=target.kind() || !from.getName().equals(to.getName()) || from.isFinal()!=to.isFinal()
                || from.isAbstractable()!=to.isAbstractable() || from.isReturnReference()!=to.isReturnReference()
                || !PhpTypeDeclaration.fromChecker(from.getReturnTypeChecker(),from.isReturnTypeNullable())
                    .equivalentTo(PhpTypeDeclaration.fromChecker(to.getReturnTypeChecker(),to.isReturnTypeNullable())))
            throw new DumpException("Trait hook declaration differs from its selected actual source");
        var a=from.getParameters();var b=to.getParameters();
        if((a==null?0:a.length)!=(b==null?0:b.length))throw new DumpException("Trait hook parameter count changed");
        if(a!=null)for(int i=0;i<a.length;i++)
            if(!a[i].getName().equals(b[i].getName()) || a[i].isReference()!=b[i].isReference() || a[i].isVariadic()!=b[i].isVariadic()
                    || !PhpTypeDeclaration.fromChecker(a[i].getTypeChecker(),a[i].isNullable())
                        .equivalentTo(PhpTypeDeclaration.fromChecker(b[i].getTypeChecker(),b[i].isNullable())))
                throw new DumpException("Trait hook parameter contract changed");
    }
    private static void verifyTraceOrigin(ClassNode physical,Origin origin)throws IOException {
        int matches=0;
        for(MethodNode method:physical.methods)if(method.name.equals("<clinit>") || method.name.startsWith("$fill$TRC$")) {
            var code=opcodes(method);
            for(int at=5;at<code.size();at++)if(code.get(at).getOpcode()==Opcodes.AASTORE
                    && integer(code.get(at-4))==origin.targetTrace()
                    && code.get(at-3) instanceof FieldInsnNode field && field.getOpcode()==Opcodes.GETSTATIC
                    && field.owner.equals(origin.traceOwner()) && field.name.equals("$TRC") && field.desc.equals("[Lphp/runtime/env/TraceInfo;")
                    && integer(code.get(at-2))==origin.sourceTrace() && code.get(at-1).getOpcode()==Opcodes.AALOAD
                    && (code.get(at-5).getOpcode()==Opcodes.DUP || load(code.get(at-5),0)))matches++;
        }
        if(matches!=1)throw new DumpException("Trait hook trace slot does not retain its exact donor source context");
    }
    private static int importedTraceSlots(ClassNode physical,int slot) {
        int matches=0;
        for(MethodNode method:physical.methods)if(method.name.equals("<clinit>") || method.name.startsWith("$fill$TRC$")) {
            var code=opcodes(method);
            for(int at=5;at<code.size();at++)if(code.get(at).getOpcode()==Opcodes.AASTORE && integer(code.get(at-4))==slot
                    && code.get(at-3) instanceof FieldInsnNode field && field.getOpcode()==Opcodes.GETSTATIC
                    && !field.owner.equals(physical.name) && field.name.equals("$TRC") && field.desc.equals("[Lphp/runtime/env/TraceInfo;")
                    && code.get(at-1).getOpcode()==Opcodes.AALOAD)matches++;
        }
        return matches;
    }
    private static MethodNode cloneMethod(MethodNode node) {
        // MethodNode.accept(MethodVisitor) copies content; its declaration header must be copied explicitly.
        var copy=new MethodNode(Opcodes.ASM9,node.access,node.name,node.desc,node.signature,
                node.exceptions==null?null:node.exceptions.toArray(String[]::new));
        node.instructions.resetLabels();node.accept(copy);return copy;
    }
    /** Project only the expected runtime-copy source through SourceCodeImpl's zero-line sentinel rule. */
    private static byte[] runtimeExpectedBody(MethodNode reconstructed) {
        MethodNode projected=cloneMethod(reconstructed);
        for(AbstractInsnNode item:projected.instructions.toArray())
            if(item instanceof LineNumberNode line && line.line==0)projected.instructions.remove(item);
        return canonical(projected);
    }
    /** A CP-independent fresh ASM serialization compares every instruction, edge, handler and protocol suffix. */
    private static byte[] canonical(MethodNode actual) {
        MethodNode node=cloneMethod(actual);
        int previousLine=0;boolean hasLine=false;
        for(AbstractInsnNode item:node.instructions.toArray()) {
            if(item instanceof FrameNode)node.instructions.remove(item);
            else if(item instanceof LineNumberNode line) {
                // ClassFile drops repeated same-line positions; retain the first and every transition.
                if(hasLine && line.line==previousLine)node.instructions.remove(item);
                else {previousLine=line.line;hasLine=true;}
            }
        }
        if(node.attrs!=null)node.attrs.removeIf(attribute->attribute.type.equals(ATTRIBUTE));
        var writer=new ClassWriter(0);writer.visit(Opcodes.V25,Opcodes.ACC_PUBLIC,"php/runtime/proof/TraitHook",null,"java/lang/Object",null);
        node.accept(writer);writer.visitEnd();return writer.toByteArray();
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static Origin origin(MethodNode node)throws IOException {
        Origin result=null;
        if(node.attrs!=null)for(Attribute attribute:node.attrs)if(attribute.type.equals(ATTRIBUTE)) {
            if(result!=null || !(attribute instanceof OriginAttribute exact))throw new DumpException("Duplicate or unparsed trait hook source witness");
            var in=new DataInputStream(new ByteArrayInputStream(exact.payload));
            int version=in.readInt();
            if(version!=1 && version!=2)throw new DumpException("Unsupported trait hook source witness");
            String module=in.readUTF(),trait=in.readUTF(),owner=in.readUTF(),member=in.readUTF(),hash=in.readUTF();
            int strict=in.readUnsignedByte(),sourceTrace=in.readInt(),targetTrace=in.readInt();
            int mode=version==1?1:in.readUnsignedByte();
            String traceOwner=version==1?owner:in.readUTF(),targetTemplate=version==1?"":in.readUTF(),targetMember=version==1?"":in.readUTF();
            if(module.isEmpty()||trait.isEmpty()||owner.isEmpty()||member.isEmpty()
                    ||module.indexOf('\0')>=0||trait.indexOf('\0')>=0||owner.indexOf('\0')>=0||member.indexOf('\0')>=0
                    ||!hash.matches("[0-9a-f]{64}")||strict>1||sourceTrace<0||targetTrace<0||in.available()!=0
                    ||mode<1||mode>2||traceOwner.isEmpty()||traceOwner.indexOf('\0')>=0
                    ||version==2 && (targetTemplate.isEmpty()||targetMember.isEmpty()||targetTemplate.indexOf('\0')>=0||targetMember.indexOf('\0')>=0)
                    ||mode==2 && targetTrace!=sourceTrace)
                throw new DumpException("Invalid trait hook source witness");
            result=new Origin(module,trait,owner,member,hash,strict==1,sourceTrace,targetTrace,mode,traceOwner,targetTemplate,targetMember);
        }
        return result;
    }
    private static byte[] encode(Origin origin) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            boolean modern=origin.mode()==2 || !origin.targetTemplate().isEmpty();
            out.writeInt(modern?2:1);out.writeUTF(origin.module());out.writeUTF(origin.trait());out.writeUTF(origin.owner());
            out.writeUTF(origin.member());out.writeUTF(origin.hash());out.writeBoolean(origin.strict());
            out.writeInt(origin.sourceTrace());out.writeInt(origin.targetTrace());
            if(modern) {out.writeByte(origin.mode());out.writeUTF(origin.traceOwner());out.writeUTF(origin.targetTemplate());out.writeUTF(origin.targetMember());}
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    /** Opaque cold snapshot. Holds metadata/provenance, never an Environment, receiver or ownership session. */
    public static final class RuntimeSource {
        private final Source source;
        private final RuntimeGraph graph;
        private final String fingerprint;
        private RuntimeSource(Source source,RuntimeGraph graph) {
            this.source=source;this.graph=graph;
            this.fingerprint=hash((graph.fingerprint+"\0"+source.physical().name+"\0"+source.method().name+"\0"
                    +hash(source.bytes())+"\0"+source.protocolOwner()+"\0"+source.strict()+"\0"
                    +source.trace().owner()+"\0"+source.trace().index()+"\0"+source.traceCapacity()+"\0RUNTIME_RETAINED_TRACE").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        public String fingerprint() {graph.validate();return fingerprint;}
    }
    private static final class RuntimeGraph {
        private record Witness(ClassEntity declaration,Class<?> actual,ModuleEntity provenance,String module,String trait,String template,
                               boolean strict,byte[] bytes,String hash,List<ClassEntity> dependencies,Map<PhpPropertyHooks.Hook,String> hooks) {}
        private final RuntimeClassLoader loader;
        private final Map<ClassEntity,Witness> byIdentity;
        private final Map<String,Witness> byName;
        private final String fingerprint;
        RuntimeGraph(RuntimeClassLoader loader,List<ClassEntity> traits) {
            this.loader=Objects.requireNonNull(loader);
            if(traits==null || traits.isEmpty() || traits.size()>65535)throw new LinkageError("Invalid bounded trait hook graph");
            var identities=new IdentityHashMap<ClassEntity,Witness>();var names=new LinkedHashMap<String,Witness>();var text=new StringBuilder();
            for(ClassEntity trait:traits) {
                if(identities.containsKey(trait))continue;
                if(trait==null || !trait.isTrait() || trait.isDeclarationTemplate() || trait.isUnresolvedDeclaration()
                        || trait.getNativeClass()==null || trait.getModule()==null || trait.getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5)
                    throw new LinkageError("Hook graph member is not an actual bound modern trait");
                byte[] bytes=loader.traitHookDefinition(trait);
                var pins=new IdentityHashMap<PhpPropertyHooks.Hook,String>();
                for(PropertyEntity property:trait.getProperties())if(property.getClazz()==trait && property.getPhpHooks()!=null)
                    for(var hook:property.getPhpHooks().declaredOrder()) {
                        if(hook.declaration()!=property || PhpPropertyHooks.ownedMethod(trait,hook.method())!=hook)
                            throw new LinkageError("Hook graph has a foreign declared callable");
                        pins.put(hook,shape(hook));
                    }
                var witness=new Witness(trait,trait.getNativeClass(),trait.getModule(),trait.getModule().getInternalName(),trait.getName(),
                        trait.getCompiledInternalName().replace('.','/'),trait.getModule().isStrictTypes(),bytes.clone(),hash(bytes),
                        List.copyOf(trait.getTraits().values()),Collections.unmodifiableMap(pins));
                if(names.putIfAbsent(witness.module()+"\0"+PhpClassNames.canonicalKey(witness.trait()),witness)!=null)
                    throw new LinkageError("Ambiguous trait hook graph source identity");
                identities.put(trait,witness);
                text.append(witness.module()).append('\0').append(witness.trait()).append('\0').append(witness.template()).append('\0')
                        .append(witness.actual().getName()).append('\0').append(witness.strict()).append('\0').append(witness.hash()).append('\0');
                pins.values().stream().sorted().forEach(value->text.append(value).append('\0'));
            }
            for(Witness witness:identities.values())for(ClassEntity dependency:witness.dependencies())
                if(!identities.containsKey(dependency))throw new LinkageError("Trait hook graph omits an already-selected actual dependency");
            this.byIdentity=Collections.unmodifiableMap(identities);this.byName=Collections.unmodifiableMap(names);
            this.fingerprint=hash(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            validate();
        }
        ClassEntity resolve(String module,String trait) {
            Witness witness=byName.get(module+"\0"+PhpClassNames.canonicalKey(trait));
            return witness==null?null:witness.declaration();
        }
        byte[] bytes(ClassEntity trait) {
            Witness witness=byIdentity.get(trait);if(witness==null)throw new LinkageError("Hook source is outside its exact witnessed trait graph");
            validate(witness);return witness.bytes().clone();
        }
        void validate() {for(Witness witness:byIdentity.values())validate(witness);}
        private void validate(Witness witness) {
            ClassEntity trait=witness.declaration();
            if(trait.getNativeClass()!=witness.actual() || !trait.isTrait() || trait.isDeclarationTemplate() || trait.isUnresolvedDeclaration()
                    || trait.getModule()!=witness.provenance() || trait.getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5
                    || !trait.getModule().getInternalName().equals(witness.module()) || !trait.getName().equals(witness.trait())
                    || !trait.getCompiledInternalName().replace('.','/').equals(witness.template())
                    || trait.getModule().isStrictTypes()!=witness.strict()
                    || !List.copyOf(trait.getTraits().values()).equals(witness.dependencies())
                    || !MessageDigest.isEqual(witness.bytes(),loader.traitHookDefinition(trait)))
                throw new LinkageError("Trait hook graph source/definition witness changed");
            for(var pin:witness.hooks().entrySet()) {
                if(PhpPropertyHooks.ownedMethod(trait,pin.getKey().method())!=pin.getKey() || !shape(pin.getKey()).equals(pin.getValue()))
                    throw new LinkageError("Trait hook graph declared method shape changed");
                var method=pin.getKey().method();var member=method.getNativeMethod();
                if(!method.isAbstractable() && (member==null || member.getDeclaringClass()!=witness.actual()
                        || !member.getName().equals(method.getInternalName()) || member.getReturnType()!=php.runtime.Memory.class
                        || !Arrays.equals(member.getParameterTypes(),new Class<?>[]{Environment.class,php.runtime.Memory[].class})))
                    throw new LinkageError("Trait hook graph native member selection changed");
            }
        }
    }
    private static String shape(PhpPropertyHooks.Hook hook) {
        hook.validate();var method=hook.method();var property=hook.declaration();
        var text=new StringBuilder().append(hook.kind()).append('\0').append(property.getName()).append('\0')
                .append(property.isStatic()).append('\0').append(property.isFinal()).append('\0').append(property.isPhpReadonly()).append('\0')
                .append(property.getPhpSetModifier()==null?"":"AVIZ:"+property.getPhpSetModifier()+"\0")
                .append(property.isDefault()).append('\0').append(property.getModifier()).append('\0')
                .append(property.getPhpHooks().isVirtual()).append('\0').append(property.getPhpHooks().isDeclaredVirtual()).append('\0')
                .append(property.getDeclaredType()==null?"":property.getDeclaredType().getErrorSignature()).append('\0')
                .append(method.getName()).append('\0').append(method.getInternalName()).append('\0')
                .append(method.isFinal()).append('\0').append(method.isAbstractable()).append('\0').append(method.isReturnReference()).append('\0')
                .append(method.isStatic()).append('\0').append(method.isImmutable()).append('\0').append(method.getImmutableResult()!=null).append('\0')
                .append(method.getModifier()).append('\0')
                .append(PhpTypeDeclaration.fromChecker(method.getReturnTypeChecker(),method.isReturnTypeNullable()).getErrorSignature()).append('\0');
        if(method.getParameters()!=null)for(var parameter:method.getParameters())text.append(parameter.getName()).append('\0')
                .append(parameter.isReference()).append('\0').append(parameter.isVariadic()).append('\0')
                .append(PhpTypeDeclaration.fromChecker(parameter.getTypeChecker(),parameter.isNullable()).getErrorSignature()).append('\0');
        return text.toString();
    }
    public static RuntimeSource runtimeSource(PhpPropertyHooks.Hook donor,RuntimeClassLoader loader,List<ClassEntity> witnessedTraits) {
        if(donor==null || donor.method().isAbstractable())throw new LinkageError("Runtime hook source requires its concrete actual private body");
        RuntimeGraph graph=new RuntimeGraph(loader,List.copyOf(witnessedTraits));
        if(!graph.byIdentity.containsKey(donor.method().getClazz()))throw new LinkageError("Selected hook is outside its bounded actual trait graph");
        Source source=source(donor,null,graph,new HashSet<>());
        return new RuntimeSource(source,graph);
    }
    public static byte[] runtimeOrigin(RuntimeSource snapshot,String targetOwner,String targetMember) {
        snapshot.graph.validate();Source source=snapshot.source;targetOwner=targetOwner.replace('.','/');
        if(targetOwner.isEmpty() || targetMember==null || targetMember.isEmpty() || targetOwner.indexOf('\0')>=0 || targetMember.indexOf('\0')>=0)
            throw new LinkageError("Invalid exact runtime hook target tuple");
        return encode(new Origin(source.hook().method().getClazz().getModule().getInternalName(),source.hook().method().getClazz().getName(),
                source.physical().name,source.method().name,hash(source.bytes()),source.strict(),source.trace().index(),source.trace().index(),
                2,source.trace().owner(),targetOwner,targetMember));
    }
    public static void validateRuntimeTarget(RuntimeSource snapshot,byte[] targetClass,String targetOwner,String targetMember,RuntimeClassLoader loader) {
        try {
            if(snapshot.graph.loader!=loader)throw new LinkageError("Runtime hook target is being verified by a foreign loader");
            snapshot.graph.validate();ClassNode physical=readClass(targetClass);
            var matches=physical.methods.stream().filter(method->method.name.equals(targetMember)).toList();
            if(matches.size()!=1)throw new DumpException("Missing or ambiguous runtime hook target member");
            MethodNode actual=matches.get(0);Origin marker=origin(actual);
            byte[] expectedMarker=runtimeOrigin(snapshot,targetOwner,targetMember);
            if(marker==null || marker.mode()!=2 || !MessageDigest.isEqual(encode(marker),expectedMarker))
                throw new DumpException("Runtime hook target does not carry its exact immutable source recipe");
            Source source=snapshot.source;
            if(!actual.desc.equals(DESC) || actual.access!=source.method().access)
                throw new DumpException("Runtime hook target changes its exact private callable contract");
            MethodNode expected=cloneMethod(source.method());expected.name=targetMember;
            rebind(expected,source,physical.name,targetOwner.replace('.','/'),targetMember,source.trace().index(),true);
            byte[] expectedBody=runtimeExpectedBody(expected),actualBody=canonical(actual);
            if(!MessageDigest.isEqual(expectedBody,actualBody))
                throw new DumpException("Runtime hook target differs from its complete actual source body/protocol pair: "
                        +source.physical().name+"#"+source.method().name+" -> "+physical.name+"#"+targetMember
                        +"; expected-sha256="+hash(expectedBody)+"; actual-sha256="+hash(actualBody));
            PhpPropertyHookDumper.validateBodyHeader(physical,actual,opcodes(actual),source.hook().declaration(),source.hook(),source.strict(),
                    source.trace().owner(),source.trace().index(),source.traceCapacity(),source.ownershipMode());
        }catch(IOException malformed) {
            throw new LinkageError("Invalid exact runtime trait hook source/target pair: "
                    +malformed.getClass().getSimpleName()+": "+malformed.getMessage(),malformed);
        }
    }


    private static final class OriginAttribute extends Attribute {
        private final byte[] payload;
        OriginAttribute(byte[] bytes){super(ATTRIBUTE);payload=bytes.clone();}
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals) {
            return new ByteVector().putByteArray(payload,0,payload.length);
        }
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] chars,int codeOffset,Label[] labels) {
            byte[] bytes=new byte[length];for(int at=0;at<length;at++)bytes[at]=(byte)reader.readByte(offset+at);return new OriginAttribute(bytes);
        }
    }
}
