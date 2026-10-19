package php.runtime;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.util.*;
import php.runtime.loader.RuntimeClassLoader;

/** A literal annotation is not authority: the defined member and every physical CV use are checked. */
public final class PhpTypedSourcePlan {
    public static final String RUNTIME="php/runtime/PhpTypedSourceLifetime";
    private static final String CELL="php/runtime/PhpPrimitiveCellRuntime", M="Lphp/runtime/Memory;", META="Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I";
    public static final String OBSERVER="(Lphp/runtime/Memory;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;II)V";
    public record Draft(int id,int local,String declaration,List<String> properties,int createdBci,int publishedBci,int releasedBci) {}
    public record Site(Class<?> owner,String member,String descriptor,int id,int local,Class<?> declaration,List<String> properties,int createdBci,int publishedBci,int releasedBci) {}
    private record Observer(int at,String helper,int id,int local,String declaration) {}
    private PhpTypedSourcePlan() {}
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ClassValue<Map<String,Map<Integer,Site>>> PLANS=new ClassValue<>() {
        @Override protected Map<String,Map<Integer,Site>> computeValue(Class<?> owner) {
            if(!(owner.getClassLoader() instanceof RuntimeClassLoader loader))return Map.of();
            byte[] bytes=loader.definedInputBytes(owner);if(bytes==null || !loader.matchesDefinedBytecode(owner,bytes))return Map.of();
            try {
                Map<String,Map<Integer,Site>> result=new HashMap<>();
                for(var method:ClassFile.of().parse(bytes).methods()) {
                    String member=method.methodName().stringValue(),descriptor=method.methodType().stringValue();
                    Map<Integer,Site> sites=new HashMap<>();
                    for(var draft:inspect(method).values()) {
                        Class<?> declaration=Class.forName(draft.declaration().replace('/','.'),false,owner.getClassLoader());
                        sites.put(draft.id(),new Site(owner,member,descriptor,draft.id(),draft.local(),declaration,draft.properties(),draft.createdBci(),draft.publishedBci(),draft.releasedBci()));
                    }
                    result.put(member+descriptor,Map.copyOf(sites));
                }
                return Map.copyOf(result);
            } catch(ReflectiveOperationException|RuntimeException unsupported){return Map.of();}
        }
    };
    public static Site require(Class<?> owner,String member,String descriptor,int id,int local,String helper) {
        var caller=WALKER.walk(stream->stream.filter(frame->frame.getDeclaringClass()!=PhpTypedSourcePlan.class
                &&frame.getDeclaringClass()!=PhpTypedSourceLifetime.class).findFirst().orElseThrow());
        if(caller.getDeclaringClass()!=owner || !caller.getMethodName().equals(member) || !caller.getDescriptor().equals(descriptor))return null;
        Site site=PLANS.get(owner).getOrDefault(member+descriptor,Map.of()).get(id);
        if(site==null || site.local()!=local)return null;
        int expected=switch(helper){case "created"->site.createdBci();case "published"->site.publishedBci();case "released"->site.releasedBci();default->-1;};
        return expected>=0 && caller.getByteCodeIndex()==expected?site:null;
    }
    /** Compiler preflight calls this same reader on draft bytes. It cannot issue a runtime Site. */
    public static Map<Integer,Draft> inspect(MethodModel method) {
        var optional=method.code();if(optional.isEmpty())return Map.of();CodeModel code=optional.get();
        List<Instruction> ops=code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
        int[] offsets=new int[ops.size()];int bci=0;for(int at=0;at<ops.size();at++){offsets[at]=bci;bci+=ops.get(at).sizeInBytes();}
        // JDK bound branch labels are lazy; resolve before enumerating targets.
        for(var element:code.elementList()) {
            if(element instanceof BranchInstruction branch)branch.target();
            else if(element instanceof TableSwitchInstruction table){table.defaultTarget();for(var item:table.cases())item.target();}
            else if(element instanceof LookupSwitchInstruction lookup){lookup.defaultTarget();for(var item:lookup.cases())item.target();}
        }
        Map<Label,Integer> labels=new IdentityHashMap<>();int ordinal=0;
        for(var element:code.elementList()){if(element instanceof LabelTarget target)labels.put(target.label(),ordinal);else if(element instanceof Instruction)ordinal++;}
        List<Observer> observers=new ArrayList<>();Set<Integer> duplicate=new HashSet<>();Map<String,Set<Integer>> used=new HashMap<>();
        for(int at=0;at<ops.size();at++)if(ops.get(at) instanceof InvokeInstruction call && call.owner().asInternalName().equals(RUNTIME)
                && Set.of("created","published","released").contains(call.name().stringValue())) {
            if(call.opcode()!=Opcode.INVOKESTATIC || !call.type().equalsString(OBSERVER) || at<7)return Map.of();
            Object declaration=constant(ops.get(at-6)),owner=constant(ops.get(at-5)),member=constant(ops.get(at-4)),descriptor=constant(ops.get(at-3)),id=constant(ops.get(at-2)),local=constant(ops.get(at-1));
            if(!(declaration instanceof ClassDesc type) || !(owner instanceof ClassDesc actual)
                    || !actual.descriptorString().equals("L"+method.parent().orElseThrow().thisClass().asInternalName()+";")
                    || !Objects.equals(member,method.methodName().stringValue()) || !Objects.equals(descriptor,method.methodType().stringValue())
                    || !(id instanceof Integer site) || site<0 || !(local instanceof Integer cv) || cv<0)return Map.of();
            String helper=call.name().stringValue();if(!used.computeIfAbsent(helper,ignored->new HashSet<>()).add(site))duplicate.add(site);
            observers.add(new Observer(at,helper,site,cv,type.descriptorString().substring(1,type.descriptorString().length()-1)));
        }
        Map<Integer,Draft> result=new HashMap<>();
        for(var created:observers) {
            if(!created.helper().equals("created") || duplicate.contains(created.id()))continue;
            var published=observers.stream().filter(row->row.id()==created.id()&&row.helper().equals("published")).findFirst().orElse(null);
            var released=observers.stream().filter(row->row.id()==created.id()&&row.helper().equals("released")).findFirst().orElse(null);
            if(published==null || released==null || created.at()>=published.at() || published.at()>=released.at()
                    || published.local()!=created.local() || released.local()!=created.local()
                    || !published.declaration().equals(created.declaration()) || !released.declaration().equals(created.declaration()))continue;
            int first=created.at()-7,last=released.at();
            int factory=freshConstructor(ops,labels,first);
            if(ops.get(first).opcode()!=Opcode.DUP || factory<0
                    || !load(ops.get(published.at()-7),created.local()) || !load(ops.get(released.at()-7),created.local())
                    || published.at()+1>=ops.size())continue;
            int assignment=published.at()-8,unset=released.at()-8;
            int assignmentCv=assignment-1,unsetCv=unset-2,discard=-1;
            if(cellBridge(ops,assignment,method,"assignValue","("+M+M+META+")"+M))assignmentCv-=4;
            else if(!exactInvoke(ops.get(assignment),"php/runtime/Memory","assignRight","("+M+M+")"+M))continue;
            if(cellBridge(ops,unset,method,"unset","(Lphp/runtime/PhpRuntimeOwnership$Frame;I"+M+"Lphp/runtime/env/Environment;"+META+")V"))unsetCv-=4;
            else if(!exactInvoke(ops.get(unset),"php/runtime/PhpRuntimeOwnership","unsetLocal","(Lphp/runtime/PhpRuntimeOwnership$Frame;I"+M+"Lphp/runtime/env/Environment;)V"))continue;
            if(assignmentCv<0 || unsetCv<0 || !load(ops.get(assignmentCv),created.local()) || !load(ops.get(unsetCv),created.local()))continue;
            int pop=published.at()+1;
            if(ops.get(pop).opcode()!=Opcode.POP) {
                discard=pop+5;
                if(discard+1>=ops.size() || ops.get(pop).opcode()!=Opcode.DUP
                        || !cellBridge(ops,discard,method,"discard","(Ljava/lang/Object;"+META+")V")
                        || ops.get(discard+1).opcode()!=Opcode.POP)continue;
            }
            boolean closed=true;List<String> properties=new ArrayList<>();
            for(int at=0;at<ops.size() && closed;at++) {
                Instruction op=ops.get(at);
                if(op instanceof BranchInstruction jump) {
                    Integer target=labels.get(jump.target());if(target==null){closed=false;break;}
                    if(target>first && target<=last && (at<first || at>last) || at>first && at<last && target<=at)closed=false;
                    if(target>factory && target<=first && (at<=factory || at>first)) {
                        boolean managedFresh=target==first && at<=factory && jump.opcode()==Opcode.GOTO && at>0
                                && invoke(ops.get(at-1),"php/runtime/PhpRuntimeOwnership$Frame","objectNew");
                        if(!managedFresh)closed=false;
                    }
                } else if(op instanceof LookupSwitchInstruction || op instanceof TableSwitchInstruction) {
                    // Unsupported switch ingress anywhere in the method is not a proof of this interval.
                    closed=false;
                }
                if(at<first || at>last)continue;
                if(op instanceof StoreInstruction store && store.typeKind()==TypeKind.REFERENCE && store.slot()==created.local())closed=false;
                if(Set.of(Opcode.AASTORE,Opcode.PUTFIELD,Opcode.PUTSTATIC,Opcode.ARETURN,Opcode.ATHROW,Opcode.INVOKEDYNAMIC,Opcode.DUP2,Opcode.DUP_X1,Opcode.DUP_X2,Opcode.DUP2_X1,Opcode.DUP2_X2).contains(op.opcode()) || at!=first && op.opcode()==Opcode.DUP && !(at>published.at() && cellDiscard(ops,at+5,method)))closed=false;
                if(op instanceof InvokeInstruction call && !safeCall(ops,method,call,at,assignment,published.at(),unset,discard))closed=false;
                if(load(op,created.local()) && at!=assignmentCv && at!=published.at()-7 && at!=released.at()-7 && at!=unsetCv) {
                    String key=propertyUse(ops,at,last);if(key==null)closed=false;else properties.add(key);
                }
            }
            // A handler entering the interval could resume with an unrelated CV/EVAL value.
            for(var element:code.elementList())if(element instanceof ExceptionCatch guard) {
                Integer handler=labels.get(guard.handler());if(handler!=null&&handler>first&&handler<=last)closed=false;
            }
            if(closed && !properties.isEmpty())result.put(created.id(),new Draft(created.id(),created.local(),created.declaration(),List.copyOf(properties),offsets[created.at()],offsets[published.at()],offsets[released.at()]));
        }
        return Map.copyOf(result);
    }
    private static boolean safeCall(List<Instruction> ops,MethodModel method,InvokeInstruction call,int at,int assignment,int publication,int unset,int discard) {
        String owner=call.owner().asInternalName(),name=call.name().stringValue();
        if(owner.equals(RUNTIME))return call.opcode()==Opcode.INVOKESTATIC && Set.of("created","published","released").contains(name)&&call.type().equalsString(OBSERVER);
        if(owner.equals("php/runtime/Memory"))return at==assignment&&exactInvoke(call,owner,"assignRight","("+M+M+")"+M)
                || at>publication&&exactInvoke(call,owner,"assignRefRight","("+M+M+")"+M)
                || Set.of("toValue","toImmutable").contains(name)&&call.type().equalsString("()"+M);
        if(owner.equals("php/runtime/memory/ReferenceMemory"))return Set.of("toValue","toImmutable").contains(name)&&call.type().equalsString("()"+M)
                || at>publication&&exactInvoke(call,owner,"exportReference","("+M+")"+M);
        if(owner.equals("php/runtime/invoke/ObjectInvokeHelper"))return exactInvoke(call,owner,"getRefProperty",
                "("+M+"Ljava/lang/String;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/invoke/cache/PropertyCallCache;I)"+M);
        if(owner.equals("php/runtime/PhpRuntimeOwnership"))return exactInvoke(call,owner,"propertyReference",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;II"+M+"Ljava/lang/String;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)"+M)
                || at==unset&&exactInvoke(call,owner,"unsetLocal","(Lphp/runtime/PhpRuntimeOwnership$Frame;I"+M+"Lphp/runtime/env/Environment;)V");
        if(owner.equals("php/runtime/PhpRuntimeOwnership$Frame") || owner.equals("php/runtime/PhpOwnershipExecution$Entry"))
            return call.opcode()==Opcode.INVOKEVIRTUAL && (name.equals("assignLocalFromEval")&&call.type().equalsString("(II"+M+"I)"+M)
                    || name.equals("assignLocal")&&call.type().equalsString("(I"+M+"I)"+M));
        if(owner.equals(CELL))return at==assignment&&cellBridge(ops,at,method,"assignValue","("+M+M+META+")"+M)
                || at>publication&&cellBridge(ops,at,method,"assignRef","("+M+M+META+")"+M)
                || at>publication&&cellBridge(ops,at,method,"export","("+M+META+")"+M)
                || at==unset&&cellBridge(ops,at,method,"unset","(Lphp/runtime/PhpRuntimeOwnership$Frame;I"+M+"Lphp/runtime/env/Environment;"+META+")V")
                || at>publication&&cellDiscard(ops,at,method);
        return false;
    }
    /** CELL may observe each original scalar/CELL POP, but may not keep the duplicated value. */
    private static boolean cellDiscard(List<Instruction> ops,int at,MethodModel method) {
        return at>=5 && at+1<ops.size() && ops.get(at-5).opcode()==Opcode.DUP && ops.get(at+1).opcode()==Opcode.POP
                && cellBridge(ops,at,method,"discard","(Ljava/lang/Object;"+META+")V");
    }
    /** Only the exact default bridge, with physical member metadata, inherits the original operation. */
    private static boolean cellBridge(List<Instruction> ops,int at,MethodModel method,String name,String descriptor) {
        if(at<4 || !exactInvoke(ops.get(at),CELL,name,descriptor))return false;
        return constant(ops.get(at-4)) instanceof ClassDesc owner
                && owner.descriptorString().equals("L"+method.parent().orElseThrow().thisClass().asInternalName()+";")
                && Objects.equals(constant(ops.get(at-3)),method.methodName().stringValue())
                && Objects.equals(constant(ops.get(at-2)),method.methodType().stringValue())
                && constant(ops.get(at-1)) instanceof Integer site && site>=0;
    }
    private static boolean exactInvoke(Instruction op,String owner,String name,String descriptor) {
        return op instanceof InvokeInstruction call && call.opcode()==Opcode.INVOKESTATIC && call.owner().asInternalName().equals(owner)
                && call.name().equalsString(name) && call.type().equalsString(descriptor);
    }
    private static String propertyUse(List<Instruction> ops,int at,int last) {
        int next=at+1;
        while(next<=last && ops.get(next) instanceof InvokeInstruction transport
                && (transport.owner().asInternalName().equals("php/runtime/Memory") || transport.owner().asInternalName().equals("php/runtime/memory/ReferenceMemory"))
                && Set.of("toValue","toImmutable").contains(transport.name().stringValue()) && transport.type().equalsString("()Lphp/runtime/Memory;"))next++;
        if(next+5>last || !(constant(ops.get(next)) instanceof String key)
                || !(ops.get(next+1) instanceof LoadInstruction environment) || environment.typeKind()!=TypeKind.REFERENCE
                || !traceArray(ops.get(next+2)) || !(constant(ops.get(next+3)) instanceof Integer)
                || ops.get(next+4).opcode()!=Opcode.AALOAD)return null;
        next+=5;
        if(invoke(ops.get(next),"php/runtime/PhpRuntimeOwnership","propertyReference")) {
            if(at<3 || !(ops.get(at-3) instanceof LoadInstruction frame) || frame.typeKind()!=TypeKind.REFERENCE
                    || !(constant(ops.get(at-2)) instanceof Integer) || !(constant(ops.get(at-1)) instanceof Integer))return null;
            return key;
        }
        if(next+2>last || !(constant(ops.get(next+1)) instanceof Integer)
                || !invoke(ops.get(next+2),"php/runtime/invoke/ObjectInvokeHelper","getRefProperty"))return null;
        Instruction cache=ops.get(next);
        if(cache.opcode()!=Opcode.ACONST_NULL && !(cache instanceof FieldInstruction field
                && field.opcode()==Opcode.GETSTATIC && field.name().equalsString("$CALL_PROP_CACHE")
                && field.type().equalsString("Lphp/runtime/invoke/cache/PropertyCallCache;")))return null;
        return key;
    }
    private static boolean traceArray(Instruction op) {
        return op instanceof FieldInstruction field && field.opcode()==Opcode.GETSTATIC && field.name().equalsString("$TRC")
                && field.type().equalsString("[Lphp/runtime/env/TraceInfo;");
    }
    private static int freshConstructor(List<Instruction> ops,Map<Label,Integer> labels,int first) {
        if(first<1 || !(ops.get(first-1) instanceof LoadInstruction result) || result.typeKind()!=TypeKind.REFERENCE)return -1;
        for(int at=first-2;at>=0;at--) {
            Instruction op=ops.get(at);
            if(op instanceof StoreInstruction store && store.slot()==result.slot()) {
                if(at<2 || !(ops.get(at-1) instanceof InvokeInstruction invoke) || invoke.opcode()!=Opcode.INVOKEVIRTUAL
                        || !invoke.owner().asInternalName().equals("php/runtime/invoke/PhpCallFrame")
                        || !invoke.name().equalsString("invoke") || !invoke.type().equalsString("()Lphp/runtime/Memory;")
                        || !(ops.get(at-2) instanceof LoadInstruction frame))return -1;
                // The only normal successor is frame.close followed by the protected constructor join.
                if(at+3>=first || !load(ops.get(at+1),frame.slot()) || !(ops.get(at+2) instanceof InvokeInstruction close)
                        || close.opcode()!=Opcode.INVOKEVIRTUAL || !close.owner().asInternalName().equals("php/runtime/invoke/PhpCallFrame")
                        || !close.name().equalsString("close") || !close.type().equalsString("()V")
                        || !(ops.get(at+3) instanceof BranchInstruction joined) || joined.opcode()!=Opcode.GOTO
                        || !Objects.equals(labels.get(joined.target()),first-1))return -1;
                if(first!=at+10 || !(ops.get(at+4) instanceof StoreInstruction failure) || failure.typeKind()!=TypeKind.REFERENCE
                        || !load(ops.get(at+5),frame.slot()) || !load(ops.get(at+6),failure.slot())
                        || !(ops.get(at+7) instanceof InvokeInstruction fail) || fail.opcode()!=Opcode.INVOKEVIRTUAL
                        || !fail.owner().asInternalName().equals("php/runtime/invoke/PhpCallFrame") || !fail.name().equalsString("fail")
                        || !fail.type().equalsString("(Ljava/lang/Throwable;)Ljava/lang/Throwable;")
                        || ops.get(at+8).opcode()!=Opcode.ATHROW)return -1;
                for(int factory=at-3;factory>=0;factory--)if(ops.get(factory) instanceof StoreInstruction write && write.slot()==frame.slot()) {
                    // Zero arguments: no intervening send/copy/publication of the factory or its result.
                    if(factory+3!=at || !load(ops.get(factory+1),frame.slot()))return -1;
                    if(factory<2 || !(ops.get(factory-1) instanceof InvokeInstruction create) || create.opcode()!=Opcode.INVOKESTATIC
                            || !create.owner().asInternalName().equals("php/runtime/invoke/PhpCallFrame") || !create.name().equalsString("construct")
                            || !create.type().equalsString("(Lphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/invoke/cache/ClassCallCache;II)Lphp/runtime/invoke/PhpCallFrame;")
                            || !Objects.equals(constant(ops.get(factory-2)),0))return -1;
                    return factory-1;
                }
                return -1;
            }
        }
        return -1;
    }
    private static Object constant(Instruction instruction){return instruction instanceof ConstantInstruction literal?literal.constantValue():null;}
    private static boolean load(Instruction instruction,int slot){return instruction instanceof LoadInstruction load&&load.typeKind()==TypeKind.REFERENCE&&load.slot()==slot;}
    private static boolean invoke(Instruction instruction,String owner,String name){return instruction instanceof InvokeInstruction call&&call.owner().asInternalName().equals(owner)&&call.name().equalsString(name);}
}
