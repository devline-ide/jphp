package php.runtime;

import php.runtime.env.*;
import php.runtime.invoke.*;
import php.runtime.lang.*;
import php.runtime.lang.support.IPhpOwnershipObject;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.io.*;

/** Explicit execution custody. Frame-plan transport never automatically certifies a body. */
public final class PhpOwnershipExecution {
    public static final int VALUE=0,CELL=1,BORROW_ADDRESS=2;
    public enum Mode { VALUE,CELL,BORROW_ADDRESS }
    public enum Storage { VALUE,ADDRESS }
    public enum ImportKind { NONE,ARGUMENT,CAPTURE }
    public enum EntryKind { FUNCTION,METHOD,MODULE,CHUNK,LEXICAL_CLOSURE,DEFAULT_INITIALIZER,CLASS_ENV_INITIALIZER }
    public record Local(int id,int jvmSlot,String name,Storage storage,ImportKind importKind,int importIndex,Mode importMode) {
        public Local {if(id<0 || jvmSlot<0 || name==null || name.isEmpty() || storage==null || importKind==null || importMode==null)throw new IllegalArgumentException("Invalid semantic local");}
    }
    public static final class FramePlan {
        private final int schema,pendingReturn,thrown;
        private final String owner,method,descriptor;
        private final EntryKind kind;
        private final List<Local> locals;
        private final Set<String> required;
        private final List<String> unsupported;
        private final int[] eval,thrownIds,returnIds;
        public FramePlan(int schema,String owner,String method,String descriptor,EntryKind kind,List<Local> locals,Set<String> required,List<String> unsupported,int pendingReturn,int thrown,int[] eval) {
            this(schema,owner,method,descriptor,kind,locals,required,unsupported,pendingReturn,thrown,eval,new int[0]);
        }
        public FramePlan(int schema,String owner,String method,String descriptor,EntryKind kind,List<Local> locals,Set<String> required,List<String> unsupported,int pendingReturn,int thrown,int[] eval,int[] thrownIds) {
            this(schema,owner,method,descriptor,kind,locals,required,unsupported,pendingReturn,thrown,eval,thrownIds,new int[0]);
        }
        public FramePlan(int schema,String owner,String method,String descriptor,EntryKind kind,List<Local> locals,Set<String> required,List<String> unsupported,int pendingReturn,int thrown,int[] eval,int[] thrownIds,int[] returnIds) {
            if((schema!=1 && schema!=2) || schema==1 && (thrownIds.length!=0 || returnIds.length!=0) || schema==2 && thrown!=-1 || owner==null || method==null || descriptor==null || kind==null)throw new IllegalArgumentException("Unknown ownership frame schema");
            this.schema=schema;this.owner=owner;this.method=method;this.descriptor=descriptor;this.kind=kind;
            this.locals=List.copyOf(locals);this.required=Set.copyOf(required);this.unsupported=List.copyOf(unsupported);this.pendingReturn=pendingReturn;this.thrown=thrown;this.eval=eval.clone();this.thrownIds=thrownIds.clone();this.returnIds=returnIds.clone();
            Set<Integer> ids=new HashSet<>();Set<String> names=new HashSet<>();
            for(Local local:this.locals)if(!ids.add(local.id()) || !names.add(local.name()))throw new IllegalArgumentException("Duplicate semantic local");
            for(Local local:this.locals)if(local.importKind()==ImportKind.NONE?local.importIndex()!=-1:local.importIndex()<0)throw new IllegalArgumentException("Invalid local import identity");
            for(int id:new int[]{pendingReturn,thrown})if(id < -1 || id >= 0 && !ids.add(id))throw new IllegalArgumentException("Invalid temporary identity");
            for(int id:this.eval)if(id<0 || !ids.add(id))throw new IllegalArgumentException("Duplicate evaluation identity");
            for(int id:this.thrownIds)if(id<0 || !ids.add(id))throw new IllegalArgumentException("Duplicate thrown custody identity");
            for(int id:this.returnIds)if(id<0 || !ids.add(id))throw new IllegalArgumentException("Duplicate return custody identity");
        }
        public int schema(){return schema;}public String ownerInternalName(){return owner;}public String javaMethod(){return method;}
        public String descriptor(){return descriptor;}public EntryKind entryKind(){return kind;}public List<Local> locals(){return locals;}
        public Set<String> requiredCapabilities(){return required;}public List<String> unsupportedLowering(){return unsupported;}
        public int pendingReturnId(){return pendingReturn;}public int thrownId(){return thrown;}public int[] evalIds(){return eval.clone();}public int[] thrownIds(){return thrownIds.clone();}public int[] returnIds(){return returnIds.clone();}
        private boolean matches(Method member){return owner.equals(member.getDeclaringClass().getName().replace('.','/')) && method.equals(member.getName()) && descriptor.equals(PhpOwnershipExecution.descriptor(member));}
    }
    /** Explicit provider attests every reached producer, holder, call and mutation; a marker is insufficient. */
    @FunctionalInterface public interface CompleteFrameProtocol {void validate(FramePlan plan,Method member,byte[] definedBytes)throws Throwable;}
    private PhpOwnershipExecution(){}
    public static Session current(Environment env){Context c=env.getOwnershipExecutionContext();return c==null?null:c.session;}
    public static Entry currentEntry(Environment env){Context c=env.getOwnershipExecutionContext();return c==null || c.ticket==null?null:c.ticket.entry;}
    public static CallRoots currentCallRoots(Environment env){Context c=env.getOwnershipExecutionContext();return c==null || c.ticket==null?null:c.ticket.roots;}
    public static Entry enterGenerated(Environment env,Class<?> owner,String name,String descriptor){
        Context c=env.getOwnershipExecutionContext();if(c==null)return null;
        if(c.ticket==null)throw new IllegalStateException("Generated entry needs an exact invocation ticket");return c.ticket.enter(owner,name,descriptor);
    }
    public static Entry enterGenerated(Environment env,Class<?> owner,String name,String descriptor,FramePlan plan){Entry entry=enterGenerated(env,owner,name,descriptor);if(entry!=null && entry.plan!=plan)throw new IllegalArgumentException("Foreign frame plan");return entry;}
    private static Mode mode(int code){return switch(code){case VALUE->Mode.VALUE;case CELL->Mode.CELL;case BORROW_ADDRESS->Mode.BORROW_ADDRESS;default->throw new IllegalArgumentException("Unknown ownership mode");};}
    public static String descriptor(Method member){StringBuilder b=new StringBuilder("(");for(Class<?> t:member.getParameterTypes())b.append(descriptor(t));return b.append(')').append(descriptor(member.getReturnType())).toString();}
    private static String descriptor(Class<?> t){
        if(t.isArray())return t.getName().replace('.','/');if(!t.isPrimitive())return "L"+t.getName().replace('.','/')+";";
        if(t==void.class)return "V";if(t==boolean.class)return "Z";if(t==byte.class)return "B";if(t==char.class)return "C";if(t==short.class)return "S";if(t==int.class)return "I";if(t==long.class)return "J";if(t==float.class)return "F";return "D";
    }
    /** PHP85 verifies a return candidate before entering its finally cleanup. */
    public static Memory verifyReturnBeforeFinally(Environment env,Class<?> owner,String javaMember,TraceInfo trace,Memory raw,int mode,int evalId)throws Throwable {
        var entry=currentEntry(env);
        if(entry!=null) {
            var member=entry.ticket.target.member;
            if(member.getDeclaringClass()!=owner || !member.getName().equals(javaMember))throw new IllegalArgumentException("Return verifier differs from its actual invocation target");
            return entry.verifyReturnBeforeFinally(raw,mode,evalId,trace);
        }
        if(!(owner.getClassLoader() instanceof RuntimeClassLoader))throw new IllegalArgumentException("Return verifier owner is not a generated class");
        var loader=(RuntimeClassLoader)owner.getClassLoader();var function=loader.getFunction(owner.getName());
        if(function!=null && function.getNativeMethod().getDeclaringClass()==owner && function.getNativeMethod().getName().equals(javaMember))return InvokeHelper.checkReturnType(env,trace,raw,function);
        var clazz=loader.getClass(owner.getName());
        if(clazz!=null)for(var method:clazz.getMethods().values()) {var member=method.getNativeMethod();if(member!=null && member.getDeclaringClass()==owner && member.getName().equals(javaMember))return InvokeHelper.checkReturnType(env,trace,raw,method);}
        throw new IllegalArgumentException("Return verifier has no exact reflected PHP member");
    }
    public static final class Context {
        private final Session session;private final Ticket ticket;private final Thread thread=Thread.currentThread();
        private Context(Session session,Ticket ticket){this.session=session;this.ticket=ticket;}
        public Environment environment(){return session.env;}
        public void validateBinding(Environment env){if(env!=session.env || thread!=Thread.currentThread())throw new IllegalArgumentException("Foreign execution context");session.open();}
    }
    public static final class Scope implements AutoCloseable {
        private final Environment.OwnershipExecutionBinding binding;private final PhpOwnershipDomain.Execution execution;private boolean closed;
        private Scope(Environment.OwnershipExecutionBinding binding,PhpOwnershipDomain.Execution execution){this.binding=binding;this.execution=execution;}
        public void close(){if(!closed){binding.close();execution.close();closed=true;}}
    }
    public static final class Session {
        private final PhpOwnershipDomain.Participant participant;private final Environment env;
        private final IdentityHashMap<Object,Target> targets=new IdentityHashMap<>();private final Map<Method,Target> members=new HashMap<>();
        private PhpOwnershipArrays arrays;private PhpOwnershipObjects objects;
        private final Set<Target> validating=Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<CallStackItem,Ticket> callFrames=new IdentityHashMap<>();
        public Session(PhpOwnershipDomain.Participant participant,Environment env){this.participant=Objects.requireNonNull(participant);this.env=Objects.requireNonNull(env);if(participant.cleanupContext()!=env)throw new IllegalArgumentException("Participant needs its exact releasing Environment");}
        public PhpOwnershipDomain domain(){return participant.domain();}public PhpOwnershipDomain.Participant participant(){return participant;}public Environment environment(){return env;}
        private void open(){domain().graph().checkMutationAccess();if(participant.isClosed())throw new IllegalStateException("Execution participant was closed");}
        public Scope enter(){open();var execution=participant.enter();try{return new Scope(env.bindOwnershipExecutionContext(new Context(this,null)),execution);}catch(Throwable failure){execution.close();throw failure;}}
        public PhpOwnershipArrays arrays(){open();if(arrays==null)arrays=new PhpOwnershipArrays(domain().heap());return arrays;}
        public PhpOwnershipObjects objects(){open();if(objects==null)objects=new PhpOwnershipObjects(domain().heap(),arrays());return objects;}
        public PhpOwnershipValues.Binding value(Memory raw){
            open();PhpOwnership.Node node=null;
            if(raw instanceof ObjectMemory){var o=((ObjectMemory)raw).value;if(o.getClass()==php.runtime.lang.exception.BaseTypeError.class){var nativeOrigin=((php.runtime.lang.exception.BaseTypeError)o).getOwnershipThrowable();if(nativeOrigin==null)throw new IllegalArgumentException("Native TypeError lacks its complete origin");node=nativeOrigin.node();}else{if(!(o instanceof IPhpOwnershipObject) || ((IPhpOwnershipObject)o).getOwnershipObject()==null)throw new IllegalArgumentException("Invocation OBJECT lacks its allocation origin");node=((IPhpOwnershipObject)o).getOwnershipObject().node();}}
            else if(raw instanceof ArrayMemory)node=arrays().certificate((ArrayMemory)raw).node();
            return PhpOwnershipValues.bindValue(domain(),raw,node).requireCertifiedObject();
        }
        public PhpOwnershipValues.Binding cell(ReferenceMemory cell){open();return PhpOwnershipValues.bindCell(domain(),cell,arrays().certificate(cell).node());}
        private PhpOwnershipValues.Binding candidate(Memory raw,Mode mode){if(mode==Mode.BORROW_ADDRESS)throw new IllegalArgumentException("An address is not a VALUE/CELL");return mode==Mode.CELL?cell((ReferenceMemory)raw):value(raw);}
        public Target registerGenerated(Object entity,Method member,byte[] bytes,FramePlan plan,CompleteFrameProtocol protocol)throws Throwable{
            open();Objects.requireNonNull(entity);Objects.requireNonNull(member);Objects.requireNonNull(bytes);Objects.requireNonNull(plan);Objects.requireNonNull(protocol);
            FramePlan transported=readFramePlan(bytes,member.getName(),descriptor(member));
            if(transported==null || !samePlan(plan,transported))throw new IllegalArgumentException("Method lacks its exact transported execution plan");
            if(!plan.matches(member) || !plan.unsupportedLowering().isEmpty())throw new IllegalArgumentException("Incomplete or foreign generated frame plan");
            if(!(member.getDeclaringClass().getClassLoader() instanceof RuntimeClassLoader) || !((RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),bytes))throw new IllegalArgumentException("Frame differs from actual defined class");
            if(targets.containsKey(entity) || members.containsKey(member))throw new IllegalArgumentException("Member already admitted");
            protocol.validate(plan,member,bytes.clone());Target target=new Target(this,entity,member,bytes.clone(),plan);target.validate();targets.put(entity,target);members.put(member,target);return target;
        }
        public Target registerAttributeArgument(php.runtime.reflection.ModuleEntity module,php.runtime.reflection.support.Entity declaration,php.runtime.reflection.support.PhpAttribute attribute,php.runtime.reflection.support.PhpAttribute.Argument argument,CompleteFrameProtocol protocol)throws Throwable {
            open();var source=php.runtime.loader.dump.PhpAttributeDumper.resolveArgument(env,module,declaration,attribute,argument);
            var plan=readFramePlan(source.bytes(),source.member().getName(),descriptor(source.member()));
            if(plan==null||plan.entryKind()!=EntryKind.CLASS_ENV_INITIALIZER)throw new IllegalArgumentException("Attribute argument has no genuine initializer frame");
            return registerGenerated(source,source.member(),source.bytes(),plan,protocol);
        }
        private Target attributeTarget(php.runtime.reflection.support.PhpAttribute.Argument argument){
            for(Target target:targets.values())if(target.entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source&&source.argument()==argument){target.validate();return target;}
            throw new IllegalArgumentException("Attribute argument has no admitted physical initializer protocol");
        }
        public void requireAttributeArgument(php.runtime.reflection.support.PhpAttribute.Argument argument){attributeTarget(argument);}
        public Memory invokeAttributeArgument(php.runtime.reflection.support.PhpAttribute.Argument argument,TraceInfo trace)throws Throwable{return invokeInitializer(attributeTarget(argument).entity,trace);}
        public CallRoots newCallRoots(){open();return new CallRoots(this);}
        private void preflightThrowableTrace(){
            for(int i=0;i<env.getCallStackTop();i++) {var item=env.peekCall(i);if(callFrames.containsKey(item))continue;
                if(item.args!=null)for(Memory value:item.args){if(value instanceof ObjectMemory || value instanceof ArrayMemory || value instanceof ReferenceMemory)throw new IllegalArgumentException("Outer PHP trace arguments lack their actual invocation frame origin");if(value!=null)value(value);}
            }
        }
        public List<PhpOwnershipThrowables.TraceFrame> captureThrowableTrace(){open();preflightThrowableTrace();List<PhpOwnershipThrowables.TraceFrame> frames=new ArrayList<>();
            for(int i=0;i<env.getCallStackTop();i++) {var actual=env.peekCall(i);var owner=callFrames.get(actual);List<PhpOwnershipValues.Binding> arguments;
                if(owner!=null)arguments=owner.entry.traceArguments();else{arguments=new ArrayList<>();if(actual.args!=null)for(Memory raw:actual.args)arguments.add(value(raw==null?Memory.NULL:raw));}
                frames.add(new PhpOwnershipThrowables.TraceFrame(new CallStackItem(actual),arguments));
            }return List.copyOf(frames);
        }
        public void requireGeneratedTarget(Object entity){target(entity);}
        public void requireMethodCapability(MethodEntity method){target(method);}
        private Target target(Object entity){Target target=targets.get(entity);if(target==null)throw new IllegalArgumentException("Target has no complete invocation capability");target.validate();return target;}
        public CallRoots requireNativeCallRoots(Method member,Memory[] args){open();Context c=env.getOwnershipExecutionContext();if(c==null || c.session!=this || c.ticket==null || !member.equals(c.ticket.nativeMember))throw new IllegalArgumentException("Native body needs its exact invocation packet");c.ticket.roots.validateArguments(args);c.ticket.bindCallFrame();return c.ticket.roots;}
        public void requireFunctionCapability(FunctionEntity function,int arity){
            open();if(function==null || arity<0)throw new IllegalArgumentException("Unknown function dependency");
            if(function.getParameters()!=null)for(ParameterEntity parameter:function.getParameters())if(parameter.isReference() || parameter.isVariadic() || parameter.getDefaultInitializer()!=null || parameter.getDefaultValueConstName()!=null)throw new IllegalArgumentException("Function SEND/default protocol is incomplete");
            if(function instanceof CompileFunctionEntity){var method=((CompileFunctionEntity)function).getCompileFunction().find(arity);if(method==null || method.isVarArg() || method.argsCount!=arity)throw new IllegalArgumentException("Native argument vector has no complete exact contract");PhpOwnershipNativeCalls.requireCapability(method.method);}else target(function);
        }
        private FunctionEntity resolveFunction(String name){
            if(env.getClass()!=Environment.class)throw new IllegalArgumentException("Function resolution has no exact host contract");
            FunctionEntity function=env.fetchFunction(name);int separator=name.lastIndexOf('\\');
            if(function==null && separator>=0)function=env.fetchFunction(name.substring(separator+1));
            if(function==null)throw new IllegalArgumentException("Unknown function dependency: "+name);return function;
        }
        public Memory invokeFunction(FunctionEntity function,TraceInfo trace,CallRoots roots)throws Throwable{return invokeFunction(function,trace,roots,false);}
        public Memory invokeFunction(FunctionEntity function,TraceInfo trace,CallRoots roots,boolean discarded)throws Throwable{
            roots.same(this);Method nativeMember=null;
            if(function instanceof CompileFunctionEntity){var method=((CompileFunctionEntity)function).getCompileFunction().find(roots.size());if(method==null)throw new IllegalArgumentException("Native overload has no exact contract");nativeMember=method.method;PhpOwnershipNativeCalls.requireCapability(nativeMember);}
            Ticket ticket=new Ticket(this,nativeMember==null?target(function):null,nativeMember,roots,trace);ticket.nativeFunction=nativeMember==null?null:function;
            return scoped(ticket,()->InvokeHelper.call(env,trace,function,roots.rawArguments(),false,discarded));
        }
        public void requireInvokerCapability(Invoker invoker){
            open();if(invoker==null || invoker.getEnvironment()!=env)throw new IllegalArgumentException("Invoker belongs to another Environment");
            Object entity;if(invoker.getClass()==DynamicMethodInvoker.class)entity=((DynamicMethodInvoker)invoker).getMethod();
            else if(invoker.getClass()==StaticMethodInvoker.class)entity=((StaticMethodInvoker)invoker).getMethod();
            else if(invoker.getClass()==FunctionInvoker.class)entity=((FunctionInvoker)invoker).getFunction();
            else throw new IllegalArgumentException("Custom or magic Invoker lacks a complete target protocol");target(entity);
        }
        public Memory invokeInvoker(Invoker invoker,Memory[] args,CallRoots roots)throws Throwable{return invokeInvoker(invoker,args,roots,TraceInfo.UNKNOWN,false);}
        public Memory invokeInvoker(Invoker invoker,Memory[] args,CallRoots roots,TraceInfo trace,boolean discarded)throws Throwable{
            roots.same(this);roots.validateArguments(args);requireInvokerCapability(invoker);Object entity;
            if(invoker instanceof DynamicMethodInvoker)entity=((DynamicMethodInvoker)invoker).getMethod();else if(invoker instanceof StaticMethodInvoker)entity=((StaticMethodInvoker)invoker).getMethod();else if(invoker.getClass()==FunctionInvoker.class)entity=((FunctionInvoker)invoker).getFunction();else throw new IllegalArgumentException("Invoker has no exact target contract");
            Ticket ticket=new Ticket(this,target(entity),null,roots,trace);return scoped(ticket,()->discarded?PhpNoDiscardInvocation.invokeDiscarded(env,trace,invoker,args,false):invoker.call(args));
        }
        public Memory invokeMethod(MethodEntity method,IObject receiver,TraceInfo trace,CallRoots roots)throws Throwable {
            roots.same(this);Ticket ticket=new Ticket(this,target(method),null,roots,trace);
            return scoped(ticket,()->method.invokeDynamic(receiver,env,trace,roots.rawArguments()));
        }
        public Memory invokeCheckedMethod(MethodEntity method,IObject receiver,TraceInfo trace,CallRoots roots,boolean checkAccess)throws Throwable {
            roots.same(this);Ticket ticket=new Ticket(this,target(method),null,roots,trace);
            return scoped(ticket,()->ObjectInvokeHelper.invokeMethod(receiver,method,env,trace,roots.rawArguments(),checkAccess));
        }
        public Memory invokeInitializer(Object exactKey,TraceInfo trace)throws Throwable {
            Target target=target(exactKey);Method member=target.member;
            if(!Arrays.equals(member.getParameterTypes(),new Class<?>[]{Environment.class}) || !java.lang.reflect.Modifier.isStatic(member.getModifiers()))throw new IllegalArgumentException("Initializer has no exact Environment-only protocol");
            Entry caller=currentEntry(env);if(caller==null)throw new IllegalArgumentException("Initializer needs its actual caller frame");
            try(CallRoots roots=newCallRoots()) {
                Ticket ticket=new Ticket(this,target,null,roots,trace);
                Memory raw=scoped(ticket,()->{try{Object returned=member.invoke(null,env);if(member.getReturnType()==void.class){if(!ticket.entered)throw new IllegalArgumentException("Initializer bypassed admitted entry");return Memory.NULL;}return ticket.checked((Memory)returned);}catch(InvocationTargetException failure){throw failure.getCause();}});
                if(member.getReturnType()==void.class)return Memory.NULL;
                var stage=caller.publisher(caller.frame.temporary("stage:"+caller.stages.size()));
                caller.transfer(roots.result,stage,raw);caller.stages.add(stage);return raw;
            }
        }
        public Memory invokeNativePropertyConstructor(MethodEntity method,IObject receiver,TraceInfo trace,CallRoots roots)throws Throwable {
            roots.same(this);var member=method.getNativeMethod();
            if(method.getClazz().methodConstruct!=method||method.isStatic()||member==null||member.getDeclaringClass()!=receiver.getClass()
                    ||!Arrays.equals(member.getParameterTypes(),new Class<?>[]{Environment.class,Memory[].class})||member.getReturnType()!=Memory.class)
                throw new IllegalArgumentException("Native constructor differs from its actual property allocation");
            var binding=roots.receiver();
            if(binding==null||!(binding.rawValue() instanceof ObjectMemory)||((ObjectMemory)binding.rawValue()).value!=receiver)
                throw new IllegalArgumentException("Native constructor needs its exact certified receiver root");
            php.runtime.reflection.helper.PhpNativePropertiesShellVerifier.require(receiver.getClass().getConstructor(Environment.class,ClassEntity.class));
            Ticket ticket=new Ticket(this,null,member,roots,trace);
            return scoped(ticket,()->{
                Memory[] args=roots.rawArguments();env.pushCall(trace,receiver,args,method.getName(),method.getClazz().getName(),method.getClazz().getName());
                try {ticket.bindCallFrame();Memory result;
                    try{result=(Memory)member.invoke(receiver,env,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                    roots.stageResult(value(result));return result;
                }finally{env.popCall();}
            });
        }
        public Memory invokeDestructor(MethodEntity method,IObject receiver,TraceInfo trace)throws Throwable {
            target(method);Entry caller=currentEntry(env);try(CallRoots roots=newCallRoots()) {roots.stageReceiver(value(new ObjectMemory(receiver)));try{return invokeMethod(method,receiver,trace,roots);}catch(Throwable failure){if(caller!=null)caller.ticket.roots.adoptFailure(failure);throw failure;}}
        }
        public Memory invokeModule(ModuleEntity module,ArrayMemory locals,CallRoots roots)throws Throwable{roots.same(this);Ticket ticket=new Ticket(this,target(module),null,roots,TraceInfo.UNKNOWN);return scoped(ticket,()->module.include(env,locals));}
        private Ticket runtime(Object entity){Context c=env.getOwnershipExecutionContext();if(c==null || c.session!=this || c.ticket==null || c.ticket.target==null || c.ticket.target.entity!=entity)throw new IllegalArgumentException("Runtime invocation has no matching ticket");c.ticket.target.validate();return c.ticket;}
        private Memory scoped(Ticket ticket,Body body)throws Throwable{Memory result=null;Throwable failure=null;boolean forced=false;
            // The ordinary immutable/trace shortcut is disabled in this explicit lane. Native PHP traces still see pure functions.
            FunctionEntity function=ticket.target!=null && ticket.target.entity instanceof FunctionEntity?(FunctionEntity)ticket.target.entity:ticket.nativeFunction;
            if(function!=null && !function.isUsesStackTrace()) {env.pushCall(ticket.trace,null,ticket.roots.rawArguments(),function.getName(),null,null);forced=true;}
            try{result=body.call();}catch(Throwable error){failure=error;}finally{if(forced)env.popCall();}ticket.finish(failure);return result;}
    }
    @FunctionalInterface private interface Body{Memory call()throws Throwable;}
    public static final class Target {
        private final Session session;private final Object entity;private final Method member;private final byte[] bytes;private final FramePlan plan;
        private final Map<String,FunctionEntity> functionDependencies=new HashMap<>();
        private Target(Session session,Object entity,Method member,byte[] bytes,FramePlan plan){this.session=session;this.entity=entity;this.member=member;this.bytes=bytes;this.plan=plan;}
        public FramePlan plan(){return plan;}
        private void validate(){session.open();
            if(!session.validating.add(this))throw new IllegalArgumentException("Recursive function capability graph is not admitted");
            try {
            for(String capability:plan.requiredCapabilities()) {
                if(capability.equals("empty-tick-handler")){if(session.env.getClass()!=Environment.class || session.env.getScope().getTickHandler()!=null)throw new IllegalArgumentException("Tick callback has no admitted invocation protocol");}
                else if(capability.equals("literal-buffered-output"))PhpOwnershipNativeCalls.requireLiteralBufferedOutput(session.env);
                else if(capability.startsWith("function-call:")){
                    int separator=capability.lastIndexOf(':');if(separator<=14)throw new IllegalArgumentException("Malformed function dependency");
                    String name=capability.substring(14,separator);int arity;
                    try{arity=Integer.parseInt(capability.substring(separator+1));}catch(NumberFormatException failure){throw new IllegalArgumentException("Malformed function arity",failure);}
                    FunctionEntity function=session.resolveFunction(name);FunctionEntity previous=functionDependencies.putIfAbsent(capability,function);
                    if(previous!=null && previous!=function)throw new IllegalArgumentException("Function dependency changed after admission");
                    session.requireFunctionCapability(function,arity);
                }
                else if(capability.equals("php-throwable-custody-v1")){if(plan.schema()!=2 || session.env.getClass()!=Environment.class)throw new IllegalArgumentException("Throwable custody needs its exact generated and host protocol");}
                else if(!capability.equals("frame-local-publication-v1"))throw new IllegalArgumentException("Unknown complete frame capability: "+capability);
            }
            Method actual=entity instanceof FunctionEntity?((FunctionEntity)entity).getNativeMethod():entity instanceof MethodEntity?((MethodEntity)entity).getNativeMethod():entity instanceof ModuleEntity?((ModuleEntity)entity).getNativeMethod():entity instanceof ClassEntity?((ClassEntity)entity).getNativeInitEnvironment():member;
            if(entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source)source.validate(session.env);
            if(entity instanceof ParameterEntity){var initializer=((ParameterEntity)entity).getDefaultInitializer();if(initializer==null || !member.getDeclaringClass().getName().equals(initializer.javaClass()) || !member.getName().equals(initializer.method()))throw new IllegalArgumentException("Default initializer changed after admission");}
            if(!member.equals(actual) || !((RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),bytes))throw new IllegalArgumentException("Admitted target changed");
            } finally {session.validating.remove(this);}}
        private ModuleEntity module(){return entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source?source.module():entity instanceof FunctionEntity?((FunctionEntity)entity).getModule():entity instanceof MethodEntity?((MethodEntity)entity).getClazz().getModule():entity instanceof ClassEntity?((ClassEntity)entity).getModule():entity instanceof ParameterEntity?session.env.getScope().getClassLoader().getModule(((ParameterEntity)entity).getDefaultInitializer().moduleName()):entity instanceof ModuleEntity?(ModuleEntity)entity:null;}
    }
    public static final class Ticket implements AutoCloseable {
        private final Session session;private final Target target;private final Method nativeMember;private final CallRoots roots;private final Entry entry;private final Scope scope;private final TraceInfo trace;
        private final PhpPropertyWriteContext.Frame propertyContext;private boolean entered,closed,runtime;
        private final int initialStackDepth;private CallStackItem callFrame;private FunctionEntity nativeFunction;
        private Ticket(Session session,Target target,Method nativeMember,CallRoots roots,TraceInfo trace)throws Throwable{
            this.session=session;this.target=target;this.nativeMember=nativeMember;this.roots=roots;this.trace=trace;
            session.preflightThrowableTrace();initialStackDepth=session.env.getCallStackTop();
            var execution=session.participant.enter();Environment.OwnershipExecutionBinding binding=null;Entry created=null;
            try{binding=session.env.bindOwnershipExecutionContext(new Context(session,this));created=new Entry(this);entry=created;scope=new Scope(binding,execution);
                var inherited=PhpPropertyWriteContext.current(session.env);ModuleEntity module=target==null?null:target.module();
                propertyContext=PhpPropertyWriteContext.enter(session.env,trace,module==null?inherited!=null && inherited.strict:module.isStrictTypes());
            }catch(Throwable failure){if(created!=null)try{created.frame.unwind(failure);}catch(Throwable cleanup){failure=cleanup;}if(binding!=null)binding.close();execution.close();throw failure;}
        }
        private Entry enter(Class<?> owner,String name,String descriptor){session.open();if(closed || target==null || target.member.getDeclaringClass()!=owner || !target.member.getName().equals(name) || !PhpOwnershipExecution.descriptor(target.member).equals(descriptor) || entered)throw new IllegalArgumentException("Generated entry differs from its one-shot ticket");entered=true;bindCallFrame();return entry;}
        private void bindCallFrame(){var actual=session.env.peekCall(0);if(actual==null || session.callFrames.containsKey(actual))return;if(session.env.getCallStackTop()>initialStackDepth || target!=null && (target.entity instanceof ClassEntity || target.entity instanceof ParameterEntity || target.entity instanceof MethodEntity && actual.object!=null)){callFrame=actual;session.callFrames.put(actual,this);}}
        private Memory checked(Memory raw)throws Throwable{
            if(!entered)throw new IllegalArgumentException("Admitted body did not enter its frame protocol");
            var pending=entry.selectedReturn.current().binding();
            if(pending==null){if(raw instanceof ObjectMemory || raw instanceof ArrayMemory || raw instanceof ReferenceMemory)throw new IllegalArgumentException("Heap return lacks a pending root");entry.publish(entry.selectedReturn,session.value(raw));pending=entry.selectedReturn.current().binding();}
            Mode mode=pending.role()==PhpOwnershipValues.Role.CELL?Mode.CELL:Mode.VALUE;var actual=session.candidate(raw,mode);
            if(actual.node()!=pending.node())throw new IllegalArgumentException("Return differs from its pending owner");
            Memory input=mode==Mode.CELL?((ReferenceMemory)raw).getRawValue():raw;
            Memory checked=target.entity instanceof FunctionEntity?InvokeHelper.checkReturnType(session.env,trace,input,(FunctionEntity)target.entity):target.entity instanceof MethodEntity?InvokeHelper.checkReturnType(session.env,trace,input,(MethodEntity)target.entity):input;
            if(mode==Mode.CELL){if(checked!=input){var payload=(PhpOwnershipArrays.Entry)session.arrays().certificate((ReferenceMemory)raw).value();payload.assign(session.value(checked));}checked=raw;}
            entry.publish(entry.selectedReturn,session.candidate(checked,mode));entry.transfer(entry.selectedReturn,roots.result,checked);return checked;
        }
        private void finish(Throwable primary)throws Throwable{
            if(closed){if(primary!=null)throw primary;return;}closed=true;Throwable failure=primary;
            if(failure==null && target!=null && !entered)failure=new IllegalArgumentException("Admitted body bypassed generated entry");
            try{if(failure!=null)roots.adoptFailure(failure);}catch(Throwable error){failure=session.domain().graph().composeFailure(failure,error);}
            try{session.env.collectGarbageIfNeeded();}catch(Throwable error){failure=session.domain().graph().composeFailure(failure,error);}
            failure=entry.closeCalls(failure);
            try{if(failure==null)entry.frame.close();else entry.frame.unwind(failure);}catch(Throwable error){failure=error;}
            try{PhpPropertyWriteContext.leave(propertyContext);}catch(Throwable error){failure=session.domain().graph().composeFailure(failure,error);}
            if(callFrame!=null && session.callFrames.get(callFrame)==this)session.callFrames.remove(callFrame);
            try{scope.close();}catch(Throwable error){failure=session.domain().graph().composeFailure(failure,error);}
            if(failure!=null)throw failure;
        }
        public void close()throws Exception{try{finish(null);}catch(Throwable failure){PhpOwnershipExecution.<Exception>raise(failure);}}
    }
    public static Memory invokeFunction(FunctionEntity function,Environment env,TraceInfo trace,Memory[] args)throws Throwable{
        Session session=Objects.requireNonNull(current(env));Ticket ticket=session.runtime(function);if(ticket.runtime)throw new IllegalStateException("Invocation ticket reused");ticket.runtime=true;
        try{return ticket.checked((Memory)function.getNativeMethod().invoke(null,env,args));}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static Memory invokeMethod(MethodEntity method,IObject receiver,Environment env,TraceInfo trace,Memory[] args)throws Throwable{
        Session session=Objects.requireNonNull(current(env));Ticket ticket=session.runtime(method);if(ticket.runtime)throw new IllegalStateException("Invocation ticket reused");
        if(!method.isStatic()){var binding=ticket.roots.receiver();if(binding==null || !(binding.rawValue() instanceof ObjectMemory) || ((ObjectMemory)binding.rawValue()).value!=receiver)throw new IllegalArgumentException("Method receiver lacks its actual invocation owner");}
        ticket.runtime=true;try{return ticket.checked((Memory)method.getNativeMethod().invoke(receiver,env,args));}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static Memory invokeModule(ModuleEntity module,Environment env,ArrayMemory locals)throws Throwable{
        Session session=Objects.requireNonNull(current(env));Ticket ticket=session.runtime(module);if(ticket.runtime)throw new IllegalStateException("Invocation ticket reused");session.arrays().certificate(locals);ticket.runtime=true;
        try{return ticket.checked((Memory)module.getNativeMethod().invoke(null,env,new Memory[0],locals));}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    public static final class CallRoots implements AutoCloseable {
        private final Session session;private final PhpOwnershipFrame frame;private final List<PhpOwnershipFramePublisher> arguments=new ArrayList<>();private final List<Memory> raw=new ArrayList<>();
        private final PhpOwnershipFramePublisher receiver,result;private boolean closed;
        private final IdentityHashMap<Throwable,PhpOwnershipFramePublisher> failures=new IdentityHashMap<>();
        private CallRoots(Session session){this.session=session;frame=new PhpOwnershipFrame(session.domain().graph());receiver=session.domain().heap().framePublisher(frame.receiver("receiver"));result=session.domain().heap().framePublisher(frame.temporary("result"));}
        private void same(Session expected){if(session!=expected || closed)throw new IllegalArgumentException("Foreign or closed call roots");session.open();}
        public Session session(){return session;}public int size(){same(session);return arguments.size();}
        public PhpOwnershipValues.Binding argument(int index){same(session);return arguments.get(index).current().binding();}public PhpOwnershipValues.Binding argumentBinding(int index){return argument(index);}
        public PhpOwnershipValues.Binding receiver(){same(session);return receiver.current().binding();}public PhpOwnershipValues.Binding result(){same(session);return result.current().binding();}
        public PhpOwnershipFramePublisher resultPublisher(){same(session);return result;}
        public void stageReceiver(PhpOwnershipValues.Binding binding)throws Throwable{same(session);publish(receiver,binding);}
        private void adoptFailure(Throwable failure)throws Throwable{var certificate=PhpOwnershipThrowables.certificate(failure);if(certificate==null)return;same(session);var destination=failures.get(failure);if(destination==null){destination=session.domain().heap().framePublisher(frame.temporary("failure:"+failures.size()));failures.put(failure,destination);}certificate.moveTo(destination);}
        public PhpOwnershipValues.Binding failureBinding(Throwable failure){same(session);var holder=failures.get(failure);return holder==null?null:holder.current().binding();}
        private PhpOwnershipFramePublisher appendArgument(int index,Memory value){same(session);if(index!=arguments.size())throw new IllegalArgumentException("SEND must publish in argument order");var publisher=session.domain().heap().framePublisher(frame.argument(Integer.toString(index)));arguments.add(publisher);raw.add(value);return publisher;}
        public void send(int index,PhpOwnershipValues.Binding binding)throws Throwable{same(session);if(binding.domain()!=session.domain())throw new IllegalArgumentException("Foreign SEND");binding.requireCertifiedObject();var publisher=appendArgument(index,binding.rawValue());publish(publisher,binding);}
        private void sendFromEval(int index,PhpOwnershipFramePublisher source,PhpOwnershipValues.Binding binding)throws Throwable{same(session);var publisher=appendArgument(index,binding.rawValue());try(var publication=source.prepareTransferTo(source.current(),publisher,publisher.current(),binding.rawValue())){publication.commit();}}
        public void send(PhpOwnershipValues.Binding binding)throws Throwable{send(arguments.size(),binding);}
        public void stageResult(PhpOwnershipValues.Binding binding)throws Throwable{same(session);Entry entry=currentEntry(session.env);if(entry!=null && entry.ticket.roots==this && entry.moveStaged(binding,result))return;publish(result,binding);}
        private void publish(PhpOwnershipFramePublisher publisher,PhpOwnershipValues.Binding binding)throws Throwable{if(binding.domain()!=session.domain())throw new IllegalArgumentException("Foreign invocation child");binding.requireCertifiedObject();try(var publication=publisher.prepare(publisher.current(),binding)){publication.commit();}}
        public Memory[] rawArguments(){same(session);return raw.toArray(new Memory[0]);}
        private void validateArguments(Memory[] args){same(session);if(args==null)args=new Memory[0];if(args.length!=raw.size())throw new IllegalArgumentException("Raw vector differs from SEND packet");for(int i=0;i<args.length;i++)if(args[i]!=raw.get(i))throw new IllegalArgumentException("Raw argument differs from SEND slot");}
        public Memory invokeFunction(FunctionEntity function,TraceInfo trace)throws Throwable{return session.invokeFunction(function,trace,this);}
        public Memory invokeFunction(FunctionEntity function,TraceInfo trace,boolean discarded)throws Throwable{return session.invokeFunction(function,trace,this,discarded);}
        public Memory invokeInvoker(Invoker invoker,Memory[] args,TraceInfo trace,boolean discarded)throws Throwable{return session.invokeInvoker(invoker,args,this,trace,discarded);}
        public Memory invokeInvoker(Invoker invoker,Memory[] args)throws Throwable{return session.invokeInvoker(invoker,args,this);}public Memory invokeCallable(Invoker invoker,Memory[] args)throws Throwable{return invokeInvoker(invoker,args);}
        public Memory invokeModule(ModuleEntity module,ArrayMemory locals)throws Throwable{return session.invokeModule(module,locals,this);}
        public void unwind(Throwable primary)throws Throwable{closed=true;frame.unwind(primary);}
        public void close()throws Exception{if(closed)return;closed=true;try{frame.close();}catch(Throwable failure){PhpOwnershipExecution.<Exception>raise(failure);}}
    }
    private static final class NoPublication implements PhpOwnershipHeap.Publication {
        private boolean closed,committed;public void commit(){if(closed)throw new IllegalStateException("Publication consumed");committed=true;closed=true;}public void close(){closed=true;}public boolean isCommitted(){return committed;}
    }
    public static final class Entry {
        private final Ticket ticket;private final Session session;private final FramePlan plan;private final PhpOwnershipFrame frame;
        private final Map<Integer,Local> localPlans=new HashMap<>();private final Map<Integer,PhpOwnershipFramePublisher> locals=new HashMap<>(),evals=new HashMap<>();
        private final Map<Integer,PhpOwnershipArrays.Entry> addresses=new HashMap<>();private final Map<Integer,PhpOwnershipArrays.PhysicalSlot> physical=new HashMap<>();
        private final List<PhpOwnershipFramePublisher> stages=new ArrayList<>();private final PhpOwnershipFramePublisher pending,thrown;private PhpOwnershipFramePublisher selectedReturn;private ArrayMemory table;
        private final Map<Integer,CallRoots> calls=new LinkedHashMap<>();
        private final Map<Integer,ThrownCustody> thrownCustody=new HashMap<>();
        private final Map<Integer,ReturnCustody> returnCustody=new HashMap<>();
        private final IdentityHashMap<Object,PhpOwnershipArrays.RestorationAssembly> restoration=new IdentityHashMap<>();
        private Entry(Ticket ticket)throws Throwable {
            this.ticket=ticket;session=ticket.session;plan=ticket.target==null?null:ticket.target.plan;frame=new PhpOwnershipFrame(session.domain().graph());
            pending=publisher(frame.temporary("pending-return"));selectedReturn=pending;thrown=publisher(frame.temporary("pending-thrown"));
            try {
            if(plan==null)return;
            for(Local local:plan.locals()) {localPlans.put(local.id(),local);if(local.storage()==Storage.VALUE)locals.put(local.id(),publisher(frame.local(local.name())));}
            boolean needsTable=plan.requiredCapabilities().contains("empty-tick-handler") || plan.locals().stream().anyMatch(local->local.storage()==Storage.ADDRESS);
            if(needsTable) {
                var allocation=session.arrays().allocate(true);
                try {
                    for(Local local:plan.locals())if(local.storage()==Storage.ADDRESS) {addresses.put(local.id(),allocation.insertBucket(local.name(),session.value(Memory.NULL)));physical.put(local.id(),allocation.physicalSlot(local.name()));}
                    table=allocation.array();var owner=publisher(frame.local("address-table"));
                    session.domain().graph().deferDestruction(()->{try(var publication=allocation.preparePublication(owner,owner.current())){publication.commit();}allocation.close();});
                } catch(Throwable failure) {try{allocation.close();}catch(Throwable cleanup){failure=session.domain().graph().composeFailure(failure,cleanup);}throw failure;}
            }
            } catch(Throwable failure) {frame.unwind(failure);throw failure;}
        }
        private PhpOwnershipFramePublisher publisher(PhpOwnershipFrame.Slot slot){return session.domain().heap().framePublisher(slot);}
        public Session session(){return session;}public PhpOwnershipArrays arrays(){return session.arrays();}public PhpOwnershipObjects objects(){return session.objects();}
        private Local local(int id){session.open();Local local=localPlans.get(id);if(local==null)throw new IllegalArgumentException("Unknown semantic local");return local;}
        public ArrayMemory nullableLocalTable(){session.open();return table;}
        public boolean hasLocalTable(){session.open();return table!=null;}
        public ArrayMemory localTable(){session.open();if(table==null)throw new IllegalStateException("Entry has no addressable local table");return table;}
        public Memory localAddress(int id,String name){Local local=local(id);if(local.storage()!=Storage.ADDRESS || !local.name().equals(name))throw new IllegalArgumentException("Not this local address");return addresses.get(id).carrier();}
        public PhpOwnershipHeap.Publication prepareLocal(int id,Memory raw,int mode){Local local=local(id);if(raw==null)return new NoPublication();if(local.storage()==Storage.ADDRESS){if(mode!=BORROW_ADDRESS || raw!=localAddress(id,local.name()))throw new IllegalArgumentException("Address wrapper is not a VALUE or CELL");return new NoPublication();}return locals.get(id).prepare(locals.get(id).current(),session.candidate(raw,mode(mode)));}
        private PhpOwnershipFramePublisher eval(int id){session.open();if(plan!=null && !contains(plan.evalIds(),id))throw new IllegalArgumentException("Unplanned evaluation root");return evals.computeIfAbsent(id,key->publisher(frame.temporary("eval:"+key)));}
        public PhpOwnershipHeap.Publication prepareEval(int id,Memory raw,int mode){var destination=eval(id);return destination.prepare(destination.current(),session.candidate(raw,mode(mode)));}
        public void releaseEval(int id)throws Throwable{publish(eval(id),session.value(Memory.UNDEFINED));}
        public PhpOwnershipHeap.Publication prepareLocalFromEval(int evalId,int localId,Memory raw,int mode){Local local=local(localId);if(local.storage()!=Storage.VALUE)throw new IllegalArgumentException("Direct evaluation transfer requires a VALUE local");var source=eval(evalId);validateImport(source.current().binding(),session.candidate(raw,mode(mode)));var destination=locals.get(localId);return source.prepareTransferTo(source.current(),destination,destination.current(),raw);}
        public Memory assignLocalFromEval(int evalId,int localId,Memory raw,int mode)throws Throwable{var source=eval(evalId);validateImport(source.current().binding(),session.candidate(raw,mode(mode)));session.domain().graph().deferDestruction(()->{assignLocal(localId,raw,mode);publish(source,session.value(Memory.UNDEFINED));});return raw;}
        public CallRoots openFunctionCall(int callId,String name,int arity){session.open();if(currentEntry(session.env)!=this || plan==null || callId<0 || calls.containsKey(callId))throw new IllegalArgumentException("Call packet is not this active entry's evaluation");String capability="function-call:"+name+":"+arity;ticket.target.validate();FunctionEntity function=ticket.target.functionDependencies.get(capability);if(function==null)throw new IllegalArgumentException("Unplanned function invocation");session.requireFunctionCapability(function,arity);CallRoots roots=session.newCallRoots();calls.put(callId,roots);return roots;}
        public FunctionEntity functionTarget(String name,int arity){session.open();FunctionEntity target=ticket.target.functionDependencies.get("function-call:"+name+":"+arity);if(target==null)throw new IllegalArgumentException("Unplanned function target");return target;}
        public void sendCallArgument(CallRoots roots,int index,Memory raw,int evalId)throws Throwable{session.open();if(!calls.containsValue(roots))throw new IllegalArgumentException("Untracked invocation packet");var binding=session.value(raw);if(evalId<0)roots.send(index,binding);else{var source=eval(evalId);validateImport(source.current().binding(),binding);roots.sendFromEval(index,source,binding);}}
        public Memory finishCallToEval(CallRoots roots,int evalId,Memory raw)throws Throwable{session.open();if(!calls.containsValue(roots))throw new IllegalArgumentException("Untracked invocation result");try(var publication=prepareEvalFromCall(evalId,roots,raw)){publication.commit();}calls.values().remove(roots);roots.close();return raw;}
        private Throwable closeCalls(Throwable primary){List<CallRoots> pending=new ArrayList<>(calls.values());calls.clear();for(int i=pending.size()-1;i>=0;i--)try{pending.get(i).close();}catch(Throwable failure){primary=session.domain().graph().composeFailure(primary,failure);}return primary;}
        public PhpOwnershipHeap.Publication prepareImportedArgument(int argument,int id,Memory raw,int mode){Local local=local(id);if(local.storage()!=Storage.VALUE)throw new IllegalArgumentException("Direct import requires a VALUE local");if(raw==null)return new NoPublication();var destination=locals.get(id);var binding=session.candidate(raw,mode(mode));if(argument>=ticket.roots.arguments.size())return destination.prepare(destination.current(),binding);var source=ticket.roots.arguments.get(argument);validateImport(source.current().binding(),binding);return source.prepareTransferTo(source.current(),destination,destination.current(),raw);}
        public Memory importArgument(int argument,int id,Memory raw,int mode)throws Throwable {Local local=local(id);if(local.storage()!=Storage.ADDRESS)throw new IllegalArgumentException("Address import requires an ADDRESS local");if(raw==null)return localAddress(id,local.name());var binding=session.candidate(raw,mode(mode));if(argument>=ticket.roots.arguments.size()){assignLocal(id,raw,mode);return localAddress(id,local.name());}var source=ticket.roots.arguments.get(argument);validateImport(source.current().binding(),binding);session.domain().graph().deferDestruction(()->{assignLocal(id,raw,mode);publish(source,session.value(Memory.UNDEFINED));});return localAddress(id,local.name());}
        public Memory importCapture(int capture,int id,Memory raw,int mode)throws Throwable {var receiver=ticket.roots.receiver();if(receiver==null || !(receiver.rawValue() instanceof ObjectMemory) || !(((ObjectMemory)receiver.rawValue()).value instanceof Closure))throw new IllegalArgumentException("Capture lacks an actual lexical closure receiver");Closure closure=(Closure)((ObjectMemory)receiver.rawValue()).value;var certificate=closure.ownershipClosure();if(certificate==null)throw new IllegalArgumentException("Closure captures lack allocation certification");var source=certificate.captureBindings().get(capture);var binding=session.candidate(raw,mode(mode));validateImport(source,binding);assignLocal(id,raw,mode);return localAddress(id,local(id).name());}
        private void validateImport(PhpOwnershipValues.Binding source,PhpOwnershipValues.Binding next){if(source==null || source.domain()!=session.domain() || source.role()!=next.role() || source.node()!=next.node())throw new IllegalArgumentException("Imported value differs from its certified SEND/capture");source.view(next.rawValue());}
        public Memory assignLocal(int id,Memory raw,int mode)throws Throwable {Local local=local(id);if(local.storage()!=Storage.ADDRESS)throw new IllegalArgumentException("Direct JVM locals require prepare/store/commit");var binding=session.candidate(raw,mode(mode));var address=addresses.get(id);if(binding.role()==PhpOwnershipValues.Role.CELL){try(var publication=physical.get(id).prepareRebind(address.current(),binding)){publication.store();publication.commit();}}else physical.get(id).payloadPublisher().assign(binding);return raw;}
        public Memory referenceLocal(int id)throws Throwable {Local local=local(id);if(local.storage()!=Storage.ADDRESS)throw new IllegalArgumentException("Reference fetch needs an addressable local");var slot=physical.get(id);var current=slot.ownerPublisher().current();if(current.binding().role()==PhpOwnershipValues.Role.CELL)return current.binding().rawValue();try(var publication=slot.preparePromotion(current)){Memory cell=publication.cell();publication.store();publication.commit();return cell;}}
        public Memory normalizeReturn(Memory raw,int mode) {session.open();if(mode!=VALUE)throw new IllegalArgumentException("Reference return normalization lacks its certified payload protocol");session.value(raw);return raw.isUndefined()?Memory.NULL:raw;}
        private final class ReturnCustody {
            final PhpOwnershipFramePublisher publisher;PhpOwnershipFramePublisher previous;boolean active;
            ReturnCustody(int id){publisher=id==plan.pendingReturnId()?pending:Entry.this.publisher(frame.temporary("return:"+id));}
        }
        private ReturnCustody returnCustody(int id) {
            session.open();if(plan==null || plan.schema()!=2 || id<0 || id!=plan.pendingReturnId() && !contains(plan.returnIds(),id))throw new IllegalArgumentException("Unplanned pending return custody");
            return returnCustody.computeIfAbsent(id,key->new ReturnCustody(key));
        }
        private PhpOwnershipHeap.Publication selectReturn(ReturnCustody destination,PhpOwnershipHeap.Publication delegate,PhpOwnershipFramePublisher restored,ReturnCustody source) {
            var previous=selectedReturn;var oldRestored=destination.previous;boolean oldActive=destination.active,oldSource=source!=null && source.active;
            return new PhpOwnershipHeap.Publication() {
                public boolean isCommitted(){return delegate.isCommitted();}
                public void commit()throws Throwable{
                    if(!destination.active && destination.publisher!=restored)destination.previous=restored;
                    destination.active=true;if(source!=null && source!=destination)source.active=false;selectedReturn=destination.publisher;
                    try{delegate.commit();}finally{if(!delegate.isCommitted()){selectedReturn=previous;destination.previous=oldRestored;destination.active=oldActive;if(source!=null)source.active=oldSource;}}
                }
                public void close()throws Exception{delegate.close();}
            };
        }
        private PhpOwnershipHeap.Publication selectDefaultReturn(PhpOwnershipHeap.Publication delegate) {
            var previous=selectedReturn;
            return new PhpOwnershipHeap.Publication() {
                public boolean isCommitted(){return delegate.isCommitted();}
                public void commit()throws Throwable{selectedReturn=pending;try{delegate.commit();}finally{if(!delegate.isCommitted())selectedReturn=previous;}}
                public void close()throws Exception{delegate.close();}
            };
        }
        public PhpOwnershipHeap.Publication prepareScopedReturn(int returnId,Memory raw,int mode,int evalId) {
            var destination=returnCustody(returnId);var binding=session.candidate(raw,mode(mode));PhpOwnershipHeap.Publication delegate;
            if(evalId<0)delegate=destination.publisher.prepare(destination.publisher.current(),binding);
            else{var source=eval(evalId);validateImport(source.current().binding(),binding);delegate=source.prepareTransferTo(source.current(),destination.publisher,destination.publisher.current(),raw);}
            return selectReturn(destination,delegate,selectedReturn,null);
        }
        public PhpOwnershipHeap.Publication prepareReturnTransfer(int fromId,int toId,Memory raw) {
            var source=returnCustody(fromId);var destination=returnCustody(toId);
            if(selectedReturn!=source.publisher || source.publisher.current().binding()==null)throw new IllegalArgumentException("Return route differs from the actual pending candidate");
            var delegate=source.publisher.prepareTransferTo(source.publisher.current(),destination.publisher,destination.publisher.current(),raw);
            return selectReturn(destination,delegate,source.previous,source);
        }
        public void abortReturn(int returnId)throws Throwable {
            var source=returnCustody(returnId);if(selectedReturn==source.publisher)selectedReturn=source.previous==null?pending:source.previous;
            source.previous=null;source.active=false;publish(source.publisher,session.value(Memory.UNDEFINED));
        }
        public Memory verifyReturnBeforeFinally(Memory raw,int mode,int evalId)throws Throwable {return verifyReturnBeforeFinally(raw,mode,evalId,ticket.trace);}
        private Memory verifyReturnBeforeFinally(Memory raw,int mode,int evalId,TraceInfo trace)throws Throwable {
            var binding=session.candidate(raw,mode(mode));if(evalId>=0)validateImport(eval(evalId).current().binding(),binding);
            try {
                Memory input=binding.role()==PhpOwnershipValues.Role.CELL?((ReferenceMemory)raw).getRawValue():raw;
                Memory checked=ticket.target.entity instanceof FunctionEntity?InvokeHelper.checkReturnType(session.env,trace,input,(FunctionEntity)ticket.target.entity)
                        :ticket.target.entity instanceof MethodEntity?InvokeHelper.checkReturnType(session.env,trace,input,(MethodEntity)ticket.target.entity):input;
                if(binding.role()==PhpOwnershipValues.Role.CELL){if(checked!=input)((PhpOwnershipArrays.Entry)session.arrays().certificate((ReferenceMemory)raw).value()).assign(session.value(checked));return raw;}
                return checked;
            }catch(Throwable failure) {
                if(evalId>=0)try{releaseEval(evalId);}catch(Throwable cleanup){
                    if(PhpOwnershipThrowables.certificate(failure)!=null && PhpOwnershipThrowables.certificate(cleanup)!=null) {
                        PhpOwnershipThrowables.chain(session,(php.runtime.lang.exception.BaseBaseException)cleanup,(php.runtime.lang.exception.BaseBaseException)failure);
                        if(cleanup!=failure){var certificate=PhpOwnershipThrowables.certificate(failure);var source=certificate.custody();certificate.forget(source);publish(source,session.value(Memory.UNDEFINED));}
                        throw cleanup;
                    }
                    throw session.domain().graph().composeFailure(failure,cleanup);
                }
                throw failure;
            }
        }
        public PhpOwnershipHeap.Publication preparePendingReturn(Memory raw,int mode){return selectDefaultReturn(pending.prepare(pending.current(),session.candidate(raw,mode(mode))));}
        public PhpOwnershipHeap.Publication preparePendingReturnFromEval(int evalId,Memory raw,int mode){var source=eval(evalId);validateImport(source.current().binding(),session.candidate(raw,mode(mode)));return selectDefaultReturn(source.prepareTransferTo(source.current(),pending,pending.current(),raw));}
        private final class ThrownCustody {
            final PhpOwnershipFramePublisher publisher;php.runtime.lang.exception.BaseBaseException error;boolean active,propagating;
            ThrownCustody(int id){publisher=Entry.this.publisher(frame.temporary("thrown:"+id));}
        }
        private ThrownCustody custody(int id) {
            session.open();if(plan==null || !plan.requiredCapabilities().contains("php-throwable-custody-v1") || !contains(plan.thrownIds(),id))throw new IllegalArgumentException("Unplanned PHP throwable custody");
            return thrownCustody.computeIfAbsent(id,key->new ThrownCustody(key));
        }
        private ThrownCustody activeCustody(int id) {var custody=custody(id);if(!custody.active)throw new IllegalStateException("PHP throwable custody is not active");return custody;}
        public php.runtime.lang.exception.BaseBaseException captureThrown(int id,php.runtime.lang.exception.BaseBaseException error)throws Throwable {
            var destination=custody(id);if(destination.active)throw new IllegalStateException("PHP throwable handler custody was reused");
            if(error.getClass()!=php.runtime.exceptions.FinallyException.class) {
                var certificate=PhpOwnershipThrowables.require(session,error);var source=certificate.custody();
                ThrownCustody sourceCustody=null;for(var existing:thrownCustody.values())if(existing.active && existing.publisher==source){sourceCustody=existing;break;}
                boolean heldByHandler=sourceCustody!=null && !sourceCustody.propagating;
                if(heldByHandler){publish(destination.publisher,certificate.binding(session));certificate.claim(destination.publisher);}
                else {certificate.moveTo(destination.publisher);if(sourceCustody!=null)consumed(sourceCustody);}
            }
            destination.error=error;destination.active=true;destination.propagating=false;return error;
        }
        private void consumed(ThrownCustody custody) {
            var certificate=PhpOwnershipThrowables.certificate(custody.error);if(certificate!=null)certificate.forget(custody.publisher);
            custody.active=false;custody.propagating=false;custody.error=null;
        }
        public void releaseThrown(int id)throws Throwable {var custody=activeCustody(id);consumed(custody);publish(custody.publisher,session.value(Memory.UNDEFINED));}
        public php.runtime.lang.exception.BaseBaseException rethrowThrown(int id) {var custody=activeCustody(id);var certificate=PhpOwnershipThrowables.certificate(custody.error);if(certificate!=null)certificate.claim(custody.publisher);custody.propagating=true;return custody.error;}
        public Memory matchThrown(int custodyId,String className,String lowerClassName) {
            var source=activeCustody(custodyId);
            if(source.error.getClass()==php.runtime.exceptions.FinallyException.class)return Memory.NULL;
            PhpOwnershipThrowables.require(session,source.error);
            return session.env.__throwCatch(source.error,className,lowerClassName);
        }
        private PhpOwnershipValues.Binding caught(ThrownCustody custody,Memory raw) {
            if(!(raw instanceof ObjectMemory) || ((ObjectMemory)raw).value!=custody.error)throw new IllegalArgumentException("Catch value differs from its pending PHP throwable");
            return session.value(raw);
        }
        public PhpOwnershipHeap.Publication prepareCatchLocal(int custodyId,int localId,Memory raw) {
            var source=activeCustody(custodyId);caught(source,raw);var local=local(localId);
            if(local.storage()!=Storage.VALUE)throw new IllegalArgumentException("Direct catch import needs a VALUE local");
            var destination=locals.get(localId);var delegate=source.publisher.prepareTransferTo(source.publisher.current(),destination,destination.current(),raw);
            return new PhpOwnershipHeap.Publication() {
                public boolean isCommitted(){return delegate.isCommitted();}
                public void commit()throws Throwable{consumed(source);delegate.commit();}
                public void close()throws Exception{delegate.close();}
            };
        }
        public Memory assignCatchLocal(int custodyId,int localId,Memory raw)throws Throwable {
            var source=activeCustody(custodyId);caught(source,raw);
            session.domain().graph().deferDestruction(()->{assignLocal(localId,raw,VALUE);releaseThrown(custodyId);});return raw;
        }
        public php.runtime.lang.exception.BaseBaseException prepareExplicitThrow(int custodyId,Memory raw,int evalId)throws Throwable {
            var destination=custody(custodyId);if(destination.active)throw new IllegalStateException("Throw expression custody was reused");
            if(!(raw instanceof ObjectMemory) || !(((ObjectMemory)raw).value instanceof php.runtime.lang.exception.BaseBaseException))throw new IllegalArgumentException("Throw expression lacks its actual PHP throwable VALUE");
            var error=(php.runtime.lang.exception.BaseBaseException)((ObjectMemory)raw).value;var certificate=PhpOwnershipThrowables.require(session,error);var binding=certificate.binding(session);binding.view(raw);
            if(evalId<0)publish(destination.publisher,binding.view(raw));else{var source=eval(evalId);validateImport(source.current().binding(),binding);transfer(source,destination.publisher,raw);}
            certificate.claim(destination.publisher);destination.error=error;destination.active=true;destination.propagating=true;return error;
        }
        public php.runtime.lang.exception.BaseBaseException chainThrown(php.runtime.lang.exception.BaseBaseException replacement,php.runtime.lang.exception.BaseBaseException pending)throws Throwable {
            custody(plan.thrownIds().length==0?-1:plan.thrownIds()[0]);return PhpOwnershipThrowables.chain(session,replacement,pending);
        }
        public PhpOwnershipHeap.Publication prepareThrown(Memory raw){return thrown.prepare(thrown.current(),session.value(raw));}
        public PhpOwnershipHeap.Publication prepareEvalFromCall(int evalId,CallRoots roots,Memory raw){roots.same(session);return roots.result.prepareTransferTo(roots.result.current(),eval(evalId),eval(evalId).current(),raw);}
        public PhpOwnershipFramePublisher stageValue(PhpOwnershipValues.Binding binding)throws Throwable {session.open();var stage=publisher(frame.temporary("stage:"+stages.size()));publish(stage,binding);stages.add(stage);return stage;}
        public PhpOwnershipFramePublisher stageAllocation(PhpOwnershipObjects.Allocation allocation)throws Throwable {session.open();var binding=allocation.binding();if(binding.domain()!=session.domain())throw new IllegalArgumentException("Foreign allocation");binding.requireCertifiedObject();var stage=publisher(frame.temporary("stage:"+stages.size()));session.domain().graph().deferDestruction(()->{try(var publication=allocation.preparePublication(stage,stage.current())){publication.commit();}allocation.close();});stages.add(stage);return stage;}
        public PhpOwnershipFramePublisher stageAllocation(PhpOwnershipArrays.Allocation allocation)throws Throwable {session.open();if(allocation.binding().domain()!=session.domain())throw new IllegalArgumentException("Foreign allocation");var stage=publisher(frame.temporary("stage:"+stages.size()));session.domain().graph().deferDestruction(()->{try(var publication=allocation.preparePublication(stage,stage.current())){publication.commit();}allocation.close();});stages.add(stage);return stage;}
        public PhpOwnershipFramePublisher stageAllocation(PhpOwnershipArrays.CellAllocation allocation)throws Throwable {session.open();var binding=allocation.binding();if(binding.domain()!=session.domain())throw new IllegalArgumentException("Foreign CELL allocation");var stage=publisher(frame.temporary("stage:"+stages.size()));session.domain().graph().deferDestruction(()->{publish(stage,binding);allocation.close();});stages.add(stage);return stage;}
        public void stageResult(PhpOwnershipValues.Binding binding)throws Throwable {ticket.roots.stageResult(binding);}
        public PhpOwnershipFramePublisher stageThrowable(PhpOwnershipValues.Binding binding,PhpOwnershipDomain.OwnedValue allocation)throws Throwable{session.open();binding.requireCertifiedObject();var stage=publisher(frame.temporary("engine-error:"+stages.size()));publish(stage,binding);stages.add(stage);allocation.close();return stage;}
        public PhpOwnershipFramePublisher stageAllocation(PhpOwnershipObjects.Allocation allocation,PhpOwnershipArrays.RestorationAssembly assembly)throws Throwable{session.open();Objects.requireNonNull(assembly);if(assembly.domain()!=session.domain() || !assembly.matchesTable(allocation.object().getProperties()))throw new IllegalArgumentException("Restoration assembly differs from its fresh object allocation");Object identity=allocation.object();if(restoration.containsKey(identity))throw new IllegalArgumentException("Object restoration origin already staged");var publisher=stageAllocation(allocation);restoration.put(identity,Objects.requireNonNull(assembly));return publisher;}
        public PhpOwnershipFramePublisher stageAllocation(PhpOwnershipArrays.Allocation allocation,PhpOwnershipArrays.RestorationAssembly assembly)throws Throwable{session.open();Objects.requireNonNull(assembly);if(assembly.domain()!=session.domain() || !assembly.matchesArray(allocation.array()))throw new IllegalArgumentException("Restoration assembly differs from its fresh array allocation");Object identity=allocation.array().getStorageIdentity();if(restoration.containsKey(identity))throw new IllegalArgumentException("Array restoration origin already staged");var publisher=stageAllocation(allocation);restoration.put(identity,Objects.requireNonNull(assembly));return publisher;}
        public PhpOwnershipArrays.RestorationAssembly consumeObjectRestoration(IObject object){session.open();var assembly=restoration.remove(object);if(assembly==null)throw new IllegalArgumentException("Object has no fresh invocation restoration assembly");return assembly;}
        public PhpOwnershipArrays.RestorationAssembly consumeArrayRestoration(ArrayMemory array){session.open();var assembly=restoration.remove(array.getStorageIdentity());if(assembly==null)throw new IllegalArgumentException("Array has no fresh invocation restoration assembly");return assembly;}
        private List<PhpOwnershipValues.Binding> traceArguments(){List<PhpOwnershipValues.Binding> result=new ArrayList<>();int count=ticket.roots.arguments.size();if(plan!=null)for(Local local:plan.locals())if(local.importKind()==ImportKind.ARGUMENT)count=Math.max(count,local.importIndex()+1);
            for(int index=0;index<count;index++){PhpOwnershipValues.Binding binding=null;if(plan!=null)for(Local local:plan.locals())if(local.importKind()==ImportKind.ARGUMENT && local.importIndex()==index){binding=local.storage()==Storage.VALUE?locals.get(local.id()).current().binding():physical.get(local.id()).payloadPublisher().current().binding();break;}
                if(binding==null && index<ticket.roots.arguments.size())binding=ticket.roots.argument(index);if(binding==null)binding=session.value(Memory.NULL);
                if(binding.role()==PhpOwnershipValues.Role.CELL)binding=session.arrays().certificate((ReferenceMemory)binding.rawValue()).value().current().binding();
                var raw=binding.rawValue().toImmutable();result.add(binding.view(raw));
            }return List.copyOf(result);
        }
        public PhpOwnershipFramePublisher findFreshStage(PhpOwnershipValues.Binding binding){session.open();if(currentEntry(session.env)!=this)throw new IllegalArgumentException("Fresh stage belongs to another producer entry");binding.requireCertifiedObject();for(var source:stages){var current=source.current().binding();if(current!=null && current.node()==binding.node() && current.role()==binding.role() && (current.node()!=null || current.rawValue()==binding.rawValue())){validateImport(current,binding);return source;}}return null;}
        /** Promote only the concrete fresh producer root; var_hash aliases do not acquire owners. */
        public PhpOwnershipValues.Binding promoteFreshStage(PhpOwnershipValues.Binding value)throws Throwable {
            if(value.role()!=PhpOwnershipValues.Role.VALUE)throw new IllegalArgumentException("Fresh promotion needs a VALUE stage");
            var source=findFreshStage(value);if(source==null)throw new IllegalArgumentException("Value has no fresh producer stage");
            try(var allocation=session.arrays().allocateCell(value)) {
                var binding=allocation.binding();
                session.domain().graph().deferDestruction(()->{publish(source,binding);allocation.close();});return binding;
            }
        }
        /** Copy the actual CELL payload into the caller result, then relinquish only its fresh producer root. */
        public Memory stageValueResultFromCell(ReferenceMemory cell)throws Throwable {
            var binding=session.cell(cell);var source=findFreshStage(binding);
            if(source==null)throw new IllegalArgumentException("CELL has no fresh producer stage");
            var payload=session.arrays().certificate(cell).value().current().binding();
            session.domain().graph().deferDestruction(()->{publish(ticket.roots.result,payload);publish(source,session.value(Memory.UNDEFINED));});
            return payload.rawValue();
        }
        private boolean moveStaged(PhpOwnershipValues.Binding binding,PhpOwnershipFramePublisher destination)throws Throwable {for(var source:stages){var current=source.current().binding();if(current!=null && current.node()==binding.node() && current.role()==binding.role() && (current.node()!=null || current.rawValue()==binding.rawValue())){validateImport(current,binding);transfer(source,destination,binding.rawValue());return true;}}return false;}
        private void publish(PhpOwnershipFramePublisher destination,PhpOwnershipValues.Binding binding)throws Throwable {if(binding.domain()!=session.domain())throw new IllegalArgumentException("Foreign frame child");binding.requireCertifiedObject();try(var publication=destination.prepare(destination.current(),binding)){publication.commit();}}
        private void transfer(PhpOwnershipFramePublisher source,PhpOwnershipFramePublisher destination,Memory raw)throws Throwable {try(var publication=source.prepareTransferTo(source.current(),destination,destination.current(),raw)){publication.commit();}}
    }
    /** Bounded data-only method-attribute reader; it grants no body capability. */
    public static FramePlan readFramePlan(byte[] bytes,String method,String descriptor)throws IOException {
        if(bytes==null || bytes.length>64*1024*1024)throw new IOException("Invalid generated class size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0xcafebabe)throw new IOException("Invalid classfile");in.readUnsignedShort();in.readUnsignedShort();
        int count=in.readUnsignedShort();Object[] pool=new Object[count];
        for(int i=1;i<count;i++)switch(in.readUnsignedByte()){
            case 1:pool[i]=in.readUTF();break;case 3:case 4:in.skipNBytes(4);break;case 5:case 6:in.skipNBytes(8);if(++i>=count)throw new IOException("Invalid wide constant");break;
            case 7:pool[i]=in.readUnsignedShort();break;case 8:case 16:case 19:case 20:in.skipNBytes(2);break;case 9:case 10:case 11:case 12:case 17:case 18:in.skipNBytes(4);break;case 15:in.skipNBytes(3);break;default:throw new IOException("Unknown class constant");
        }
        in.readUnsignedShort();int thisClass=in.readUnsignedShort();in.readUnsignedShort();if(thisClass<=0 || thisClass>=count || !(pool[thisClass] instanceof Integer))throw new IOException("Invalid class identity");String owner=utf(pool,(Integer)pool[thisClass]);
        int interfaces=in.readUnsignedShort();in.skipNBytes(2L*interfaces);
        int fields=in.readUnsignedShort();for(int i=0;i<fields;i++){in.skipNBytes(6);skipAttributes(in,pool);}
        int methods=in.readUnsignedShort();byte[] payload=null;
        for(int i=0;i<methods;i++){
            in.readUnsignedShort();String name=utf(pool,in.readUnsignedShort()),desc=utf(pool,in.readUnsignedShort());int attributes=in.readUnsignedShort();boolean matching=name.equals(method)&&desc.equals(descriptor);
            for(int j=0;j<attributes;j++){String attribute=utf(pool,in.readUnsignedShort());long length=Integer.toUnsignedLong(in.readInt());if(length>in.available())throw new IOException("Truncated class attribute");
                if(matching && attribute.equals("JPHP_Ownership_Frame_v1")){if(payload!=null || length>1024*1024)throw new IOException("Duplicate/oversized execution plan");payload=in.readNBytes((int)length);}else in.skipNBytes(length);
            }
        }
        skipAttributes(in,pool);if(in.read()!=-1)throw new IOException("Trailing class data");if(payload==null)return null;
        DataInputStream data=new DataInputStream(new ByteArrayInputStream(payload));int schema=data.readInt();String planOwner=data.readUTF(),planMethod=data.readUTF(),planDescriptor=data.readUTF();
        try {
            EntryKind kind=EntryKind.valueOf(data.readUTF());List<Local> locals=new ArrayList<>();int n=planCount(data);
            for(int i=0;i<n;i++)locals.add(new Local(data.readInt(),data.readInt(),data.readUTF(),Storage.valueOf(data.readUTF()),ImportKind.valueOf(data.readUTF()),data.readInt(),Mode.valueOf(data.readUTF())));
            Set<String> required=planStrings(data);List<String> unsupported=new ArrayList<>(planStrings(data));int pending=data.readInt(),thrown=data.readInt();int[] eval=new int[planCount(data)];for(int i=0;i<eval.length;i++)eval[i]=data.readInt();
            int[] thrownIds=new int[schema==2?planCount(data):0];for(int i=0;i<thrownIds.length;i++)thrownIds[i]=data.readInt();
            int[] returnIds=new int[schema==2?planCount(data):0];for(int i=0;i<returnIds.length;i++)returnIds[i]=data.readInt();
            if(data.read()!=-1 || !owner.equals(planOwner) || !method.equals(planMethod) || !descriptor.equals(planDescriptor))throw new IOException("Foreign/trailing execution plan");
            return new FramePlan(schema,planOwner,planMethod,planDescriptor,kind,locals,required,unsupported,pending,thrown,eval,thrownIds,returnIds);
        } catch(IllegalArgumentException failure){throw new IOException("Invalid execution plan",failure);}
    }
    private static String utf(Object[] pool,int index)throws IOException {if(index<=0 || index>=pool.length || !(pool[index] instanceof String))throw new IOException("Invalid UTF constant");return (String)pool[index];}
    private static void skipAttributes(DataInputStream in,Object[] pool)throws IOException {int n=in.readUnsignedShort();for(int i=0;i<n;i++){utf(pool,in.readUnsignedShort());long size=Integer.toUnsignedLong(in.readInt());if(size>in.available())throw new IOException("Truncated class attribute");in.skipNBytes(size);}}
    private static int planCount(DataInputStream in)throws IOException {int n=in.readInt();if(n<0 || n>65535)throw new IOException("Invalid plan count");return n;}
    private static Set<String> planStrings(DataInputStream in)throws IOException {Set<String> values=new LinkedHashSet<>();int n=planCount(in);for(int i=0;i<n;i++){String value=in.readUTF();if(value.isEmpty() || !values.add(value))throw new IOException("Invalid plan capability");}return values;}
    private static boolean samePlan(FramePlan a,FramePlan b){return a.schema==b.schema && a.owner.equals(b.owner) && a.method.equals(b.method) && a.descriptor.equals(b.descriptor) && a.kind==b.kind && a.locals.equals(b.locals) && a.required.equals(b.required) && a.unsupported.equals(b.unsupported) && a.pendingReturn==b.pendingReturn && a.thrown==b.thrown && Arrays.equals(a.eval,b.eval) && Arrays.equals(a.thrownIds,b.thrownIds) && Arrays.equals(a.returnIds,b.returnIds);}
    private static boolean contains(int[] values,int value){for(int item:values)if(item==value)return true;return false;}
    @SuppressWarnings("unchecked") private static <E extends Throwable> void raise(Throwable failure)throws E {throw (E)failure;}
}
