package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;

import java.io.*;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/** Actual frame publication lowering; metadata is a preflight description, never an admission certificate. */
public final class OwnershipFrameCompiler {
    public static final String ATTRIBUTE = "JPHP_Ownership_Frame_v1";
    private static final String EXECUTION = "php/runtime/PhpOwnershipExecution";
    private static final String ENTRY = EXECUTION + "$Entry";
    private static final String PUBLICATION = "php/runtime/PhpOwnershipHeap$Publication";
    private static final String MEMORY = "Lphp/runtime/Memory;";
    private final MethodStmtCompiler method;
    private LocalVariable entry, candidate, publication, argumentKind, throwableCandidate, throwableReplacement, runtimeFrame, runtimePublication;
    private int runtimeEvaluationSequence;
    public static final String RUNTIME_ATTRIBUTE="JPHP_Runtime_Frame_v1";
    private final Map<Integer,String> runtimeProducers=new LinkedHashMap<>();
    private final Map<Integer,Set<String>> runtimeConsumers=new LinkedHashMap<>();
    private record RuntimeNative(int id,String owner,String name,String descriptor,int argc,
                                 String physicalOwner,String physicalName,String physicalDescriptor,boolean discarded) {
        RuntimeNative(int id,String owner,String name,String descriptor,int argc) {
            this(id,owner,name,descriptor,argc,owner,name,descriptor,false);
        }
    }
    private final Map<Integer,RuntimeNative> runtimeNatives=new LinkedHashMap<>();
    private final Set<MethodInsnNode> runtimeComparisonFallbacks=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer,MethodInsnNode> runtimeComparisonFallbackSites=new LinkedHashMap<>();
    public void runtimeComparisonFallback(int id,MethodInsnNode body,MethodInsnNode boxing) {
        RuntimeNative member=runtimeNatives.get(id);
        if(member==null||!member.owner().equals("php/runtime/PhpComparisonCalls")||body.getOpcode()!=INVOKESTATIC||!body.owner.equals(member.owner())&&!(body.owner.equals("php/runtime/PhpComparison")&&member.descriptor().endsWith(")Z"))||!body.name.equals(member.name())||!body.desc.equals(member.descriptor())||runtimeComparisonFallbackSites.putIfAbsent(id,body)!=null||!runtimeComparisonFallbacks.add(body))throw new IllegalArgumentException("Ordinary comparison lacks its exact managed twin");
        if(boxing!=null){if(boxing.getOpcode()!=INVOKESTATIC||!boxing.owner.equals("php/runtime/memory/TrueMemory")||!boxing.name.equals("valueOf")||!boxing.desc.equals("(Z)Lphp/runtime/Memory;")||!runtimeComparisonFallbacks.add(boxing))throw new IllegalArgumentException("Ordinary comparison result boxing changed");}
    }
    public void runtimeNativeMember(int id,String owner,String name,String descriptor,int argc) {
        if(id<4_000_000||owner==null||owner.isEmpty()||name==null||name.isEmpty()||descriptor==null||!descriptor.startsWith("(")||argc<0||argc>65535||runtimeNatives.putIfAbsent(id,new RuntimeNative(id,owner,name,descriptor,argc))!=null)throw new IllegalArgumentException("Invalid duplicate exact native dependency");
    }
    private record RuntimeNativeScalarArgument(int id,int index,MethodInsnNode borrow,MethodInsnNode literalFactory,MethodInsnNode conversion,AbstractInsnNode last) {}
    private record RuntimeNativeScalarResult(int id,MethodInsnNode boxing,MethodInsnNode result) {}
    private final Map<String,RuntimeNativeScalarArgument> runtimeNativeScalarArguments=new LinkedHashMap<>();
    private final Map<Integer,RuntimeNativeScalarResult> runtimeNativeScalarResults=new LinkedHashMap<>();
    public void runtimeNativeScalarArgument(int id,int index,MethodInsnNode conversion,AbstractInsnNode last) {
        RuntimeNative member=runtimeNatives.get(id);if(member==null||index<0||index>=member.argc())throw new IllegalArgumentException("Scalar argument lacks its selected native member");
        MethodInsnNode borrow=runtimeBorrows.getOrDefault(id,List.of()).stream().filter(node->node.owner.equals(RUNTIME)&&node.name.equals("nativeBorrowSend")&&runtimeIntegerOperand(runtimePreviousOpcode(runtimePreviousOpcode(runtimePreviousOpcode(runtimePreviousOpcode(runtimePreviousOpcode(node))))))==index).findFirst().orElseThrow(()->new IllegalArgumentException("Scalar argument lacks its exact borrowed SEND"));
        AbstractInsnNode input=borrow;for(int step=0;step<8;step++)input=runtimePreviousOpcode(input);
        if(!(input instanceof MethodInsnNode literalFactory)||literalFactory.getOpcode()!=INVOKESTATIC||!literalFactory.name.equals("valueOf")||!(literalFactory.owner.equals("php/runtime/memory/LongMemory")&&literalFactory.desc.equals("(J)Lphp/runtime/Memory;")||literalFactory.owner.equals("php/runtime/memory/StringMemory")&&literalFactory.desc.equals("(Ljava/lang/String;)Lphp/runtime/Memory;")))throw new IllegalArgumentException("Scalar native input lacks its actual constant factory");
        if(conversion.getOpcode()!=INVOKEVIRTUAL||!conversion.owner.equals("php/runtime/Memory")||!Set.of("toLong","toString").contains(conversion.name)||runtimeNativeScalarArguments.putIfAbsent(id+":"+index,new RuntimeNativeScalarArgument(id,index,borrow,literalFactory,conversion,last))!=null)throw new IllegalArgumentException("Scalar native conversion changed");
    }
    private static int runtimeIntegerOperand(AbstractInsnNode node) {
        if(node instanceof LdcInsnNode literal&&literal.cst instanceof Integer)return (Integer)literal.cst;
        if(node.getOpcode()>=ICONST_M1&&node.getOpcode()<=ICONST_5)return node.getOpcode()-ICONST_0;
        if(node instanceof IntInsnNode integer)return integer.operand;throw new IllegalArgumentException("Native argument has no actual constant index");
    }
    public void runtimeNativeScalarResult(int id,MethodInsnNode boxing,MethodInsnNode result) {
        RuntimeNative member=runtimeNatives.get(id);String type=member==null?"":member.descriptor().substring(member.descriptor().indexOf(')')+1);
        if(!Set.of("I","J","Z").contains(type)||boxing.getOpcode()!=INVOKESTATIC||!boxing.owner.equals(type.equals("Z")?"php/runtime/memory/TrueMemory":"php/runtime/memory/LongMemory")||!boxing.name.equals("valueOf")||!boxing.desc.equals("("+type+")"+MEMORY)||result.getOpcode()!=INVOKESTATIC||!result.owner.equals(RUNTIME)||!result.name.equals("nativeResult")||!result.desc.equals("(L"+RUNTIME+"$NativePacket;"+MEMORY+"I)"+MEMORY)||runtimeNativeScalarResults.putIfAbsent(id,new RuntimeNativeScalarResult(id,boxing,result))!=null)throw new IllegalArgumentException("Scalar result lacks its actual BODY boxing/result");
    }
    private record RuntimeConditional(int id,int source,int kind,boolean retainTrue,int trueSource,int falseSource,MethodInsnNode test,JumpInsnNode branch,MethodInsnNode trueResult,JumpInsnNode jump,MethodInsnNode falseResult,LabelNode alternative,LabelNode end,int scratch,String trueLiteral,String falseLiteral) {}
    private final Map<Integer,RuntimeConditional> runtimeConditionals=new LinkedHashMap<>();
    private record RuntimeConditionalLocal(int id,int local,MethodInsnNode helper,VarInsnNode read) {}
    private final Map<Integer,RuntimeConditionalLocal> runtimeConditionalLocals=new LinkedHashMap<>();
    private record RuntimeLifecycleReceiver(int local,VarInsnNode read,MethodInsnNode boxing,MethodInsnNode helper,VarInsnNode store,LabelNode start,LabelNode fallback,LabelNode end) {}
    private final Map<Integer,RuntimeLifecycleReceiver> runtimeLifecycleReceivers=new LinkedHashMap<>();
    private record RuntimeLifecycleDiscard(int local,VarInsnNode read,JumpInsnNode branch,MethodInsnNode exception,AbstractInsnNode pop) {}
    private final Map<VarInsnNode,RuntimeLifecycleDiscard> runtimeLifecycleDiscards=new LinkedHashMap<>();
    public boolean runtimeLifecycleEnabled() {
        return runtimeEnabled()&&method.getGeneratorEntity()==null&&method.statement!=null&&!method.statement.isStatic()&&!method.isExternal()&&!method.clazz.isSystem()&&!method.clazz.isClosure()&&method.getEntity().getName().equalsIgnoreCase("__destruct");
    }
    public void runtimeLifecycleReceiver(int local,VarInsnNode read,MethodInsnNode boxing,MethodInsnNode helper,VarInsnNode store,LabelNode start,LabelNode fallback,LabelNode end) {
        if(!runtimeLifecycleEnabled()||local<0||read==null||read.getOpcode()!=ALOAD||read.var!=0||boxing==null||boxing.getOpcode()!=INVOKESTATIC||!boxing.owner.equals("php/runtime/memory/ObjectMemory")||!boxing.name.equals("valueOf")||!boxing.desc.equals("(Lphp/runtime/lang/IObject;)Lphp/runtime/Memory;")||helper==null||helper.getOpcode()!=INVOKEVIRTUAL||!helper.owner.equals(RUNTIME+"$Frame")||!helper.name.equals("importLifecycleReceiver")||!helper.desc.equals("(ILphp/runtime/Memory;)Lphp/runtime/Memory;")||store==null||store.getOpcode()!=ASTORE||start==null||fallback==null||end==null||runtimeLifecycleReceivers.putIfAbsent(local,new RuntimeLifecycleReceiver(local,read,boxing,helper,store,start,fallback,end))!=null)throw new IllegalArgumentException("Lifecycle receiver lacks its actual nonowning import");
    }
    /** A proven nonowning lifecycle receiver has no CELL lease to retire. Inspect only the actual pending POP guard. */
    public boolean isBorrowedLifecycleDiscard(InsnList code) {
        if(!runtimeLifecycleEnabled()||code!=method.node.instructions||runtimeLifecycleReceivers.isEmpty())return false;
        AbstractInsnNode[] ops=new AbstractInsnNode[12];AbstractInsnNode cursor=code.getLast();
        for(int i=11;i>=0;i--){while(cursor!=null&&cursor.getOpcode()<0)cursor=cursor.getPrevious();if(cursor==null)return false;ops[i]=cursor;cursor=cursor.getPrevious();}
        if(!(ops[0] instanceof VarInsnNode read)||read.getOpcode()!=ALOAD)return false;
        RuntimeLifecycleReceiver receiver=runtimeLifecycleReceivers.values().stream().filter(value->value.store().var==read.var).findFirst().orElse(null);
        if(receiver==null||code.indexOf(receiver.read())<0||code.indexOf(receiver.boxing())<=code.indexOf(receiver.read())||code.indexOf(receiver.helper())<=code.indexOf(receiver.boxing())||code.indexOf(receiver.store())<=code.indexOf(receiver.helper())||code.indexOf(receiver.store())>=code.indexOf(read))return false;
        if(ops[1].getOpcode()!=DUP||!(ops[2] instanceof MethodInsnNode test)||test.getOpcode()!=INVOKEVIRTUAL||test.itf||!test.owner.equals("php/runtime/Memory")||!test.name.equals("isNull")||!test.desc.equals("()Z")||!(ops[3] instanceof JumpInsnNode branch)||branch.getOpcode()!=IFEQ||!(ops[4] instanceof VarInsnNode env)||env.getOpcode()!=ALOAD||env.var!=1||!(ops[5] instanceof FieldInsnNode trace)||trace.getOpcode()!=GETSTATIC||!trace.owner.equals(method.clazz.node.name)||!trace.name.equals("$TRC")||!trace.desc.equals("[Lphp/runtime/env/TraceInfo;")||runtimeIntegerOperand(ops[6])<0||ops[7].getOpcode()!=AALOAD||!(ops[8] instanceof LdcInsnNode error)||!Type.getType("Lphp/runtime/lang/exception/BaseError;").equals(error.cst)||!(ops[9] instanceof LdcInsnNode message)||!"Using $this when not in object context".equals(message.cst)||ops[10].getOpcode()!=ACONST_NULL||!(ops[11] instanceof MethodInsnNode exception)||exception.getOpcode()!=INVOKEVIRTUAL||exception.itf||!exception.owner.equals("php/runtime/env/Environment")||!exception.name.equals("exception")||!exception.desc.equals("(Lphp/runtime/env/TraceInfo;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)V"))return false;
        // The emitted IFEQ must join precisely at the pending POP, with no intervening opcode.
        for(cursor=exception.getNext();cursor!=null;cursor=cursor.getNext()){if(cursor.getOpcode()>=0)return false;if(cursor==branch.label)return true;}
        return false;
    }
    public void runtimeConditionalLocal(int id,int local,MethodInsnNode helper,VarInsnNode read) {
        if(!"CV_READ_RESULT".equals(runtimeProducers.get(id))||local<0||read==null||read.getOpcode()!=ALOAD||runtimePreviousOpcode(helper)!=read||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("conditionalLocal")||!helper.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;)Lphp/runtime/Memory;")||runtimeConditionalLocals.putIfAbsent(id,new RuntimeConditionalLocal(id,local,helper,read))!=null)throw new IllegalArgumentException("Conditional CV lacks its actual address read");
    }
    private final Set<Integer> runtimeQuietGlobalReads=new LinkedHashSet<>();
    private final Set<MethodInsnNode> runtimeConditionalLiterals=Collections.newSetFromMap(new IdentityHashMap<>());
    public MethodInsnNode runtimeGlobalReadHelper(int id){RuntimeGlobalRead read=runtimeGlobalReads.get(id);return read==null?null:read.helper();}
    public void runtimeQuietGlobalRead(int id,MethodInsnNode actual) {
        RuntimeGlobalRead read=runtimeGlobalReads.get(id);
        if(read==null||read.cell()||read.helper()!=actual||!actual.name.equals("globalRead")||!runtimeQuietGlobalReads.add(id))throw new IllegalArgumentException("Quiet fetch requires its exact prior named VALUE helper");
        actual.name="globalReadQuiet";
    }
    private static AbstractInsnNode runtimePreviousOpcode(AbstractInsnNode node) {
        do{node=node.getPrevious();}while(node!=null&&node.getOpcode()<0);if(node==null)throw new IllegalArgumentException("Conditional operand escapes its actual code");return node;
    }
    private String runtimeConditionalLiteral(MethodInsnNode result,int selected) {
        if(selected>=0)return "";
        AbstractInsnNode store=result;for(int i=0;i<7;i++)store=runtimePreviousOpcode(store);
        if(store.getOpcode()!=ASTORE)throw new IllegalArgumentException("Conditional primitive lacks its actual scratch store");
        AbstractInsnNode raw=runtimePreviousOpcode(store);
        if(raw instanceof FieldInsnNode field&&field.getOpcode()==GETSTATIC&&field.owner.equals("php/runtime/Memory")&&field.name.equals("NULL")&&field.desc.equals(MEMORY))return "N";
        if(raw instanceof MethodInsnNode call&&call.getOpcode()==INVOKESTATIC&&call.owner.equals("php/runtime/memory/StringMemory")&&call.name.equals("valueOf")&&call.desc.equals("(Ljava/lang/String;)"+MEMORY)) {
            AbstractInsnNode constant=runtimePreviousOpcode(raw);
            if(constant instanceof LdcInsnNode literal&&literal.cst instanceof String){runtimeConditionalLiterals.add(call);return "S"+literal.cst;}
        }
        throw new IllegalArgumentException("Conditional primitive needs its exact NULL/STRING span");
    }
    public void runtimeConditional(int id,int source,int kind,boolean retainTrue,int trueSource,int falseSource,MethodInsnNode test,JumpInsnNode branch,MethodInsnNode trueResult,JumpInsnNode jump,MethodInsnNode falseResult,LabelNode alternative,LabelNode end,int scratch) {
        if(!"JOIN_RESULT".equals(runtimeProducers.get(id))||!runtimeProducers.containsKey(source)||kind!=0&&kind!=1||kind==0&&!retainTrue||branch.getOpcode()!=IFEQ||jump.getOpcode()!=GOTO||branch.label!=alternative||jump.label!=end||scratch<0||runtimeConditionals.containsKey(id))throw new IllegalArgumentException("Conditional lacks its actual producer/branch tuple");
        if(test.getOpcode()!=INVOKESTATIC||!test.owner.equals(RUNTIME)||!test.name.equals("conditionalTest")||!test.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"IZ)Z"))throw new IllegalArgumentException("Conditional test helper changed");
        for(MethodInsnNode result:List.of(trueResult,falseResult))if(result.getOpcode()!=INVOKESTATIC||!result.owner.equals(RUNTIME)||!result.name.equals("conditionalResult")||!result.desc.equals("(L"+RUNTIME+"$Frame;III"+MEMORY+"Z)"+MEMORY))throw new IllegalArgumentException("Conditional result helper changed");
        runtimeConditionals.put(id,new RuntimeConditional(id,source,kind,retainTrue,trueSource,falseSource,test,branch,trueResult,jump,falseResult,alternative,end,scratch,runtimeConditionalLiteral(trueResult,trueSource),runtimeConditionalLiteral(falseResult,falseSource)));
        runtimeConsumer(source,"JOIN");if(trueSource>=0)runtimeConsumer(trueSource,"JOIN");if(falseSource>=0)runtimeConsumer(falseSource,"JOIN");
    }
    private record NativeBodySite(int id,MethodInsnNode enter,MethodInsnNode body,MethodInsnNode leave,MethodInsnNode fail,TryCatchBlockNode guard) {}
    private final Map<Integer,NativeBodySite> runtimeBodies=new LinkedHashMap<>();
    private final Map<Integer,List<MethodInsnNode>> runtimeConversions=new LinkedHashMap<>();
    private final Map<Integer,List<MethodInsnNode>> runtimeBorrows=new LinkedHashMap<>();
    private record RuntimeNativeDirectCv(int local,VarInsnNode read,VarInsnNode store,MethodInsnNode helper) {}
    private final Map<MethodInsnNode,RuntimeNativeDirectCv> runtimeNativeDirectCvs=new IdentityHashMap<>();
    public void runtimeNativeDirectCv(int id,int local,VarInsnNode read,VarInsnNode store,MethodInsnNode helper) {
        if(!runtimeNatives.containsKey(id)||local<0||read.getOpcode()!=ALOAD||read.var!=local
                ||store.getOpcode()!=ASTORE||runtimePreviousOpcode(store)!=read
                ||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("nativeDirectCvSend")
                ||!helper.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$NativePacket;I"+MEMORY+"I)"+MEMORY)
                ||runtimeNativeDirectCvs.putIfAbsent(helper,new RuntimeNativeDirectCv(local,read,store,helper))!=null)
            throw new IllegalArgumentException("Native direct CV lacks its actual local/borrow opcode proof");
        runtimeBorrows.computeIfAbsent(id,ignored->new ArrayList<>()).add(helper);
    }
    private String runtimeNativeBorrowTag(MethodInsnNode borrow) {
        RuntimeNativeDirectCv cv=runtimeNativeDirectCvs.get(borrow);
        if(cv!=null)return "nativeDirectCvSend-fast-wrapped:"+runtimeOrdinal(cv.read())+":"+runtimeOrdinal(cv.store())+":"+cv.local();
        return borrow.name.equals("nativeBorrowSend")?(borrow.getPrevious().getPrevious().getOpcode()==ICONST_1?"nativeBorrowSend-fast-":"nativeBorrowSend-value-")+(borrow.getPrevious().getOpcode()==ICONST_1?"wrapped":"plain"):borrow.name;
    }
    public void runtimeNativeBorrow(int id,MethodInsnNode actual) {
        boolean combined=actual.owner.equals(RUNTIME)&&actual.name.equals("nativeBorrowSend")&&actual.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$NativePacket;I"+MEMORY+"IZZ)"+MEMORY);
        boolean separate=actual.owner.equals("php/runtime/invoke/PhpCallFrame")&&Set.of("borrowNativeArgument","borrowNativeFastArgument").contains(actual.name)&&actual.desc.equals("("+MEMORY+")"+MEMORY);
        if(!runtimeNatives.containsKey(id)||actual.getOpcode()!=INVOKESTATIC||!combined&&!separate)throw new IllegalArgumentException("Unknown native argument borrow");
        if(combined&&(actual.getPrevious().getOpcode()!=ICONST_0&&actual.getPrevious().getOpcode()!=ICONST_1||actual.getPrevious().getPrevious().getOpcode()!=ICONST_0&&actual.getPrevious().getPrevious().getOpcode()!=ICONST_1))throw new IllegalArgumentException("Native borrow needs its exact opcode mode");
        runtimeBorrows.computeIfAbsent(id,ignored->new ArrayList<>()).add(actual);
    }
    public void runtimeNativeConversion(int id,MethodInsnNode actual) {
        if(!runtimeNatives.containsKey(id)||actual.getOpcode()!=INVOKEVIRTUAL||!actual.owner.equals("php/runtime/Memory")||!actual.name.equals("toValue")||!actual.desc.equals("()"+MEMORY))throw new IllegalArgumentException("Unknown native transport conversion");
        runtimeConversions.computeIfAbsent(id,ignored->new ArrayList<>()).add(actual);
    }
    public void runtimeNativeBody(int id,java.lang.reflect.Method semantic,java.lang.reflect.Method physical,
                                  MethodInsnNode enter,MethodInsnNode body,MethodInsnNode leave,MethodInsnNode fail,TryCatchBlockNode guard) {
        RuntimeNative nativeMember=runtimeNatives.get(id);
        if(nativeMember==null||!org.objectweb.asm.Type.getInternalName(semantic.getDeclaringClass()).equals(nativeMember.owner())
                ||!semantic.getName().equals(nativeMember.name())||!org.objectweb.asm.Type.getMethodDescriptor(semantic).equals(nativeMember.descriptor())
                ||body.getOpcode()!=INVOKESTATIC||!body.owner.equals(org.objectweb.asm.Type.getInternalName(physical.getDeclaringClass()))
                ||!body.name.equals(physical.getName())||!body.desc.equals(org.objectweb.asm.Type.getMethodDescriptor(physical))
                ||!"java/lang/Throwable".equals(guard.type)||runtimeBodies.putIfAbsent(id,new NativeBodySite(id,enter,body,leave,fail,guard))!=null)
            throw new IllegalArgumentException("Native BODY lacks its exact selected member and Throwable guard");
        var proof=php.runtime.invoke.PhpNativeResultUse.require(semantic,body.owner,body.name,body.desc,!semantic.equals(physical));
        runtimeNatives.put(id,new RuntimeNative(id,nativeMember.owner(),nativeMember.name(),nativeMember.descriptor(),nativeMember.argc(),
                body.owner,body.name,body.desc,proof.discarded()));
    }
    private int runtimeOrdinal(AbstractInsnNode expected) {
        int index=0;for(AbstractInsnNode node:method.node.instructions){if(node==expected)return index;if(node.getOpcode()>=0)index++;}
        throw new IllegalArgumentException("Native BODY instruction is outside its generated method");
    }
    private record RuntimeArrayEntry(int id,int producer,int local,boolean append,MethodInsnNode factory,MethodInsnNode step,MethodInsnNode assignment) {}
    private final Map<Integer,RuntimeArrayEntry> runtimeArrayEntries=new LinkedHashMap<>();
    private record RuntimeHandler(int call,int producer,String closure,MethodInsnNode send) {}
    private final Map<Integer,RuntimeHandler> runtimeHandlers=new LinkedHashMap<>();
    public void runtimeArrayEntry(int id,int producer,int local,boolean append,MethodInsnNode factory,MethodInsnNode step,MethodInsnNode assignment) {
        String owner="php/runtime/NullCoalesceAssignment",target="L"+owner+";";
        if(id<4_000_000||local<0||!runtimeProducers.containsKey(producer)||factory==null||step==null||assignment==null
                ||factory.getOpcode()!=INVOKESTATIC||!factory.owner.equals(owner)||!factory.name.equals("variableRuntime")||!factory.desc.equals("("+MEMORY+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;L"+RUNTIME+"$Frame;II)"+target)
                ||step.getOpcode()!=INVOKEVIRTUAL||!step.owner.equals(owner)||!step.name.equals(append?"append":"index")||!step.desc.equals(append?"()"+target:"("+MEMORY+")"+target)
                ||assignment.getOpcode()!=INVOKEVIRTUAL||!assignment.owner.equals(owner)||!assignment.name.equals("assignRuntime")||!assignment.desc.equals("("+MEMORY+"L"+RUNTIME+"$Frame;I)"+MEMORY)
                ||runtimeArrayEntries.putIfAbsent(id,new RuntimeArrayEntry(id,producer,local,append,factory,step,assignment))!=null)throw new IllegalArgumentException("ARRAY entry has no exact addressed source and write protocol");
    }
    public void runtimeHandlerSite(int call,int producer,String closure,MethodInsnNode send) {
        RuntimeNative member=runtimeNatives.get(call);
        if(member==null||!member.owner().equals("php/runtime/ext/core/LangFunctions")||!member.name().equals("set_error_handler")
                ||!"CLOSURE_LITERAL".equals(runtimeProducers.get(producer))||!runtimeClosureClass(producer).equals(closure)
                ||!runtimeBorrows.getOrDefault(call,List.of()).contains(send)||!send.name.equals("nativeBorrowSend")
                ||runtimeHandlers.putIfAbsent(call,new RuntimeHandler(call,producer,closure,send))!=null)throw new IllegalArgumentException("Handler FIELD lacks its actual native SEND and literal allocation");
    }
    private record RuntimeNamedGlobal(int local,String name,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {}
    private final List<RuntimeNamedGlobal> runtimeNamedGlobals=new ArrayList<>();
    private record RuntimeEntryString(int id,String value,boolean raw,MethodInsnNode helper) {}
    private final Map<Integer,RuntimeEntryString> runtimeEntryStrings=new LinkedHashMap<>();
    private final Map<MethodInsnNode,Integer> runtimeReturnBoundaries=new LinkedHashMap<>();
    public void runtimeNamedGlobal(int local,String name,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {
        if(local<0||name==null||name.equals("GLOBALS")||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("namedGlobal")||!helper.desc.equals("(L"+RUNTIME+"$Frame;ILjava/lang/String;Lphp/runtime/env/Environment;)"+MEMORY)||start==null||fallback==null||end==null)throw new IllegalArgumentException("Named global lacks its exact generated address import");
        runtimeNamedGlobals.add(new RuntimeNamedGlobal(local,name,helper,start,fallback,end));
    }
    public void runtimeEntryString(int id,String value,boolean raw,MethodInsnNode helper) {
        if(!"ENTRY_STRING_LITERAL".equals(runtimeProducers.get(id))||value==null||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("entryString")||!helper.desc.equals("(L"+RUNTIME+"$Frame;ILjava/lang/String;Z)"+MEMORY)||runtimeEntryStrings.putIfAbsent(id,new RuntimeEntryString(id,value,raw,helper))!=null)throw new IllegalArgumentException("String ENTRY lacks its exact literal producer");
    }
    private record RuntimeGlobalImport(int local,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {}
    private record RuntimeObjectNew(int id,String name,String owner,String fingerprint,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {}
    private final Map<Integer,RuntimeObjectNew> runtimeObjectNews=new LinkedHashMap<>();
    public void runtimeObjectNew(int id,String name,String owner,String fingerprint,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {
        if(!"OBJECT_NEW".equals(runtimeProducers.get(id))||name==null||name.isEmpty()||owner==null||owner.isEmpty()||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||helper==null||helper.getOpcode()!=INVOKEVIRTUAL||!helper.owner.equals(RUNTIME+"$Frame")||!helper.name.equals("objectNew")||!helper.desc.equals("(ILjava/lang/String;)"+MEMORY)||start==null||fallback==null||end==null||runtimeObjectNews.putIfAbsent(id,new RuntimeObjectNew(id,name,owner,fingerprint,helper,start,fallback,end))!=null)throw new IllegalArgumentException("Object NEW lacks its actual class/default/allocation helper");
    }
    private record RuntimeObjectCv(int producer,int local,String owner,MethodInsnNode publication) {}
    private final Map<Integer,RuntimeObjectCv> runtimeObjectLocals=new LinkedHashMap<>();
    private final Map<AddressSource,RuntimeObjectCv> runtimeObjectCvs=new LinkedHashMap<>();
    private record RuntimePropertyReference(int id,int root,int producer,String key,MethodInsnNode helper,VarInsnNode read) {}
    private final Map<Integer,RuntimePropertyReference> runtimePropertyReferences=new LinkedHashMap<>();
    public Object runtimeObjectLocal(int local){return runtimeObjectLocals.get(local);}
    public void runtimePropertyReference(int id,int root,String key,MethodInsnNode helper,VarInsnNode read) {
        RuntimeObjectCv source=runtimeObjectLocals.get(root);
        if(source==null||!"PROPERTY_REF_RESULT".equals(runtimeProducers.get(id))||key==null||read==null||read.getOpcode()!=ALOAD||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("propertyReference")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"Ljava/lang/String;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+MEMORY)||runtimePropertyReferences.putIfAbsent(id,new RuntimePropertyReference(id,root,source.producer(),key,helper,read))!=null)throw new IllegalArgumentException("Property reference lacks its actual NEW/CV/declared FIELD source");
    }
    private record RuntimeArrayIncrement(int id,int root,String key,boolean decrement,boolean post,MethodInsnNode helper,VarInsnNode read) {}
    private final Map<Integer,RuntimeArrayIncrement> runtimeArrayIncrements=new LinkedHashMap<>();
    public void runtimeArrayIncrement(int id,int root,String key,boolean decrement,boolean post,MethodInsnNode helper,VarInsnNode read) {
        if(!"ARRAY_INCREMENT_RESULT".equals(runtimeProducers.get(id))||root<0||key==null||helper==null||read==null||read.getOpcode()!=ALOAD||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals("php/runtime/PhpArithmetic")||!helper.name.equals("arrayIncrement")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"Ljava/lang/String;ZZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/env/TraceInfo;)"+MEMORY)||runtimeArrayIncrements.putIfAbsent(id,new RuntimeArrayIncrement(id,root,key,decrement,post,helper,read))!=null)throw new IllegalArgumentException("ARRAY increment lacks its actual CV/key/opcode/result helper");
    }
    private record RuntimeLocalIncrement(int id,int root,boolean decrement,boolean post,MethodInsnNode helper,VarInsnNode read) {}
    private final Map<Integer,RuntimeLocalIncrement> runtimeLocalIncrements=new LinkedHashMap<>();
    public void runtimeLocalIncrement(int id,int root,boolean decrement,boolean post,MethodInsnNode helper,VarInsnNode read) {
        if(!"LOCAL_INCREMENT_RESULT".equals(runtimeProducers.get(id))||root<0||helper==null||read==null||read.getOpcode()!=ALOAD||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals("php/runtime/PhpArithmetic")||!helper.name.equals("localIncrement")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"ZZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+MEMORY)||runtimeLocalIncrements.putIfAbsent(id,new RuntimeLocalIncrement(id,root,decrement,post,helper,read))!=null)throw new IllegalArgumentException("Local increment lacks its actual CV/opcode/result helper");
    }
    private record RuntimeFunctionCall(int id,String name,String owner,String member,String descriptor,int local,MethodInsnNode factory,MethodInsnNode send,VarInsnNode read,MethodInsnNode invoke,LabelNode start,LabelNode fallback,LabelNode end) {}
    private final Map<Integer,RuntimeFunctionCall> runtimeFunctions=new LinkedHashMap<>();
    public void runtimeFunctionCall(int id,String name,String owner,String member,String descriptor,int local,MethodInsnNode factory,MethodInsnNode send,VarInsnNode read,MethodInsnNode invoke,LabelNode start,LabelNode fallback,LabelNode end) {
        if(!"FUNCTION_RESULT".equals(runtimeProducers.get(id))||name==null||owner==null||member==null||descriptor==null||local<0||factory==null||send==null||read==null||invoke==null||start==null||fallback==null||end==null||runtimeFunctions.putIfAbsent(id,new RuntimeFunctionCall(id,name,owner,member,descriptor,local,factory,send,read,invoke,start,fallback,end))!=null)throw new IllegalArgumentException("FUNCTION site lacks its actual dependency/SEND/RESULT nodes");
    }
    private record RuntimeArrayReference(int id,int root,String key,MethodInsnNode helper,VarInsnNode read) {}
    private final Map<Integer,RuntimeArrayReference> runtimeArrayReferences=new LinkedHashMap<>();
    public void runtimeArrayReference(int id,int root,String key,MethodInsnNode helper,VarInsnNode read) {
        if(!"ARRAY_REF_RESULT".equals(runtimeProducers.get(id))||root<0||key==null||helper==null||read==null||read.getOpcode()!=ALOAD||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals("php/runtime/PhpOwnershipArrays")||!helper.name.equals("arrayReference")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"Ljava/lang/String;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+MEMORY)||runtimeArrayReferences.putIfAbsent(id,new RuntimeArrayReference(id,root,key,helper,read))!=null)throw new IllegalArgumentException("ARRAY reference lacks its actual CV/key/result helper");
    }
    private record RuntimeGlobalNull(int id,MethodInsnNode helper) {}
    private final Map<Integer,RuntimeGlobalNull> runtimeGlobalNulls=new LinkedHashMap<>();
    public void runtimeGlobalNull(int id,MethodInsnNode helper) {
        if(!"NULL_LITERAL".equals(runtimeProducers.get(id))||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("globalNull")||!helper.desc.equals("(L"+RUNTIME+"$Frame;I)"+MEMORY)||runtimeGlobalNulls.putIfAbsent(id,new RuntimeGlobalNull(id,helper))!=null)throw new IllegalArgumentException("NULL global RHS lacks its actual literal EVAL producer");
    }
    public boolean runtimeArrayCastSource(int id) {return "ARRAY_LITERAL".equals(runtimeProducers.get(id));}
    private record RuntimeLiteralEntry(int parent,MethodInsnNode wrapper,MethodInsnNode helper) {}
    private final List<RuntimeLiteralEntry> runtimeLiteralEntries=new ArrayList<>();
    public void runtimeLiteralEntry(int parent,MethodInsnNode wrapper,MethodInsnNode helper) {
        if(!runtimeArrayCastSource(parent)||wrapper==null||wrapper.getOpcode()!=INVOKESTATIC||!wrapper.owner.equals("php/runtime/memory/KeyValueMemory")||!wrapper.name.equals("valueOf")||!wrapper.desc.equals("("+MEMORY+MEMORY+")"+MEMORY)||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("literalEntry")||!helper.desc.equals("(Lphp/runtime/memory/ArrayMemory;"+MEMORY+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;L"+RUNTIME+"$Frame;I)Lphp/runtime/memory/ReferenceMemory;")||runtimeLiteralEntries.stream().anyMatch(value->value.wrapper()==wrapper||value.helper()==helper))throw new IllegalArgumentException("Keyed literal lacks its actual ARRAY/wrapper/publication helper");
        runtimeLiteralEntries.add(new RuntimeLiteralEntry(parent,wrapper,helper));
    }
    private record RuntimeObjectCast(int id,int source,MethodInsnNode helper) {}
    private final Map<Integer,RuntimeObjectCast> runtimeObjectCasts=new LinkedHashMap<>();
    public void runtimeObjectCast(int id,int source,MethodInsnNode helper) {
        if(!"OBJECT_CAST_RESULT".equals(runtimeProducers.get(id))||!runtimeArrayCastSource(source)||id==source||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("objectCast")||!helper.desc.equals("("+MEMORY+"L"+RUNTIME+"$Frame;IILphp/runtime/env/Environment;)"+MEMORY)||runtimeObjectCasts.putIfAbsent(id,new RuntimeObjectCast(id,source,helper))!=null)throw new IllegalArgumentException("OBJECT cast lacks its actual ARRAY source and result helper");
    }
    private record RuntimeEntryScalar(int id,long value,MethodInsnNode helper) {}
    private final Map<Integer,RuntimeEntryScalar> runtimeEntryScalars=new LinkedHashMap<>();
    public void runtimeEntryScalar(int id,long value,MethodInsnNode helper) {
        if(!"ENTRY_SCALAR_LITERAL".equals(runtimeProducers.get(id))||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("entryScalar")||!helper.desc.equals("(L"+RUNTIME+"$Frame;IJ)"+MEMORY)||runtimeEntryScalars.putIfAbsent(id,new RuntimeEntryScalar(id,value,helper))!=null)throw new IllegalArgumentException("Scalar ENTRY RHS lacks its actual literal EVAL producer");
    }
    private record RuntimeGlobalScalar(int id,long value,MethodInsnNode helper) {}
    private final Map<Integer,RuntimeGlobalScalar> runtimeGlobalScalars=new LinkedHashMap<>();
    public void runtimeGlobalScalar(int id,long value,MethodInsnNode helper) {
        if(!"SCALAR_LITERAL".equals(runtimeProducers.get(id))||helper==null||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("globalScalar")||!helper.desc.equals("(L"+RUNTIME+"$Frame;IJ)"+MEMORY)||runtimeGlobalScalars.putIfAbsent(id,new RuntimeGlobalScalar(id,value,helper))!=null)throw new IllegalArgumentException("Scalar global RHS lacks its actual literal EVAL producer");
    }
    private record RuntimeGlobalRead(int id,int root,String key,boolean cell,MethodInsnNode helper,VarInsnNode read) {}
    private record RuntimeGlobalUnset(int id,int root,String key,MethodInsnNode helper,VarInsnNode read) {}
    private final Map<Integer,RuntimeGlobalRead> runtimeGlobalReads=new LinkedHashMap<>();
    private final Map<Integer,RuntimeGlobalUnset> runtimeGlobalUnsets=new LinkedHashMap<>();
    public void runtimeGlobalRead(int id,int root,String key,boolean cell,MethodInsnNode helper,VarInsnNode read) {
        if(!"GLOBAL_RESULT".equals(runtimeProducers.get(id))||root<0||key==null||helper==null||read==null||read.getOpcode()!=ALOAD||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("globalRead")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"Ljava/lang/String;ZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+MEMORY)||runtimeGlobalReads.putIfAbsent(id,new RuntimeGlobalRead(id,root,key,cell,helper,read))!=null)throw new IllegalArgumentException("Named global READ lacks its actual RESULT and borrowed table LOAD");
    }
    public void runtimeGlobalUnset(int id,int root,String key,MethodInsnNode helper,VarInsnNode read) {
        if(id<4_000_000||root<0||key==null||helper==null||read==null||read.getOpcode()!=ALOAD||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals(RUNTIME)||!helper.name.equals("globalUnset")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"Ljava/lang/String;Lphp/runtime/env/Environment;)V")||runtimeGlobalUnsets.putIfAbsent(id,new RuntimeGlobalUnset(id,root,key,helper,read))!=null)throw new IllegalArgumentException("Named global UNSET lacks its actual borrowed table LOAD and placement");
    }
    private record RuntimeGlobalAccess(int id,int root,String key,int eval,int local,int mode,MethodInsnNode factory,MethodInsnNode index,MethodInsnNode assignment,VarInsnNode read) {}
    private final Map<Integer,RuntimeGlobalImport> runtimeGlobalImports=new LinkedHashMap<>();
    private final Map<Integer,RuntimeGlobalAccess> runtimeGlobalAccesses=new LinkedHashMap<>();
    public void runtimeGlobalImport(int local,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {
        if(local<0||helper==null||helper.getOpcode()!=INVOKEVIRTUAL||!helper.owner.equals(RUNTIME+"$Frame")||!helper.name.equals("globalsAddress")||!helper.desc.equals("(I)Lphp/runtime/memory/ReferenceMemory;")||start==null||fallback==null||end==null||runtimeGlobalImports.putIfAbsent(local,new RuntimeGlobalImport(local,helper,start,fallback,end))!=null)throw new IllegalArgumentException("Literal GLOBALS lacks its guarded actual Frame address import");
    }
    public void runtimeGlobalAccess(int id,int root,String key,int eval,int local,int mode,MethodInsnNode factory,MethodInsnNode index,MethodInsnNode assignment) {
        runtimeGlobalAccess(id,root,key,eval,local,mode,factory,index,assignment,null);
    }
    public void runtimeGlobalAccess(int id,int root,String key,int eval,int local,int mode,MethodInsnNode factory,MethodInsnNode index,MethodInsnNode assignment,VarInsnNode read) {
        String owner="php/runtime/NullCoalesceAssignment",target="L"+owner+";";
        if(id<4_000_000||root<0||key==null||mode<0||mode>1||(eval<0)==(local<0)||eval>=0&&!runtimeProducers.containsKey(eval)||local>=0&&(read==null||read.getOpcode()!=ALOAD)||local<0&&read!=null||factory==null||index==null||assignment==null
                ||factory.getOpcode()!=INVOKESTATIC||!factory.owner.equals(owner)||!factory.name.equals("variableGlobalsRuntime")||!factory.desc.equals("("+MEMORY+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;L"+RUNTIME+"$Frame;II)"+target)
                ||index.getOpcode()!=INVOKEVIRTUAL||!index.owner.equals(owner)||!index.name.equals("indexGlobal")||!index.desc.equals("(Ljava/lang/String;)"+target)
                ||assignment.getOpcode()!=INVOKEVIRTUAL||!assignment.owner.equals(owner)||!assignment.name.equals("assignGlobalRuntime")||!assignment.desc.equals("("+MEMORY+"L"+RUNTIME+"$Frame;III)"+MEMORY)
                ||runtimeGlobalAccesses.putIfAbsent(id,new RuntimeGlobalAccess(id,root,key,eval,local,mode,factory,index,assignment,read))!=null)throw new IllegalArgumentException("Named global write has no exclusive actual source and keyed publication");
    }
    private record RuntimeLiteral(int producer,String closure,String module,int index,MethodInsnNode lookup,MethodInsnNode prepare) {}
    private final Map<Integer,RuntimeLiteral> runtimeLiterals=new LinkedHashMap<>();
    public void runtimeClosureLiteral(int producer,String closure,String module,int index,MethodInsnNode lookup,MethodInsnNode prepare) {
        if(!"CLOSURE_LITERAL".equals(runtimeProducers.get(producer))||closure==null||closure.isEmpty()||module==null||module.isEmpty()||index<0||lookup==null||prepare==null
                ||lookup.getOpcode()!=INVOKEVIRTUAL||!lookup.owner.equals("php/runtime/env/Environment")||!lookup.name.equals("__getClosure")||!lookup.desc.equals("(Ljava/lang/String;I)Lphp/runtime/reflection/ClassEntity;")
                ||prepare.getOpcode()!=INVOKESTATIC||!prepare.owner.equals("php/runtime/PhpOwnershipClosures")||!prepare.name.equals("prepareRuntime")||!prepare.desc.equals("(L"+RUNTIME+"$Frame;ILphp/runtime/reflection/helper/ClosureEntity;)Lphp/runtime/PhpOwnershipClosures$RuntimeAllocation;")
                ||runtimeLiterals.putIfAbsent(producer,new RuntimeLiteral(producer,closure,module,index,lookup,prepare))!=null)throw new IllegalArgumentException("Closure source lacks its actual module lookup and empty-shell allocation");
    }
    private record AddressSource(int producer,int local) {}
    private record RuntimeCv(int producer,int local,String closure,MethodInsnNode publication) {}
    private record RuntimeCall(int id,int local,int evaluation,int producer,String closure,int arity,MethodInsnNode factory,MethodInsnNode invoke,LabelNode start,LabelNode fallback,LabelNode end) {}
    private final Map<AddressSource,MethodInsnNode> runtimeAddressPublications=new LinkedHashMap<>();
    private final Map<AddressSource,RuntimeCv> runtimeClosureCvs=new LinkedHashMap<>();
    private final Map<Integer,RuntimeCall> runtimeCalls=new LinkedHashMap<>();
    private record RuntimeClone(int id,int local,int producer,String closure,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {}
    private final Map<Integer,RuntimeClone> runtimeClones=new LinkedHashMap<>();
    public void runtimeCloneSite(int id,int local,int producer,String closure,MethodInsnNode helper,LabelNode start,LabelNode fallback,LabelNode end) {
        AbstractInsnNode guard=start==null?null:start.getPrevious(),load=guard==null?null:guard.getPrevious();
        if(!(guard instanceof JumpInsnNode)||guard.getOpcode()!=IFNULL||((JumpInsnNode)guard).label!=fallback||!(load instanceof VarInsnNode)||load.getOpcode()!=ALOAD||((VarInsnNode)load).var!=runtimeFrameIndex()||fallback==null||end==null)throw new IllegalArgumentException("CLONE lacks its exact ordinary Frame guard");
        RuntimeCv cv=runtimeClosureCvs.get(new AddressSource(producer,local));
        if(!"CLONE".equals(runtimeProducers.get(id))||id==producer||local<0||cv==null||!cv.closure().equals(closure)||!runtimeClosureClass(producer).equals(closure)
                ||helper.getOpcode()!=INVOKESTATIC||!helper.owner.equals("php/runtime/PhpOwnershipClosures")||!helper.name.equals("cloneLocalRuntime")||!helper.desc.equals("(L"+RUNTIME+"$Frame;II"+MEMORY+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+MEMORY)
                ||runtimeClones.putIfAbsent(id,new RuntimeClone(id,local,producer,closure,helper,start,fallback,end))!=null)throw new IllegalArgumentException("CLONE lacks its exact literal-rooted CV publication");
    }
    private String runtimeClosureClass(int producer) {
        RuntimeLiteral literal=runtimeLiterals.get(producer);if(literal!=null)return literal.closure();
        RuntimeClone clone=runtimeClones.get(producer);if(clone!=null)return clone.closure();
        throw new IllegalArgumentException("Closure producer has no exact literal-rooted factory proof");
    }
    public MethodInsnNode runtimeAddressPublication(int producer,int local){return runtimeAddressPublications.get(new AddressSource(producer,local));}
    public void runtimeClosureCv(int producer,int local,String closure,MethodInsnNode publication) {
        AddressSource key=new AddressSource(producer,local);
        if(!Set.of("CLOSURE_LITERAL","CLONE").contains(runtimeProducers.get(producer))||closure==null||closure.isEmpty()||publication==null||runtimeAddressPublications.get(key)!=publication||runtimeClosureCvs.putIfAbsent(key,new RuntimeCv(producer,local,closure,publication))!=null)throw new IllegalArgumentException("Closure CV lacks its exact producer publication");
    }
    public void runtimeCallSite(int id,int local,int evaluation,int producer,String closure,int arity,MethodInsnNode factory,MethodInsnNode invoke,LabelNode start,LabelNode fallback,LabelNode end) {
        AbstractInsnNode guard=start==null?null:start.getPrevious(),load=guard==null?null:guard.getPrevious();
        if(!(guard instanceof JumpInsnNode)||guard.getOpcode()!=IFNULL||((JumpInsnNode)guard).label!=fallback||!(load instanceof VarInsnNode)||load.getOpcode()!=ALOAD||((VarInsnNode)load).var!=runtimeFrameIndex()||end==null||fallback==null)throw new IllegalArgumentException("CALL lacks its exact ordinary Frame guard");
        if(!runtimeClosureClass(producer).equals(closure))throw new IllegalArgumentException("CALL source has no exact literal-rooted factory proof");
        if(arity!=0||id==producer||(local<0)==(evaluation<0)||evaluation>=0&&evaluation!=producer||!Set.of("CLOSURE_LITERAL","CLONE").contains(runtimeProducers.get(producer))||closure==null||closure.isEmpty()
                ||factory.getOpcode()!=INVOKEVIRTUAL||!factory.owner.equals(RUNTIME+"$Frame")||!factory.name.equals("callable")||!factory.desc.equals("(I"+MEMORY+"IILphp/runtime/env/TraceInfo;I)L"+RUNTIME+"$CallPacket;")
                ||invoke.getOpcode()!=INVOKEVIRTUAL||!invoke.owner.equals(RUNTIME+"$CallPacket")||!Set.of("invokeToEval","invokeToEvalDiscarded").contains(invoke.name)||!invoke.desc.equals("(I)"+MEMORY))throw new IllegalArgumentException("CALL lacks its exact zero-argument source/opcode protocol");
        RuntimeCv cv=runtimeClosureCvs.get(new AddressSource(producer,local));if(local>=0&&(cv==null||!cv.closure().equals(closure)))throw new IllegalArgumentException("CALL CV was not published by its recorded Closure producer");
        if(runtimeCalls.putIfAbsent(id,new RuntimeCall(id,local,evaluation,producer,closure,arity,factory,invoke,start,fallback,end))!=null)throw new IllegalArgumentException("Duplicate CALL producer");
    }
    private final Set<MethodInsnNode> runtimeEchoes=Collections.newSetFromMap(new IdentityHashMap<>());
    public void runtimeEcho(MethodInsnNode actual) {
        String scalar="(L"+RUNTIME+"$Frame;"+MEMORY+"Lphp/runtime/env/Environment;)V",literal="(L"+RUNTIME+"$Frame;Ljava/lang/String;Lphp/runtime/env/Environment;)V";
        if(actual.getOpcode()!=INVOKESTATIC||!actual.owner.equals("php/runtime/PhpOwnershipNativeCalls")||!(actual.name.equals("echoRuntime")&&actual.desc.equals(scalar)||actual.name.equals("echoLiteralRuntime")&&actual.desc.equals(literal)))throw new IllegalArgumentException("Output lacks its exact scalar/literal frame helper");
        runtimeEchoes.add(actual);
    }
    private final Set<String> runtimeDebt=new LinkedHashSet<>();
    private final Map<LabelNode,LabelNode> runtimeActiveRanges=new LinkedHashMap<>();
    private int runtimeActiveDepth;
    public void beginRuntimeActiveEmission(){runtimeActiveDepth++;}
    public void endRuntimeActiveEmission(){if(runtimeActiveDepth==0)throw new IllegalStateException("Unbalanced active Session emission");runtimeActiveDepth--;}
    public void runtimeActiveBranch(LabelNode start,LabelNode end) {
        AbstractInsnNode guard=start==null?null:start.getPrevious(),load=guard==null?null:guard.getPrevious();
        if(start==null||end==null||start==end||entry==null||!(guard instanceof JumpInsnNode)||guard.getOpcode()!=IFNULL||!(load instanceof VarInsnNode)||load.getOpcode()!=ALOAD||((VarInsnNode)load).var!=entry.index||runtimeActiveRanges.putIfAbsent(start,end)!=null)throw new IllegalArgumentException("Ordinary exclusion lacks its exact active Session guard");
    }
    private final Map<LabelNode,LabelNode> runtimeFallbacks=new LinkedHashMap<>();
    private final Map<LabelNode,LabelNode> runtimeAddressRanges=new LinkedHashMap<>();
    private final Set<AbstractInsnNode> runtimeAddresses=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<LabelNode,LabelNode> runtimeStaticRanges=new LinkedHashMap<>();
    private final Set<AbstractInsnNode> runtimeStaticAddresses=Collections.newSetFromMap(new IdentityHashMap<>());
    public void runtimeStaticAddress(LabelNode start,LabelNode end) {
        if(!method.clazz.isClosure()||start==null||end==null||start==end||runtimeStaticRanges.putIfAbsent(start,end)!=null)throw new IllegalArgumentException("Invalid actual lexical static address span");
    }
    public void runtimeFallback(LabelNode start,LabelNode end) {
        if(start==null||end==null||start==end||runtimeFallbacks.putIfAbsent(start,end)!=null)
            throw new IllegalArgumentException("Invalid runtime passive producer range");
    }
    public void runtimeProducer(int id,String kind) {
        if(id<4_000_000||!Set.of("ARRAY_LITERAL","CLOSURE_LITERAL","CLONE","NATIVE_RESULT","CALL_RESULT","GLOBAL_RESULT","SCALAR_LITERAL","ARRAY_REF_RESULT","FUNCTION_RESULT","ARRAY_INCREMENT_RESULT","OBJECT_NEW","PROPERTY_REF_RESULT","JOIN_RESULT","CV_READ_RESULT","NULL_LITERAL","ENTRY_SCALAR_LITERAL","ENTRY_STRING_LITERAL","OBJECT_CAST_RESULT","LOCAL_INCREMENT_RESULT").contains(kind)
                ||runtimeProducers.putIfAbsent(id,kind)!=null)throw new IllegalArgumentException("Invalid duplicate runtime producer");
    }
    public void runtimeConsumer(int id,String route) {
        if(id<4_000_000||!Set.of("CV","GLOBAL","ENTRY","POP","RETURN","SEND","JOIN","CAST").contains(route))
            throw new IllegalArgumentException("Unknown runtime producer consumer");
        runtimeConsumers.computeIfAbsent(id,ignored->new LinkedHashSet<>()).add(route);
    }
    public void runtimeUnsupported(String reason) {
        if(reason==null||reason.isEmpty())throw new IllegalArgumentException("Missing runtime coverage debt");runtimeDebt.add(reason);
    }
    public void runtimeBindStaticLocal(InsnList code,LocalVariable local) {
        if(!runtimeEnabled()||!method.clazz.isClosure())throw new IllegalArgumentException("Static borrow lowering requires its lexical runtime route");
        scratch();code.add(new VarInsnNode(ASTORE,candidate.index));code.add(new VarInsnNode(ALOAD,runtimeFrameIndex()));
        code.add(new LdcInsnNode(local.index));code.add(new LdcInsnNode(local.name));code.add(new VarInsnNode(ALOAD,local.index));code.add(new VarInsnNode(ALOAD,candidate.index));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","bindStaticLocal","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILjava/lang/String;"+MEMORY+MEMORY+")"+MEMORY,false));
    }
    private static final String RUNTIME="php/runtime/PhpRuntimeOwnership";
    public LabelNode activeParameterDefault(InsnList code,int index) {
        if(!enabled())return null;
        LabelNode fallback=guardActive(code);
        code.add(new VarInsnNode(ALOAD,entry.index));code.add(new LdcInsnNode(index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,ENTRY,"parameterDefault","(I)"+MEMORY,false));
        return fallback;
    }
    public int reserveRuntimeEval(){return 4_000_000+runtimeEvaluationSequence++;}
    public int runtimeFrameIndex(){if(runtimeFrame==null)runtimeFrame=method.addLocalVariable("~runtimeOwnershipFrame",null,Object.class);return runtimeFrame.index;}
    private int importIndex = -1;
    private boolean captureImport, referenceImport;
    private boolean referencePromotion, finished, passiveEmission;
    private final Set<Integer> evaluations = new LinkedHashSet<>(), unconsumedEvaluations = new LinkedHashSet<>();
    private final Set<String> functionDependencies = new LinkedHashSet<>();
    private final List<Integer> thrownCustodies = new ArrayList<>(), returnRoutes = new ArrayList<>();

    public int thrownCustody() { int id = 2_000_000 + thrownCustodies.size(); thrownCustodies.add(id); return id; }
    public int returnRoute() { int id = 3_000_000 + returnRoutes.size(); returnRoutes.add(id); return id; }
    private final Map<Integer, LocalVariable> managed = new LinkedHashMap<>();
    private final Map<Integer, Import> imports = new LinkedHashMap<>();
    private final Map<LabelNode, LabelNode> passiveAddressRanges = new LinkedHashMap<>();
    private final Set<AbstractInsnNode> passiveInstructions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AbstractInsnNode> passiveCalls = Collections.newSetFromMap(new IdentityHashMap<>());

    private record Import(int index, boolean capture, boolean reference) { }
    private record ThrownScope(org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken body, int id) { }
    private final Deque<ThrownScope> thrownScopes = new ArrayDeque<>();
    public void beginThrownFinally(org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken body, int id) { thrownScopes.push(new ThrownScope(body, id)); }
    public void endThrownFinally() { thrownScopes.pop(); }

    public void leaveThrownScopes(InsnList code, List<org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken> targetBodies) {
        for (ThrownScope scope : thrownScopes) if (targetBodies.stream().noneMatch(body -> body == scope.body)) releaseThrown(code, scope.id);
    }

    OwnershipFrameCompiler(MethodStmtCompiler method) { this.method = method; }

    public boolean runtimeEnabled() {
        if(runtimeActiveDepth!=0 || !method.getCompiler().getModule().getOwnershipMode().supportsAdmission())return false;
        return !"<init>".equals(method.node.name)&&!"<clinit>".equals(method.node.name)
                &&(method.statement!=null||method.isInitializer())&&method.getCompiler().getModule().getPhpLanguageProfile()==PhpLanguageProfile.PHP_8_5;
    }
    public boolean enabled() {
        return method.getCompiler().getModule().getOwnershipMode().supportsAdmission() && !passiveEmission && !"<init>".equals(method.node.name) && !"<clinit>".equals(method.node.name)
                && (method.statement != null || method.isInitializer()) && method.getCompiler().getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
    }

    private final Set<Integer> receiverImports = new LinkedHashSet<>();
    /** Import only the actual JVM receiver into its planned ADDRESS CV, under the real Session Ticket. */
    public LabelNode receiverImport(InsnList code, LocalVariable local) {
        if (!enabled() || runtimeLifecycleEnabled() || method.clazz.isClosure() || method.getGeneratorEntity() != null
                || method.statement == null || method.statement.isStatic() || !local.name.equals("this")
                || !local.isOwnershipLocal()) return null;
        LabelNode fallback = guardActive(code), active = new LabelNode(), end = new LabelNode();
        code.add(active);
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(local.index));
        code.add(new VarInsnNode(ALOAD, method.getLocalVariable("~this").index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "importReceiver", "(ILphp/runtime/lang/IObject;)" + MEMORY, false));
        code.add(new VarInsnNode(ASTORE, local.index)); code.add(end);
        runtimeActiveBranch(active, end);
        if (!receiverImports.add(local.index)) throw new IllegalStateException("Receiver CV imported twice");
        return fallback;
    }
    /** The active import proved non-null; preserve the complete ordinary error path otherwise. */
    public void receiverReadGuard(InsnList code, LocalVariable local, LabelNode present) {
        if (!enabled() || local == null || !receiverImports.contains(local.index)) return;
        LabelNode ordinary = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNONNULL, present));
        code.add(ordinary); passiveAddressRanges.put(ordinary, present);
    }

    void defineEntry(LabelNode start) {
        if (enabled()) entry = method.addLocalVariable("~ownershipEntry", start, Object.class);
    }

    public void manage(LocalVariable local) { if (enabled()) { local.setOwnershipLocal(true); managed.put(local.index, local); } }
    void importing(int index, boolean capture, boolean reference) {
        importIndex = index; captureImport = capture; referenceImport = reference;
    }
    void endImport() { importIndex = -1; }

    private boolean address(LocalVariable local) {
        return method.statement != null && method.statement.isDynamicLocal() || local.isReference();
    }

    private void scratch() {
        if (candidate == null) {
            candidate = method.addLocalVariable("~ownershipCandidate", null, Memory.class);
            publication = method.addLocalVariable("~ownershipPublication", null, Object.class);
        }
    }

    LabelNode table(InsnList code) {
        if (!enabled()) return null;
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "localTable", "()Lphp/runtime/memory/ArrayMemory;", false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        passiveAddressRanges.put(legacy, end);
        return end;
    }

    public void literalEcho(InsnList code) {
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new MethodInsnNode(INVOKESTATIC, "php/runtime/PhpOwnershipNativeCalls", "echoLiteral", "(Lphp/runtime/env/Environment;Ljava/lang/String;)V", false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        code.add(new VarInsnNode(ALOAD,runtimeFrameIndex()));code.add(new InsnNode(DUP_X2));code.add(new InsnNode(POP));code.add(new InsnNode(SWAP));
        MethodInsnNode fallback = new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpOwnershipNativeCalls","echoLiteralRuntime","(L"+RUNTIME+"$Frame;Ljava/lang/String;Lphp/runtime/env/Environment;)V",false);
        code.add(fallback);runtimeEcho(fallback);passiveCalls.add(fallback);code.add(end);
    }

    public void tick(InsnList code) {
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new MethodInsnNode(INVOKESTATIC, "php/runtime/PhpOwnershipNativeCalls", "tickEmpty", "(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/memory/ArrayMemory;)V", false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        MethodInsnNode fallback = new MethodInsnNode(INVOKEVIRTUAL, "php/runtime/env/Environment", "__tick", "(Lphp/runtime/env/TraceInfo;Lphp/runtime/memory/ArrayMemory;)V", false);
        code.add(fallback); passiveCalls.add(fallback); code.add(end);
    }

    /** Branch leaves the exact borrowed carrier on the JVM stack; legacy emission fills the other arm. */
    public LabelNode address(InsnList code, LocalVariable local) {
        if (!enabled() || !local.isOwnershipLocal()) return null;
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(local.index));
        code.add(new LdcInsnNode(local.name));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "localAddress", "(ILjava/lang/String;)" + MEMORY, false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        passiveAddressRanges.put(legacy, end);runtimeAddressRanges.put(legacy,end);
        return end;
    }

    public LabelNode guardActive(InsnList code) {
        if (entry == null) { method.reserveEmittedLocals(); entry = method.addLocalVariable("~ownershipEntry", null, Object.class); }
        LabelNode legacy = new LabelNode(); code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy)); return legacy;
    }
    public void beginPassive() { if (passiveEmission) throw new IllegalStateException("Nested passive frame emission"); passiveEmission = true; }
    public void endPassive(LabelNode start, LabelNode end) { passiveEmission = false; passiveAddressRanges.put(start, end); }

    /** Named actual EVAL and declaration dependency, consumed through the existing CV/SEND/RETURN routes. */
    public int closureEvaluation(int closureId,boolean cells) {
        if(!enabled())throw new IllegalStateException("Lexical producer requires an emitted Session frame");
        int id=1_000_000+evaluations.size();evaluations.add(id);unconsumedEvaluations.add(id);
        functionDependencies.add((cells?"closure-capture-producer-v2:":"closure-value-producer-v1:")+id+":"+closureId);return id;
    }
    public int entryIndex(){if(entry==null)throw new IllegalStateException("Entry local was not defined");return entry.index;}
    /** Only exact calls emitted by the active producer arm, not a class/name whitelist. */
    public void closureProducerCall(MethodInsnNode call) {
        String allocation="php/runtime/PhpOwnershipClosures$SessionAllocation";
        if(call.getOpcode()!=INVOKEVIRTUAL || !call.owner.equals(allocation)
                || !(call.name.equals("capture") && call.desc.equals("(II"+MEMORY+"[Lphp/runtime/env/TraceInfo;I)V")
                    || call.name.equals("captureReference") && call.desc.equals("(II"+MEMORY+")V")
                    || call.name.equals("complete") && call.desc.equals("()"+MEMORY)))
            throw new IllegalArgumentException("Not this lexical VALUE producer helper");
        passiveCalls.add(call);
    }
    /** Keep the canonical capture on the active route; the ordinary wrapper/import route is unchanged. */
    public LabelNode[] captureReferenceHeader(InsnList code,LocalVariable local,int index) {
        if(!enabled())return null;scratch();imports.put(local.index,new Import(index,true,true));
        LabelNode fallback=new LabelNode(),active=new LabelNode(),end=new LabelNode();
        code.add(new VarInsnNode(ASTORE,candidate.index));
        code.add(new VarInsnNode(ALOAD,entry.index));code.add(new JumpInsnNode(IFNULL,fallback));code.add(active);
        code.add(new VarInsnNode(ALOAD,entry.index));code.add(new LdcInsnNode(index));code.add(new LdcInsnNode(local.index));
        code.add(new VarInsnNode(ALOAD,candidate.index));code.add(new InsnNode(ICONST_1));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,ENTRY,"importCapture","(II"+MEMORY+"I)"+MEMORY,false));
        code.add(new VarInsnNode(ASTORE,local.index));code.add(new JumpInsnNode(GOTO,end));code.add(fallback);
        code.add(new VarInsnNode(ALOAD,candidate.index));runtimeActiveBranch(active,fallback);
        return new LabelNode[]{fallback,end};
    }
    public void endCaptureReferenceHeader(InsnList code,LabelNode[] branch) {
        if(branch!=null){code.add(branch[1]);passiveAddressRanges.put(branch[0],branch[1]);}
    }
    /** Active-only raw owner unlink; old manualUnset/Authority behavior remains in the fallback. */
    public LabelNode[] unsetLocal(InsnList code,LocalVariable local) {
        if(!enabled() || !local.isOwnershipLocal() || !address(local))return null;
        LabelNode fallback=guardActive(code),active=new LabelNode(),end=new LabelNode();code.add(active);
        code.add(new VarInsnNode(ALOAD,entry.index));code.add(new LdcInsnNode(local.index));code.add(new VarInsnNode(ALOAD,local.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,ENTRY,"unsetLocal","(I"+MEMORY+")V",false));
        code.add(new JumpInsnNode(GOTO,end));code.add(fallback);runtimeActiveBranch(active,fallback);
        functionDependencies.add("lexical-cv-unset-v2:"+local.index);return new LabelNode[]{fallback,end};
    }
    public void endUnsetLocal(InsnList code,LabelNode[] branch) {
        if(branch!=null){code.add(branch[1]);passiveAddressRanges.put(branch[0],branch[1]);}
    }
    private boolean captureClosureImports() {
        return method.clazz.isClosure() && method.statement!=null && !method.statement.isThisExists()
                && method.statement.getStaticLocal().isEmpty() && !method.statement.isGenerator();
    }
    public int functionFrame(InsnList code, String name, int arity) {
        if (entry == null) { method.reserveEmittedLocals(); entry = method.addLocalVariable("~ownershipEntry", null, Object.class); }
        int id = 1_000_000 + evaluations.size(); evaluations.add(id); unconsumedEvaluations.add(id);
        functionDependencies.add("function-call:" + name + ":" + arity);
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id));
        code.add(new MethodInsnNode(INVOKESTATIC, "php/runtime/invoke/PhpCallFrame", "functionWithOwnership",
                "(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Ljava/lang/String;Ljava/lang/String;Lphp/runtime/invoke/cache/FunctionCallCache;IIL" + ENTRY + ";I)Lphp/runtime/invoke/PhpCallFrame;", false));
        return id;
    }

    public void sendArgument(InsnList code, int evalId) {
        scratch();
        if (argumentKind == null) argumentKind = method.addLocalVariable("~ownershipArgumentKind", null, int.class);
        code.add(new VarInsnNode(ISTORE, argumentKind.index)); code.add(new VarInsnNode(ASTORE, candidate.index));
        LabelNode legacy = new LabelNode(), send = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        // Active call preflight allows only VALUE parameters: read the exact CV/address before SEND.
        code.add(new VarInsnNode(ALOAD, candidate.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, "php/runtime/Memory", "toImmutable", "()" + MEMORY, false));
        code.add(new JumpInsnNode(GOTO, send)); code.add(legacy); code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(send);
        code.add(new VarInsnNode(ILOAD, argumentKind.index)); code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(evalId));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, "php/runtime/invoke/PhpCallFrame", "sendCompiler",
                "(" + MEMORY + "IL" + ENTRY + ";I)V", false));
        consumed(evalId);
    }

    public void invokeToEval(InsnList code, int id) {invokeToEval(code,id,false);}
    public void invokeToEval(InsnList code, int id, boolean discarded) {
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, "php/runtime/invoke/PhpCallFrame", discarded ? "invokeToEvalDiscarded" : "invokeToEval", "(L" + ENTRY + ";I)" + MEMORY, false));
    }

    public void releaseEvaluation(InsnList code, int id) {
        if (id < 0) return;
        LabelNode end = new LabelNode(); code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, end));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "releaseEval", "(I)V", false)); code.add(end); consumed(id);
    }

    private void consumed(int id) { if (id >= 0) unconsumedEvaluations.remove(id); }

    /** The candidate is borrowed from its producing root until prepare retains it. No callback separates store/commit. */
    public boolean store(InsnList code, LocalVariable local) { return store(code, local, -1); }

    public boolean store(InsnList code, LocalVariable local, int evalId) {
        if (!enabled() || !local.isOwnershipLocal()) return false;
        scratch();
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, candidate.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index));
        if (evalId >= 0) code.add(new LdcInsnNode(evalId));
        else if (importIndex >= 0 && !address(local)) code.add(new LdcInsnNode(importIndex));
        code.add(new LdcInsnNode(local.index)); code.add(new VarInsnNode(ALOAD, candidate.index));
        code.add(new InsnNode(address(local) ? ICONST_2 : referenceImport && importIndex >= 0 ? ICONST_1 : ICONST_0));
        String name = evalId >= 0 ? "prepareLocalFromEval" : importIndex >= 0 && !address(local) ? captureImport ? "prepareImportedCapture" : "prepareImportedArgument" : "prepareLocal";
        String desc = evalId >= 0 || name.equals("prepareImportedArgument") || name.equals("prepareImportedCapture") ? "(II" + MEMORY + "I)L" + PUBLICATION + ";" : "(I" + MEMORY + "I)L" + PUBLICATION + ";";
        // ADDRESS initialization is an address reservation, not an argument import.
        if (importIndex >= 0 && !captureImport && address(local)) throw new IllegalStateException("Address import must use assignLocal");
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, name, desc, false));
        code.add(new VarInsnNode(ASTORE, publication.index));
        code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new VarInsnNode(ASTORE, local.index));
        commit(code);
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        LabelNode ordinaryEnd=new LabelNode();
        code.add(new VarInsnNode(ALOAD,runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,ordinaryEnd));
        if(runtimePublication==null)runtimePublication=method.addLocalVariable("~runtimePublication",null,Object.class);
        code.add(new VarInsnNode(ALOAD,runtimeFrame.index));code.add(new LdcInsnNode(local.index));code.add(new LdcInsnNode(local.name));
        code.add(new VarInsnNode(ALOAD,candidate.index));code.add(new InsnNode(address(local)?ICONST_2:referenceImport&&importIndex>=0?ICONST_1:ICONST_0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Frame","prepareLocal","(ILjava/lang/String;"+MEMORY+"I)L"+RUNTIME+"$Publication;",false));
        code.add(new VarInsnNode(ASTORE,runtimePublication.index));
        code.add(new VarInsnNode(ALOAD,candidate.index));code.add(new VarInsnNode(ASTORE,local.index));
        code.add(new VarInsnNode(ALOAD,runtimePublication.index));code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Publication","afterLocalStore","()V",false));
        code.add(new VarInsnNode(ALOAD,runtimePublication.index));code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Publication","commit","()V",false));
        code.add(new JumpInsnNode(GOTO,end));code.add(ordinaryEnd);
        code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new VarInsnNode(ASTORE, local.index)); code.add(end);
        if (importIndex >= 0) imports.put(local.index, new Import(importIndex, captureImport, referenceImport));
        consumed(evalId); return true;
    }

    public boolean assign(InsnList code, LocalVariable local, boolean cell) { return assign(code, local, cell, -1); }

    public boolean assign(InsnList code,LocalVariable local,boolean cell,int activeEval,int runtimeEval,boolean returned) {
        runtimeObjectLocals.remove(local.index);
        if(runtimeEval<0)return assign(code,local,cell,activeEval);
        if(!runtimeEnabled()||!local.isOwnershipLocal())return false;
        if(returned) {
            runtimeUnsupported("returned-address-assignment:"+runtimeEval);
            return assign(code,local,cell,activeEval);
        }
        scratch();LabelNode fallback=new LabelNode(),end=new LabelNode();
        code.add(new VarInsnNode(ASTORE,candidate.index));code.add(new VarInsnNode(ALOAD,runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,fallback));
        code.add(new VarInsnNode(ALOAD,runtimeFrame.index));code.add(new LdcInsnNode(runtimeEval));code.add(new LdcInsnNode(local.index));code.add(new VarInsnNode(ALOAD,candidate.index));code.add(new InsnNode(cell?ICONST_1:ICONST_0));
        MethodInsnNode actualPublication=new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Frame","assignLocalFromEval","(II"+MEMORY+"I)"+MEMORY,false);code.add(actualPublication);
        runtimeAddressPublications.put(new AddressSource(runtimeEval,local.index),actualPublication);
        if(!cell&&"OBJECT_NEW".equals(runtimeProducers.get(runtimeEval))) {
            RuntimeObjectNew source=runtimeObjectNews.get(runtimeEval);
            if(source!=null){RuntimeObjectCv witness=new RuntimeObjectCv(runtimeEval,local.index,source.owner(),actualPublication);runtimeObjectLocals.put(local.index,witness);runtimeObjectCvs.put(new AddressSource(runtimeEval,local.index),witness);}
        }
        code.add(new JumpInsnNode(GOTO,end));code.add(fallback);code.add(new VarInsnNode(ALOAD,candidate.index));
        if(!assign(code,local,cell,activeEval)) {
            code.add(new VarInsnNode(ALOAD,local.index));MethodInsnNode old=new MethodInsnNode(INVOKESTATIC,"php/runtime/Memory",cell?"assignRefRight":"assignRight","("+MEMORY+MEMORY+")"+MEMORY,false);code.add(old);passiveCalls.add(old);
        }
        code.add(end);runtimeConsumer(runtimeEval,"CV");return true;
    }

    public boolean assign(InsnList code, LocalVariable local, boolean cell, int evalId) {
        if (!enabled() || !local.isOwnershipLocal()) return false;
        scratch();
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        if (cell) referencePromotion = true;
        code.add(new VarInsnNode(ASTORE, candidate.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index));
        if (evalId >= 0) code.add(new LdcInsnNode(evalId));
        else if (importIndex >= 0) code.add(new LdcInsnNode(importIndex));
        code.add(new LdcInsnNode(local.index)); code.add(new VarInsnNode(ALOAD, candidate.index));
        code.add(new InsnNode((importIndex >= 0 ? referenceImport : cell) ? ICONST_1 : ICONST_0));
        String name = evalId >= 0 ? "assignLocalFromEval" : importIndex < 0 ? "assignLocal" : captureImport ? "importCapture" : "importArgument";
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, name,
                (importIndex < 0 && evalId < 0 ? "(I" : "(II") + MEMORY + "I)" + MEMORY, false));
        if (importIndex >= 0) {
            // Header tests the actual argument for Java null, not the always-present borrowed carrier.
            code.add(new InsnNode(POP)); code.add(new VarInsnNode(ALOAD, candidate.index));
            imports.put(local.index, new Import(importIndex, captureImport, referenceImport));
        }
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        LabelNode ordinaryEnd=new LabelNode();code.add(new VarInsnNode(ALOAD,runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,ordinaryEnd));
        code.add(new VarInsnNode(ALOAD,runtimeFrame.index));
        if(importIndex>=0)code.add(new LdcInsnNode(importIndex));code.add(new LdcInsnNode(local.index));code.add(new VarInsnNode(ALOAD,candidate.index));
        code.add(new InsnNode((importIndex>=0?referenceImport:cell)?ICONST_1:ICONST_0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Frame",importIndex<0?"assignLocal":captureImport?"importCapture":"importArgument",
                (importIndex<0?"(I":"(II")+MEMORY+"I)"+MEMORY,false));
        if(importIndex>=0){code.add(new InsnNode(POP));code.add(new VarInsnNode(ALOAD,candidate.index));}
        code.add(new JumpInsnNode(GOTO,end));code.add(ordinaryEnd);
        code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new VarInsnNode(ALOAD, local.index));
        MethodInsnNode fallback = new MethodInsnNode(INVOKESTATIC, "php/runtime/Memory", cell ? "assignRefRight" : "assignRight",
                "(" + MEMORY + MEMORY + ")" + MEMORY, false);
        code.add(fallback); passiveCalls.add(fallback);
        if(importIndex>=0&&!captureImport)method.primitiveCells().argumentImport(fallback,importIndex,referenceImport);
        code.add(end);
        consumed(evalId); return true;
    }

    private void commit(InsnList code) {
        code.add(new VarInsnNode(ALOAD, publication.index));
        code.add(new MethodInsnNode(INVOKEINTERFACE, PUBLICATION, "commit", "()V", true));
    }

    public boolean normalizeReturn(InsnList code) {
        if (!enabled()) return false;
        scratch(); LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, candidate.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new VarInsnNode(ALOAD, candidate.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, "php/runtime/Memory", "toImmutable", "()" + MEMORY, false));
        code.add(new InsnNode(ICONST_0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "normalizeReturn", "(" + MEMORY + "I)" + MEMORY, false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy); code.add(new VarInsnNode(ALOAD, candidate.index));
        MethodInsnNode fallback = new MethodInsnNode(INVOKESTATIC, "php/runtime/invoke/InvokeHelper", "normalizeExplicitReturn", "(" + MEMORY + ")" + MEMORY, false);
        code.add(fallback); runtimeReturnBoundaries.put(fallback,candidate.index); passiveCalls.add(fallback); code.add(end); return true;
    }

    public void captureThrown(InsnList code, int id) {
        if (!enabled()) return;
        if (throwableCandidate == null) throwableCandidate = method.addLocalVariable("~ownershipThrowable", null, Object.class);
        LabelNode legacy = new LabelNode(), end = new LabelNode(); code.add(new VarInsnNode(ASTORE, throwableCandidate.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id)); code.add(new VarInsnNode(ALOAD, throwableCandidate.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "captureThrown", "(ILphp/runtime/lang/exception/BaseBaseException;)Lphp/runtime/lang/exception/BaseBaseException;", false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy); code.add(new VarInsnNode(ALOAD, throwableCandidate.index)); code.add(end);
    }

    public boolean rethrowThrown(InsnList code, int id, LocalVariable raw) {
        if (!enabled()) return false;
        LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "rethrowThrown", "(I)Lphp/runtime/lang/exception/BaseBaseException;", false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy); code.add(new VarInsnNode(ALOAD, raw.index)); code.add(end); return true;
    }

    public boolean chainThrown(ExpressionStmtCompiler expr) {
        if (!enabled()) return false;
        if (throwableCandidate == null) throwableCandidate = method.addLocalVariable("~ownershipThrowable", null, Object.class);
        if (throwableReplacement == null) throwableReplacement = method.addLocalVariable("~ownershipReplacement", null, Object.class);
        InsnList code = method.node.instructions; LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, throwableCandidate.index)); code.add(new VarInsnNode(ASTORE, throwableReplacement.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new VarInsnNode(ALOAD, throwableReplacement.index)); code.add(new VarInsnNode(ALOAD, throwableCandidate.index));
        String descriptor = "(Lphp/runtime/lang/exception/BaseBaseException;Lphp/runtime/lang/exception/BaseBaseException;)Lphp/runtime/lang/exception/BaseBaseException;";
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "chainThrown", descriptor, false)); code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        code.add(new VarInsnNode(ALOAD, throwableReplacement.index)); code.add(new VarInsnNode(ALOAD, throwableCandidate.index));
        MethodInsnNode fallback = new MethodInsnNode(INVOKESTATIC, "php/runtime/invoke/InvokeHelper", "chainFinallyException", descriptor, false);
        code.add(fallback); passiveCalls.add(fallback); code.add(end); expr.stackPop(); return true;
    }

    public void releaseThrown(InsnList code, int id) { custodyVoid(code, "releaseThrown", id); }
    public void abortReturn(InsnList code, int id) { custodyVoid(code, "abortReturn", id); }
    private void custodyVoid(InsnList code, String name, int id) {
        if (!enabled() || id < 0) return;
        LabelNode end = new LabelNode(); code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, end));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id)); code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, name, "(I)V", false)); code.add(end);
    }

    public boolean matchThrown(ExpressionStmtCompiler expr, int id, LocalVariable raw, String name) {
        if (!enabled()) return false;
        InsnList code = method.node.instructions; LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id)); code.add(new LdcInsnNode(name)); code.add(new LdcInsnNode(name.toLowerCase()));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "matchThrown", "(ILjava/lang/String;Ljava/lang/String;)" + MEMORY, false));
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy); beginPassive();
        try { expr.writePushEnv(); expr.writeVarLoad(raw); expr.writePushConstString(name); expr.writePushConstString(name.toLowerCase());
            expr.writeSysDynamicCall(php.runtime.env.Environment.class, "__throwCatch", Memory.class, php.runtime.lang.exception.BaseBaseException.class, String.class, String.class);
        } finally { endPassive(legacy, end); }
        code.add(end); return true;
    }

    public boolean assignCatch(ExpressionStmtCompiler expr, int id, LocalVariable local, org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken token) {
        if (!enabled()) return false;
        scratch(); InsnList code = method.node.instructions; LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, candidate.index)); code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id)); code.add(new LdcInsnNode(local.index)); code.add(new VarInsnNode(ALOAD, candidate.index));
        if (address(local)) {
            code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "assignCatchLocal", "(II" + MEMORY + ")" + MEMORY, false)); code.add(new InsnNode(POP));
        } else {
            code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "prepareCatchLocal", "(II" + MEMORY + ")L" + PUBLICATION + ";", false));
            code.add(new VarInsnNode(ASTORE, publication.index)); code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new VarInsnNode(ASTORE, local.index)); commit(code);
        }
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy); code.add(new VarInsnNode(ALOAD, candidate.index)); beginPassive();
        try { expr.writeVarAssign(local, token, false, false); } finally { endPassive(legacy, end); }
        code.add(end); return true;
    }

    public boolean explicitThrow(ExpressionStmtCompiler expr, org.develnext.jphp.core.tokenizer.token.stmt.ThrowStmtToken token, int evalId) {
        if (!enabled()) return false;
        scratch(); int id = thrownCustody(); InsnList code = method.node.instructions; LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, candidate.index)); code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(id)); code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new LdcInsnNode(evalId));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "prepareExplicitThrow", "(I" + MEMORY + "I)Lphp/runtime/lang/exception/BaseBaseException;", false));
        code.add(new InsnNode(ATHROW)); code.add(legacy); beginPassive();
        try { expr.writePushEnv(); expr.writePushTraceInfo(token.getException()); code.add(new VarInsnNode(ALOAD, candidate.index)); expr.stackPush(Memory.Type.REFERENCE);
            expr.writeSysDynamicCall(php.runtime.env.Environment.class, "__throwException", void.class, php.runtime.env.TraceInfo.class, Memory.class);
        } finally { endPassive(legacy, end); }
        code.add(end); expr.stackPop(); consumed(evalId); return true;
    }

    public void verifyReturnBeforeFinally(ExpressionStmtCompiler expr, org.develnext.jphp.core.tokenizer.token.stmt.ReturnStmtToken token, int evalId) {
        scratch(); InsnList code = method.node.instructions;
        code.add(new VarInsnNode(ASTORE, candidate.index));
        code.add(new VarInsnNode(ALOAD, method.getLocalVariable("~env").index));
        code.add(new LdcInsnNode(Type.getObjectType(method.clazz.node.name))); code.add(new LdcInsnNode(method.node.name));
        expr.writePushTraceInfo(token); expr.stackPop();
        code.add(new VarInsnNode(ALOAD, candidate.index));
        if (enabled()) {
            code.add(new InsnNode(method.getEntity().isReturnReference() ? ICONST_1 : ICONST_0)); code.add(new LdcInsnNode(evalId));
            code.add(new MethodInsnNode(INVOKESTATIC, EXECUTION, "verifyReturnBeforeFinally", "(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Lphp/runtime/env/TraceInfo;" + MEMORY + "II)" + MEMORY, false));
        } else {
            code.add(new MethodInsnNode(INVOKESTATIC, "php/runtime/invoke/InvokeHelper", "verifyReturnBeforeFinally", "(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Lphp/runtime/env/TraceInfo;" + MEMORY + ")" + MEMORY, false));
        }
    }

    public void runtimePendingReturn(InsnList code,boolean cell,int evalId) {
        if(!enabled()||method.statement!=null&&method.statement.isGenerator())return;
        if(evalId>=0)runtimeConsumer(evalId,"RETURN");scratch();
        code.add(new VarInsnNode(ASTORE,candidate.index));LabelNode end=new LabelNode();
        code.add(new VarInsnNode(ALOAD,runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,end));
        if(runtimePublication==null)runtimePublication=method.addLocalVariable("~runtimePublication",null,Object.class);
        code.add(new VarInsnNode(ALOAD,runtimeFrame.index));code.add(new VarInsnNode(ALOAD,candidate.index));code.add(new LdcInsnNode(evalId));code.add(new InsnNode(cell?ICONST_1:ICONST_0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Frame","prepareReturn","("+MEMORY+"II)L"+RUNTIME+"$Publication;",false));
        code.add(new VarInsnNode(ASTORE,runtimePublication.index));code.add(new VarInsnNode(ALOAD,runtimePublication.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Publication","storeDirect","()V",false));
        code.add(new VarInsnNode(ALOAD,runtimePublication.index));code.add(new MethodInsnNode(INVOKEVIRTUAL,RUNTIME+"$Publication","commit","()V",false));
        code.add(end);code.add(new VarInsnNode(ALOAD,candidate.index));
    }

    public void pendingReturn(InsnList code, boolean cell) { pendingReturn(code, cell, -1, Integer.MAX_VALUE - 1); }

    public void pendingReturn(InsnList code, boolean cell, int evalId, int returnId) {
        if (!enabled() || method.statement != null && method.statement.isGenerator()) return;
        scratch();
        LabelNode end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, candidate.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, end));
        code.add(new VarInsnNode(ALOAD, entry.index));
        code.add(new LdcInsnNode(returnId));
        code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new InsnNode(cell ? ICONST_1 : ICONST_0)); code.add(new LdcInsnNode(evalId));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "prepareScopedReturn", "(I" + MEMORY + "II)L" + PUBLICATION + ";", false));
        code.add(new VarInsnNode(ASTORE, publication.index));
        // Runtime pending-return mirror is the exact raw publication; no JVM return occurs here.
        commit(code); code.add(end); code.add(new VarInsnNode(ALOAD, candidate.index)); consumed(evalId);
    }

    public boolean forwardReturn(InsnList code, int sourceId, int targetId, LocalVariable target) {
        if (!enabled()) return false;
        scratch(); LabelNode legacy = new LabelNode(), end = new LabelNode();
        code.add(new VarInsnNode(ASTORE, candidate.index));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new JumpInsnNode(IFNULL, legacy));
        code.add(new VarInsnNode(ALOAD, entry.index)); code.add(new LdcInsnNode(sourceId)); code.add(new LdcInsnNode(targetId)); code.add(new VarInsnNode(ALOAD, candidate.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, ENTRY, "prepareReturnTransfer", "(II" + MEMORY + ")L" + PUBLICATION + ";", false));
        code.add(new VarInsnNode(ASTORE, publication.index));
        code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new VarInsnNode(ASTORE, target.index)); commit(code);
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy);
        code.add(new VarInsnNode(ALOAD, candidate.index)); code.add(new VarInsnNode(ASTORE, target.index)); code.add(end);
        return true;
    }

    void finish() {
        if (!enabled() || finished) return;
        LocalVariable env = method.getLocalVariable("~env");
        if (env == null) return;
        finished = true;
        if (entry == null) { method.reserveEmittedLocals(); entry = method.addLocalVariable("~ownershipEntry", null, Object.class); }
        if (method.isInitializer() && Type.getReturnType(method.node.desc).equals(Type.getType(Memory.class)))
            for (AbstractInsnNode instruction : method.node.instructions.toArray()) if (instruction.getOpcode() == ARETURN) {
                InsnList retention = new InsnList(); pendingReturn(retention, false); method.node.instructions.insertBefore(instruction, retention);
            }
        InsnList prefix = new InsnList();
        prefix.add(new VarInsnNode(ALOAD, env.index)); prefix.add(new LdcInsnNode(Type.getObjectType(method.clazz.node.name)));
        prefix.add(new LdcInsnNode(method.node.name)); prefix.add(new LdcInsnNode(method.node.desc));
        prefix.add(new MethodInsnNode(INVOKESTATIC, EXECUTION, "enterGenerated",
                "(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)L" + ENTRY + ";", false));
        prefix.add(new VarInsnNode(ASTORE, entry.index));
        prefix.add(new VarInsnNode(ALOAD,env.index));prefix.add(new LdcInsnNode(Type.getObjectType(method.clazz.node.name)));
        prefix.add(new LdcInsnNode(method.node.name));prefix.add(new LdcInsnNode(method.node.desc));
        prefix.add(new MethodInsnNode(INVOKESTATIC,RUNTIME,"enterGenerated","(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)L"+RUNTIME+"$Frame;",false));
        prefix.add(new VarInsnNode(ASTORE,runtimeFrameIndex()));method.node.instructions.insert(prefix);
        for (var range : passiveAddressRanges.entrySet())
            for (AbstractInsnNode i = range.getKey().getNext(); i != range.getValue(); i = i.getNext())
                { passiveInstructions.add(i); if (i instanceof MethodInsnNode) passiveCalls.add(i); }
        for(var range:runtimeAddressRanges.entrySet())for(AbstractInsnNode cursor=range.getKey().getNext();cursor!=range.getValue();cursor=cursor.getNext()) {
            if(cursor==null)throw new IllegalStateException("Borrowed address route escapes the method");runtimeAddresses.add(cursor);
        }
        for(var range:runtimeStaticRanges.entrySet())for(AbstractInsnNode cursor=range.getKey().getNext();cursor!=range.getValue();cursor=cursor.getNext()) {
            if(cursor==null)throw new IllegalStateException("Lexical static address span escapes its method");runtimeStaticAddresses.add(cursor);
        }
        for(AbstractInsnNode instruction:new ArrayList<>(runtimeAddresses))if(instruction instanceof MethodInsnNode call&&call.owner.equals("php/runtime/Memory")&&call.name.equals("refOfIndex")&&call.desc.equals("(Ljava/lang/String;)"+MEMORY)) {
            method.node.instructions.insertBefore(call,new VarInsnNode(ALOAD,runtimeFrameIndex()));
            call.setOpcode(INVOKESTATIC);call.owner=RUNTIME;call.name="localAddress";call.desc="("+MEMORY+"Ljava/lang/String;Lphp/runtime/PhpRuntimeOwnership$Frame;)"+MEMORY;call.itf=false;
        }
        method.node.attrs.add(new FrameAttribute(encode()));

    }

    /** Serialize instruction provenance after the declaration property-context prefix/footer exists. */
    void finishTransport() {
        if(!finished)return;
        method.node.attrs.add(new RuntimeFrameAttribute(encodeRuntime()));
    }
    private byte[] encodeRuntime() {
        try {
            Plan base=decode(encode());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            boolean schema24=runtimeNatives.values().stream().anyMatch(RuntimeNative::discarded);
            boolean schema23=schema24||!runtimeNamedGlobals.isEmpty()||!runtimeEntryStrings.isEmpty()||!runtimeReturnBoundaries.isEmpty();
            boolean schema22=schema23||!runtimeComparisonFallbackSites.isEmpty();
            boolean schema21=schema22||!runtimeLocalIncrements.isEmpty();
            boolean schema20=schema21||!runtimeLiteralEntries.isEmpty();
            boolean schema19=schema20||!runtimeObjectCasts.isEmpty();
            boolean schema18=schema19||!runtimeEntryScalars.isEmpty();
            boolean schema17=schema18||!runtimeGlobalNulls.isEmpty();
            boolean schema16=schema17||runtimeLifecycleEnabled();
            boolean schema15=schema16||!runtimeConditionals.isEmpty()||!runtimeQuietGlobalReads.isEmpty()||!runtimeNativeScalarArguments.isEmpty()||!runtimeNativeScalarResults.isEmpty()||!runtimeConditionalLocals.isEmpty();
            boolean schema14=schema15||!runtimePropertyReferences.isEmpty();
            boolean schema13=schema14||!runtimeObjectNews.isEmpty();
            boolean schema12=schema13||!runtimeArrayIncrements.isEmpty();
            boolean schema11=schema12||!runtimeFunctions.isEmpty();
            boolean schema10=schema11||!runtimeArrayReferences.isEmpty();
            boolean schema9=schema10||!runtimeGlobalScalars.isEmpty();
            boolean schema8=schema9||!runtimeGlobalReads.isEmpty()||!runtimeGlobalUnsets.isEmpty();
            boolean schema7=schema8||!runtimeGlobalImports.isEmpty()||!runtimeGlobalAccesses.isEmpty();
            boolean schema6=schema7||!runtimeArrayEntries.isEmpty()||!runtimeHandlers.isEmpty();
            out.writeInt(schema24?24:schema23?23:schema22?22:schema21?21:schema20?20:schema19?19:schema18?18:schema17?17:schema16?16:schema15?15:schema14?14:schema13?13:schema12?12:schema11?11:schema10?10:schema9?9:schema8?8:schema7?7:schema6?6:!runtimeClones.isEmpty()?5:runtimeCalls.isEmpty()&&runtimeClosureCvs.isEmpty()&&runtimeLiterals.isEmpty()?3:4);out.writeUTF(base.owner());out.writeUTF(base.method());out.writeUTF(base.descriptor());
            out.writeInt(runtimeProducers.size());
            for(var producer:runtimeProducers.entrySet()) {
                out.writeInt(producer.getKey());out.writeUTF(producer.getValue());
                Set<String> routes=runtimeConsumers.getOrDefault(producer.getKey(),Set.of());
                out.writeInt(routes.size());for(String route:routes)out.writeUTF(route);
                if(routes.isEmpty())runtimeDebt.add("unconsumed-runtime-producer:"+producer.getKey());
            }
            for(int id:runtimeConsumers.keySet())if(!runtimeProducers.containsKey(id))runtimeDebt.add("consumer-without-runtime-producer:"+id);
            Set<String> debt=new LinkedHashSet<>(runtimeDebt);
            for(String active:base.unsupported()) {
                if(active.startsWith("unlowered-member:")||active.startsWith("unstaged-allocation:")||active.startsWith("unlowered-eval-consumer:"))continue;
                if(active.equals("module-logical-frame"))continue;
                if(active.equals("global-cell-import:GLOBALS")&&!runtimeGlobalImports.isEmpty())continue;
                if((method.clazz.isClosure()&&active.equals("closure-self-and-static-import")||active.equals("receiver-local-import")
                        ||active.equals("local-reference-promotion-and-rebind")))continue;
                debt.add(active);
            }
            Set<AbstractInsnNode> passive=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var range:runtimeFallbacks.entrySet()) {
                AbstractInsnNode cursor=range.getKey().getNext();
                while(cursor!=range.getValue()) {
                    if(cursor==null)throw new IllegalStateException("Runtime fallback escapes its actual method");
                    passive.add(cursor);cursor=cursor.getNext();
                }
            }
            for(var range:runtimeActiveRanges.entrySet()) {
                AbstractInsnNode cursor=range.getKey().getNext();
                while(cursor!=range.getValue()){if(cursor==null)throw new IllegalStateException("Active Session branch escapes its method");passive.add(cursor);cursor=cursor.getNext();}
            }
            if(schema16) {
                List<AbstractInsnNode> ops=new ArrayList<>();for(AbstractInsnNode op:method.node.instructions)if(op.getOpcode()>=0)ops.add(op);
                for(int at=0;at+12<ops.size();at++) {
                    if(!(ops.get(at) instanceof VarInsnNode read)||read.getOpcode()!=ALOAD||passive.contains(read))continue;
                    RuntimeLifecycleReceiver receiver=runtimeLifecycleReceivers.values().stream().filter(value->value.store().var==read.var).findFirst().orElse(null);if(receiver==null)continue;
                    if(ops.get(at+1).getOpcode()!=DUP||!(ops.get(at+2) instanceof MethodInsnNode test)||test.getOpcode()!=INVOKEVIRTUAL||!test.owner.equals("php/runtime/Memory")||!test.name.equals("isNull")||!test.desc.equals("()Z")||!(ops.get(at+3) instanceof JumpInsnNode branch)||branch.getOpcode()!=IFEQ||runtimeOrdinal(branch.label)!=runtimeOrdinal(ops.get(at+12))||!(ops.get(at+4) instanceof VarInsnNode env)||env.getOpcode()!=ALOAD||env.var!=1||!(ops.get(at+5) instanceof FieldInsnNode trace)||trace.getOpcode()!=GETSTATIC||!trace.owner.equals(method.clazz.node.name)||!trace.name.equals("$TRC")||!trace.desc.equals("[Lphp/runtime/env/TraceInfo;")||runtimeIntegerOperand(ops.get(at+6))<0||ops.get(at+7).getOpcode()!=AALOAD||!(ops.get(at+8) instanceof LdcInsnNode error)||!Type.getType("Lphp/runtime/lang/exception/BaseError;").equals(error.cst)||!(ops.get(at+9) instanceof LdcInsnNode message)||!"Using $this when not in object context".equals(message.cst)||ops.get(at+10).getOpcode()!=ACONST_NULL||!(ops.get(at+11) instanceof MethodInsnNode exception)||exception.getOpcode()!=INVOKEVIRTUAL||!exception.owner.equals("php/runtime/env/Environment")||!exception.name.equals("exception")||!exception.desc.equals("(Lphp/runtime/env/TraceInfo;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)V")||ops.get(at+12).getOpcode()!=POP)continue;
                    runtimeLifecycleDiscards.put(read,new RuntimeLifecycleDiscard(receiver.local(),read,branch,exception,ops.get(at+12)));
                }
            }
            for(AbstractInsnNode instruction:method.node.instructions) {
                if(passive.contains(instruction))continue;
                if(instruction instanceof MethodInsnNode call&&!ordinaryPure(call))
                    debt.add("ordinary-unlowered-member:"+call.owner+"."+call.name+call.desc);
                if(instruction instanceof TypeInsnNode type&&type.getOpcode()==NEW
                        &&!type.desc.equals("php/runtime/memory/ReferenceMemory")
                        &&!(type.desc.equals("php/runtime/memory/ArrayMemory")&&runtimeProducers.containsValue("ARRAY_LITERAL")))
                    debt.add("ordinary-unstaged-allocation:"+type.desc);
            }
            for(var producer:runtimeProducers.entrySet())if(producer.getValue().equals("NATIVE_RESULT")&&!runtimeNatives.containsKey(producer.getKey()))debt.add("native-result-without-member:"+producer.getKey());
            for(int id:runtimeNatives.keySet())if(!runtimeBodies.containsKey(id))debt.add("native-body-protocol-missing:"+id);
            out.writeInt(debt.size());for(String item:debt)out.writeUTF(item);
            out.writeInt(runtimeNatives.size());for(RuntimeNative nativeMember:runtimeNatives.values()) {
                out.writeInt(nativeMember.id());out.writeUTF(nativeMember.owner());out.writeUTF(nativeMember.name());out.writeUTF(nativeMember.descriptor());out.writeInt(nativeMember.argc());
                if(schema24){out.writeUTF(nativeMember.physicalOwner());out.writeUTF(nativeMember.physicalName());out.writeUTF(nativeMember.physicalDescriptor());out.writeBoolean(nativeMember.discarded());}
            }
            out.writeInt(runtimeBodies.size());for(NativeBodySite site:runtimeBodies.values()) {
                out.writeInt(site.id());out.writeInt(runtimeOrdinal(site.enter()));out.writeInt(runtimeOrdinal(site.body()));
                out.writeInt(runtimeOrdinal(site.leave()));out.writeInt(runtimeOrdinal(site.fail()));
                int guard=method.node.tryCatchBlocks.indexOf(site.guard());if(guard<0)throw new IllegalArgumentException("Native BODY guard was removed");out.writeInt(guard);
                var conversions=runtimeConversions.getOrDefault(site.id(),List.of());out.writeInt(conversions.size());for(var conversion:conversions)out.writeInt(runtimeOrdinal(conversion));
                var borrows=runtimeBorrows.getOrDefault(site.id(),List.of());out.writeInt(borrows.size());for(var borrow:borrows){out.writeInt(runtimeOrdinal(borrow));out.writeUTF(runtimeNativeBorrowTag(borrow));}
            }
            if(schema6||!runtimeCalls.isEmpty()||!runtimeClosureCvs.isEmpty()||!runtimeLiterals.isEmpty()||!runtimeClones.isEmpty()) {
                out.writeInt(runtimeLiterals.size());for(RuntimeLiteral literal:runtimeLiterals.values()){out.writeInt(literal.producer());out.writeUTF(literal.closure());out.writeUTF(literal.module());out.writeInt(literal.index());out.writeInt(runtimeOrdinal(literal.lookup()));out.writeInt(runtimeOrdinal(literal.prepare()));}
                out.writeInt(runtimeClosureCvs.size());for(RuntimeCv cv:runtimeClosureCvs.values()){out.writeInt(cv.producer());out.writeInt(cv.local());out.writeUTF(cv.closure());out.writeInt(runtimeOrdinal(cv.publication()));}
                out.writeInt(runtimeCalls.size());for(RuntimeCall call:runtimeCalls.values()){out.writeInt(call.id());out.writeInt(call.local());out.writeInt(call.evaluation());out.writeInt(call.producer());out.writeUTF(call.closure());out.writeInt(call.arity());out.writeInt(runtimeOrdinal(call.factory()));out.writeInt(runtimeOrdinal(call.invoke()));out.writeInt(runtimeOrdinal(call.start()));out.writeInt(runtimeOrdinal(call.fallback()));out.writeInt(runtimeOrdinal(call.end()));}
            }
            if(schema6||!runtimeClones.isEmpty()) {
                out.writeInt(runtimeClones.size());for(RuntimeClone clone:runtimeClones.values()){out.writeInt(clone.id());out.writeInt(clone.local());out.writeInt(clone.producer());out.writeUTF(clone.closure());out.writeInt(runtimeOrdinal(clone.helper()));out.writeInt(runtimeOrdinal(clone.start()));out.writeInt(runtimeOrdinal(clone.fallback()));out.writeInt(runtimeOrdinal(clone.end()));}
            }
            if(schema6) {
                out.writeInt(runtimeArrayEntries.size());for(RuntimeArrayEntry site:runtimeArrayEntries.values()){out.writeInt(site.id());out.writeInt(site.producer());out.writeInt(site.local());out.writeBoolean(site.append());out.writeInt(runtimeOrdinal(site.factory()));out.writeInt(runtimeOrdinal(site.step()));out.writeInt(runtimeOrdinal(site.assignment()));}
                out.writeInt(runtimeHandlers.size());for(RuntimeHandler site:runtimeHandlers.values()){out.writeInt(site.call());out.writeInt(site.producer());out.writeUTF(site.closure());out.writeInt(runtimeOrdinal(site.send()));}
            }
            if(schema7) {
                out.writeInt(runtimeGlobalImports.size());for(RuntimeGlobalImport item:runtimeGlobalImports.values()){out.writeInt(item.local());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.start()));out.writeInt(runtimeOrdinal(item.fallback()));out.writeInt(runtimeOrdinal(item.end()));}
                out.writeInt(runtimeGlobalAccesses.size());for(RuntimeGlobalAccess item:runtimeGlobalAccesses.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeUTF(item.key());out.writeInt(item.eval());out.writeInt(item.local());out.writeInt(item.mode());out.writeInt(item.read()==null?-1:runtimeOrdinal(item.read()));out.writeInt(runtimeOrdinal(item.factory()));out.writeInt(runtimeOrdinal(item.index()));out.writeInt(runtimeOrdinal(item.assignment()));}
            }
            if(schema8) {
                out.writeInt(runtimeGlobalReads.size());for(RuntimeGlobalRead item:runtimeGlobalReads.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeUTF(item.key());out.writeBoolean(item.cell());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));}
                out.writeInt(runtimeGlobalUnsets.size());for(RuntimeGlobalUnset item:runtimeGlobalUnsets.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeUTF(item.key());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));}
            }
            if(schema9){out.writeInt(runtimeGlobalScalars.size());for(RuntimeGlobalScalar item:runtimeGlobalScalars.values()){out.writeInt(item.id());out.writeLong(item.value());out.writeInt(runtimeOrdinal(item.helper()));}}
            if(schema10){out.writeInt(runtimeArrayReferences.size());for(RuntimeArrayReference item:runtimeArrayReferences.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeUTF(item.key());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));}}
            if(schema11){out.writeInt(runtimeFunctions.size());for(RuntimeFunctionCall item:runtimeFunctions.values()){out.writeInt(item.id());out.writeUTF(item.name());out.writeUTF(item.owner());out.writeUTF(item.member());out.writeUTF(item.descriptor());out.writeInt(item.local());out.writeInt(runtimeOrdinal(item.factory()));out.writeInt(runtimeOrdinal(item.send()));out.writeInt(runtimeOrdinal(item.read()));out.writeInt(runtimeOrdinal(item.invoke()));out.writeInt(runtimeOrdinal(item.start()));out.writeInt(runtimeOrdinal(item.fallback()));out.writeInt(runtimeOrdinal(item.end()));}}
            if(schema12){out.writeInt(runtimeArrayIncrements.size());for(RuntimeArrayIncrement item:runtimeArrayIncrements.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeUTF(item.key());out.writeBoolean(item.decrement());out.writeBoolean(item.post());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));}}
            if(schema13){out.writeInt(runtimeObjectNews.size());for(RuntimeObjectNew item:runtimeObjectNews.values()){out.writeInt(item.id());out.writeUTF(item.name());out.writeUTF(item.owner());out.writeUTF(item.fingerprint());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.start()));out.writeInt(runtimeOrdinal(item.fallback()));out.writeInt(runtimeOrdinal(item.end()));}}
            if(schema14) {
                Set<AddressSource> sources=new LinkedHashSet<>();for(RuntimePropertyReference item:runtimePropertyReferences.values())sources.add(new AddressSource(item.producer(),item.root()));
                out.writeInt(sources.size());for(AddressSource source:sources){RuntimeObjectCv item=runtimeObjectCvs.get(source);out.writeInt(item.producer());out.writeInt(item.local());out.writeUTF(item.owner());out.writeInt(runtimeOrdinal(item.publication()));}
                out.writeInt(runtimePropertyReferences.size());for(RuntimePropertyReference item:runtimePropertyReferences.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeInt(item.producer());out.writeUTF(item.key());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));
                    var result=runtimeAddressPublications.entrySet().stream().filter(value->value.getKey().producer()==item.id()).findFirst().orElseThrow(()->new IllegalArgumentException("FIELD result lacks its actual reference CV publication"));out.writeInt(result.getKey().local());out.writeInt(runtimeOrdinal(result.getValue()));}
            }
            if(schema15) {
                out.writeInt(runtimeConditionals.size());for(RuntimeConditional item:runtimeConditionals.values()) {
                    out.writeInt(item.id());out.writeInt(item.source());out.writeInt(item.kind());out.writeBoolean(item.retainTrue());out.writeInt(item.trueSource());out.writeInt(item.falseSource());
                    out.writeInt(runtimeOrdinal(item.test()));out.writeInt(runtimeOrdinal(item.branch()));out.writeInt(runtimeOrdinal(item.trueResult()));out.writeInt(runtimeOrdinal(item.jump()));out.writeInt(runtimeOrdinal(item.falseResult()));out.writeInt(runtimeOrdinal(item.alternative()));out.writeInt(runtimeOrdinal(item.end()));out.writeInt(item.scratch());out.writeUTF(item.trueLiteral());out.writeUTF(item.falseLiteral());
                }
                out.writeInt(runtimeQuietGlobalReads.size());for(int id:runtimeQuietGlobalReads)out.writeInt(id);
                out.writeInt(runtimeNativeScalarArguments.size());for(RuntimeNativeScalarArgument item:runtimeNativeScalarArguments.values()){out.writeInt(item.id());out.writeInt(item.index());out.writeInt(runtimeOrdinal(item.borrow()));out.writeInt(runtimeOrdinal(item.conversion()));out.writeInt(runtimeOrdinal(item.last()));}
                out.writeInt(runtimeNativeScalarResults.size());for(RuntimeNativeScalarResult item:runtimeNativeScalarResults.values()){out.writeInt(item.id());out.writeInt(runtimeOrdinal(item.boxing()));out.writeInt(runtimeOrdinal(item.result()));}
                out.writeInt(runtimeConditionalLocals.size());for(RuntimeConditionalLocal item:runtimeConditionalLocals.values()){out.writeInt(item.id());out.writeInt(item.local());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));}
            }
            if(schema16) {
                out.writeBoolean(runtimeLifecycleEnabled());out.writeInt(runtimeLifecycleReceivers.size());for(RuntimeLifecycleReceiver item:runtimeLifecycleReceivers.values()){out.writeInt(item.local());out.writeInt(runtimeOrdinal(item.read()));out.writeInt(runtimeOrdinal(item.boxing()));out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.store()));out.writeInt(runtimeOrdinal(item.start()));out.writeInt(runtimeOrdinal(item.fallback()));out.writeInt(runtimeOrdinal(item.end()));}
                out.writeInt(runtimeLifecycleDiscards.size());for(RuntimeLifecycleDiscard item:runtimeLifecycleDiscards.values()){out.writeInt(item.local());out.writeInt(runtimeOrdinal(item.read()));out.writeInt(runtimeOrdinal(item.branch()));out.writeInt(runtimeOrdinal(item.exception()));out.writeInt(runtimeOrdinal(item.pop()));}
            }
            if(schema17){out.writeInt(runtimeGlobalNulls.size());for(RuntimeGlobalNull item:runtimeGlobalNulls.values()){out.writeInt(item.id());out.writeInt(runtimeOrdinal(item.helper()));}}
            if(schema18){out.writeInt(runtimeEntryScalars.size());for(RuntimeEntryScalar item:runtimeEntryScalars.values()){out.writeInt(item.id());out.writeLong(item.value());out.writeInt(runtimeOrdinal(item.helper()));}}
            if(schema19){out.writeInt(runtimeObjectCasts.size());for(RuntimeObjectCast item:runtimeObjectCasts.values()){out.writeInt(item.id());out.writeInt(item.source());out.writeInt(runtimeOrdinal(item.helper()));}}
            if(schema20){out.writeInt(runtimeLiteralEntries.size());for(RuntimeLiteralEntry item:runtimeLiteralEntries){out.writeInt(item.parent());out.writeInt(runtimeOrdinal(item.wrapper()));out.writeInt(runtimeOrdinal(item.helper()));}}
            if(schema21){out.writeInt(runtimeLocalIncrements.size());for(RuntimeLocalIncrement item:runtimeLocalIncrements.values()){out.writeInt(item.id());out.writeInt(item.root());out.writeBoolean(item.decrement());out.writeBoolean(item.post());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.read()));}}
            if(schema22){out.writeInt(runtimeComparisonFallbackSites.size());for(var item:runtimeComparisonFallbackSites.entrySet()){out.writeInt(item.getKey());out.writeInt(runtimeOrdinal(item.getValue()));}}
            if(schema23) {
                out.writeInt(runtimeNamedGlobals.size());for(RuntimeNamedGlobal item:runtimeNamedGlobals){out.writeInt(item.local());out.writeUTF(item.name());out.writeInt(runtimeOrdinal(item.helper()));out.writeInt(runtimeOrdinal(item.start()));out.writeInt(runtimeOrdinal(item.fallback()));out.writeInt(runtimeOrdinal(item.end()));}
                out.writeInt(runtimeEntryStrings.size());for(RuntimeEntryString item:runtimeEntryStrings.values()){out.writeInt(item.id());out.writeUTF(item.value());out.writeBoolean(item.raw());out.writeInt(runtimeOrdinal(item.helper()));}
                out.writeInt(runtimeReturnBoundaries.size());for(var item:runtimeReturnBoundaries.entrySet()){out.writeInt(runtimeOrdinal(item.getKey()));out.writeInt(item.getValue());}
            }
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }

    // These calls have effects. Their helper validates the defined physical site and publishes actual VALUE custody.
    private final Set<MethodInsnNode> constantReads=Collections.newSetFromMap(new IdentityHashMap<>());
    public void constantRead(MethodInsnNode call) {
        if(call.getOpcode()!=INVOKESTATIC || !call.owner.equals(php.runtime.PhpConstantReads.OWNER)
                || !call.name.equals("fetch") || !call.desc.equals(php.runtime.PhpConstantReads.DESCRIPTOR))
            throw new IllegalArgumentException("Constant read lost its typed effect helper");
        constantReads.add(call);
    }
    private boolean ordinaryPure(MethodInsnNode call) {
        if(constantReads.contains(call))return true;
        if(runtimeNamedGlobals.stream().anyMatch(item->item.helper()==call)||runtimeEntryStrings.values().stream().anyMatch(item->item.helper()==call)||runtimeReturnBoundaries.containsKey(call))return true;
        if(runtimeComparisonFallbacks.contains(call))return true;
        if(runtimeLifecycleDiscards.values().stream().anyMatch(item->item.exception()==call))return true;
        if(runtimeLifecycleReceivers.values().stream().anyMatch(item->item.boxing()==call||item.helper()==call))return true;
        if(runtimeConditionalLiterals.contains(call))return true;
        if(runtimeConditionalLocals.values().stream().anyMatch(item->item.helper()==call))return true;
        if(runtimeNativeScalarArguments.values().stream().anyMatch(item->item.conversion()==call||item.literalFactory()==call)||runtimeNativeScalarResults.values().stream().anyMatch(item->item.boxing()==call))return true;
        if(call.owner.equals(RUNTIME)&&Set.of("conditionalTest","conditionalResult").contains(call.name))return runtimeConditionals.values().stream().anyMatch(item->item.test()==call||item.trueResult()==call||item.falseResult()==call);
        if(call.owner.equals(RUNTIME)&&call.name.equals("propertyReference"))return runtimePropertyReferences.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME+"$Frame")&&call.name.equals("objectNew"))return runtimeObjectNews.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals("php/runtime/PhpArithmetic")&&call.name.equals("localIncrement"))return runtimeLocalIncrements.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals("php/runtime/PhpArithmetic")&&call.name.equals("arrayIncrement"))return runtimeArrayIncrements.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME+"$Frame")&&call.name.equals("functionPacket")||call.owner.equals(RUNTIME+"$FunctionPacket"))return runtimeFunctions.values().stream().anyMatch(item->item.factory()==call||item.send()==call||item.invoke()==call);
        if(call.owner.equals("php/runtime/PhpOwnershipArrays")&&call.name.equals("arrayReference"))return runtimeArrayReferences.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME)&&call.name.equals("globalNull"))return runtimeGlobalNulls.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME)&&call.name.equals("globalScalar"))return runtimeGlobalScalars.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME)&&call.name.equals("entryScalar"))return runtimeEntryScalars.values().stream().anyMatch(item->item.helper()==call);
        if(runtimeLiteralEntries.stream().anyMatch(item->item.wrapper()==call||item.helper()==call))return true;
        if(call.owner.equals(RUNTIME)&&call.name.equals("objectCast"))return runtimeObjectCasts.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME)&&(call.name.equals("globalRead")||call.name.equals("globalReadQuiet")||call.name.equals("globalUnset")))return runtimeGlobalReads.values().stream().anyMatch(item->item.helper()==call)||runtimeGlobalUnsets.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals("php/runtime/NullCoalesceAssignment"))return runtimeArrayEntries.values().stream().anyMatch(site->site.factory()==call||site.step()==call||site.assignment()==call)||runtimeGlobalAccesses.values().stream().anyMatch(site->site.factory()==call||site.index()==call||site.assignment()==call);
        if(call.owner.equals(RUNTIME+"$Frame")&&call.name.equals("globalsAddress"))return runtimeGlobalImports.values().stream().anyMatch(item->item.helper()==call);
        if(call.owner.equals(RUNTIME+"$Frame")&&call.name.equals("callable")||call.owner.equals(RUNTIME+"$CallPacket"))return runtimeCalls.values().stream().anyMatch(site->site.factory()==call||site.invoke()==call);
        if(call.owner.equals("php/runtime/PhpOwnershipClosures")&&call.name.equals("cloneLocalRuntime"))return runtimeClones.values().stream().anyMatch(clone->clone.helper()==call);
        if(runtimeEchoes.contains(call))return true;
        if(runtimeConversions.values().stream().anyMatch(nodes->nodes.contains(call)))return true;
        if(runtimeBorrows.values().stream().anyMatch(nodes->nodes.contains(call)))return true;
        if(call.owner.equals(php.runtime.PhpPrimitiveCellPlan.RUNTIME)) return php.runtime.PhpPrimitiveCellPlan.pureCall(call.owner,call.name,call.desc);
        if(call.owner.equals("php/runtime/PhpPropertyWriteContext")) {
            if(call.getOpcode()!=INVOKESTATIC)return false;
            if(call.name.equals("leave"))return call.desc.equals("(Lphp/runtime/PhpPropertyWriteContext$Frame;)V");
            if(!call.name.equals("enter"))return false;
            if(call.desc.equals("(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Z)Lphp/runtime/PhpPropertyWriteContext$Frame;"))return true;
            if(!call.desc.equals("(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;ZI)Lphp/runtime/PhpPropertyWriteContext$Frame;"))return false;
            AbstractInsnNode profile=call.getPrevious();while(profile!=null && profile.getOpcode()<0)profile=profile.getPrevious();
            return profile instanceof LdcInsnNode literal && literal.cst instanceof Integer id
                    && id==method.getCompiler().getModule().getOptimizationProfile().getId();
        }
        if(runtimeStaticAddresses.contains(call)&&method.clazz.isClosure()&&call.owner.equals(method.clazz.node.name))return call.name.equals("getStatic")&&call.desc.equals("(Ljava/lang/String;)"+MEMORY)||call.name.equals("getOrCreateStatic")&&call.desc.equals("(Ljava/lang/String;"+MEMORY+")"+MEMORY);
        if(call.owner.equals(RUNTIME)&&call.name.equals("nativeEnterBody"))return call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$NativePacket;)Lphp/runtime/PhpRuntimeOwnership$NativeBody;");
        if(call.owner.equals(RUNTIME)&&call.name.equals("nativeLeaveBody"))return call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$NativeBody;)V");
        if(call.owner.equals(RUNTIME)&&call.name.equals("nativeFailBody"))return call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$NativeBody;Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        if(call.owner.equals(RUNTIME)&&call.name.equals("unsetLocal"))return call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$Frame;I"+MEMORY+"Lphp/runtime/env/Environment;)V");
        if(call.owner.equals(RUNTIME)&&call.name.equals("prepareReturnReference"))return call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$Frame;"+MEMORY+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+MEMORY);

        if(runtimeNatives.values().stream().anyMatch(n->n.physicalOwner().equals(call.owner)&&n.physicalName().equals(call.name)&&n.physicalDescriptor().equals(call.desc)))return true;
        if(call.owner.equals(RUNTIME)&&Set.of("nativeCall","nativeSend","nativeResult","bindStaticLocal","localAddress").contains(call.name))return true;

        if(runtimeAddresses.contains(call)&&call.owner.equals("php/runtime/Memory")&&call.name.equals("refOfIndex")
                &&call.desc.equals("(Ljava/lang/String;)"+MEMORY))return true;
        if(call.owner.equals("php/runtime/PhpOwnershipNativeCalls")&&Set.of("tickEmpty","echoLiteral").contains(call.name))return true;
        if(pure(call))return true;
        if(call.owner.equals("php/runtime/PhpOwnershipArrays"))return call.name.equals("runtimeFresh")
                &&call.desc.equals("(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;");
        if(call.owner.equals("php/runtime/PhpOwnershipClosures"))return call.name.equals("prepareRuntime")||call.name.equals("cloneRuntime");
        if(call.owner.equals("php/runtime/PhpOwnershipClosures$RuntimeAllocation"))return Set.of("self","capture","complete").contains(call.name);
        if(call.owner.equals("php/runtime/env/Environment"))return call.name.equals("__getClosure")
                &&call.desc.equals("(Ljava/lang/String;I)Lphp/runtime/reflection/ClassEntity;")
                ||call.name.equals("__tick")&&call.desc.equals("(Lphp/runtime/env/TraceInfo;Lphp/runtime/memory/ArrayMemory;)V");
        if(call.owner.equals("php/runtime/Memory"))return Set.of("assignRight","assignRefRight").contains(call.name);
        if(call.owner.equals("php/runtime/memory/ReferenceMemory"))return call.name.equals("exportReference");
        if(call.owner.equals("php/runtime/memory/ArrayMemory")&&runtimeProducers.containsValue("ARRAY_LITERAL"))
            return Set.of("valueOf","createHashed","createListed","add","put").contains(call.name)||call.name.equals("addPhp8")&&call.desc.equals("("+MEMORY+"Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/memory/ReferenceMemory;");
        return false;
    }

    private byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(2); out.writeUTF(method.clazz.node.name); out.writeUTF(method.node.name); out.writeUTF(method.node.desc);
            out.writeUTF(method.isInitializer() ? method.node.name.startsWith("__$parameterDefault") ? "DEFAULT_INITIALIZER" : "CLASS_ENV_INITIALIZER" : method.statement.isModuleChunk() ? "CHUNK" : method.isExternal() ? "MODULE" : method.clazz.isClosure() ? "LEXICAL_CLOSURE" : method.clazz.isSystem() ? "FUNCTION" : "METHOD");
            List<LocalVariable> locals = new ArrayList<>(managed.values());
            out.writeInt(locals.size());
            for (LocalVariable local : locals) {
                out.writeInt(local.index); out.writeInt(local.index); out.writeUTF(local.name); out.writeUTF(address(local) ? "ADDRESS" : "VALUE");
                Import imp = imports.get(local.index); out.writeUTF(imp == null ? "NONE" : imp.capture ? "CAPTURE" : "ARGUMENT");
                out.writeInt(imp == null ? -1 : imp.index); out.writeUTF(imp != null && imp.reference ? "CELL" : "VALUE");
            }
            Set<String> required = new LinkedHashSet<>(); required.add("frame-local-publication-v1"); required.addAll(functionDependencies);
            if (!receiverImports.isEmpty()) required.add("invocation-receiver-import-v1");
            if(!runtimeEchoes.isEmpty())required.add("literal-buffered-output");
            if (!thrownCustodies.isEmpty()) required.add("php-throwable-custody-v1");
            if (method.statement != null && method.statement.isDynamicLocal()) required.add("empty-tick-handler");
            Set<String> unsupported = new LinkedHashSet<>();
            for (int id : unconsumedEvaluations) unsupported.add("unlowered-eval-consumer:" + id);
            if (referencePromotion || method.getEntity().isReturnReference() || imports.values().stream().anyMatch(i -> i.reference && !i.capture)) unsupported.add("local-reference-promotion-and-rebind");
            Set<String> names = new HashSet<>();
            for (LocalVariable local : locals) {
                if (!names.add(local.name)) unsupported.add("multiple-jvm-slots-for-cv:" + local.name);
                if (local.name.equals("this") && !receiverImports.contains(local.index)) unsupported.add("receiver-local-import");
                if (method.getCompiler().getScope().superGlobals.contains(local.name)) unsupported.add("global-cell-import:" + local.name);
            }
            if (method.statement != null && method.statement.isGenerator()) unsupported.add("generator-handoff");
            if (method.statement != null && !method.statement.getArguments().isEmpty() && method.statement.getArguments().stream().anyMatch(a -> a.getValue() != null && !a.getValue().isSingle())) unsupported.add("unstaged-default-initializer");
            if (method.clazz.isClosure()) {if(captureClosureImports())required.add(method.statement.getUses().stream().anyMatch(capture->capture.isReference())?"closure-capture-import-v2":"closure-value-import-v1");else unsupported.add("closure-self-and-static-import");}
            if (method.isExternal() || method.statement != null && method.statement.isModuleChunk()) unsupported.add("module-logical-frame");
            for (AbstractInsnNode instruction : method.node.instructions) {
                if (passiveInstructions.contains(instruction)) continue;
                if (instruction instanceof MethodInsnNode call && !passiveCalls.contains(call) && !pure(call)) {
                    if (call.owner.equals("php/runtime/PhpOwnershipNativeCalls") && call.name.equals("echoLiteral")) required.add("literal-buffered-output");
                    else if (call.owner.equals("php/runtime/PhpOwnershipNativeCalls") && call.name.equals("tickEmpty")) required.add("empty-tick-handler");
                    else if (call.owner.equals("php/runtime/env/Environment") && call.name.equals("__tick") && call.desc.equals("(Lphp/runtime/env/TraceInfo;Lphp/runtime/memory/ArrayMemory;)V")) required.add("empty-tick-handler");
                    else unsupported.add("unlowered-member:" + call.owner + "." + call.name + call.desc);
                }
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == NEW && !type.desc.equals("php/runtime/memory/ReferenceMemory"))
                    unsupported.add("unstaged-allocation:" + type.desc);
            }
            out.writeInt(required.size()); for (String item : required) out.writeUTF(item);
            out.writeInt(unsupported.size()); for (String item : unsupported) out.writeUTF(item);
            out.writeInt(Type.getReturnType(method.node.desc).equals(Type.getType(Memory.class)) ? Integer.MAX_VALUE - 1 : -1); out.writeInt(-1); out.writeInt(evaluations.size()); for (int id : evaluations) out.writeInt(id);
            out.writeInt(thrownCustodies.size()); for (int id : thrownCustodies) out.writeInt(id);
            out.writeInt(returnRoutes.size()); for (int id : returnRoutes) out.writeInt(id);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Admit only this emitter's immutable single-LDC byte carrier, with no callback or PHP object allocation. */
    public void immutableByteLiteral(MethodInsnNode call) {
        if (!isImmutableByteLiteral(call)) throw new IllegalArgumentException("Byte literal lacks its exact LDC/factory producer");
        passiveCalls.add(call);
    }

    private boolean isImmutableByteLiteral(MethodInsnNode call) {
        if (call.getOpcode()!=INVOKESTATIC || !call.owner.equals("php/runtime/memory/PhpByteStringMemory")
                || !call.name.equals("fromBinaryString")
                || !call.desc.equals("(Ljava/lang/String;)Lphp/runtime/memory/PhpByteStringMemory;")
                || !(call.getPrevious() instanceof LdcInsnNode literal) || !(literal.cst instanceof String)) return false;
        String text=(String)((LdcInsnNode)call.getPrevious()).cst;
        for (int i=0;i<text.length();i++) if(text.charAt(i)>255) return false;
        return true;
    }

    /** Check-only final bridge, with the actual module operands and no interior CFG ingress. */
    private boolean isClassDeclarationCheck(MethodInsnNode call) {
        if(call.getOpcode()!=INVOKEVIRTUAL||!call.owner.equals("php/runtime/env/Environment")
                ||!call.name.equals("__checkClassDeclaration")
                ||!call.desc.equals("(Lphp/runtime/env/TraceInfo;Ljava/lang/String;Ljava/lang/String;)V"))return false;
        try {
            if(!java.lang.reflect.Modifier.isFinal(php.runtime.env.Environment.class.getMethod("__checkClassDeclaration",
                    php.runtime.env.TraceInfo.class,String.class,String.class).getModifiers()))return false;
            var module=method.getCompiler().getModule();
            if(module.getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5||!method.clazz.isSystem()
                    ||!method.isExternal()||(method.node.access&ACC_STATIC)==0
                    ||!(method.node.name.equals("__include")||method.node.name.matches("__include_chunk_[0-9]+"))
                    ||!method.node.desc.equals("(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/Memory;")
                    ||!method.clazz.node.name.equals(module.getInternalName()))return false;
            AbstractInsnNode[] prefix=new AbstractInsnNode[6];AbstractInsnNode previous=call;
            for(int i=5;i>=0;i--){previous=runtimePreviousOpcode(previous);prefix[i]=previous;}
            int indexOpcode=prefix[2].getOpcode();
            if(!(prefix[2] instanceof LdcInsnNode literal&&literal.cst instanceof Integer)
                    &&!(indexOpcode>=ICONST_0&&indexOpcode<=ICONST_5)&&indexOpcode!=BIPUSH&&indexOpcode!=SIPUSH)return false;
            if(!(prefix[0] instanceof VarInsnNode env)||env.getOpcode()!=ALOAD||env.var!=0
                    ||!(prefix[1] instanceof FieldInsnNode trace)||trace.getOpcode()!=GETSTATIC
                    ||!trace.owner.equals(method.clazz.node.name)||!trace.name.equals("$TRC")
                    ||!trace.desc.equals("[Lphp/runtime/env/TraceInfo;")||runtimeIntegerOperand(prefix[2])<0
                    ||prefix[3].getOpcode()!=AALOAD||!(prefix[4] instanceof LdcInsnNode moduleName)
                    ||!module.getInternalName().equals(moduleName.cst)||!(prefix[5] instanceof LdcInsnNode className)
                    ||!(className.cst instanceof String name)||name.isEmpty())return false;
            var entity=module.findClass(name);
            if(entity==null||entity.getModule()!=module||!entity.isStatic()||entity.isDeferredDeclaration()
                    ||entity.isUnresolvedDeclaration()||entity.isAnonymous())return false;
            int start=runtimeOrdinal(prefix[0]),end=runtimeOrdinal(call);
            for(AbstractInsnNode instruction:method.node.instructions) {
                if(instruction instanceof VarInsnNode store&&store.var==0&&store.getOpcode()>=ISTORE&&store.getOpcode()<=ASTORE
                        ||instruction instanceof IincInsnNode increment&&increment.var==0)return false;
                List<LabelNode> targets=new ArrayList<>();
                if(instruction instanceof JumpInsnNode jump)targets.add(jump.label);
                else if(instruction instanceof TableSwitchInsnNode table){targets.add(table.dflt);targets.addAll(table.labels);}
                else if(instruction instanceof LookupSwitchInsnNode lookup){targets.add(lookup.dflt);targets.addAll(lookup.labels);}
                for(var target:targets){int at=runtimeOrdinal(target);if(at>start&&at<=end)return false;}
            }
            for(var handler:method.node.tryCatchBlocks)for(var target:List.of(handler.start,handler.end,handler.handler)) {
                int at=runtimeOrdinal(target);if(at>start&&at<=end)return false;
            }
            return true;
        }catch(ReflectiveOperationException|IllegalArgumentException invalid){return false;}
    }


    private boolean pure(MethodInsnNode call) {
        if(constantReads.contains(call))return true;
        if(isClassDeclarationCheck(call))return true;
        if (method.typedSources().isObserver(call)) return true;
        if (method.primitiveCells().isDiscardObserver(call)) return true;
        if (passiveCalls.contains(call) && isImmutableByteLiteral(call)) return true;
        if (call.owner.equals("php/runtime/invoke/PhpCallFrame") && Set.of("functionWithOwnership", "sendCompiler", "invokeToEval", "invokeToEvalDiscarded").contains(call.name)) return true;
        if (call.owner.equals("php/runtime/memory/TrueMemory") && call.name.equals("valueOf") && call.desc.equals("(Z)" + MEMORY)) return true;
        if (call.owner.equals(ENTRY) || call.owner.equals(EXECUTION) || call.owner.equals(PUBLICATION)) return true;
        if(call.owner.equals(RUNTIME)||call.owner.equals(RUNTIME+"$Frame")||call.owner.equals(RUNTIME+"$Publication"))return true;
        if (call.owner.equals("php/runtime/Memory")) return Set.of("toImmutable", "isNull", "toBoolean").contains(call.name);
        if (call.owner.equals("php/runtime/memory/ReferenceMemory")) return call.name.equals("<init>") && call.desc.equals("()V") || call.name.equals("valueOf");
        if (call.owner.equals("php/runtime/memory/ArrayMemory")) return call.name.equals("valueOfRef") || call.name.equals("refOfIndex");
        return false;
    }

    public record LocalPlan(int id, int jvmSlot, String name, String storage, String importKind, int importIndex, String mode) { }
    public record Plan(int schema, String owner, String method, String descriptor, String kind, List<LocalPlan> locals,
                       List<String> required, List<String> unsupported, int pendingReturn, int thrown, List<Integer> eval,
                       List<Integer> thrownIds, List<Integer> returnIds) {
        public Plan { locals = List.copyOf(locals); required = List.copyOf(required); unsupported = List.copyOf(unsupported); eval = List.copyOf(eval); thrownIds = List.copyOf(thrownIds); returnIds = List.copyOf(returnIds); }
        public php.runtime.PhpOwnershipExecution.FramePlan runtimePlan() {
            List<php.runtime.PhpOwnershipExecution.Local> actual = new ArrayList<>();
            for (LocalPlan local : locals) actual.add(new php.runtime.PhpOwnershipExecution.Local(local.id, local.jvmSlot, local.name,
                    php.runtime.PhpOwnershipExecution.Storage.valueOf(local.storage), php.runtime.PhpOwnershipExecution.ImportKind.valueOf(local.importKind),
                    local.importIndex, php.runtime.PhpOwnershipExecution.Mode.valueOf(local.mode)));
            return new php.runtime.PhpOwnershipExecution.FramePlan(schema, owner, method, descriptor,
                    php.runtime.PhpOwnershipExecution.EntryKind.valueOf(kind), actual, new LinkedHashSet<>(required), unsupported,
                    pendingReturn, thrown, eval.stream().mapToInt(Integer::intValue).toArray(),
                    thrownIds.stream().mapToInt(Integer::intValue).toArray(), returnIds.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** Strict parsing provides data only; the execution layer separately checks actual loaded bytes and lowering coverage. */
    public static Plan decode(byte[] data) throws IOException {
        if (data == null || data.length > 1024 * 1024) throw new IOException("Invalid ownership frame payload size");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        int schema = input.readInt();
        if (schema != 1 && schema != 2) throw new IOException("Unknown ownership frame schema");
        String owner = input.readUTF(), name = input.readUTF(), descriptor = input.readUTF(), kind = input.readUTF();
        if (owner.isEmpty() || name.isEmpty() || descriptor.isEmpty()
                || !Set.of("FUNCTION", "METHOD", "MODULE", "CHUNK", "LEXICAL_CLOSURE", "DEFAULT_INITIALIZER", "CLASS_ENV_INITIALIZER").contains(kind))
            throw new IOException("Invalid ownership frame identity");
        List<LocalPlan> locals = new ArrayList<>(); Set<Integer> ids = new HashSet<>(), slots = new HashSet<>();
        int count = count(input);
        for (int i = 0; i < count; i++) {
            int id = input.readInt(), slot = input.readInt(); String localName = input.readUTF(), storage = input.readUTF(), imported = input.readUTF();
            int index = input.readInt(); String mode = input.readUTF();
            if (id < 0 || slot < 0 || !ids.add(id) || !slots.add(slot) || localName.isEmpty()
                    || !Set.of("VALUE", "ADDRESS").contains(storage) || !Set.of("NONE", "ARGUMENT", "CAPTURE").contains(imported)
                    || (imported.equals("NONE") ? index != -1 : index < 0) || !Set.of("VALUE", "CELL").contains(mode))
                throw new IOException("Invalid or duplicate ownership local");
            locals.add(new LocalPlan(id, slot, localName, storage, imported, index, mode));
        }
        List<String> required = strings(input), unsupported = strings(input);
        int pending = input.readInt(), thrown = input.readInt(), evalCount = count(input); List<Integer> eval = new ArrayList<>();
        for (int id : new int[] {pending, thrown}) if (id < -1 || id >= 0 && !ids.add(id)) throw new IOException("Invalid ownership temporary identity");
        for (int i = 0; i < evalCount; i++) {
            int id = input.readInt(); if (id < 0 || !ids.add(id)) throw new IOException("Invalid ownership evaluation identity"); eval.add(id);
        }
        if (schema >= 2 && thrown != -1) throw new IOException("Schema two uses explicit thrown custody identities");
        List<Integer> thrownIds = schema >= 2 ? rootIds(input, ids) : List.of();
        List<Integer> returnIds = schema >= 2 ? rootIds(input, ids) : List.of();
        if (input.read() != -1) throw new IOException("Trailing ownership frame metadata");
        return new Plan(schema, owner, name, descriptor, kind, locals, required, unsupported, pending, thrown, eval, thrownIds, returnIds);
    }

    private static List<Integer> rootIds(DataInputStream input, Set<Integer> ids) throws IOException {
        List<Integer> result = new ArrayList<>(); int count = count(input);
        for (int i = 0; i < count; i++) { int id = input.readInt(); if (id < 0 || !ids.add(id)) throw new IOException("Invalid or duplicate ownership custody identity"); result.add(id); }
        return result;
    }

    private static int count(DataInputStream input) throws IOException {
        int count = input.readInt(); if (count < 0 || count > 65535) throw new IOException("Invalid ownership frame count"); return count;
    }
    private static List<String> strings(DataInputStream input) throws IOException {
        List<String> result = new ArrayList<>(); Set<String> unique = new HashSet<>(); int count = count(input);
        for (int i = 0; i < count; i++) { String value = input.readUTF(); if (value.isEmpty() || !unique.add(value)) throw new IOException("Invalid ownership capability"); result.add(value); }
        return result;
    }
    public static Plan read(byte[] bytecode, String method, String descriptor) throws IOException {
        final byte[][] found = {null}; final boolean[] duplicate = {false};
        new ClassReader(bytecode).accept(new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(method) || !desc.equals(descriptor)) return null;
                return new MethodVisitor(ASM9) {
                    @Override public void visitAttribute(Attribute attribute) {
                        if (attribute instanceof FrameAttribute frame) { if (found[0] != null) duplicate[0] = true; found[0] = frame.payload(); }
                    }
                };
            }
        }, new Attribute[] {new FrameAttribute(new byte[0])}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (duplicate[0]) throw new IOException("Duplicate ownership frame attribute");
        return found[0] == null ? null : decode(found[0]);
    }

    public static final class RuntimeFrameAttribute extends Attribute {
        private final byte[] payload;
        public RuntimeFrameAttribute(byte[] payload){super(RUNTIME_ATTRIBUTE);this.payload=payload.clone();}
        @Override protected ByteVector write(ClassWriter writer,byte[] code,int length,int maxStack,int maxLocals){return new ByteVector().putByteArray(payload,0,payload.length);}
        @Override protected Attribute read(ClassReader reader,int offset,int length,char[] buffer,int codeOffset,Label[] labels){
            byte[] data=new byte[length];for(int i=0;i<length;i++)data[i]=(byte)reader.readByte(offset+i);return new RuntimeFrameAttribute(data);
        }
    }

    /** The classfile attribute is transported with bytecode; it grants no execution authority by itself. */
    public static final class FrameAttribute extends Attribute {
        private final byte[] payload;
        public FrameAttribute(byte[] payload) { super(ATTRIBUTE); this.payload = payload.clone(); }
        public byte[] payload() { return payload.clone(); }
        @Override protected ByteVector write(ClassWriter writer, byte[] code, int length, int maxStack, int maxLocals) {
            return new ByteVector().putByteArray(payload, 0, payload.length);
        }
        @Override protected Attribute read(ClassReader reader, int offset, int length, char[] buffer, int codeOffset, Label[] labels) {
            byte[] data = new byte[length]; for (int i = 0; i < length; i++) data[i] = (byte) reader.readByte(offset + i);
            return new FrameAttribute(data);
        }
    }
}
