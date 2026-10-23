package php.runtime;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.util.*;
import php.runtime.env.Environment;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.support.ArrayMapEntryMemory;

/** Source and PHB share the exact defined-class witness and physical-site reader. */
public final class PhpPrimitiveCellPlan {
    public static final String ATTRIBUTE = "JPHP_Primitive_Cell_Plan_v1";
    public static final String RUNTIME = "php/runtime/PhpPrimitiveCellRuntime";
    public record Site(int id,String helper,int local) {}
    private record CaptureSite(Class<?> type,int index,int role,int count){}
    private record CaptureChain(String type,int count,int start,int end){}
    public static final class Member {
        private final Class<?> owner;
        private final String name,descriptor;
        private final boolean closed,reference;
        private final Map<Integer,Site> sites;
        private final Map<Integer,String> locals;
        private final List<String> statics;
        private final Map<Integer,PhpOwnershipExecution.Local> arguments;
        private final Map<Integer,CaptureSite> captures;
        private final Map<Integer,Class<?>> factories;
        private Member(Class<?> owner,String name,String descriptor,boolean closed,boolean reference,
                Map<Integer,Site> sites,Map<Integer,String> locals,List<String> statics,Map<Integer,PhpOwnershipExecution.Local> arguments,Map<Integer,CaptureSite> captures,Map<Integer,Class<?>> factories) {
            this.owner=owner;this.name=name;this.descriptor=descriptor;this.closed=closed;this.reference=reference;
            this.sites=Map.copyOf(sites);this.locals=Map.copyOf(locals);this.statics=List.copyOf(statics);this.arguments=Map.copyOf(arguments);this.captures=Map.copyOf(captures);this.factories=Map.copyOf(factories);
        }
        public boolean closed(){return closed;}
        public boolean returnReference(){return reference;}
        public List<String> staticNames(){return statics;}
        public boolean argument(int site,int index,int role) {
            Site row=sites.get(site);var argument=row==null?null:arguments.get(row.local());
            return argument!=null&&argument.importIndex()==index&&role==(argument.importMode()==PhpOwnershipExecution.Mode.CELL?1:0);
        }
        public boolean referenceArguments(int count){
            if(reference||arguments.size()!=count)return false;
            Set<Integer> seen=new HashSet<>();for(var argument:arguments.values())
                if(argument.importMode()!=PhpOwnershipExecution.Mode.CELL||argument.importIndex()<0||argument.importIndex()>=count||!seen.add(argument.importIndex()))return false;
            return seen.size()==count;
        }
        public boolean capture(int site,Class<?> type,int index,int role,int count){var capture=captures.get(site);return capture!=null&&capture.type()==type&&capture.index()==index&&capture.role()==role&&capture.count()==count;}
        public boolean factory(int site,Class<?> type){return factories.get(site)==type;}
        public boolean actualMember(Class<?> type,String method,String signature){return owner==type&&name.equals(method)&&descriptor.equals(signature);}
        public boolean isActualLocal(int site,ReferenceMemory actual,Environment environment) {
            Site row=sites.get(site);String local=row==null?null:locals.get(row.local());if(local==null)return false;
            if(actual.getClass()==ReferenceMemory.class)return true;
            if(actual.getClass()==ArrayMapEntryMemory.class)
                return environment.getGlobals().getByScalar(local)==actual||PhpPrimitiveCellRuntime.actualLocal(this,actual,local,environment);
            return false;
        }
    }
    private PhpPrimitiveCellPlan(){}
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ClassValue<Map<String,Member>> PLANS=new ClassValue<>() {
        @Override protected Map<String,Member> computeValue(Class<?> owner) {
            if(!(owner.getClassLoader() instanceof RuntimeClassLoader loader))return Map.of();
            byte[] bytes=loader.primitiveDefinedBytes(owner);if(bytes==null)return Map.of();
            try{return read(owner,bytes);}catch(IOException|RuntimeException failure){return Map.of();}
        }
    };
    public static Member requireSite(Class<?> owner,String name,String descriptor,int site) {
        // A public Java call cannot forge the generated member/site constants.
        var caller=WALKER.walk(stream->stream.filter(frame->frame.getDeclaringClass()!=PhpPrimitiveCellPlan.class
                &&frame.getDeclaringClass()!=PhpPrimitiveCellRuntime.class).findFirst().orElseThrow());
        if(caller.getDeclaringClass()!=owner||!caller.getMethodName().equals(name)||!caller.getDescriptor().equals(descriptor))
            return untrusted(caller.getDeclaringClass(),caller.getMethodName(),caller.getDescriptor());
        Member member=PLANS.get(owner).get(name+descriptor);
        if(member==null||!member.sites.containsKey(site))return untrusted(owner,name,descriptor);
        return member;
    }
    private static Member untrusted(Class<?> owner,String name,String descriptor){return new Member(owner,name,descriptor,false,false,Map.of(),Map.of(),List.of(),Map.of(),Map.of(),Map.of());}
    public static boolean hasClosedMember(Class<?> owner,String name,String descriptor) {
        Member member=PLANS.get(owner).get(name+descriptor);return member!=null&&member.closed;
    }
    public static Member closedMember(Class<?> owner,String name,String descriptor){var member=PLANS.get(owner).get(name+descriptor);return member!=null&&member.closed?member:null;}
    /** Validate actual generated bodies and bridge frames up to the current outer PHP context. */
    public static boolean closedInvocationPath(php.runtime.lang.Closure closure) {
        if(!php.runtime.invoke.PhpCallFrame.primitiveReturnTarget(closure))return false;
        int needed=0;for(var frame=PhpPropertyWriteContext.current(null);frame!=null;frame=frame.previousFrame())needed++;
        if(needed==0)return false;final int count=needed;
        return WALKER.walk(stream->{
            var iterator=stream.iterator();int found=0;
            while(iterator.hasNext()) {
                var frame=iterator.next();Class<?> actual=frame.getDeclaringClass();
                if(actual.getClassLoader() instanceof RuntimeClassLoader) {
                    if(!hasClosedMember(actual,frame.getMethodName(),frame.getDescriptor()))return false;
                    if(++found==count)return true;
                }else if(!Set.of(PhpPrimitiveCellPlan.class,PhpPrimitiveCellRuntime.class,
                        php.runtime.invoke.PhpCallFrame.class,php.runtime.invoke.DynamicMethodInvoker.class,
                        php.runtime.invoke.Invoker.class,php.runtime.invoke.ObjectInvokeHelper.class,
                        php.runtime.reflection.MethodEntity.class,php.runtime.reflection.FunctionEntity.class,php.runtime.invoke.InvokeHelper.class,php.runtime.invoke.InvokeArgumentHelper.class).contains(actual)
                        &&!actual.getName().startsWith("java.lang.reflect.")&&!actual.getName().startsWith("jdk.internal.reflect."))return false;
            }
            return false;
        });
    }

    /** Exact Java helper contracts, not PHP function/class-name admission. Heap/SEND/native paths fail closed. */
    public static boolean pureCall(String owner,String name,String descriptor) {
        if(discardedCall(owner,name,descriptor))return true;
        if(owner.equals(RUNTIME))return Set.of("getStatic","getOrCreateStatic","borrowStatic","prepareReturn","assignRef","assignValue","unset","cloneLocal","cloneEval","bindStatic","export","discard","importArgument","sendCompiler","localAddress","capture","wrapClosure").contains(name);
        if(owner.equals("php/runtime/output/OutputBuffer"))return name.equals("echoPhp85")&&descriptor.equals("(Lphp/runtime/env/Environment;Lphp/runtime/Memory;Lphp/runtime/env/TraceInfo;)V");
        if(owner.equals("php/runtime/PhpPropertyWriteContext"))return name.equals("enter")||name.equals("leave");
        if(owner.equals("php/runtime/Memory"))return Set.of("isNull","toValue","toImmutable","toBoolean","toLong","toString","assignRight","assign","assignRefRight","inc","dec","valueOf").contains(name)
                &&!descriptor.contains("[Lphp/runtime/Memory;");
        if(owner.equals("php/runtime/memory/ReferenceMemory"))return Set.of("<init>","valueOf","toValue","toImmutable","toBoolean","toLong","toString").contains(name);
        if(owner.equals("php/runtime/memory/LongMemory")||owner.equals("php/runtime/memory/StringMemory")||owner.equals("php/runtime/memory/ObjectMemory"))return name.equals("<init>")||name.equals("valueOf");
        if(owner.equals("php/runtime/PhpOwnershipExecution"))return name.equals("enterGenerated");
        if(owner.equals("php/runtime/PhpRuntimeOwnership"))return Set.of("enterGenerated","localAddress","bindStaticLocal").contains(name);
        if(owner.equals("php/runtime/PhpOwnershipExecution$Entry")||owner.equals("php/runtime/PhpRuntimeOwnership$Frame"))return Set.of("localAddress","localTable","prepareLocal","assignLocal","assignLocalFromEval","prepareReturn","prepareScopedReturn","prepareReturnTransfer","importCapture","importArgument","callable","functionPacket","releaseEval","close","leave").contains(name);
        if(owner.equals("php/runtime/PhpRuntimeOwnership$FunctionPacket"))return name.equals("sendCell")||name.equals("invokeToEval");
        if(owner.equals("php/runtime/PhpRuntimeOwnership$CallPacket"))return name.equals("invokeToEval");
        if(owner.equals("php/runtime/PhpRuntimeOwnership$Publication")||owner.equals("php/runtime/PhpOwnershipHeap$Publication"))return name.equals("storeDirect")||name.equals("afterLocalStore")||name.equals("commit");
        if(owner.equals("php/runtime/PhpOwnershipClosures"))return name.equals("prepareRuntime")||name.equals("cloneLocalRuntime");
        if(owner.equals("php/runtime/PhpOwnershipClosures$RuntimeAllocation"))return Set.of("self","complete").contains(name);
        if(owner.equals("php/runtime/invoke/PhpCallFrame"))return Set.of("callable","invoke","invokeToEval","acquireCallable","acquireInternalCallable","acquireFromCallable","functionWithOwnership","close").contains(name);
        if(owner.equals("php/runtime/env/Environment"))return Set.of("__getClosure","__tick","getGlobals").contains(name);
        if(owner.equals("php/runtime/PhpOwnershipNativeCalls"))return Set.of("echoLiteral","echoRuntime","echoLiteralRuntime","tickEmpty").contains(name);
        if(owner.equals("php/runtime/memory/PhpByteStringMemory"))return name.equals("fromBinaryString")&&descriptor.equals("(Ljava/lang/String;)Lphp/runtime/memory/PhpByteStringMemory;");
        if(owner.equals("java/lang/String"))return name.equals("concat");
        return false;
    }
    /** Only the compiler-emitted result-use siblings of the existing closed invocation protocol. */
    private static boolean discardedCall(String owner,String name,String descriptor) {
        if(owner.equals("php/runtime/invoke/PhpCallFrame"))return name.equals("invokeDiscarded")&&descriptor.equals("()Lphp/runtime/Memory;")
                ||name.equals("invokeToEvalDiscarded")&&descriptor.equals("(Lphp/runtime/PhpOwnershipExecution$Entry;I)Lphp/runtime/Memory;");
        return (owner.equals("php/runtime/PhpRuntimeOwnership$FunctionPacket")||owner.equals("php/runtime/PhpRuntimeOwnership$CallPacket"))
                &&name.equals("invokeToEvalDiscarded")&&descriptor.equals("(I)Lphp/runtime/Memory;");
    }
    public static Map<String,Member> read(Class<?> owner,byte[] bytes)throws IOException {
        Map<String,byte[]> headers=headers(bytes);var model=ClassFile.of().parse(bytes);
        // RuntimeClassLoader proves this exact source -> actual-definition transform, including a deferred self name.
        if(!(owner.getClassLoader() instanceof RuntimeClassLoader loader)||!loader.matchesDefinedBytecode(owner,bytes))throw new IOException("Foreign CELL source/definition witness");
        Map<String,Member> result=new HashMap<>();
        for(var method:model.methods()) {
            String name=method.methodName().stringValue(),descriptor=method.methodType().stringValue();byte[] header=headers.get(name+descriptor);
            if(header==null)continue;var input=new DataInputStream(new ByteArrayInputStream(header));
            if(input.readInt()!=1||!input.readUTF().equals(model.thisClass().asInternalName())||!input.readUTF().equals(name)||!input.readUTF().equals(descriptor))throw new IOException("Foreign CELL member plan");
            boolean reference=input.readBoolean();int count=count(input);Map<Integer,String> locals=new LinkedHashMap<>();
            for(int i=0;i<count;i++)if(locals.putIfAbsent(input.readInt(),input.readUTF())!=null)throw new IOException("Duplicate CELL local");
            Map<Integer,String> staticLocals=new LinkedHashMap<>();List<String> statics=new ArrayList<>();
            for(int i=0,n=count(input);i<n;i++){int id=input.readInt();String key=input.readUTF();if(key.isEmpty()||statics.contains(key)||!locals.containsKey(id)||staticLocals.putIfAbsent(id,key)!=null)throw new IOException("Invalid CELL static address");statics.add(key);}
            var common=PhpOwnershipExecution.readFramePlan(bytes,name,descriptor);
            if(common==null||!common.ownerInternalName().equals(model.thisClass().asInternalName()))throw new IOException("CELL lacks common physical CV plan");
            Map<Integer,String> addresses=new LinkedHashMap<>();for(var cv:common.locals()) {
                if(cv.id()!=cv.jvmSlot()||addresses.putIfAbsent(cv.jvmSlot(),cv.name())!=null)throw new IOException("Ambiguous common CELL address");
            }
            if(!addresses.equals(locals))throw new IOException("CELL locals differ from common physical addresses");
            Map<Integer,Site> sites=new LinkedHashMap<>();for(int i=0,n=count(input);i<n;i++){Site site=new Site(input.readInt(),input.readUTF(),input.readInt());if(site.id()<0||sites.putIfAbsent(site.id(),site)!=null)throw new IOException("Duplicate CELL site");}
            if(input.read()!=-1||sites.isEmpty()||method.code().isEmpty())throw new IOException("Trailing/empty CELL plan");
            var code=method.code().orElseThrow();var instructions=code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            Map<Integer,CaptureSite> captures=new HashMap<>();Map<Integer,Class<?>> factories=new HashMap<>();
            boolean closed=closedCleanup(code,instructions);Set<Integer> seen=new HashSet<>(),verifiedArguments=new HashSet<>();Map<Integer,PhpOwnershipExecution.Local> arguments=new HashMap<>();
            for(var cv:common.locals())if(cv.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT)arguments.put(cv.id(),cv);
                else if(cv.importKind()!=PhpOwnershipExecution.ImportKind.NONE)closed=false;
            for(int at=0;at<instructions.size();at++) {
                var instruction=instructions.get(at);
                if(instruction.opcode()==Opcode.PUTFIELD||instruction.opcode()==Opcode.PUTSTATIC||instruction.opcode()==Opcode.AASTORE
                        ||instruction.opcode()==Opcode.INVOKEDYNAMIC||instruction.opcode()==Opcode.JSR||instruction.opcode()==Opcode.RET
                        ||instruction.opcode()==Opcode.TABLESWITCH||instruction.opcode()==Opcode.LOOKUPSWITCH)closed=false;
                if(instruction instanceof FieldInstruction field) {
                    boolean constant=field.opcode()==Opcode.GETSTATIC&&field.owner().asInternalName().equals("php/runtime/Memory")
                            &&(field.name().stringValue().startsWith("CONST_")||field.name().equalsString("NULL")||field.name().equalsString("UNDEFINED"));
                    boolean declared=field.opcode()==Opcode.GETSTATIC&&field.owner().asInternalName().equals(model.thisClass().asInternalName())
                            &&(field.name().equalsString("$TRC")&&field.type().equalsString("[Lphp/runtime/env/TraceInfo;")
                            ||field.name().equalsString("$MEM")&&field.type().equalsString("[Lphp/runtime/Memory;")
                            ||field.name().equalsString("$CALL_FUNC_CACHE")&&field.type().equalsString("Lphp/runtime/invoke/cache/FunctionCallCache;"));
                    if(!constant&&!declared)closed=false;
                }
                if(!(instruction instanceof InvokeInstruction invoke))continue;
                String target=invoke.owner().asInternalName(),helper=invoke.name().stringValue(),type=invoke.type().stringValue();
                if(target.equals(RUNTIME)) {
                    if(invoke.opcode()!=Opcode.INVOKESTATIC||at<4||!(instructions.get(at-4) instanceof ConstantInstruction clazz)
                            ||!clazz.constantValue().equals(ClassDesc.ofInternalName(model.thisClass().asInternalName()))||!(instructions.get(at-3) instanceof ConstantInstruction nm)||!nm.constantValue().equals(name)
                            ||!(instructions.get(at-2) instanceof ConstantInstruction ds)||!ds.constantValue().equals(descriptor)
                            ||!(instructions.get(at-1) instanceof ConstantInstruction sid)||!(sid.constantValue() instanceof Integer id))throw new IOException("CELL site operands were changed");
                    if(!runtimeDescriptor(helper,type))throw new IOException("CELL helper descriptor differs from its physical Java contract");
                    Site row=sites.get(id);if(row==null||!row.helper().equals(helper)||!seen.add(id))throw new IOException("CELL helper lacks one exact row");
                    if(helper.equals("capture")) {
                        CaptureSite capture=capturePublisher(owner,method,code,instructions,at,row,sites);
                        if(capture==null)closed=false;else captures.put(id,capture);
                    } else if(helper.equals("wrapClosure")) {
                        Class<?> factory=closureFactory(owner,method,code,instructions,at,sites);
                        if(factory==null)closed=false;else factories.put(id,factory);
                    } else if(row.local()>=0) {
                        if(helper.equals("localAddress")) {
                            if(!localAddressPublisher(method,code,instructions,at,row.local(),locals.get(row.local())))throw new IOException("CELL local-table address lacks its exact key/CV publication");
                        } else if(helper.equals("importArgument")) {
                            var argument=arguments.get(row.local());
                            if(argument==null||!verifiedArguments.add(row.local())||!argumentPublisher(method,instructions,at,row.local(),argument))throw new IOException("CELL argument lacks its exact incoming vector/CV publisher");
                        } else if(helper.equals("sendCompiler")) {
                            if(!sendPublisher(instructions,at,row.local()))throw new IOException("CELL SEND differs from its actual source CV");
                        } else if(helper.equals("borrowStatic")) {
                            if(!staticLocals.containsKey(row.local())
                                    ||!publishedLocal(code,instructions,at,row.local(),locals.get(row.local())))
                                throw new IOException("CELL static borrow lacks its exact CV store");
                        } else {
                            int original=(helper.equals("unset")||helper.equals("bindStatic"))?at-6:at-5;
                            if(!locals.containsKey(row.local())||original<0||!(instructions.get(original) instanceof LoadInstruction read)
                                    ||read.slot()!=row.local()||read.typeKind()!=TypeKind.REFERENCE)throw new IOException("CELL row does not identify actual CV operand");
                            if(helper.equals("bindStatic")&&(!staticLocals.containsKey(row.local())||!(instructions.get(at-7) instanceof ConstantInstruction key)
                                    ||!key.constantValue().equals(staticLocals.get(row.local()))))throw new IOException("CELL static binding key was changed");
                        }
                    }
                } else if(!pureCall(target,helper,type)||discardedCall(target,helper,type)&&invoke.opcode()!=Opcode.INVOKEVIRTUAL) {
                    if(!classDeclarationCheck(model.thisClass().asInternalName(),method,code,instructions,at)&&!emptyClosureConstructor(owner,instructions,at,invoke)&&!emptyClosureFlag(owner,instructions,at,invoke)&&!capturedFactoryCall(owner,method,code,instructions,at,sites)&&!guardedCaptureAllocation(owner,method,code,instructions,at,sites)&&!localTableInitialization(method,code,instructions,at,invoke)&&!guardedSessionCvUnset(method,code,instructions,at,sites,-1,model.thisClass().asInternalName()))closed=false;
                }
            }
            if(!seen.equals(sites.keySet()))throw new IOException("CELL row omits/repeats its physical publisher");
            if(!verifiedArguments.equals(arguments.keySet()))closed=false;
            // Admission never trusts a transported 'complete' flag.
            result.put(name+descriptor,new Member(owner,name,descriptor,closed,reference,sites,locals,statics,arguments,captures,factories));
        }
        return Map.copyOf(result);
    }
    /** Independent cold proof; a transported complete frame never grants this bridge admission. */
    public static void validateClassDeclarationChecks(byte[] bytes,String name,String descriptor)throws IOException {
        var model=ClassFile.of().parse(bytes);
        for(var method:model.methods())if(method.methodName().equalsString(name)&&method.methodType().equalsString(descriptor)) {
            if(method.code().isEmpty())return;
            var code=method.code().orElseThrow();
            var ops=code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            for(int at=0;at<ops.size();at++)if(ops.get(at) instanceof InvokeInstruction call&&call.name().equalsString("__checkClassDeclaration")
                    &&!classDeclarationCheck(model.thisClass().asInternalName(),method,code,ops,at))
                throw new IOException("Static class declaration check changed its physical module operands or control flow");
            return;
        }
        throw new IOException("Static class declaration check member is absent");
    }
    private static boolean classDeclarationCheck(String owner,MethodModel method,CodeModel code,List<Instruction> ops,int at) {
        if(at<6||!invoke(ops.get(at),Opcode.INVOKEVIRTUAL,"php/runtime/env/Environment","__checkClassDeclaration",
                "(Lphp/runtime/env/TraceInfo;Ljava/lang/String;Ljava/lang/String;)V")||(method.flags().flagsMask()&8)==0
                ||!(method.methodName().equalsString("__include")||method.methodName().stringValue().matches("__include_chunk_[0-9]+"))
                ||!method.methodType().equalsString("(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/Memory;"))return false;
        try {
            if(!java.lang.reflect.Modifier.isFinal(Environment.class.getMethod("__checkClassDeclaration",
                    php.runtime.env.TraceInfo.class,String.class,String.class).getModifiers()))return false;
        }catch(ReflectiveOperationException absent){return false;}
        if(!load(ops.get(at-6),0)||!(ops.get(at-5) instanceof FieldInstruction trace)||trace.opcode()!=Opcode.GETSTATIC
                ||!trace.owner().asInternalName().equals(owner)||!trace.name().equalsString("$TRC")
                ||!trace.type().equalsString("[Lphp/runtime/env/TraceInfo;")
                ||!(ops.get(at-4) instanceof ConstantInstruction index)||!(index.constantValue() instanceof Integer value)||value<0
                ||ops.get(at-3).opcode()!=Opcode.AALOAD||!constant(ops.get(at-2),owner)
                ||!(ops.get(at-1) instanceof ConstantInstruction clazz)||!(clazz.constantValue() instanceof String name)||name.isEmpty())return false;
        Map<Label,Integer> labels=labels(code);int start=at-6;
        for(var instruction:ops) {
            if(instruction instanceof StoreInstruction store&&store.slot()==0
                    ||instruction instanceof IncrementInstruction increment&&increment.slot()==0)return false;
            List<Label> targets=new ArrayList<>();
            if(instruction instanceof BranchInstruction branch)targets.add(branch.target());
            else if(instruction instanceof TableSwitchInstruction table){targets.add(table.defaultTarget());for(var item:table.cases())targets.add(item.target());}
            else if(instruction instanceof LookupSwitchInstruction lookup){targets.add(lookup.defaultTarget());for(var item:lookup.cases())targets.add(item.target());}
            for(var target:targets){Integer ordinal=labels.get(target);if(ordinal==null||ordinal>start&&ordinal<=at)return false;}
        }
        for(var element:code.elementList())if(element instanceof ExceptionCatch handler)
            for(var label:List.of(handler.tryStart(),handler.tryEnd(),handler.handler())) {
                Integer target=labels.get(label);if(target==null||target>start&&target<=at)return false;
            }
        return true;
    }


    /** Data-only bridge: exact defined bytes, existing CELL rows, and the guarded active/ordinary unset pair. */
    static boolean hasSessionCvUnset(Class<?> owner,byte[] bytes,String name,String descriptor,int local)throws IOException {
        Member member=read(owner,bytes).get(name+descriptor);if(member==null)return false;
        var model=ClassFile.of().parse(bytes);
        for(var method:model.methods())if(method.methodName().equalsString(name)&&method.methodType().equalsString(descriptor)) {
            if(method.code().isEmpty())return false;var code=method.code().orElseThrow();
            var ops=code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            for(int at=0;at<ops.size();at++)if(guardedSessionCvUnset(method,code,ops,at,member.sites,local,model.thisClass().asInternalName()))return true;
        }
        return false;
    }
    /** Recognition is not a pure-call allowance: the exact old physical unset site must remain in the fallback. */
    private static boolean guardedSessionCvUnset(MethodModel method,CodeModel code,List<Instruction> ops,int at,Map<Integer,Site> sites,int expected,String generatedOwner) {
        if(at<5 || at+11>=ops.size() || !invoke(ops.get(at),Opcode.INVOKEVIRTUAL,"php/runtime/PhpOwnershipExecution$Entry","unsetLocal","(ILphp/runtime/Memory;)V"))return false;
        if(!(ops.get(at-5) instanceof LoadInstruction entry) || entry.typeKind()!=TypeKind.REFERENCE
                || !(ops.get(at-2) instanceof ConstantInstruction id) || !(id.constantValue() instanceof Integer local)
                || expected>=0 && local!=expected || !load(ops.get(at-3),entry.slot()) || !load(ops.get(at-1),local))return false;
        var labels=labels(code);
        int end=at+11;
        // The sparse typed observer is admitted by its complete independent NEW/CV/reference/unset proof.
        if(at+18<ops.size() && invoke(ops.get(at+18),Opcode.INVOKESTATIC,PhpTypedSourcePlan.RUNTIME,"released",PhpTypedSourcePlan.OBSERVER)) {
            int releasedBci=0;for(int i=0;i<at+18;i++)releasedBci+=ops.get(i).sizeInBytes();
            final int actualBci=releasedBci;
            if(PhpTypedSourcePlan.inspect(method).values().stream().noneMatch(proof->proof.local()==local&&proof.releasedBci()==actualBci))return false;
            end=at+19;
        }
        if(!branch(ops.get(at-4),Opcode.IFNULL,labels,at+2) || !branch(ops.get(at+1),Opcode.GOTO,labels,end)
                || !(ops.get(at+2) instanceof LoadInstruction frame) || frame.typeKind()!=TypeKind.REFERENCE
                || !constant(ops.get(at+3),local) || !load(ops.get(at+4),local)
                || !method.methodType().stringValue().startsWith("(Lphp/runtime/env/Environment;")
                || !load(ops.get(at+5),(method.flags().flagsMask()&8)==0?1:0)
                || !(ops.get(at+6) instanceof ConstantInstruction owner) || !owner.constantValue().equals(ClassDesc.ofInternalName(generatedOwner))
                || !constant(ops.get(at+7),method.methodName().stringValue()) || !constant(ops.get(at+8),method.methodType().stringValue())
                || !(ops.get(at+9) instanceof ConstantInstruction index) || !(index.constantValue() instanceof Integer site)
                || !invoke(ops.get(at+10),Opcode.INVOKESTATIC,RUNTIME,"unset","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)V"))return false;
        Site row=sites.get(site);if(row==null || !row.helper().equals("unset") || row.local()!=local)return false;
        // Neither branch nor handler may enter an interior instruction of this exact paired publication.
        for(int pos=0;pos<ops.size();pos++)if(ops.get(pos) instanceof BranchInstruction jump) {
            Integer target=labels.get(jump.target());if(target==null || target>at-5 && target<end && pos!=at-4)return false;
        }
        for(var element:code.elementList())if(element instanceof ExceptionCatch handler) {Integer target=labels.get(handler.handler());if(target==null||target>at-5&&target<end)return false;}
        return true;
    }
    private static boolean argumentPublisher(MethodModel method,List<Instruction> ops,int at,int local,PhpOwnershipExecution.Local argument){
        int role=argument.importMode()==PhpOwnershipExecution.Mode.CELL?1:0;
        if(argument.storage()!=PhpOwnershipExecution.Storage.ADDRESS||at<8||!load(ops.get(at-7),local)
                ||!constant(ops.get(at-6),argument.importIndex())||!constant(ops.get(at-5),role)
                ||!(ops.get(at-8) instanceof LoadInstruction value)||value.typeKind()!=TypeKind.REFERENCE)return false;
        int args=(method.flags().flagsMask()&8)==0?2:1;
        if(!method.methodType().equalsString("(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;"))return false;
        for(int pos=at-9;pos>=3;pos--)if(ops.get(pos) instanceof StoreInstruction store&&store.slot()==value.slot())
            return store.typeKind()==TypeKind.REFERENCE&&ops.get(pos-1).opcode()==Opcode.AALOAD
                    &&constant(ops.get(pos-2),argument.importIndex())&&load(ops.get(pos-3),args);
        return false;
    }
    private static boolean sendPublisher(List<Instruction> ops,int at,int local){
        if(at<8||!(ops.get(at-8) instanceof LoadInstruction value)||value.typeKind()!=TypeKind.REFERENCE)return false;
        for(int pos=at-9;pos>=3;pos--)if(ops.get(pos) instanceof StoreInstruction store&&store.slot()==value.slot())
            return store.typeKind()==TypeKind.REFERENCE&&ops.get(pos-1) instanceof StoreInstruction kind&&kind.typeKind()==TypeKind.INT
                    &&constant(ops.get(pos-2),0)&&load(ops.get(pos-3),local);
        return false;
    }
    /** The existing Entry address arm joins the default address at the exact scratch store. */
    private static boolean localAddressPublisher(MethodModel method,CodeModel code,List<Instruction> ops,int at,int local,String name) {
        if(at<14||at+15>=ops.size())return false;
        Map<Label,Integer> labels=labels(code);
        if(!(ops.get(at-14) instanceof LoadInstruction entry)||entry.typeKind()!=TypeKind.REFERENCE
                ||!branch(ops.get(at-13),Opcode.IFNULL,labels,at-7)||!load(ops.get(at-12),entry.slot())
                ||!constant(ops.get(at-11),local)||!constant(ops.get(at-10),name)
                ||!invoke(ops.get(at-9),Opcode.INVOKEVIRTUAL,"php/runtime/PhpOwnershipExecution$Entry","localAddress","(ILjava/lang/String;)Lphp/runtime/Memory;")
                ||!branch(ops.get(at-8),Opcode.GOTO,labels,at+1)
                ||!(ops.get(at-7) instanceof LoadInstruction table)||table.typeKind()!=TypeKind.REFERENCE
                ||!constant(ops.get(at-6),name)||!(ops.get(at-5) instanceof LoadInstruction frame)||frame.typeKind()!=TypeKind.REFERENCE
                ||!load(ops.get(at+2),entry.slot())||!load(ops.get(at+15),frame.slot()))return false;
        boolean issued=false;
        for(int pos=0;pos<at-14;pos++)if(ops.get(pos) instanceof InvokeInstruction call
                &&invoke(call,Opcode.INVOKESTATIC,"php/runtime/memory/ArrayMemory","valueOfRef","(Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;")
                &&store(ops.get(pos+1),table.slot())&&localTableInitialization(method,code,ops,pos,call))issued=true;
        if(!issued)return false;
        for(int pos=0;pos<ops.size();pos++)if(ops.get(pos) instanceof BranchInstruction jump){
            Integer target=labels.get(jump.target());
            if(target==null||target>at-14&&target<=at&&pos!=at-13)return false;
        }
        return publishedLocal(code,ops,at,local,name,at-8);
    }
    /** The producer first enters the existing scratch, then all guarded arms publish that same value to the CV. */
    private static boolean publishedLocal(CodeModel code,List<Instruction> ops,int at,int local,String name) {
        return publishedLocal(code,ops,at,local,name,-1);
    }
    private static boolean publishedLocal(CodeModel code,List<Instruction> ops,int at,int local,String name,int producerJoin) {
        if(at+1>=ops.size()||!(ops.get(at+1) instanceof StoreInstruction scratch)||scratch.typeKind()!=TypeKind.REFERENCE)return false;
        if(scratch.slot()==local)return true;
        if(at+33>=ops.size()||scratch.slot()==local)return false;
        int tmp=scratch.slot();Map<Label,Integer> labels=labels(code);
        if(!(ops.get(at+2) instanceof LoadInstruction entry)||entry.typeKind()!=TypeKind.REFERENCE
                ||!branch(ops.get(at+3),Opcode.IFNULL,labels,at+15)||!load(ops.get(at+4),entry.slot())
                ||!constant(ops.get(at+5),local)||!load(ops.get(at+6),tmp)||!constant(ops.get(at+7),2)
                ||!invoke(ops.get(at+8),Opcode.INVOKEVIRTUAL,"php/runtime/PhpOwnershipExecution$Entry","prepareLocal","(ILphp/runtime/Memory;I)Lphp/runtime/PhpOwnershipHeap$Publication;")
                ||!(ops.get(at+9) instanceof StoreInstruction first)||first.typeKind()!=TypeKind.REFERENCE
                ||!load(ops.get(at+10),tmp)||!store(ops.get(at+11),local)||!load(ops.get(at+12),first.slot())
                ||!invoke(ops.get(at+13),Opcode.INVOKEINTERFACE,"php/runtime/PhpOwnershipHeap$Publication","commit","()V")
                ||!branch(ops.get(at+14),Opcode.GOTO,labels,at+33)
                ||!(ops.get(at+15) instanceof LoadInstruction frame)||frame.typeKind()!=TypeKind.REFERENCE
                ||!branch(ops.get(at+16),Opcode.IFNULL,labels,at+31)||!load(ops.get(at+17),frame.slot())
                ||!constant(ops.get(at+18),local)||!constant(ops.get(at+19),name)||!load(ops.get(at+20),tmp)||!constant(ops.get(at+21),2)
                ||!invoke(ops.get(at+22),Opcode.INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","prepareLocal","(ILjava/lang/String;Lphp/runtime/Memory;I)Lphp/runtime/PhpRuntimeOwnership$Publication;")
                ||!(ops.get(at+23) instanceof StoreInstruction second)||second.typeKind()!=TypeKind.REFERENCE
                ||!load(ops.get(at+24),tmp)||!store(ops.get(at+25),local)||!load(ops.get(at+26),second.slot())
                ||!invoke(ops.get(at+27),Opcode.INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Publication","afterLocalStore","()V")
                ||!load(ops.get(at+28),second.slot())||!invoke(ops.get(at+29),Opcode.INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Publication","commit","()V")
                ||!branch(ops.get(at+30),Opcode.GOTO,labels,at+33)||!load(ops.get(at+31),tmp)||!store(ops.get(at+32),local))return false;
        // Only the exact generated joins enter this publication.
        for(int pos=0;pos<ops.size();pos++)if(ops.get(pos) instanceof BranchInstruction jump) {
            Integer target=labels.get(jump.target());if(target==null||target>at&&target<at+33&&pos!=at+3&&pos!=at+16&&!(pos==producerJoin&&target==at+1))return false;
        }
        return true;
    }
    private static boolean localTableInitialization(MethodModel method,CodeModel code,List<Instruction> ops,int at,InvokeInstruction invoke) {
        if(!invoke(ops.get(at),Opcode.INVOKESTATIC,"php/runtime/memory/ArrayMemory","valueOfRef","(Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;")||at<6||at+1>=ops.size())return false;
        Map<Label,Integer> labels=labels(code);
        if(!(ops.get(at-6) instanceof LoadInstruction entry)||entry.typeKind()!=TypeKind.REFERENCE
                ||!branch(ops.get(at-5),Opcode.IFNULL,labels,at-1)||!load(ops.get(at-4),entry.slot())
                ||!invoke(ops.get(at-3),Opcode.INVOKEVIRTUAL,"php/runtime/PhpOwnershipExecution$Entry","localTable","()Lphp/runtime/memory/ArrayMemory;")
                ||!branch(ops.get(at-2),Opcode.GOTO,labels,at+1)||!(ops.get(at+1) instanceof StoreInstruction table)||table.typeKind()!=TypeKind.REFERENCE)return false;
        boolean input=ops.get(at-1).opcode()==Opcode.ACONST_NULL;
        if(!input&&method.methodType().equalsString("(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/Memory;"))
            input=load(ops.get(at-1),(method.flags().flagsMask()&8)==0?3:2);
        if(!input)return false;
        int stores=0;for(var op:ops)if(op instanceof StoreInstruction write&&write.slot()==table.slot())stores++;
        if(stores!=1)return false;
        for(int pos=0;pos<ops.size();pos++)if(ops.get(pos) instanceof BranchInstruction jump) {
            Integer target=labels.get(jump.target());if(target==null||target>at-6&&target<at+1&&pos!=at-5)return false;
        }
        return true;
    }
    private static Map<Label,Integer> labels(CodeModel code) {
        // Bound branch targets are lazy in the JDK classfile reader. Resolve them before
        // enumerating LabelTargets, including jumps absent from the StackMapTable.
        for(var element:code.elementList()) {
            if(element instanceof BranchInstruction branch)branch.target();
            else if(element instanceof TableSwitchInstruction table){table.defaultTarget();for(var item:table.cases())item.target();}
            else if(element instanceof LookupSwitchInstruction lookup){lookup.defaultTarget();for(var item:lookup.cases())item.target();}
        }
        Map<Label,Integer> labels=new IdentityHashMap<>();int ordinal=0;
        for(var element:code.elementList()){if(element instanceof LabelTarget target)labels.put(target.label(),ordinal);if(element instanceof Instruction)ordinal++;}
        return labels;
    }
    private static boolean branch(Instruction instruction,Opcode opcode,Map<Label,Integer> labels,int ordinal){return instruction instanceof BranchInstruction jump&&jump.opcode()==opcode&&Objects.equals(labels.get(jump.target()),ordinal);}
    private static boolean store(Instruction instruction,int slot){return instruction instanceof StoreInstruction write&&write.typeKind()==TypeKind.REFERENCE&&write.slot()==slot;}
    private static boolean constant(Instruction instruction,Object value){return instruction instanceof ConstantInstruction constant&&value.equals(constant.constantValue());}
    private static boolean invoke(Instruction instruction,Opcode opcode,String owner,String name,String descriptor){return instruction instanceof InvokeInstruction call&&call.opcode()==opcode&&call.owner().asInternalName().equals(owner)&&call.name().equalsString(name)&&call.type().equalsString(descriptor);}
    /** Runtime GLOBAL RHS validates the exact transformed export row without changing reference publication semantics. */
    public static void validateExportSite(byte[] bytes,String owner,String name,String descriptor,int site,int local)throws IOException {
        byte[] header=headers(bytes).get(name+descriptor);if(header==null)throw new IOException("GLOBAL export lacks its physical CELL plan");
        var input=new DataInputStream(new ByteArrayInputStream(header));
        if(input.readInt()!=1||!input.readUTF().equals(owner)||!input.readUTF().equals(name)||!input.readUTF().equals(descriptor))throw new IOException("GLOBAL export changed its physical member");
        input.readBoolean();Set<Integer> locals=new HashSet<>();for(int i=0,n=count(input);i<n;i++){if(!locals.add(input.readInt()))throw new IOException("Duplicate GLOBAL export CV");input.readUTF();}
        for(int i=0,n=count(input);i<n;i++){input.readInt();input.readUTF();}
        boolean matched=false;Set<Integer> ids=new HashSet<>();for(int i=0,n=count(input);i<n;i++){int id=input.readInt();String helper=input.readUTF();int cv=input.readInt();if(!ids.add(id))throw new IOException("Duplicate GLOBAL export site");if(id==site)matched=helper.equals("export")&&cv==local&&locals.contains(local);}
        if(input.read()!=-1||!matched)throw new IOException("GLOBAL export lacks its exact actual CV row");
    }
    private static boolean runtimeDescriptor(String name,String descriptor) {
        for(var method:PhpPrimitiveCellRuntime.class.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(method.getModifiers())
                &&java.lang.reflect.Modifier.isStatic(method.getModifiers())&&method.getName().equals(name)
                &&PhpOwnershipExecution.descriptor(method).equals(descriptor))return true;
        return false;
    }
    private static boolean emptyClosureConstructor(Class<?> owner,List<Instruction> code,int at,InvokeInstruction invoke) {
        if(invoke.opcode()!=Opcode.INVOKESPECIAL||!invoke.name().equalsString("<init>")
                ||!invoke.type().equalsString("(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;Lphp/runtime/Memory;Ljava/lang/String;[Lphp/runtime/Memory;)V")
                ||at<1||code.get(at-1).opcode()!=Opcode.ACONST_NULL)return false;
        return plainClosureConstructor(owner,invoke);
    }
    private static boolean plainClosureConstructor(Class<?> owner,InvokeInstruction invoke){
        try {
            Class<?> actual=Class.forName(invoke.owner().asInternalName().replace('/','.'),false,owner.getClassLoader());
            if(actual.getClassLoader()!=owner.getClassLoader()||actual.getSuperclass()!=php.runtime.lang.Closure.class
                    ||!(actual.getClassLoader() instanceof RuntimeClassLoader loader))return false;
            byte[] bytes=loader.primitiveDefinedBytes(actual);if(bytes==null)return false;
            var model=ClassFile.of().parse(bytes);String descriptor=invoke.type().stringValue();
            var constructor=model.methods().stream().filter(method->method.methodName().equalsString("<init>")&&method.methodType().equalsString(descriptor)).findFirst().orElse(null);
            if(constructor==null||constructor.code().isEmpty())return false;var body=constructor.code().orElseThrow();
            var ops=body.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            if(ops.size()!=8||body.elementList().stream().anyMatch(ExceptionCatch.class::isInstance))return false;
            for(int i=0;i<6;i++)if(!load(ops.get(i),i))return false;
            return invoke(ops.get(6),Opcode.INVOKESPECIAL,"php/runtime/lang/Closure","<init>",descriptor)&&ops.get(7).opcode()==Opcode.RETURN;
        }catch(ClassNotFoundException failure){return false;}
    }
    private static boolean emptyClosureFlag(Class<?> owner,List<Instruction> ops,int at,InvokeInstruction setter) {
        if(setter.opcode()!=Opcode.INVOKEVIRTUAL||!setter.owner().asInternalName().equals("php/runtime/lang/Closure")||!setter.type().equalsString("(Z)V")
                ||at<3||ops.get(at-2).opcode()!=Opcode.DUP||!booleanConstant(ops.get(at-1)))return false;
        int factory;
        if(setter.name().equalsString("setStaticClosure"))factory=at-3;
        else if(setter.name().equalsString("setUsesThis")&&at>=6&&ops.get(at-5).opcode()==Opcode.DUP&&booleanConstant(ops.get(at-4))
                &&invoke(ops.get(at-3),Opcode.INVOKEVIRTUAL,"php/runtime/lang/Closure","setStaticClosure","(Z)V"))factory=at-6;
        else return false;
        if(!(ops.get(factory) instanceof InvokeInstruction constructor)||!emptyClosureConstructor(owner,ops,factory,constructor))return false;
        try {
            Class<?> actual=Class.forName(constructor.owner().asInternalName().replace('/','.'),false,owner.getClassLoader());
            return actual.getMethod("setStaticClosure",boolean.class).getDeclaringClass()==php.runtime.lang.Closure.class
                    &&actual.getMethod("setUsesThis",boolean.class).getDeclaringClass()==php.runtime.lang.Closure.class;
        }catch(ReflectiveOperationException failure){return false;}
    }
    private static final String CAPTURE="([Lphp/runtime/Memory;ILphp/runtime/Memory;Ljava/lang/Class;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)V";
    private static final String WRAP="(Lphp/runtime/lang/IObject;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/Memory;";
    private static final String EXPORT="(Lphp/runtime/Memory;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/Memory;";
    /** A fresh Java array remains on the operand stack until its exact constructor consumes every use slot. */
    private static CaptureChain captureChain(CodeModel code,List<Instruction> ops,int end,String type,Map<Integer,Site> sites){
        for(var op:ops)if(op instanceof TableSwitchInstruction||op instanceof LookupSwitchInstruction)return null;
        int cursor=end,expected=-1,count=-1,start=-1;
        while(cursor>=16&&invoke(ops.get(cursor),Opcode.INVOKESTATIC,RUNTIME,"capture",CAPTURE)){
            if(!constant(ops.get(cursor-6),ClassDesc.ofInternalName(type))||!constant(ops.get(cursor-5),1)
                    ||!invoke(ops.get(cursor-7),Opcode.INVOKESTATIC,RUNTIME,"export",EXPORT)
                    ||!(ops.get(cursor-1) instanceof ConstantInstruction id)||!(id.constantValue() instanceof Integer site)
                    ||!(ops.get(cursor-8) instanceof ConstantInstruction exported)||!(exported.constantValue() instanceof Integer exportId)
                    ||!(ops.get(cursor-13) instanceof ConstantInstruction index)||!(index.constantValue() instanceof Integer slot)||slot<0
                    ||!(ops.get(cursor-12) instanceof LoadInstruction source)||source.typeKind()!=TypeKind.REFERENCE||ops.get(cursor-14).opcode()!=Opcode.DUP)return null;
            Site row=sites.get(site),export=sites.get(exportId);
            if(row==null||!row.helper().equals("capture")||row.local()!=source.slot()||export==null||!export.helper().equals("export")||export.local()!=source.slot())return null;
            if(expected<0){count=slot+1;expected=slot;}
            if(slot!=expected--)return null;
            if(slot==0){
                if(!(ops.get(cursor-15) instanceof NewReferenceArrayInstruction array)||!array.componentType().asInternalName().equals("php/runtime/Memory")
                        ||!constant(ops.get(cursor-16),count))return null;
                start=cursor-16;break;
            }
            cursor-=15;
        }
        if(start<0||expected!=-1)return null;
        Map<Label,Integer> labels=labels(code);
        for(var op:ops)if(op instanceof BranchInstruction branch){Integer target=labels.get(branch.target());if(target==null||target>start&&target<=end)return null;}
        for(var element:code.elementList())if(element instanceof ExceptionCatch guard){Integer handler=labels.get(guard.handler());if(handler==null||handler>start&&handler<=end)return null;}
        return new CaptureChain(type,count,start,end);
    }
    private static CaptureChain capturedClosureConstructor(Class<?> owner,MethodModel method,CodeModel code,List<Instruction> ops,int at,Map<Integer,Site> sites){
        if(at<1||!(ops.get(at) instanceof InvokeInstruction constructor)||constructor.opcode()!=Opcode.INVOKESPECIAL||!constructor.name().equalsString("<init>")
                ||!constructor.type().equalsString("(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;Lphp/runtime/Memory;Ljava/lang/String;[Lphp/runtime/Memory;)V")
                ||!plainClosureConstructor(owner,constructor))return null;
        String type=constructor.owner().asInternalName();CaptureChain chain=captureChain(code,ops,at-1,type,sites);if(chain==null||chain.start()<9)return null;
        int start=chain.start();
        if(!(ops.get(start-9) instanceof NewObjectInstruction object)||!object.className().asInternalName().equals(type)||ops.get(start-8).opcode()!=Opcode.DUP
                ||!(ops.get(start-7) instanceof LoadInstruction environment)||environment.typeKind()!=TypeKind.REFERENCE||!load(ops.get(start-6),environment.slot())
                ||!(ops.get(start-5) instanceof ConstantInstruction module)||!(module.constantValue() instanceof String)
                ||!(ops.get(start-4) instanceof ConstantInstruction closure)||!(closure.constantValue() instanceof Integer)
                ||!invoke(ops.get(start-3),Opcode.INVOKEVIRTUAL,"php/runtime/env/Environment","__getClosure","(Ljava/lang/String;I)Lphp/runtime/reflection/ClassEntity;")
                ||!(ops.get(start-2) instanceof FieldInstruction self)||self.opcode()!=Opcode.GETSTATIC||!self.owner().asInternalName().equals("php/runtime/Memory")||!self.name().equalsString("NULL")
                ||ops.get(start-1).opcode()!=Opcode.ACONST_NULL)return null;
        Map<Label,Integer> labels=labels(code);
        for(var op:ops)if(op instanceof BranchInstruction branch){Integer target=labels.get(branch.target());if(target==null||target>start-9&&target<=at)return null;}
        for(var element:code.elementList())if(element instanceof ExceptionCatch guard){Integer handler=labels.get(guard.handler());if(handler==null||handler>start-9&&handler<=at)return null;}
        return chain;
    }
    private static CaptureSite capturePublisher(Class<?> owner,MethodModel method,CodeModel code,List<Instruction> ops,int at,Site row,Map<Integer,Site> sites){
        for(int end=at+1;end<ops.size();end++){
            if(invoke(ops.get(end),Opcode.INVOKESTATIC,RUNTIME,"capture",CAPTURE))continue;
            if(!(ops.get(end) instanceof InvokeInstruction constructor)||constructor.opcode()!=Opcode.INVOKESPECIAL||!constructor.name().equalsString("<init>"))continue;
            var chain=capturedClosureConstructor(owner,method,code,ops,end,sites);if(chain==null||at<chain.start()||at>chain.end())return null;
            if(!(ops.get(at-13) instanceof ConstantInstruction index)||!(index.constantValue() instanceof Integer slot))return null;
            try{return new CaptureSite(Class.forName(chain.type().replace('/','.'),false,owner.getClassLoader()),slot,1,chain.count());}catch(ClassNotFoundException failure){return null;}
        }
        return null;
    }
    private static Class<?> closureFactory(Class<?> owner,MethodModel method,CodeModel code,List<Instruction> ops,int at,Map<Integer,Site> sites){
        if(at<11||!invoke(ops.get(at),Opcode.INVOKESTATIC,RUNTIME,"wrapClosure",WRAP))return null;
        int constructor=at-11;
        if(ops.get(constructor+1).opcode()!=Opcode.DUP||!booleanConstant(ops.get(constructor+2))
                ||!invoke(ops.get(constructor+3),Opcode.INVOKEVIRTUAL,"php/runtime/lang/Closure","setStaticClosure","(Z)V")
                ||ops.get(constructor+4).opcode()!=Opcode.DUP||!booleanConstant(ops.get(constructor+5))
                ||!invoke(ops.get(constructor+6),Opcode.INVOKEVIRTUAL,"php/runtime/lang/Closure","setUsesThis","(Z)V")
                ||!(ops.get(constructor) instanceof InvokeInstruction call))return null;
        var captured=capturedClosureConstructor(owner,method,code,ops,constructor,sites);
        if(captured==null&&!emptyClosureConstructor(owner,ops,constructor,call))return null;
        try{Class<?> type=Class.forName(call.owner().asInternalName().replace('/','.'),false,owner.getClassLoader());
            if(type.getMethod("setStaticClosure",boolean.class).getDeclaringClass()!=php.runtime.lang.Closure.class||type.getMethod("setUsesThis",boolean.class).getDeclaringClass()!=php.runtime.lang.Closure.class)return null;return type;
        }catch(ReflectiveOperationException failure){return null;}
    }
    private static boolean capturedFactoryCall(Class<?> owner,MethodModel method,CodeModel code,List<Instruction> ops,int at,Map<Integer,Site> sites){
        for(int wrapper: new int[]{at+11,at+8,at+5})if(wrapper<ops.size()&&closureFactory(owner,method,code,ops,wrapper,sites)!=null)
            return at==wrapper-11||at==wrapper-8||at==wrapper-5;
        return false;
    }
    /** Capture on the old heavy sibling is admitted only inside its exact null-Frame factory guard and fresh-default join. */
    private static boolean guardedCaptureAllocation(Class<?> owner,MethodModel method,CodeModel code,List<Instruction> ops,int at,Map<Integer,Site> sites){
        if(!invoke(ops.get(at),Opcode.INVOKEVIRTUAL,"php/runtime/PhpOwnershipClosures$RuntimeAllocation","capture","(ILphp/runtime/Memory;)V"))return false;
        for(int prepare=at-1;prepare>=9;prepare--)if(invoke(ops.get(prepare),Opcode.INVOKESTATIC,"php/runtime/PhpOwnershipClosures","prepareRuntime","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/reflection/helper/ClosureEntity;)Lphp/runtime/PhpOwnershipClosures$RuntimeAllocation;")){
            Map<Label,Integer> labels=labels(code);
            if(!(ops.get(prepare+1) instanceof StoreInstruction allocation)||allocation.typeKind()!=TypeKind.REFERENCE
                    ||!(ops.get(prepare-9) instanceof LoadInstruction frame)||frame.typeKind()!=TypeKind.REFERENCE||!load(ops.get(prepare-7),frame.slot())
                    ||!(ops.get(prepare-8) instanceof BranchInstruction guard)||guard.opcode()!=Opcode.IFNULL)return false;
            Integer fallback=labels.get(guard.target());if(fallback==null||fallback<=at)return false;
            int wrapper=-1;for(int pos=fallback;pos<ops.size();pos++)if(invoke(ops.get(pos),Opcode.INVOKESTATIC,RUNTIME,"wrapClosure",WRAP)){wrapper=pos;break;}
            if(wrapper<0||closureFactory(owner,method,code,ops,wrapper,sites)==null)return false;
            var chain=capturedClosureConstructor(owner,method,code,ops,wrapper-11,sites);if(chain==null||chain.start()-9!=fallback)return false;
            int last=fallback-1;
            if(last<3||!branch(ops.get(last),Opcode.GOTO,labels,wrapper+1)||!invoke(ops.get(last-1),Opcode.INVOKEVIRTUAL,"php/runtime/PhpOwnershipClosures$RuntimeAllocation","complete","()Lphp/runtime/Memory;")||!load(ops.get(last-2),allocation.slot()))return false;
            if(at<8||!load(ops.get(at-8),allocation.slot())||!(ops.get(at-7) instanceof ConstantInstruction index)||!(index.constantValue() instanceof Integer slot)||slot<0||slot>=chain.count()
                    ||!(ops.get(at-6) instanceof LoadInstruction source)||source.typeKind()!=TypeKind.REFERENCE||!invoke(ops.get(at-1),Opcode.INVOKESTATIC,RUNTIME,"export",EXPORT))return false;
            for(int pos=0;pos<ops.size();pos++)if(ops.get(pos) instanceof BranchInstruction jump){Integer target=labels.get(jump.target());if(target==null||target>prepare-9&&target<fallback&&pos!=prepare-8)return false;}
            return true;
        }
        return false;
    }
    private static boolean booleanConstant(Instruction op){return constant(op,0)||constant(op,1);}
    /** Existing generated context owns retirement on each return and the outer catch-all. */
    private static boolean closedCleanup(CodeModel code,List<Instruction> instructions) {
        int frame=-1,enter=-1;String context="php/runtime/PhpPropertyWriteContext",desc="(Lphp/runtime/PhpPropertyWriteContext$Frame;)V";
        Map<Label,Integer> labels=labels(code);
        for(int at=0;at<instructions.size();at++)if(instructions.get(at) instanceof InvokeInstruction call
                &&call.owner().asInternalName().equals(context)&&call.name().equalsString("enter")) {
            if(enter>=0||call.opcode()!=Opcode.INVOKESTATIC||!call.type().equalsString("(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Z)Lphp/runtime/PhpPropertyWriteContext$Frame;")
                    ||at+1>=instructions.size()||!(instructions.get(at+1) instanceof StoreInstruction store)||store.typeKind()!=TypeKind.REFERENCE)return false;
            frame=store.slot();enter=at;
        }
        if(enter<0)return false;
        Set<Integer> interiors=new HashSet<>();
        for(int at=0;at<instructions.size();at++)if(instructions.get(at).opcode()==Opcode.ARETURN) {
            if(at<2||!load(instructions.get(at-2),frame)||!call(instructions.get(at-1),context,"leave",desc))return false;
            interiors.add(at-1);interiors.add(at);
        }
        for(var instruction:instructions)if(instruction instanceof BranchInstruction branch&&(labels.get(branch.target())==null||interiors.contains(labels.get(branch.target()))))return false;
        for(var element:code.elementList())if(element instanceof ExceptionCatch guard&&guard.catchType().isEmpty()) {
            Integer start=labels.get(guard.tryStart()),end=labels.get(guard.tryEnd()),handler=labels.get(guard.handler());
            if(start==null||end==null||handler==null||start!=enter+2||end>handler||handler+4>=instructions.size()||!erasedCleanupGap(code,instructions,labels,end,handler))continue;
            if(instructions.get(handler) instanceof StoreInstruction error&&error.typeKind()==TypeKind.REFERENCE
                    &&load(instructions.get(handler+1),frame)&&call(instructions.get(handler+2),context,"leave",desc)
                    &&load(instructions.get(handler+3),error.slot())&&instructions.get(handler+4).opcode()==Opcode.ATHROW)return true;
        }
        return false;
    }
    /** ASM cuts dead implicit-return bytes out of the catch range and rewrites them to NOP/ATHROW traps. */
    private static boolean erasedCleanupGap(CodeModel code,List<Instruction> ops,Map<Label,Integer> labels,int end,int handler) {
        if(end==handler)return true;
        if(end<1||end>handler||handler>ops.size()
                ||ops.get(end-1).opcode()!=Opcode.ARETURN&&ops.get(end-1).opcode()!=Opcode.ATHROW)return false;
        for(int at=end;at<handler;at++)if(ops.get(at).opcode()!=Opcode.NOP&&ops.get(at).opcode()!=Opcode.ATHROW)return false;
        for(var op:ops)if(op instanceof BranchInstruction jump){Integer target=labels.get(jump.target());if(target==null||target>=end&&target<handler)return false;}
        for(var element:code.elementList())if(element instanceof ExceptionCatch guard){Integer target=labels.get(guard.handler());if(target==null||target>=end&&target<handler)return false;}
        return true;
    }
    private static boolean load(Instruction instruction,int slot){return instruction instanceof LoadInstruction load&&load.typeKind()==TypeKind.REFERENCE&&load.slot()==slot;}
    private static boolean call(Instruction instruction,String owner,String name,String descriptor){return instruction instanceof InvokeInstruction call&&call.opcode()==Opcode.INVOKESTATIC&&call.owner().asInternalName().equals(owner)&&call.name().equalsString(name)&&call.type().equalsString(descriptor);}
    private static int count(DataInputStream in)throws IOException {int count=in.readInt();if(count<0||count>65535)throw new IOException("Invalid CELL plan count");return count;}
    private static Map<String,byte[]> headers(byte[] bytes)throws IOException {
        var input=new DataInputStream(new ByteArrayInputStream(bytes));if(input.readInt()!=0xcafebabe)throw new IOException("Invalid CELL classfile");input.skipNBytes(4);int count=input.readUnsignedShort();String[] utf=new String[count];
        for(int i=1;i<count;i++)switch(input.readUnsignedByte()) {
            case 1:utf[i]=input.readUTF();break;case 3:case 4:input.skipNBytes(4);break;case 5:case 6:input.skipNBytes(8);i++;break;
            case 7:case 8:case 16:case 19:case 20:input.skipNBytes(2);break;case 9:case 10:case 11:case 12:case 17:case 18:input.skipNBytes(4);break;case 15:input.skipNBytes(3);break;default:throw new IOException("Unknown CELL constant");
        }
        input.skipNBytes(6);input.skipNBytes(2L*input.readUnsignedShort());
        for(int i=0,n=input.readUnsignedShort();i<n;i++){input.skipNBytes(6);skipAttributes(input);}
        Map<String,byte[]> result=new HashMap<>();
        for(int i=0,n=input.readUnsignedShort();i<n;i++){
            input.readUnsignedShort();String name=utf[input.readUnsignedShort()],descriptor=utf[input.readUnsignedShort()];
            for(int j=0,k=input.readUnsignedShort();j<k;j++){String attribute=utf[input.readUnsignedShort()];long size=Integer.toUnsignedLong(input.readInt());if(size>input.available())throw new IOException("Truncated CELL attribute");
                if(attribute.equals(ATTRIBUTE)){if(size>1024*1024||result.putIfAbsent(name+descriptor,input.readNBytes((int)size))!=null)throw new IOException("Duplicate/oversized CELL plan");}else input.skipNBytes(size);}
        }
        skipAttributes(input);if(input.read()!=-1)throw new IOException("Trailing CELL classfile");return result;
    }
    private static void skipAttributes(DataInputStream input)throws IOException {for(int i=0,n=input.readUnsignedShort();i<n;i++){input.readUnsignedShort();long size=Integer.toUnsignedLong(input.readInt());if(size>input.available())throw new IOException("Truncated CELL classfile");input.skipNBytes(size);}}
}
