package php.runtime.lang;

import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import php.runtime.Memory;
import php.runtime.PhpFiberScheduler;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.Invoker;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.lang.exception.PhpFiberError;
import php.runtime.lang.support.IPhpNativeRetirement;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import static php.runtime.annotation.Reflection.*;

/** Ordinary-Environment stackful candidate; parked owned execution requires separate attestation. */
@Name("Fiber") @Final @NativePhpSignature
public final class PhpFiber extends BaseObject implements IPhpNativeRetirement,
        php.runtime.lang.support.ICloneableObject<PhpFiber>,IPhpNativePropertyGuard {
    private enum State { NEW,RUNNING,SUSPENDED,TERMINATED }
    private record Request(php.runtime.memory.ArrayMemory arguments,Memory value,BaseBaseException injected,boolean close) {}
    private record Event(Memory value,Throwable failure,boolean terminal) {}
    /** Native dispatch must not wrap force-close into a catchable PHP JavaException. */
    private static final class Cancelled extends php.runtime.exceptions.PhpUnwindSignal {
        private Cancelled() {super();}
    }
    private static final ThreadLocal<PhpFiber> CURRENT=new ThreadLocal<>();
    private final LinkedBlockingQueue<Request> inbox=new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Event> outbox=new LinkedBlockingQueue<>();
    private volatile State state=State.NEW;
    private volatile boolean closing;
    private boolean retiring;
    private volatile boolean constructed;
    private boolean returned,threw;
    private Memory callable,returnValue;
    private Invoker invoker;
    private ExecutorService executor;
    private volatile Thread worker;
    private PhpFiberScheduler scheduler;
    private PhpFiberScheduler.Registration registration;
    private Environment.FiberState segment,callerSegment;
    private Thread caller;
    private final ThreadLocal<Environment.ThreadBinding> bindings=new ThreadLocal<>();
    private final ThreadLocal<Environment.FiberStateBinding> hostBindings=new ThreadLocal<>();
    private boolean segmentOwned;
    private TraceInfo suspendedTrace;

    public PhpFiber(Environment env,ClassEntity clazz) {super(env,clazz);}
    @Ignore public static PhpFiber current(Environment env) {
        var fiber=CURRENT.get();return fiber!=null && fiber.getEnvironment()==env?fiber:null;
    }
    /** A generator borrows its dynamically active Fiber for exactly one resume request. */
    @Ignore public static php.runtime.util.generator.Collector.ExecutionContext generatorExecution(
            Environment env,Generator generator) {
        php.runtime.env.CallStack generatorStack=generator.callStack;
        if(env.getFiberScheduler(false)==null)return null;
        PhpFiber fiber=current(env);if(fiber==null)return null;
        Thread parent=Thread.currentThread();
        Environment.FiberState parentState=env.currentFiberState();
        // The blocked parent must not remain strongly registered in Environment while
        // the delegated Fiber stack is parked and may become an orphan.
        var parentBinding=fiber.bindings.get();
        if(parentBinding!=null){parentBinding.close();fiber.bindings.remove();}
        return new php.runtime.util.generator.Collector.ExecutionContext() {
            private Thread delegate;
            private PhpFiber previous;
            @Override public void enter() {
                delegate=Thread.currentThread();previous=CURRENT.get();
                fiber.scheduler.switchThread(parent,delegate);
                fiber.hostBindings.set(env.bindFiberState(env.delegatedFiberState(parentState,generatorStack),parentState));
                fiber.bindings.set(env.bindCurrentThread());CURRENT.set(fiber);generator.fiberOwner=fiber;
            }
            @Override public void restoreCaller() {
                if(Thread.currentThread()!=parent || !fiber.scheduler.isActiveThread(parent))
                    throw new IllegalStateException("Generator caller restored before execution handoff");
                var host=fiber.hostBindings.get();
                if(host==null)throw new IllegalStateException("Missing generator caller lineage binding");
                host.refreshLineage(parentState);fiber.bindings.set(env.bindCurrentThread());
            }
            @Override public void close() {
                if(delegate!=Thread.currentThread())throw new IllegalStateException("Foreign generator execution release");
                generator.fiberOwner=null;
                var binding=fiber.bindings.get();if(binding!=null){binding.close();fiber.bindings.remove();}
                env.finishDelegatedFiberState(parentState,env.currentFiberState());
                var host=fiber.hostBindings.get();if(host==null)throw new IllegalStateException("Missing generator host-state binding");
                host.closeTo(parentState);fiber.hostBindings.remove();fiber.scheduler.switchThread(delegate,parent);
                if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            }
        };
    }
    @Ignore public static void fail(Environment env,String message) {
        var declaration=env.getScope().fetchPhp85Class("FiberError");
        if(declaration==null)throw new IllegalStateException("Modern FiberError declaration is unavailable");
        var error=new PhpFiberError(env,declaration);error.__construct(env,new php.runtime.memory.StringMemory(message));
        error.setTraceInfo(env,env.trace());throw error;
    }
    private static void ordinary(Environment env) {
        env.validateCallbackDomain();
        if(!env.supportsFiberHost())fail(env,"Fiber requires an explicitly supported Environment and runtime loader; custom execution state is not supported");
        if(env.getRuntimeOwnership()!=null || php.runtime.PhpOwnershipExecution.current(env)!=null)
            fail(env,"Fiber suspension requires a parked ownership attestation; owned execution is not yet supported");
    }
    private static void requireSuspendableCallback(Environment env) {
        if(!php.runtime.env.PhpExecutionLineage.fiberAllowed())
            fail(env,"Fiber entry or suspension is unavailable inside a nontransactional host callback");
    }
    private static void requireFinalization(Environment env) {
        for(String argument:ManagementFactory.getRuntimeMXBean().getInputArguments())
            if(argument.equals("--finalization=disabled") || argument.equals("-XX:+DisableFinalization"))
                fail(env,"Fiber is unavailable when JVM finalization is disabled");
    }
    private void validateEnvironment(Environment env) {
        if(env!=getEnvironment())fail(env,"Fiber belongs to another Environment");
        ordinary(env);
    }
    @Signature(@Arg(value="callback",typeDeclaration="callable"))
    public Memory __construct(Environment env,Memory...args) {
        requireSuspendableCallback(env);
        validateEnvironment(env);requireFinalization(env);
        if(constructed)fail(env,"Fiber has already been constructed");
        Invoker selected;
        try(var frame=php.runtime.invoke.PhpCallFrame.acquireInternalRetainedCallable(args[0],env,env.trace())) {
            selected=frame.resolvedTarget();
        } catch(php.runtime.invoke.PhpMethodResolver.InvalidCallback invalid) {
            env.exception(env.trace(),php.runtime.lang.exception.BaseTypeError.class,
                    "Fiber::__construct(): Argument #1 ($callback) must be a valid callback, %s",invalid.getMessage());
            return Memory.NULL;
        }
        callable=args[0].toValue().toImmutable();invoker=selected;
        scheduler=env.getFiberScheduler(true);registration=scheduler.register(this);
        constructed=true;env.registerObjectInGC(this);return Memory.NULL;
    }
    @Signature(value=@Arg(value="args",typeDeclaration="mixed",variadic=true),result=@Arg(typeDeclaration="mixed"))
    public Memory start(Environment env,Memory...args) throws Throwable {
        requireSuspendableCallback(env);
        validateEnvironment(env);requireFinalization(env);
        synchronized(this) {
            if(!constructed)fail(env,"Fiber has not been constructed");
        }
        var passed=args.length==0?new php.runtime.memory.ArrayMemory():args[0].toValue(php.runtime.memory.ArrayMemory.class);
        return transfer(env,new Request(passed,Memory.NULL,null,false),true);
    }
    @Signature(value=@Arg(value="value",typeDeclaration="mixed",optional=@Optional("null")),result=@Arg(typeDeclaration="mixed"))
    public Memory resume(Environment env,Memory...args) throws Throwable {
        requireSuspendableCallback(env);
        validateEnvironment(env);return transfer(env,new Request(null,args[0].toValue().toImmutable(),null,false),false);
    }
    @Name("throw")
    @Signature(value=@Arg(value="exception",typeDeclaration="Throwable"),result=@Arg(typeDeclaration="mixed"))
    public Memory inject(Environment env,Memory...args) throws Throwable {
        requireSuspendableCallback(env);
        validateEnvironment(env);
        var object=args[0].toValue(ObjectMemory.class).value;
        if(!(object instanceof BaseBaseException exception))fail(env,"Fiber::throw requires the actual PHP Throwable carrier");
        return transfer(env,new Request(null,Memory.NULL,(BaseBaseException)object,false),false);
    }
    private Memory transfer(Environment env,Request request,boolean first) throws Throwable {
        try(var callerLease=scheduler.enterCaller()) {
        synchronized(this) {
            if(first ? state!=State.NEW : state!=State.SUSPENDED)
                fail(env,first?"Cannot start a fiber that has already been started":"Cannot resume a fiber that is not suspended");
            caller=Thread.currentThread();callerSegment=env.currentFiberState();
            if(first) {
                php.runtime.invoke.PhpCallFrame.retargetRetainedInvocation(invoker,env.trace());
                segment=env.newFiberState();
            }
            env.prepareFiberHandoff(segment);state=State.RUNNING;
        }
        if(first) {
            try {executor=Executors.newVirtualThreadPerTaskExecutor();executor.execute(this::runCarrier);}
            catch(Throwable failure) {
                state=State.TERMINATED;
                try {clearCarrier();}catch(Throwable cleanup) {if(cleanup!=failure)failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        inbox.offer(request);
        Event event;boolean interrupted=false;
        for(;;) {
            try {event=outbox.take();}
            catch(InterruptedException interruption) {
                interrupted=true;closing=true;
                Thread carrier=worker;if(carrier!=null)carrier.interrupt();
                // Do not return into PHP finally while the child still owns shared Env state.
                // Non-cooperative native code requires host/process supervision, not a fake timeout restoration.
                continue;
            }
            if(!interrupted || event.terminal)break;
            // A yield may already be queued when the caller is interrupted. The caller segment
            // is restored, but cancellation must still reach a terminal carrier before return.
            synchronized(this) {if(state==State.SUSPENDED)state=State.RUNNING;}
            inbox.offer(new Request(null,Memory.NULL,null,true));
        }
        try {
            Throwable failure=event.failure;
            if(event.terminal)try {joinWorker();}
            catch(Throwable cleanup) {
                if(cleanup instanceof InterruptedException)interrupted=true;
                if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);
            }
            if(failure!=null)throw failure;
            if(interrupted) {
                throw new InterruptedException("Fiber caller interrupted after its segment was safely restored");
            }
            return event.value;
        } finally {if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private void activate() {
        Environment env=getEnvironment();
        scheduler.switchThread(caller,Thread.currentThread());segmentOwned=true;
        hostBindings.set(env.bindFiberState(segment,callerSegment));
        bindings.set(env.bindCurrentThread());CURRENT.set(this);
    }
    private void returnSegment() {
        Environment env=getEnvironment();segment=env.currentFiberState();
        var binding=bindings.get();if(binding!=null) {binding.close();bindings.remove();}
        var host=hostBindings.get();if(host==null)throw new IllegalStateException("Missing Fiber host-state binding");
        host.closeTo(callerSegment);hostBindings.remove();scheduler.switchThread(Thread.currentThread(),caller);segmentOwned=false;
    }
    private void runCarrier() {
        worker=Thread.currentThread();Throwable failure=null;Memory result=Memory.NULL;
        try {
            Request initial=inbox.take();activate();
            if(initial.close || closing)throw new Cancelled();
            if(invoker instanceof php.runtime.invoke.RunnableInvoker) {
                var values=initial.arguments.getNewIterator(getEnvironment(),false,false);
                while(values.next())if(values.getMemoryKey().isString())
                    fail(getEnvironment(),"Native runnable callback has no named PHP declaration");
                result=invoker.call(initial.arguments.values());
            } else try(var frame=php.runtime.invoke.PhpCallFrame.resolvedInvoker(getEnvironment(),TraceInfo.UNKNOWN,invoker,initial.arguments.size())) {
                result=frame.invokeKeyed(initial.arguments,true);
            }
            if(result==null)result=Memory.NULL;
            returnValue=result.toValue().toImmutable();returned=true;
        } catch(Cancelled cancelled) {
            // Internal force-close is not a PHP Throwable; generated finally blocks already ran.
        } catch(Throwable throwable) {threw=true;failure=throwable;}
        finally {
            try {if(segmentOwned)returnSegment();}
            catch(Throwable cleanup) {if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);}
            CURRENT.remove();
            if(!segmentOwned) {
                callable=null;invoker=null;
                state=State.TERMINATED;outbox.offer(new Event(Memory.NULL,failure,true));
            }
            // A failed restoration is a host-supervision failure. Publishing a terminal event
            // here would allow PHP finally to touch an Environment still owned by this segment.
        }
    }
    private Memory park(Memory value) throws Throwable {
        Environment env=getEnvironment();
        if(closing)fail(env,"Cannot suspend in a force-closed fiber");
        if(state!=State.RUNNING || current(env)!=this || !scheduler.isActiveThread(Thread.currentThread()))fail(env,"Cannot suspend outside of a fiber");
        suspendedTrace=env.trace();returnSegment();state=State.SUSPENDED;
        outbox.offer(new Event(value.toValue().toImmutable(),null,false));
        Request next;boolean interrupted=false;
        // A parked stack no longer owns Env. Never unwind its PHP finally until a caller
        // has granted the next segment handoff, even when native cancellation interrupts it.
        for(;;)try {next=inbox.take();break;}
        catch(InterruptedException interruption) {interrupted=true;}
        activate();if(interrupted)Thread.currentThread().interrupt();
        if(next.close || closing)throw new Cancelled();
        if(next.injected!=null)throw next.injected;
        return next.value;
    }
    @Name("suspend")
    @Signature(value=@Arg(value="value",typeDeclaration="mixed",optional=@Optional("null")),result=@Arg(typeDeclaration="mixed"))
    public static Memory suspendFiber(Environment env,Memory...args) throws Throwable {
        requireSuspendableCallback(env);
        ordinary(env);var fiber=current(env);
        if(fiber==null)fail(env,"Cannot suspend outside of a fiber");
        return fiber.park(args[0]);
    }
    @Signature(result=@Arg(typeDeclaration="?Fiber"))
    public static Memory getCurrent(Environment env,Memory...args) {
        var fiber=current(env);return fiber==null?Memory.NULL:new ObjectMemory(fiber);
    }
    @Signature(result=@Arg(typeDeclaration="bool"))
    public Memory isStarted(Environment env,Memory...args) {validateEnvironment(env);return state!=State.NEW?Memory.TRUE:Memory.FALSE;}
    @Signature(result=@Arg(typeDeclaration="bool"))
    public Memory isSuspended(Environment env,Memory...args) {validateEnvironment(env);return state==State.SUSPENDED?Memory.TRUE:Memory.FALSE;}
    @Signature(result=@Arg(typeDeclaration="bool"))
    public Memory isRunning(Environment env,Memory...args) {validateEnvironment(env);return state==State.RUNNING?Memory.TRUE:Memory.FALSE;}
    @Signature(result=@Arg(typeDeclaration="bool"))
    public Memory isTerminated(Environment env,Memory...args) {validateEnvironment(env);return state==State.TERMINATED?Memory.TRUE:Memory.FALSE;}
    @Signature(result=@Arg(typeDeclaration="mixed"))
    public Memory getReturn(Environment env,Memory...args) {
        validateEnvironment(env);
        if(!returned)fail(env,state==State.NEW?"Cannot get fiber return value: The fiber has not been started":threw?"Cannot get fiber return value: The fiber threw an exception":"Cannot get fiber return value: The fiber has not returned");
        return returnValue.toImmutable();
    }
    @Ignore public Memory callable() {return callable;}
    @Ignore public boolean hasStack() {return state==State.RUNNING || state==State.SUSPENDED;}
    @Ignore public TraceInfo suspensionTrace() {
        return current(getEnvironment())==this?getEnvironment().trace():suspendedTrace!=null?suspendedTrace:segment.stack().trace();
    }
    @Ignore public php.runtime.env.CallStackItem[] stackSnapshot() {
        return current(getEnvironment())==this?getEnvironment().getCallStackSnapshot():segment.stack().getSnapshot();
    }
    @Ignore @Override public void retireNative(Environment env) throws Throwable {
        try (var admission=env.enterCallbackDomainForRetirement()) {
            validateEnvironment(env);
            synchronized(this) {
                if(state==State.RUNNING)throw new IllegalStateException("Cannot force-close a running fiber");
                if(retiring)throw new IllegalStateException("Fiber retirement already has an active owner");
                retiring=true;
            }
            Throwable failure=null;
            try {
                if(state==State.SUSPENDED) {closing=true;transfer(env,new Request(null,Memory.NULL,null,true),false);}
            } catch(Throwable cleanup) {failure=cleanup;}
            finally {
                try {
                    if(state==State.NEW || state==State.TERMINATED) {
                        try {clearCarrier();if(registration!=null){scheduler.retire(registration);registration=null;}doFinalize();}
                        catch(Throwable cleanup) {if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);}
                    } else closing=false;
                } finally {synchronized(this) {retiring=false;}}
            }
            if(failure!=null)throw failure;
        }
    }
    private void joinWorker() throws InterruptedException {
        Thread thread=worker;if(thread!=null && thread!=Thread.currentThread()) {
            thread.join(5000);if(thread.isAlive())throw new IllegalStateException("Fiber carrier did not terminate");
        }
        if(executor!=null)executor.shutdown();
    }
    private void clearCarrier() throws InterruptedException {
        if(state==State.TERMINATED)joinWorker();
        if(executor!=null)executor.shutdown();
        callable=null;invoker=null;returnValue=null;segment=null;callerSegment=null;suspendedTrace=null;
        worker=null;caller=null;executor=null;inbox.clear();outbox.clear();
    }
    @Ignore @SuppressWarnings("removal") @Override protected void finalize() {
        if(constructed && !isFinalized())php.runtime.invoke.PhpObjectLifecycle.enqueueJvmFinalizer(this);
    }
    @Ignore @Override public PhpFiber __clone(Environment env,TraceInfo trace) {
        env.exception(trace,php.runtime.lang.exception.BaseError.class,"Trying to clone an uncloneable object of class Fiber");return null;
    }
    @Ignore @Override public void validateNativePropertyWrite(Environment env,TraceInfo trace,String property) {
        env.exception(trace,php.runtime.lang.exception.BaseError.class,"Cannot create dynamic property Fiber::$%s",property);
    }
    @Ignore @Override public void validateNativePropertyReference(Environment env,TraceInfo trace,String property,Memory current) {
        validateNativePropertyWrite(env,trace,property);
    }
}
