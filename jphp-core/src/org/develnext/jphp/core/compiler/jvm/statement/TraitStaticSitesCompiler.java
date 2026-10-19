package org.develnext.jphp.core.compiler.jvm.statement;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;

/** Source-minted trait static key producers/consumers; unrelated initializer code is never inspected. */
public final class TraitStaticSitesCompiler {
    public static final String ATTRIBUTE="JPHP_Trait_Static_Sites_v1";
    public record Key(MethodInsnNode self,LdcInsnNode literal,MethodInsnNode concat) { }
    private record Site(String local,String key,AbstractInsnNode[] nodes) { }
    private final MethodStmtCompiler method;
    private final List<Site> sites=new ArrayList<>();
    private boolean finished;
    public TraitStaticSitesCompiler(MethodStmtCompiler method){this.method=method;}
    public int size(){return sites.size();}
    public void record(String local,Key getter,Key creator,AbstractInsnNode getterEnv,AbstractInsnNode getterCall,
                       AbstractInsnNode creatorEnvStore,AbstractInsnNode creatorKeyStore,AbstractInsnNode creatorValueStore,
                       AbstractInsnNode creatorEnvRead,AbstractInsnNode creatorKeyRead,AbstractInsnNode creatorValueRead,AbstractInsnNode creatorCall) {
        if(finished||getter==null||creator==null||!(getter.literal().cst instanceof String key)
                ||!key.equals(creator.literal().cst))throw new IllegalStateException("Trait static source key mismatch");
        sites.add(new Site(local,key,new AbstractInsnNode[]{getterEnv,getter.self(),getter.literal(),getter.concat(),getterCall,
                creatorEnvStore,creator.self(),creator.literal(),creator.concat(),creatorKeyStore,creatorValueStore,
                creatorEnvRead,creatorKeyRead,creatorValueRead,creatorCall}));
    }
    public void finish() {
        if(finished)return;finished=true;if(sites.isEmpty())return;
        try {
            var ordinals=new IdentityHashMap<AbstractInsnNode,Integer>();int next=0;
            for(AbstractInsnNode node:method.node.instructions)if(node.getOpcode()>=0)ordinals.put(node,next++);
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(1);out.writeUTF(method.clazz.entity.getCompiledInternalName().replace('.','/'));
            out.writeUTF(method.getEntity().getInternalName());out.writeUTF(method.node.desc);out.writeInt(sites.size());
            for(Site site:sites) {
                out.writeUTF(site.local());out.writeUTF(site.key());
                for(AbstractInsnNode node:site.nodes()) {
                    Integer ordinal=ordinals.get(node);if(ordinal==null)throw new IllegalStateException("Missing actual trait static node");
                    out.writeInt(ordinal);
                }
            }
            out.flush();if(method.node.attrs==null)method.node.attrs=new ArrayList<>();method.node.attrs.add(new SitesAttribute(bytes.toByteArray()));
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    /** Rebind only source-minted key operands on an already independent ASM method copy. */
    public static void rebindCopiedMethod(MethodNode node,String sourceOwner,String sourceMethod,String targetOwner,String targetMethod) {
        if(node.attrs==null)return;
        int found=-1;
        for(int at=0;at<node.attrs.size();at++)if(ATTRIBUTE.equals(node.attrs.get(at).type)) {
            if(found>=0)throw new IllegalStateException("Duplicate copied trait static sites");found=at;
        }
        if(found<0)return;
        if(!(node.attrs.get(found) instanceof SitesAttribute attribute))throw new IllegalStateException("Copied trait static sites need their real attribute prototype");
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
            out.flush();changes.forEach((literal,value)->literal.cst=value);node.attrs.set(found,new SitesAttribute(bytes.toByteArray()));
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
    public static final class SitesAttribute extends Attribute {
        private final byte[] payload;
        public SitesAttribute(byte[] payload){super(ATTRIBUTE);this.payload=payload.clone();}
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals) {
            return new ByteVector().putByteArray(payload,0,payload.length);
        }
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] chars,int codeOffset,Label[] labels) {
            byte[] copy=new byte[length];for(int at=0;at<length;at++)copy[at]=(byte)reader.readByte(offset+at);return new SitesAttribute(copy);
        }
    }
}
