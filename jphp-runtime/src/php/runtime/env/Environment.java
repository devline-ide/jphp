package php.runtime.env;

import php.runtime.common.PhpClassNames;

import php.runtime.Information;
import php.runtime.Memory;
import php.runtime.common.*;
import php.runtime.env.handler.*;
import php.runtime.env.message.CustomSystemMessage;
import php.runtime.env.message.NoticeMessage;
import php.runtime.env.message.SystemMessage;
import php.runtime.env.message.WarningMessage;
import php.runtime.exceptions.*;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.classes.WrapEnvironmentVariables;
import php.runtime.ext.java.JavaReflection;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.CompileConstant;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.invoke.Invoker;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.cache.ClassCallCache;
import php.runtime.invoke.cache.CallCache;
import php.runtime.lang.*;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseParseError;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.sourcemap.SourceMap;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.output.OutputBuffer;
import php.runtime.reflection.*;
import php.runtime.reflection.support.ReflectionUtils;
import php.runtime.util.JVMStackTracer;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static php.runtime.exceptions.support.ErrorType.*;

public class Environment {
    public final int id;
    public final CompileScope scope;
    public final Map<String, Memory> configuration = new HashMap<String, Memory>();
    public final static Map<String, ConfigChangeHandler> configurationHandler;

    // call stack
    private CallStack callStack = new CallStack(this);

    private Set<String> includePaths;

    public SplClassLoader __autoload = null;
    public SplClassLoader defaultAutoLoader = null;
    protected final List<SplClassLoader> classLoaders = new LinkedList<SplClassLoader>();

    // errors
    private int errorFlags = E_ALL.value ^ (E_NOTICE.value | E_STRICT.value | E_DEPRECATED.value);
    private Stack<Integer> silentFlags = new Stack<Integer>();

    private SystemMessage lastMessage;

    private ErrorReportHandler errorReportHandler;
    private List<ErrorHandler> previousErrorHandlers;
    private ErrorHandler errorHandler;
    private boolean ownedErrorHandlersReleased;
    private ShellExecHandler shellExecHandler = ShellExecHandler.DEFAULT;

    private List<ExceptionHandler> previousExceptionHandlers;
    private ExceptionHandler exceptionHandler = ExceptionHandler.DEFAULT;

    private OutputBuffer defaultBuffer;
    private Stack<OutputBuffer> outputBuffers;

    private List<ShutdownHandler> shutdownFunctions = new LinkedList<ShutdownHandler>();

    protected Map<String, SourceMap> sourceMaps = new HashMap<>();

    // charset, locale
    private Locale locale = Locale.getDefault();
    private Charset defaultCharset = Charset.forName("UTF-8");

    // vars
    protected final ArrayMemory globals;
    private SyntheticGlobalsOrigin syntheticGlobalsOrigin;
    /** Constructor provenance only; this metadata never owns the legacy recursive value. */
    public static final class SyntheticGlobalsOrigin {
        private final Environment environment;
        private final ReferenceMemory carrier;
        private boolean retired;
        private SyntheticGlobalsOrigin(Environment environment,ReferenceMemory carrier){this.environment=environment;this.carrier=carrier;}
        public Environment environment(){return environment;}
        public ReferenceMemory carrier(){return carrier;}
        public boolean isRetired(){return retired;}
        public void validateCarrier(ReferenceMemory actual){
            if(retired||actual!=carrier||environment.syntheticGlobalsOrigin!=this
                    ||environment.globals.getByScalar("GLOBALS")!=carrier||carrier.getRawValue()!=environment.globals)
                throw new IllegalArgumentException("Synthetic GLOBALS creation origin is stale or foreign");
        }
        /** Called only after the actual carrier's successful mutation was prevalidated. */
        public final void retire(){retired=true;}
    }
    public final SyntheticGlobalsOrigin getSyntheticGlobalsOrigin(){return syntheticGlobalsOrigin;}
    public final ArrayMemory getRuntimeGlobalsTable(){return globals;}
    protected final Map<String, ReferenceMemory> statics;
    protected final Map<String, Object> userValues = new HashMap<String, Object>();

    // classes, funcs, consts
    /** Canonical ASCII class keys. Legacy lookup state is not exposed by getLoadedClasses(). */
    public final Map<String, ClassEntity> classMap = new LinkedHashMap<String, ClassEntity>();
    private final Map<String, ClassEntity> legacyClassMap = new LinkedHashMap<>();
    private final Map<String, String> legacyClassKeys = new LinkedHashMap<>();
    private static final class ClassLookupContext {
        final boolean modern;
        final php.runtime.PhpPropertyWriteContext.Frame caller;
        final String originalName;
        ClassLookupContext(boolean modern, php.runtime.PhpPropertyWriteContext.Frame caller, String originalName) {
            this.modern = modern; this.caller = caller; this.originalName = originalName;
        }
    }
    private final ThreadLocal<ClassLookupContext> classLookupModern = new ThreadLocal<>();
    protected final Map<String, FunctionEntity> functionMap = new LinkedHashMap<String, FunctionEntity>();
    protected final Map<String, ConstantEntity> constantMap = new LinkedHashMap<String, ConstantEntity>();

    protected final ModuleManager moduleManager;
    protected final PackageManager packageManager;

    protected static final ThreadLocal<Environment> environment = new ThreadLocal<Environment>();
    private static final ThreadLocal<ThreadBinding> executionBindings = new ThreadLocal<>();
    private final Set<ThreadBinding> threadBindings = new HashSet<>();
    private ThreadBinding constructionBinding;
    private boolean finalizationStarted;
    private Thread embeddingFinalizationThread;
    private volatile boolean finalizationComplete;
    private IdentityHashMap<CallCache<?>, CallCache.Row> callCacheRows;
    private IdentityHashMap<ClassEntity,php.runtime.PhpEnums.State> enumStates;
    public php.runtime.PhpEnums.State getEnumState(ClassEntity declaration,boolean create) {
        synchronized (getClassInitializationLock()) {
            var state=enumStates==null?null:enumStates.get(declaration);
            if(state==null && create) {
                if(finalizationStarted || finalizationComplete)throw new IllegalStateException("Environment enum storage has retired");
                if(enumStates==null)enumStates=new IdentityHashMap<>();
                state=new php.runtime.PhpEnums.State();enumStates.put(declaration,state);
            }
            return state;
        }
    }
    private volatile ThreadLocal<php.runtime.PhpOwnershipExecution.Context> ownershipExecutionContexts;
    private php.runtime.PhpRuntimeOwnership.Authority runtimeOwnership;
    /** One explicitly connected ordinary authority shares the existing heap; no inferred domain. */
    public final php.runtime.PhpRuntimeOwnership.Authority getRuntimeOwnership() {return runtimeOwnership;}
    public final void bindRuntimeOwnership(php.runtime.PhpRuntimeOwnership.Authority authority) {
        if(authority==null || authority.environment()!=this)throw new IllegalArgumentException("Foreign runtime ownership authority");
        if(runtimeOwnership!=null && runtimeOwnership!=authority)throw new IllegalStateException("Environment already has a runtime authority");
        var explicit=php.runtime.PhpOwnershipExecution.current(this);
        if(explicit!=null && explicit.domain()!=authority.domain())throw new IllegalArgumentException("Environment has a foreign explicit heap");
        runtimeOwnership=authority;
    }


    /** Explicit execution tickets; ordinary environments allocate no ownership context storage. */
    public final php.runtime.PhpOwnershipExecution.Context getOwnershipExecutionContext() {
        ThreadLocal<php.runtime.PhpOwnershipExecution.Context> contexts=ownershipExecutionContexts;
        return contexts == null ? null : contexts.get();
    }
    public final OwnershipExecutionBinding bindOwnershipExecutionContext(php.runtime.PhpOwnershipExecution.Context context) {
        context.validateBinding(this);
        ThreadLocal<php.runtime.PhpOwnershipExecution.Context> contexts=ownershipExecutionContexts;
        if (contexts == null) synchronized (threadBindings) {
            contexts=ownershipExecutionContexts;
            if (contexts == null) ownershipExecutionContexts=contexts=new ThreadLocal<>();
        }
        OwnershipExecutionBinding binding=new OwnershipExecutionBinding(contexts,context,contexts.get());
        contexts.set(context);return binding;
    }
    public static final class OwnershipExecutionBinding implements AutoCloseable {
        private final ThreadLocal<php.runtime.PhpOwnershipExecution.Context> contexts;
        private final php.runtime.PhpOwnershipExecution.Context context,previous;
        private final Thread thread=Thread.currentThread();private boolean closed;
        private OwnershipExecutionBinding(ThreadLocal<php.runtime.PhpOwnershipExecution.Context> contexts,
                php.runtime.PhpOwnershipExecution.Context context,php.runtime.PhpOwnershipExecution.Context previous) {
            this.contexts=contexts;this.context=context;this.previous=previous;
        }
        @Override public void close() {
            if (closed) return;
            if (thread != Thread.currentThread() || contexts.get() != context)
                throw new IllegalStateException("Ownership execution scopes must close on their thread in LIFO order");
            if (previous == null) contexts.remove();else contexts.set(previous);closed=true;
        }
    }

    /** Runtime cache storage is owned by this Environment, never by a compiled class's static cache. */
    public CallCache.Row getCallCacheRow(CallCache<?> cache, boolean create) {
        synchronized (threadBindings) {
            if (finalizationStarted || finalizationComplete) return null;
            CallCache.Row row = callCacheRows == null ? null : callCacheRows.get(cache);
            if (row == null && create) {
                if (callCacheRows == null) callCacheRows = new IdentityHashMap<>();
                row = new CallCache.Row();
                callCacheRows.put(cache, row);
            }
            return row;
        }
    }

    /** Owns only this thread's PHP execution context and handler installation. */
    public static final class ThreadBinding implements AutoCloseable {
        private final Thread thread;
        private volatile Environment boundEnvironment;
        private volatile WeakReference<Environment> implicitEnvironment;
        private final ThreadBinding previousBinding;
        private final Thread.UncaughtExceptionHandler previousHandler;
        private final BindingHandler handler = new BindingHandler(this);
        private volatile boolean closed;
        private ThreadBinding(Thread thread, Environment env, boolean implicit) {
            this.thread = thread;
            if (implicit) implicitEnvironment = new WeakReference<>(env); else boundEnvironment = env;
            this.previousHandler = liveHandler(thread.getUncaughtExceptionHandler());
            this.previousBinding = thread == Thread.currentThread() ? normalizeBinding()
                    : previousHandler instanceof BindingHandler ? ((BindingHandler) previousHandler).binding : null;
        }
        private Environment getEnvironment() {
            WeakReference<Environment> weak = implicitEnvironment;
            return weak == null ? boundEnvironment : weak.get();
        }
        private boolean isRetired() { return closed || getEnvironment() == null; }
        @Override public void close() {
            if (thread != Thread.currentThread()) throw new IllegalStateException("Thread binding must be closed by its owning thread");
            retire();
        }
        private void retire() {
            Environment env;
            synchronized (thread) {
                if (closed) return;
                closed = true; env = getEnvironment();
                try {
                    if (thread.getUncaughtExceptionHandler() == handler)
                        thread.setUncaughtExceptionHandler(liveHandler(previousHandler));
                } finally {
                    // A foreign-thread retirement cannot edit ThreadLocal state, but must release the Environment.
                    boundEnvironment = null;
                    implicitEnvironment = null;
                    if (thread == Thread.currentThread()) normalizeBinding();
                    if (env != null) synchronized (env.threadBindings) { env.threadBindings.remove(this); }
                }
            }
        }
    }

    private static final class BindingHandler implements Thread.UncaughtExceptionHandler {
        private final ThreadBinding binding;
        private BindingHandler(ThreadBinding binding) { this.binding = binding; }
        @Override public void uncaughtException(Thread thread, Throwable failure) {
            Environment env = binding.getEnvironment();
            boolean reported = false;
            try {
                if (env != null) reported = env.reportThreadThrowable(failure);
            } catch (Throwable reportingFailure) {
                if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
            } finally {
                try { binding.retire(); }
                catch (Throwable cleanupFailure) { if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure); }
            }
            if (!reported) hostHandler(binding.previousHandler).uncaughtException(thread, failure);
        }
    }

    private static Thread.UncaughtExceptionHandler liveHandler(Thread.UncaughtExceptionHandler handler) {
        while (handler instanceof BindingHandler && ((BindingHandler) handler).binding.isRetired())
            handler = ((BindingHandler) handler).binding.previousHandler;
        return handler;
    }
    private static Thread.UncaughtExceptionHandler hostHandler(Thread.UncaughtExceptionHandler handler) {
        while (handler instanceof BindingHandler) handler = ((BindingHandler) handler).binding.previousHandler;
        return handler == null ? (thread, failure) -> failure.printStackTrace() : handler;
    }
    private static ThreadBinding normalizeBinding() {
        ThreadBinding binding = executionBindings.get();
        while (binding != null && binding.isRetired()) binding = binding.previousBinding;
        if (binding == null) executionBindings.remove(); else executionBindings.set(binding);
        return binding;
    }

    public ThreadBinding bindCurrentThread() { return bindThread(Thread.currentThread(), false); }
    private ThreadBinding bindThread(Thread thread, boolean implicit) {
        synchronized (thread) {
            synchronized (threadBindings) {
                if (finalizationComplete) throw new IllegalStateException("Environment has been finalized");
                ThreadBinding binding = new ThreadBinding(thread, this, implicit);
                try {
                    thread.setUncaughtExceptionHandler(binding.handler);
                    threadBindings.add(binding);
                    if (thread == Thread.currentThread()) executionBindings.set(binding);
                    return binding;
                } catch (RuntimeException | Error failure) {
                    try { binding.retire(); }
                    catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }
    }
    /** Releases the compatibility registration created by the constructor, without finalizing its data. */
    public void releaseThreadSupport() {
        ThreadBinding binding = constructionBinding;
        if (binding != null) binding.close();
    }
    private void retireThreadBindings() {
        ThreadBinding[] bindings;
        synchronized (threadBindings) { bindings = threadBindings.toArray(new ThreadBinding[0]); }
        Throwable first = null;
        for (ThreadBinding binding : bindings) {
            try { binding.retire(); }
            catch (RuntimeException | Error failure) { if (first == null) first = failure; else if (first != failure) first.addSuppressed(failure); }
        }
        if (first instanceof RuntimeException) throw (RuntimeException) first;
        if (first instanceof Error) throw (Error) first;
    }

    /** Reports PHP failures and flushes their owning output; Java failures remain host failures. */
    public boolean reportThreadThrowable(Throwable failure) throws Throwable {
        Throwable phpFailure = failure instanceof UncaughtException ? ((UncaughtException) failure).getException() : failure;
        // Internal finally control has no owning Environment and is never a user-visible failure.
        if (phpFailure instanceof FinallyException) return true;
        Environment target = phpFailure instanceof BaseBaseException ? ((BaseBaseException) phpFailure).getEnvironment() : this;
        if (target == null || target.finalizationComplete) return false;
        if (!(failure instanceof UncaughtException || failure instanceof BaseBaseException || failure instanceof ErrorException
                || failure instanceof DieException)) return false;
        Throwable reportingFailure = null;
        try {
            target.catchUncaught((Exception) failure);
            return true;
        } catch (Throwable thrown) {
            reportingFailure = thrown;
            throw thrown;
        } finally {
            try { target.flushAll(); }
            catch (Throwable flushFailure) {
                if (reportingFailure == null) throw flushFailure;
                if (flushFailure != reportingFailure) reportingFailure.addSuppressed(flushFailure);
            }
        }
    }

    /** Runnable failures are reported inside the PHP boundary; Java failures leave through the host boundary. */
    public void runInThread(Runnable runnable) {
        try (ThreadBinding binding = bindCurrentThread()) {
            try { runnable.run(); }
            catch (Throwable failure) {
                try { if (reportThreadThrowable(failure)) return; }
                catch (Throwable reportingFailure) { if (reportingFailure != failure) failure.addSuppressed(reportingFailure); }
                Environment.<RuntimeException>throwThreadFailure(failure);
            }
        }
    }

    /** Invokers can throw checked host failures; preserve their identity across Runnable execution. */
    public void runInThread(Invoker invoker) {
        runInThread(() -> {
            try { invoker.call(); }
            catch (Throwable failure) { Environment.<RuntimeException>throwThreadFailure(failure); }
        });
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwThreadFailure(Throwable failure) throws T { throw (T) failure; }


    /**
     * Gets Environment for current thread context
     */
    @Deprecated
    public static Environment current() {
        ThreadBinding binding = normalizeBinding();
        if (binding != null) return binding.getEnvironment();
        Environment fallback = environment.get();
        if (fallback != null && fallback.finalizationComplete) { environment.remove(); return null; }
        return fallback;
    }

    // Per execution environment: this policy never disables the JVM's physical collector.
    private volatile boolean gcEnabled = true;
    public final boolean isGcEnabled() {return gcEnabled;}
    public final void setGcEnabled(boolean enabled) {gcEnabled=enabled;}
    /** Explicit collection remains available when automatic logical collection is disabled. */
    public final int collectGarbage() throws Throwable {return collectGarbage(false);}
    public final void collectGarbageIfNeeded() throws Throwable {
        runPendingFinalizers();
        if(gcEnabled)collectGarbage(true);
    }
    private int collectGarbage(boolean automatic) throws Throwable {
        runPendingFinalizers();
        cleanGcObjects();
        var session=php.runtime.PhpOwnershipExecution.current(this);
        if(session!=null) {
            var domain=session.domain();
            return automatic && !domain.graph().shouldCollectCycles()?0:domain.collectCycles();
        }
        if(runtimeOwnership!=null)try(var execution=runtimeOwnership.enter()) {
            var domain=runtimeOwnership.domain();
            return automatic && !domain.graph().shouldCollectCycles()?0:domain.collectCycles();
        }
        return 0;
    }

    private final ReferenceQueue<IObject> gcObjectRefQueue = new ReferenceQueue<IObject>();
    private static final class GcObjectReference extends WeakReference<IObject> {
        private int index = -1;
        GcObjectReference(IObject object, ReferenceQueue<IObject> queue) {
            super(object, queue);
        }
    }
    private final List<GcObjectReference> gcObjects = new ArrayList<>();
    private volatile ArrayDeque<IObject> pendingJvmFinalizers;
    private boolean drainingJvmFinalizers;

    /** JVM finalizer notification only: no PHP callback or thread binding on the JVM daemon. */
    public final void enqueueJvmFinalizer(IObject object) {
        synchronized (gcObjects) {
            if (finalizationComplete || object.isFinalized()) return;
            if (pendingJvmFinalizers == null) pendingJvmFinalizers = new ArrayDeque<>();
            pendingJvmFinalizers.addLast(object);
        }
    }

    /** Deliver notifications on a runtime caller, with a temporary binding and balanced PHP stack. */
    public final void runPendingFinalizers() {
        if (pendingJvmFinalizers != null) { php.runtime.PhpPrimitiveCellRuntime.opaque(this);drainPendingJvmFinalizers(); }
    }

    private void drainPendingJvmFinalizers() {
        if (php.runtime.PhpOwnershipExecution.current(this) != null) return;
        synchronized (gcObjects) {
            if (finalizationComplete || drainingJvmFinalizers || pendingJvmFinalizers == null) return;
            drainingJvmFinalizers = true;
        }
        try {
            ThreadBinding binding;
            synchronized (Thread.currentThread()) {
                synchronized (threadBindings) {
                    if (finalizationComplete) return;
                    binding = bindCurrentThread();
                }
            }
            try (binding) {
                for (;;) {
                    IObject object;
                    synchronized (gcObjects) {
                        if (finalizationComplete || pendingJvmFinalizers == null) return;
                        object = pendingJvmFinalizers.pollFirst();
                        if (object == null) { pendingJvmFinalizers = null; return; }
                    }
                    php.runtime.invoke.PhpObjectLifecycle.destruct(object, this,
                            php.runtime.invoke.PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE);
                }
            }
        } catch (Throwable failure) {
            Environment.<RuntimeException>raiseFinalizerFailure(failure);
        } finally {
            synchronized (gcObjects) { drainingJvmFinalizers = false; }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raiseFinalizerFailure(Throwable failure) throws T { throw (T) failure; }
    private static final AtomicInteger ids = new AtomicInteger();
    private static int nextEnvironmentId() {
        for (;;) {
            int next = ids.get();
            if (next == Integer.MAX_VALUE) throw new IllegalStateException("Environment ID space exhausted");
            if (ids.compareAndSet(next, next + 1)) return next;
        }
    }

    public static void catchThrowable(Throwable failure, Environment env) {
        try { if (env != null && env.reportThreadThrowable(failure)) return; }
        catch (Throwable reportingFailure) { if (reportingFailure != failure) failure.addSuppressed(reportingFailure); }
        hostHandler(Thread.currentThread().getUncaughtExceptionHandler()).uncaughtException(Thread.currentThread(), failure);
    }

    public static void addThreadSupport(Environment env) { addThreadSupport(Thread.currentThread(), env); }

    public static void addThreadSupport(Thread thread, Environment env) {
        synchronized (thread) {
            Thread.UncaughtExceptionHandler installed = thread.getUncaughtExceptionHandler();
            if (installed instanceof BindingHandler && ((BindingHandler) installed).binding.getEnvironment() == env) return;
            env.bindThread(thread, true);
        }
    }

    public Environment(Environment parent) {
        this(parent, parent.defaultBuffer.getOutput());
    }

    public Environment(Environment parent, OutputStream output) {
        this(parent.scope, output);

        try {
            configuration.putAll(parent.configuration);
            //constants.putAll(parent.constants);

            synchronized (parent.classMap) {
                classMap.putAll(parent.classMap);
                legacyClassMap.putAll(parent.legacyClassMap);
                legacyClassKeys.putAll(parent.legacyClassKeys);
            }
            functionMap.putAll(parent.functionMap);
            constantMap.putAll(parent.constantMap);
            moduleManager.apply(parent.moduleManager);
            packageManager.apply(parent.packageManager);
            for (ClassEntity e : classMap.values()) {
                try {
                    e.initEnvironment(this);
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }
        } catch (RuntimeException | Error failure) {
            try { retireThreadBindings(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }

    }

    public Environment(CompileScope scope, OutputStream output) {
        this.scope = scope;

        this.id = nextEnvironmentId();

        this.moduleManager = new ModuleManager(this);
        this.packageManager = new PackageManager(this);

        this.outputBuffers = new Stack<OutputBuffer>();

        this.defaultBuffer = new OutputBuffer(this, null);
        this.defaultBuffer.setOutput(output);

        Stack<OutputBuffer> buffers = this.getOutputBuffers();

        if (buffers.isEmpty()) {
            buffers.push(defaultBuffer);
        }

        this.includePaths = new HashSet<String>();

        this.globals = new ArrayMemory();
        this.statics = new HashMap<String, ReferenceMemory>();

        this.setErrorReportHandler(new ErrorReportHandler() {
            @Override
            public boolean onError(SystemMessage error) {
                Environment.this.echo(error.getDebugMessage());
                Environment.this.echo("\n");
                return false;
            }

            @Override
            public boolean onFatal(ErrorException error) {
                Environment.this.echo("\n");
                Environment.this.echo(error.getType().getTypeName() + ": " + error.getMessage());
                if (error.getTraceInfo() != null) {
                    Environment.this.echo(
                            " in " + error.getTraceInfo().getFileName()
                                    + " on line " + (error.getTraceInfo().getStartLine() + 1)
                                    + ", position " + (error.getTraceInfo().getStartPosition() + 1)
                    );
                }
                return false;
            }
        });

        ReferenceMemory syntheticGlobalsCarrier=this.globals.put("GLOBALS", this.globals);
        this.syntheticGlobalsOrigin=new SyntheticGlobalsOrigin(this,syntheticGlobalsCarrier);
        syntheticGlobalsCarrier.attachSyntheticGlobalsOrigin(this.syntheticGlobalsOrigin);
        this.globals.put("_ENV", ObjectMemory.valueOf(new WrapEnvironmentVariables(this)));

        //classMap.putAll(scope.getClassMap());
        functionMap.putAll(scope.getFunctionMap());
        constantMap.putAll(scope.getConstantMap());

        Memory splAutoloader = new StringMemory("__$jphp_spl_autoload");
        Invoker invoker = Invoker.valueOf(this, null, splAutoloader);
        if (invoker != null)
            this.defaultAutoLoader = new SplClassLoader(invoker, splAutoloader);

        try {
        for (Extension e : scope.extensions.values()) {
            e.onLoad(this);

            if (scope.getLangMode() == LangMode.MODERN) {
                String[] packageNames = e.getPackageNames();

                if (packageNames != null) {

                    Package aPackage = null;
                    for (String packageName : packageNames) {
                        if (aPackage == null) {
                            aPackage = getPackageManager().fetch(packageName);

                            for (Class<?> aClass : e.getClasses()) {
                                aPackage.addClass(ReflectionUtils.getClassName(aClass));
                            }

                            for (CompileFunctionSpec functionSpec : e.getFunctions().values()) {
                                aPackage.addFunction(functionSpec.getName());
                            }

                            for (CompileConstant constant : e.getConstants().values()) {
                                aPackage.addConstant(constant.name);
                            }
                        } else {
                            Package aPackage2 = getPackageManager().fetch(packageName);

                            for (String s : aPackage.getClasses()) aPackage2.addClass(s);
                            for (String s : aPackage.getFunctions()) aPackage2.addFunction(s);
                            for (String s : aPackage.getConstants()) aPackage2.addConstant(s);
                        }
                    }
                }
            }
        }

        constructionBinding = bindThread(Thread.currentThread(), true);
        } catch (RuntimeException | Error failure) {
            try { retireThreadBindings(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private Throwable composeFinalizationFailure(Throwable primary, Throwable cleanup) {
        php.runtime.PhpOwnershipExecution.Session session = php.runtime.PhpOwnershipExecution.current(this);
        if (session != null) return session.domain().graph().composeFailure(primary, cleanup);
        if (primary == null) return cleanup;
        if (cleanup != primary) primary.addSuppressed(cleanup);
        return primary;
    }

    /** Finalize an embedded runtime without CLI reporting or terminating the host JVM. */
    public synchronized void doFinalForEmbedding() throws Throwable {
        Thread previous = embeddingFinalizationThread;
        embeddingFinalizationThread = Thread.currentThread();
        try {
            doFinal();
        } finally {
            embeddingFinalizationThread = previous;
        }
    }

    public void doFinal() throws Throwable {
        if (php.runtime.PhpOwnershipExecution.currentEntry(this) != null
                || runtimeOwnership != null && runtimeOwnership.isExecuting())
            throw new IllegalStateException("Environment finalization inside an owned invocation requires an explicit unwind handoff");
        synchronized (threadBindings) {
            if (finalizationStarted) return;
            finalizationStarted = true;
        }
        boolean embedding = embeddingFinalizationThread == Thread.currentThread();
        Throwable failure = null;
        try (ThreadBinding binding = bindCurrentThread()) {
            for (ShutdownHandler handler : shutdownFunctions) {
                try {
                    handler.call();
                } catch (DieException e) {
                    if (embedding) { failure = e; break; }
                    finalizeObjects();
                    catchUncaught(e);
                    break;
                } catch (BaseBaseException | ErrorException e) {
                    if (embedding) { failure = e; break; }
                    try { reportThreadThrowable(e); }
                    catch (Throwable reportingFailure) { if (reportingFailure != e) e.addSuppressed(reportingFailure); throw e; }
                    break;
                } catch (Exception e) {
                    if (embedding) { failure = e; break; }
                    boolean reported;
                    try { reported = reportThreadThrowable(e); }
                    catch (Throwable reportingFailure) { if (reportingFailure != e) e.addSuppressed(reportingFailure); throw e; }
                    if (!reported) throw e;
                    break;
                } catch (Throwable e) {
                    if (!embedding) throw e;
                    failure = e;
                    break;
                }
            }

            if (embedding) {
                try { finalizeObjects(); }
                catch (Throwable cleanup) {
                    failure = composeFinalizationFailure(failure, cleanup);
                }
                try { releaseOwnedErrorHandlers(); }
                catch (Throwable cleanup) {
                    failure = composeFinalizationFailure(failure, cleanup);
                }
                try { flushAll(); }
                catch (Throwable cleanup) {
                    failure = composeFinalizationFailure(failure, cleanup);
                }
                lastMessage = null;
                if (failure != null) throw failure;
            } else {
                finalizeObjects();
                releaseOwnedErrorHandlers();
                flushAll();
                lastMessage = null;
            }
        } catch (Throwable thrown) {
            if (thrown != failure) failure = composeFinalizationFailure(failure, thrown);
            throw failure;
        } finally {
            try {releaseOwnedErrorHandlers();}
            catch (Throwable cleanup) {
                if (failure == null) {failure=cleanup;throw cleanup;}
                php.runtime.PhpOwnershipExecution.Session session=php.runtime.PhpOwnershipExecution.current(this);
                Throwable composed=session == null ? failure : session.domain().graph().composeFailure(failure,cleanup);
                if (session == null && cleanup != failure) failure.addSuppressed(cleanup);
                if (composed != failure) {failure=composed;throw composed;}
            } finally {
            synchronized (threadBindings) {
                finalizationComplete = true;
                callCacheRows = null;
                enumStates = null;
            }
            synchronized (gcObjects) { pendingJvmFinalizers = null; }
            try { retireThreadBindings(); }
            catch (RuntimeException | Error cleanup) { if (failure == null) throw cleanup; else if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
        }
    }

    /**
     * Remove all method which does not destruct
     *
     * @throws Throwable
     */
    public void finalizeObjects() throws Throwable {
        for (;;) {
            runPendingFinalizers();
            cleanGcObjects();
            List<GcObjectReference> pending;
            synchronized (gcObjects) {
                if (gcObjects.isEmpty()) {
                    if (!drainingJvmFinalizers && pendingJvmFinalizers != null
                            && php.runtime.PhpOwnershipExecution.current(this) == null) continue;
                    return;
                }
                pending = new ArrayList<>(gcObjects);
            }
            // Callbacks run outside the registry monitor and may publish the next batch.
            for (GcObjectReference reference : pending) {
                if (!removeGcObject(reference)) continue;
                IObject object = reference.get();
                if (object != null)
                    php.runtime.invoke.PhpObjectLifecycle.destruct(object, this,
                            php.runtime.invoke.PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE);
            }
        }
    }

    /** Swap removal keeps queue cleanup constant-time without allocating a hash-table node per object. */
    private boolean removeGcObject(GcObjectReference reference) {
        synchronized (gcObjects) {
            int index = reference.index;
            if (index < 0) return false;
            if (gcObjects.get(index) != reference)
                throw new IllegalStateException("GC registry index no longer identifies its reference");
            int lastIndex = gcObjects.size() - 1;
            GcObjectReference last = gcObjects.remove(lastIndex);
            if (index != lastIndex) {
                gcObjects.set(index, last);last.index = index;
            }
            reference.index = -1;
            return true;
        }
    }

    private void cleanGcObjects() throws Throwable {
        Reference<? extends IObject> object;
        while ((object = gcObjectRefQueue.poll()) != null)
            removeGcObject((GcObjectReference)object);
    }

    public void pushCall(CallStackItem stackItem) {
        getCallStack().push(stackItem);
    }

    public CallStackItem pushCall(TraceInfo trace, IObject self, Memory[] args, String function, String clazz, String staticClazz) {
        return getCallStack().push(trace, self, args, function, clazz, staticClazz);
    }

    public CallStackItem pushCall(IObject self, String method, Memory... args) {
        return getCallStack().push(self, method, args);
    }

    public CallStackItem pushCall(TraceInfo trace, IObject self, String method, Memory... args) {
        return getCallStack().push(trace, self, method, args);
    }

    public CallStackItem popCall() {
        return getCallStack().pop();
    }

    public CallStackItem peekCall(int depth) {
        return getCallStack().peekCall(depth);
    }

    public TraceInfo trace() {
        return getCallStack().trace();
    }

    public TraceInfo trace(int systemOffsetStackTrace) {
        return getCallStack().trace(systemOffsetStackTrace);
    }

    public int getCallStackTop() {
        return getCallStack().getTop();
    }

    public CallStackItem[] getCallStackSnapshot() {
        return getCallStack().getSnapshot();
    }

    public Environment(OutputStream output) {
        this(new CompileScope(), output);
    }

    public Environment(CompileScope scope) {
        this(scope, null);
    }

    public Environment() {
        this((OutputStream) null);
    }

    public Stack<OutputBuffer> getOutputBuffers() {
        return outputBuffers;
    }

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public String findInIncludePaths(String path) {
        if (new File(path).exists())
            return path;

        for (String e : includePaths)
            if (new File(e, path).exists())
                return e + path;

        return null;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public PackageManager getPackageManager() {
        return packageManager;
    }

    public CompileScope getScope() {
        return scope;
    }

    public Collection<FunctionEntity> getFunctions() {
        return functionMap.values();
    }

    public Collection<ClassEntity> getClasses() {
        return classMap.values();
    }

    public ArrayMemory getGlobals() {
        return globals;
    }

    public Charset getDefaultCharset() {
        return defaultCharset;
    }

    public Set<String> getIncludePaths() {
        return includePaths;
    }

    public void addIncludePath(String value) {
        includePaths.add(value);
    }

    public void setIncludePaths(Set<String> includePaths) {
        this.includePaths = includePaths;
    }

    public Map<String, ClassEntity> getLoadedClasses() {
        return classMap;
    }

    public Map<String, FunctionEntity> getLoadedFunctions() {
        return functionMap;
    }

    public boolean isLoadedClass(String lowerName) {
        ClassLookupContext active = classLookupModern.get();
        String original = active != null && active.caller == php.runtime.PhpPropertyWriteContext.current(this)
                && active.originalName != null && lowerName.equals(PhpClassNames.key(active.originalName,
                active.modern ? PhpLanguageProfile.PHP_8_5 : PhpLanguageProfile.JPHP_LEGACY)) ? active.originalName : lowerName;
        return lookupClass(original, PhpClassNames.isModern(this)) != null;
    }

    public boolean isLoadedClass(String originalName, PhpLanguageProfile profile) {
        ClassLookupContext previous = classLookupModern.get();
        classLookupModern.set(new ClassLookupContext(profile == PhpLanguageProfile.PHP_8_5,
                php.runtime.PhpPropertyWriteContext.current(this), originalName));
        try { return isLoadedClass(PhpClassNames.key(originalName, profile)); }
        finally { if (previous == null) classLookupModern.remove(); else classLookupModern.set(previous); }
    }

    public boolean isLoadedFunction(String lowerName) {
        return functionMap.containsKey(lowerName);
    }

    public boolean isLoadedConstant(String lowerName) {
        return constantMap.containsKey(lowerName);
    }

    private final Set<String> autoloadLocks = new HashSet<String>();

    public ClassEntity autoloadCall(String name, String lowerName) {
        return autoloadCall(name, lowerName, PhpClassNames.isModern(this));
    }

    private ClassEntity autoloadCall(String name, String lowerName, boolean modern) {
        synchronized (autoloadLocks) {
            // detect recursion in autoload
            if (StringUtils.isValidClassName(name) && autoloadLocks.add(lowerName)) {
                try {
                    StringMemory tmp = new StringMemory(name);

                    for (SplClassLoader loader : classLoaders) {
                        loader.load(tmp);

                        ClassEntity classEntity = fetchClass(name, false, modern ? PhpLanguageProfile.PHP_8_5 : PhpLanguageProfile.JPHP_LEGACY);

                        if (classEntity != null) {
                            return classEntity;
                        }
                    }

                    if (defaultAutoLoader != null) {
                        defaultAutoLoader.load(tmp);
                    }

                    return fetchClass(name, false, modern ? PhpLanguageProfile.PHP_8_5 : PhpLanguageProfile.JPHP_LEGACY);
                } finally {
                    autoloadLocks.remove(lowerName);
                }
            } else {
                return null;
            }
        }
    }

    public ClassEntity fetchClass(Class<?> clazz) {
        ClassEntity result = fetchClass(ReflectionUtils.getClassName(clazz), false);

        if (result == null)
            throw new CriticalException("Native class is not registered - " + clazz.getName());

        return result;
    }

    public ClassEntity fetchClass(String name) {
        return fetchClass(name, false);
    }

    public ClassEntity fetchClass(String name, boolean autoLoad) {
        return fetchClass(name, name.toLowerCase(), autoLoad);
    }

    public ClassEntity fetchMagicClass(String name) {
        return fetchMagicClass(name, name.toLowerCase());
    }

    public ClassEntity fetchMagicClass(String name, String nameL) {
        if ("self".equals(nameL)) {
            ClassEntity e = getContextClass();
            if (e == null)
                e = getLateStaticClass();
            return e;
        } else if ("static".equals(nameL)) {
            return getLateStaticClass();
        }/* else if ("parent".equals(nameL)){
            ClassEntity e = getContextClass();
            if (e == null)
                e = getLateStaticClass();
            return e == null ? null : e.getParent();
        }*/ else
            return null;
    }

    /** Explicit compiler lookups survive virtual autoload hooks; executing PHP modules take precedence. */
    public boolean isModernClassLookup() {
        var frame = php.runtime.PhpPropertyWriteContext.current(this);
        ClassLookupContext active = classLookupModern.get();
        if (active != null && active.caller == frame) return active.modern;
        if (frame != null) return getModuleManager().getPhpLanguageProfile(frame.trace) == PhpLanguageProfile.PHP_8_5;
        return scope.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
    }

    private ClassEntity dispatchAutoload(String name, String lowerName, boolean modern) {
        ClassLookupContext previous = classLookupModern.get();
        classLookupModern.set(new ClassLookupContext(modern, php.runtime.PhpPropertyWriteContext.current(this), null));
        try { return autoloadCall(name, lowerName); }
        finally { if (previous == null) classLookupModern.remove(); else classLookupModern.set(previous); }
    }

    private ClassEntity lookupClass(String name, boolean modern) {
        if(modern) {ClassEntity builtin=scope.fetchPhp85Class(name);if(builtin!=null) return builtin;}
        ClassEntity entity = classMap.get(PhpClassNames.canonicalKey(name));
        return modern || PhpClassNames.isModern(entity) ? entity : legacyClassMap.get(name.toLowerCase());
    }

    private boolean hasClassCollision(String name, ClassEntity entity) {
        String key = PhpClassNames.canonicalKey(name);
        return classMap.containsKey(key)
                || !PhpClassNames.isModern(entity) && legacyClassMap.containsKey(name.toLowerCase());
    }

    private void publishClass(String name, ClassEntity entity) {
        String key = PhpClassNames.canonicalKey(name);
        ClassEntity previous = classMap.put(key, entity);
        String previousLegacyKey = legacyClassKeys.remove(key);
        if (previousLegacyKey != null) legacyClassMap.remove(previousLegacyKey, previous);
        if (!PhpClassNames.isModern(entity)) {
            String legacyKey = name.toLowerCase();
            legacyClassMap.put(legacyKey, entity); legacyClassKeys.put(key, legacyKey);
        }
    }

    public ClassEntity fetchClass(String name, boolean autoLoad, PhpLanguageProfile profile) {
        ClassLookupContext previous = classLookupModern.get();
        classLookupModern.set(new ClassLookupContext(profile == PhpLanguageProfile.PHP_8_5,
                php.runtime.PhpPropertyWriteContext.current(this), null));
        try { return fetchClass(name, PhpClassNames.key(name, profile), autoLoad); }
        finally { if (previous == null) classLookupModern.remove(); else classLookupModern.set(previous); }
    }

    public ClassEntity fetchClass(String name, String nameL, boolean autoLoad) {
        return fetchClass(name, nameL, autoLoad, PhpClassNames.isModern(this));
    }

    private ClassEntity fetchClass(String name, String nameL, boolean autoLoad, boolean modern) {
        nameL = modern ? PhpClassNames.canonicalKey(name) : name.toLowerCase();
        ClassEntity entity = lookupClass(name, modern);

        if (entity != null) {
            return entity;
        }

        synchronized (classMap) {
            entity = lookupClass(name, modern); // try retry!

            if (entity == null) {
                entity = scope.fetchUserClass(name, nameL, modern);

                if (entity != null) {
                    // A folded legacy query is not a new alias. Scope aliases retain their live Java registration binding.
                    boolean canonicalAlias = scope.getClassMap().get(PhpClassNames.canonicalKey(name)) == entity;
                    ClassEntity shadow = classMap.get(PhpClassNames.canonicalKey(entity.getName()));
                    if (!canonicalAlias && shadow != null && shadow != entity && PhpClassNames.isModern(shadow)) return null;
                    try {
                        entity.initEnvironment(this);
                    } catch (Throwable throwable) {
                        throw new CriticalException(throwable);
                    }
                    if (!scope.isPhp85Builtin(entity) && (shadow == null || shadow == entity)) publishClass(entity.getName(), entity);
                    return entity;
                }

                ClassEntity classEntity = autoLoad ? dispatchAutoload(name, nameL, modern) : null;

                return classEntity;
            } else {
                return entity;
            }
        }
    }

    public FunctionEntity fetchFunction(String name) {
        return fetchFunction(name, name.toLowerCase());
    }

    public FunctionEntity fetchFunction(String name, String nameL) {
        if(isModernClassLookup()) {FunctionEntity builtin=scope.fetchPhp85Function(name);if(builtin!=null) return builtin;}
        FunctionEntity r = functionMap.get(nameL);
        if (r == null) {
            r = scope.findUserFunction(name);
        }

        return r;
    }

    public void registerFunction(FunctionEntity entity) {
        synchronized (functionMap) {
            if (functionMap.containsKey(entity.getLowerName()) || entity.getModule()!=null
                    && entity.getModule().getPhpLanguageProfile()==PhpLanguageProfile.PHP_8_5 && scope.fetchPhp85Function(entity.getName())!=null)
                exception("Function '%s' already registered", entity.getName());

            functionMap.put(entity.getLowerName(), entity);
        }
    }

    /** Alias publication is local to this Environment and retains the actual declaration identity. */
    public boolean registerClassAlias(String name,ClassEntity entity,PhpLanguageProfile profile) {
        synchronized (classMap) {
            if (hasClassCollision(name,entity) || fetchClass(name,false,profile)!=null) return false;
            publishClass(name,entity);
            return true;
        }
    }

    public void registerClass(ClassEntity entity) {
        synchronized (classMap) {
            if (hasClassCollision(entity.getName(), entity))
                exception("Class '%s' already registered", entity.getName());

            publishClass(entity.getName(), entity);
            entity.initEnvironment(this);
        }
    }

    public Map<String, ConstantEntity> getConstants() {
        return constantMap;
    }

    public Memory findConstant(String name, String nameLower) {
        ConstantEntity entity = constantMap.get(nameLower);
        if (entity != null) {
            if (!entity.caseSensitise || name.equals(entity.getName()))
                return entity.getValue();
        }

        CompileConstant constant = scope.findCompileConstant(name);
        if (constant != null && "E_ALL".equals(name)) {
            var frame = php.runtime.PhpPropertyWriteContext.current(this);
            if (frame != null) constant = scope.findCompileConstant(name, moduleManager.getPhpLanguageProfile(frame.trace));
        }
        if (constant != null)
            return constant.value;

        ConstantEntity scoped = scope.findUserConstant(name);
        if (scoped != null && (!scoped.caseSensitise || name.equals(scoped.getName())))
            return scoped.getValue();
        return null;
    }

    public Memory findConstant(String name) {
        return findConstant(name, name.toLowerCase());
    }

    public boolean defineDeclaredConstant(String moduleName,String name,Memory value) {
        ModuleEntity module=scope.getClassLoader().getModule(moduleName);
        ConstantEntity declaration=module==null ? null : module.getDeferredGlobalConstantDeclaration(name);
        if(declaration==null) throw new php.runtime.exceptions.CriticalException("Missing deferred global constant declaration");
        if(findConstant(name)!=null) {
            error(declaration.getTrace(), E_WARNING, "Constant %s already defined, this will be an error in PHP 9", name);
            return false;
        }
        ConstantEntity constant=new ConstantEntity(name,value,true);
        constant.setModule(module);constant.setContext(declaration.getContext());constant.setTrace(declaration.getTrace());
        constant.setDocComment(declaration.getDocComment());constant.setPhpAttributes(declaration.getPhpAttributes());
        constantMap.put(name.toLowerCase(java.util.Locale.ROOT),constant);return true;
    }
    public boolean defineConstant(String name, Memory value, boolean caseSensitise) {
        Memory constant = findConstant(name);
        if (constant != null)
            return false;

        constantMap.put(name.toLowerCase(), new ConstantEntity(name, value, caseSensitise));
        return true;
    }

    public Memory getConfigValue(String name, Memory defaultValue) {
        Memory result = null;
        if (scope.configuration == null || (result = configuration.get(name)) != null) {
            if (result == null) result = configuration.get(name);
            if (result == null)
                return defaultValue;

            return result;
        }

        result = scope.configuration.get(name);
        return result == null ? defaultValue : result;
    }

    public Memory getConfigValue(String name) {
        return getConfigValue(name, null);
    }

    public ArrayMemory getConfigValues(String prefix, boolean includingGlobal) {
        if (prefix != null)
            prefix = prefix + ".";

        ArrayMemory result = new ArrayMemory();
        if (includingGlobal)
            if (scope.configuration != null) {
                for (Map.Entry<String, Memory> entry : scope.configuration.entrySet()) {
                    String key = entry.getKey();
                    if (prefix != null && !key.startsWith(prefix)) continue;

                    result.put(key, entry.getValue().toImmutable());
                }
            }

        for (Map.Entry<String, Memory> entry : configuration.entrySet()) {
            String key = entry.getKey();

            if (prefix != null && !key.startsWith(prefix)) continue;
            result.put(key, entry.getValue().toImmutable());
        }
        return result;
    }

    public Memory setConfigValue(String name, Memory value) {
        value = value.toValue();
        if (!value.isString())
            value = new StringMemory(value.toString());   // fix capability with zend php

        ConfigChangeHandler handler = configurationHandler.get(name);
        if (handler != null)
            handler.onChange(this, value);

        return configuration.put(name, value);
    }

    public void restoreConfigValue(String name) {
        configuration.remove(name);

        ConfigChangeHandler handler = configurationHandler.get(name);
        if (handler != null)
            handler.onChange(this, getConfigValue(name));
    }

    public Memory getOrCreateGlobal(String name) {
        return globals.refOfIndex(name);
    }

    public Memory getOrCreateStatic(String name, Memory initValue) {
        ReferenceMemory result = statics.get(name);
        if (result == null) {
            result = new ReferenceMemory(initValue);
            statics.put(name, result);
        }
        return result; // globals.getByScalarOrCreate(name, initValue);
    }

    public Object getClassInitializationLock() { return classMap; }

    public Memory getStatic(String name) {
        return statics.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserValue(String name, Class<T> clazz) {
        return (T) userValues.get(name);
    }

    public void setUserValue(String name, Object value) {
        userValues.put(name, value);
    }

    public <T> T getUserValue(Class<T> clazz) {
        Objects.requireNonNull(clazz);

        return getUserValue(clazz.getName(), clazz);
    }

    public <T> void setUserValue(T object) {
        Objects.requireNonNull(object);

        setUserValue(object.getClass().getName(), object);
    }

    public boolean removeUserValue(String name) {
        return userValues.remove(name) != null;
    }

    public int getErrorFlags() {
        return errorFlags;
    }

    public void setErrorFlags(int errorFlags) {
        this.errorFlags = errorFlags;
    }

    public SystemMessage getLastMessage() {
        return lastMessage;
    }

    public ShellExecHandler getShellExecHandler() {
        return shellExecHandler;
    }

    public void setShellExecHandler(ShellExecHandler shellExecHandler) {
        this.shellExecHandler = shellExecHandler;
    }

    public ExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        if (previousExceptionHandlers == null) previousExceptionHandlers = new ArrayList<>();
        previousExceptionHandlers.add(this.exceptionHandler);
        this.exceptionHandler = exceptionHandler == null ? ExceptionHandler.DEFAULT : exceptionHandler;
    }

    public ErrorReportHandler getErrorReportHandler() {
        return errorReportHandler;
    }

    public void setErrorReportHandler(ErrorReportHandler errorReportHandler) {
        this.errorReportHandler = errorReportHandler;
    }

    public ExceptionHandler getPreviousExceptionHandler() {
        return previousExceptionHandlers == null || previousExceptionHandlers.isEmpty() ? null
                : previousExceptionHandlers.get(previousExceptionHandlers.size() - 1);
    }

    /** Restore consumes one prior registration; an empty stack keeps the built-in handler. */
    public void restoreExceptionHandler() {
        exceptionHandler = previousExceptionHandlers == null || previousExceptionHandlers.isEmpty() ? ExceptionHandler.DEFAULT
                : previousExceptionHandlers.remove(previousExceptionHandlers.size() - 1);
    }

    public ErrorHandler getPreviousErrorHandler() {
        return previousErrorHandlers == null || previousErrorHandlers.isEmpty() ? null
                : previousErrorHandlers.get(previousErrorHandlers.size() - 1);
    }

    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    protected void triggerError(ErrorException err) {
        ErrorType type = err.getType();
        if (type.isFatal() || isHandleErrors(type)) {
            lastMessage = new CustomSystemMessage(
                    err.getType(), new CallStackItem(err.getTraceInfo()), new Messages.Item(err.getMessage())
            );
            throw err;
        }
    }

    public void forwardThrow(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        } else {
            throw new RuntimeException(throwable);
        }
    }

    public void wrapThrow(Throwable throwable) {
        if (throwable instanceof Exception) {
            catchUncaught((Exception) throwable);
        } else {
            throw new RuntimeException(throwable);
        }
    }

    public boolean catchUncaught(Exception e) {
        return catchUncaught(e, false);
    }

    public boolean catchUncaught(Exception e, boolean retry) {
        if (e instanceof UncaughtException)
            return catchUncaught((UncaughtException) e);
        else if (e instanceof DieException) {
            System.exit(((DieException) e).getExitCode());
            return true;
        } else if (e instanceof ErrorException) {
            ErrorException er = (ErrorException) e;

            getErrorReportHandler().onFatal(er);

            JVMStackTracer tracer = scope.getStackTracer(e);

            int i = 0;
            for (JVMStackTracer.Item el : tracer) {
                if (!el.isInternal()) {
                    echo("\n\t #" + (i++) + " " + el);
                }
            }

            echo("\n");

            for (JVMStackTracer.Item el : tracer) {
                if (!el.isSystem()) {
                    echo("\n\t " + (el.isInternal() ? "" : "->") + " " + el);
                }
            }

            return true;
        } else if (e instanceof FinallyException) {
            // nop
            return true;
        } else if (e instanceof BaseBaseException) {
            BaseBaseException be = (BaseBaseException) e;

            if (exceptionHandler != null) {
                try {
                    exceptionHandler.onException(this, be);
                } catch (BaseBaseException _e) {
                    if (retry) {
                        throw new RuntimeException(_e);
                    } else {
                        catchUncaught(_e, true);
                    }
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
                return true;
            } else {
                try {
                    ExceptionHandler.DEFAULT.onException(this, be);
                    return true;
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }
        } else {
            throw new RuntimeException(e);
        }
    }

    public boolean catchUncaught(UncaughtException e) {
        if (exceptionHandler != null) {
            try {
                if (!(e.getException() instanceof FinallyException)) {
                    exceptionHandler.onException(this, e.getException());
                }
            } catch (BaseBaseException _e) {
                catchUncaught(_e);
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
            return true;
        } else
            return false;
    }

    public void error(TraceInfo trace, ErrorType type, String message, Object... args) {
        error(trace, type, new Messages.Item(message), args);
    }

    public void error(TraceInfo trace, ErrorType type, Messages.Item message, Object... args) {
        if (type.isFatal()) {
            if (scope.getLangMode() == LangMode.MODERN) {
                if (type == E_PARSE) {
                    exception(trace, new BaseParseError(this), message.fetch(args));
                } else {
                    exception(trace, new BaseError(this, type), message.fetch(args));
                }
            }

            if (type.isHandled() && errorHandler != null && ErrorType.check(errorHandler.errorHandlerFlags, type)) {
                triggerMessage(new CustomSystemMessage(type, new CallStackItem(trace), message, args));
            } else
                triggerError(new CustomErrorException(type, message.fetch(args), trace));
        } else {
            triggerMessage(new CustomSystemMessage(type, new CallStackItem(trace), message, args));
        }
    }

    public void error(ErrorType type, String message, Object... args) {
        error(trace(), type, message, args);
    }

    public void error(TraceInfo trace, String message, Object... args) {
        error(trace, E_ERROR, message, args);
    }


    /** Trusted PHP85 lexer diagnostics are nonfatal and never invoke the PHP error handler. */
    public void reportPhp85CompileWarning(SystemMessage diagnostic) {
        if (diagnostic.getType() != E_COMPILE_WARNING)
            throw new IllegalArgumentException("Expected lexical compile warning");
        lastMessage = diagnostic;
        if (errorReportHandler != null && isHandleErrors(E_COMPILE_WARNING))
            errorReportHandler.onError(diagnostic);
    }

    public void triggerMessage(SystemMessage message) {
        lastMessage = message;
        if (errorHandler != null) {
            if (dispatchErrorHandler(message))
                return;
        }

        if (errorReportHandler != null && isHandleErrors(message.getType()))
            errorReportHandler.onError(message);
    }

    public void exception(TraceInfo trace, String message, Object... args) {
        BaseException e = new BaseException(this);
        exception(trace, e, message, args);
    }

    public void exception(String message, Object... args) {
        exception(trace(), message, args);
    }

    public void exception(TraceInfo trace, BaseError e, String message, Object... args) {
        __clearSilent();

        if (args == null || args.length == 0) {
            e.__construct(this, new StringMemory(
                    message
            ));
        } else {
            e.__construct(this, new StringMemory(
                    String.format(message, args)
            ));
        }

        e.setTraceInfo(this, trace);
        throw e;
    }

    public void exception(TraceInfo trace, BaseException e, String message, Object... args) {
        __clearSilent();

        if (args == null || args.length == 0) {
            e.__construct(this, new StringMemory(
                    message
            ));
        } else {
            e.__construct(this, new StringMemory(
                    String.format(message, args)
            ));
        }

        e.setTraceInfo(this, trace);
        throw e;
    }

    public void exception(BaseException e, String message, Object... args) {
        exception(trace(), e, message, args);
    }

    public void exception(BaseError e, String message, Object... args) {
        exception(trace(), e, message, args);
    }

    public void exception(Class<? extends BaseBaseException> e, String message, Object... args) {
        exception(trace(), e, message, args);
    }

    public void exception(TraceInfo trace, Class<? extends BaseBaseException> e, String message, Object... args) {
        ClassEntity entity = fetchClass(e);
        var ownedExecution=php.runtime.PhpOwnershipExecution.current(this);
        if(ownedExecution!=null && e==php.runtime.lang.exception.BaseTypeError.class) {
            try {throw php.runtime.PhpOwnershipThrowables.typeError(ownedExecution,entity,trace,String.format(message,args));}
            catch(Throwable failure) {Environment.<RuntimeException>throwThreadFailure(failure);return;}
        }
        IObject object = entity.newObjectWithoutConstruct(this);

        if (object instanceof BaseException) {
            exception(trace, (BaseException) object, message, args);
        } else if (object instanceof BaseError) {
            exception(trace, (BaseError) object, message, args);
        } else {
            throw new CriticalException("Unable to create extension object from class " + e.getName());
        }
    }

    public boolean isHandleErrors(ErrorType type) {
        return ErrorType.check(errorFlags, type);
    }

    public void warning(String message, Object... args) {
        triggerMessage(new WarningMessage(peekCall(0), new Messages.Item(message), args));
    }

    public void warning(TraceInfo trace, String message, Object... args) {
        error(trace, E_WARNING, message, args);
    }

    public void warning(TraceInfo trace, Messages.Item message, Object... args) {
        error(trace, E_WARNING, message, args);
    }

    public void notice(String message, Object... args) {
        triggerMessage(new NoticeMessage(peekCall(0), new Messages.Item(message), args));
    }

    public OutputBuffer getDefaultBuffer() {
        return defaultBuffer;
    }

    public OutputBuffer pushOutputBuffer(Memory callback, int chunkSize, boolean erase) {
        Stack<OutputBuffer> outputBuffers = getOutputBuffers();

        OutputBuffer buffer = new OutputBuffer(this, peekOutputBuffer(), callback, chunkSize, erase);
        buffer.setLevel(outputBuffers.size());
        buffer.setType(OutputBuffer.Type.USER);

        outputBuffers.push(buffer);
        return buffer;
    }

    public OutputBuffer popOutputBuffer() throws Throwable {
        Stack<OutputBuffer> outputBuffers = getOutputBuffers();

        if (outputBuffers.empty()) return null;
        if (outputBuffers.peek().isRoot()) return null;

        OutputBuffer result = outputBuffers.pop();
        result.close();
        return result;
    }

    public List<OutputBuffer> allOutputBuffers() {
        Stack<OutputBuffer> outputBuffers = getOutputBuffers();

        List<OutputBuffer> result = new ArrayList<OutputBuffer>();
        for (OutputBuffer el : outputBuffers)
            result.add(el);

        return result;
    }

    public OutputBuffer peekOutputBuffer() {
        Stack<OutputBuffer> outputBuffers = getOutputBuffers();

        return outputBuffers.empty() ? null : outputBuffers.peek();
    }

    public void echo(byte[] bytes, int length) {
        OutputBuffer buffer = peekOutputBuffer();
        if (buffer != null)
            try {
                buffer.write(bytes, length);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
    }

    public void echo(Memory value) {
        OutputBuffer buffer = peekOutputBuffer();
        if (buffer != null)
            try {
                buffer.write(value);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
    }

    public void echo(String value) {
        OutputBuffer buffer = peekOutputBuffer();
        if (buffer != null)
            try {
                buffer.write(value);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
    }

    public void flushAll() throws Throwable {
        while (popOutputBuffer() != null) ;
        defaultBuffer.close();
    }

    public ModuleEntity importCompiledModule(Context context, boolean debugInformation) throws Throwable {
        String moduleName = context.getModuleName();
        ModuleEntity module;
        synchronized (scope) {
            module = moduleName == null ? null : scope.findCachedModule(moduleName, true, context.getPhpLanguageProfile(scope));
            if (module == null) {
                ModuleDumper moduleDumper = new ModuleDumper(context, this, debugInformation);
                module = moduleDumper.load(context.getInputStream(getDefaultCharset()));
            }
            scope.loadModule(module);
            scope.addUserModule(module);
        }

        registerModule(module);
        return module;
    }

    public ModuleEntity importModule(Context context) throws Throwable {
        context.freezeImplicitClassImports();
        String moduleName = context.getModuleName();
        ModuleEntity module;
        synchronized (scope) {
            module = moduleName == null ? null : scope.findCachedModule(moduleName, false,
                    context.getPhpLanguageProfile(scope), context.getImplicitClassImportsFingerprint());
            if (module == null) {
                AbstractCompiler compiler = scope.createCompiler(this, context);
                module = compiler.compile(false);
            }
            scope.loadModule(module);
            scope.addUserModule(module);
        }

        registerModule(module);
        return module;
    }

    public Memory eval(String code) throws Throwable {
        return eval(code, getGlobals());
    }

    public Memory eval(String code, ArrayMemory locals) throws Throwable {
        return eval(code, locals, scope.getPhpLanguageProfile());
    }

    public Memory eval(String code, ArrayMemory locals, php.runtime.common.PhpLanguageProfile profile) throws Throwable {
        Context context = new Context(code);
        context.setPhpLanguageProfile(profile);
        AbstractCompiler compiler = scope.createCompiler(this, context);
        
        ModuleEntity module = compiler.compile();
        scope.loadModule(module);
        registerModule(module);

        return module.include(this, locals);
    }

    public void registerSourceMap(SourceMap sourceMap) {
        sourceMaps.put(sourceMap.getModuleName(), sourceMap);
    }

    public void unregisterSourceMap(SourceMap sourceMap) {
        sourceMaps.remove(sourceMap.getModuleName());
    }

    public void registerModule(ModuleEntity module) {
        registerModule(module, false);
    }

    public void registerModule(ModuleEntity module, boolean ignoreErrors) {
        synchronized (classMap) {
            // Validate the complete class batch before publishing either index.
            if (!ignoreErrors) for (ClassEntity entity : module.getClasses()) {
                if (entity.isStatic() && hasClassCollision(entity.getName(), entity)) {
                    error(entity.getTrace(), Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(entity.getName()));
                }
            }
            for (ClassEntity entity : module.getClasses()) {
                if (entity.isStatic()) {
                    entity.setModule(module);
                    publishClass(entity.getName(), entity);
                    entity.register(this);
                }
            }
        }

        for (FunctionEntity entity : module.getFunctions()) {
            if (entity.isStatic()) {
                entity.setModule(module);

                if (functionMap.put(entity.getLowerName(), entity) != null && !ignoreErrors) {
                    error(entity.getTrace(), Messages.ERR_CANNOT_REDECLARE_FUNCTION.fetch(entity.getName()));
                }

                entity.register(this);
            }
        }

        for (ConstantEntity entity : module.getConstants()) {
            entity.setModule(module);

            if (constantMap.put(entity.getLowerName(), entity) != null && !ignoreErrors) {
                error(entity.getTrace(), Messages.ERR_CANNOT_REDECLARE_CONSTANT.fetch(entity.getName()));
            }

            entity.register(this);
        }
    }

    /***** UTILS *****/
    public void __tick(TraceInfo trace, ArrayMemory locals) {
        TickHandler tickHandler = scope.getTickHandler();

        if (tickHandler != null) {
            IObject $this = this.getLateObject();

            if ($this != null) {
                Memory value = ObjectMemory.valueOf($this);

                if ($this instanceof Closure) {
                    value = ((Closure) $this).getSelf();
                }

                if (value.isObject()) {
                    locals.putAsKeyString("this", value);
                }
            }

            tickHandler.onTick(this, trace, locals);
        }
    }

    /** Successful FETCH_CONSTANT resolution is cached per emitted site and Environment, never per JVM class. */
    public Memory __getConstant(String name, String lowerName, TraceInfo trace, boolean fallback, String siteKey) {
        boolean cacheResolution = fallback && name.indexOf(Information.NAMESPACE_SEP_CHAR) >= 0;
        Memory cached = cacheResolution ? getStatic(siteKey) : null;
        if (cached != null) return findConstant(cached.toString());
        String resolved = name;
        Memory constant = findConstant(name, lowerName);
        if (constant == null && fallback) {
            int p = name.lastIndexOf(Information.NAMESPACE_SEP_CHAR);
            if (p > -1) {
                resolved = name.substring(p + 1);
                constant = findConstant(resolved, lowerName.substring(p + 1));
            }
        }
        if (constant == null) {
            exception(trace, php.runtime.lang.exception.BaseError.class, "Undefined constant \"%s\"", name);
            return Memory.NULL;
        }
        // Retain only the immutable resolved name, avoiding an extra owner of object/closure constants.
        if (cacheResolution) getOrCreateStatic(siteKey, StringMemory.valueOf(resolved));
        return constant;
    }

    public Memory __getConstant(String name, String lowerName, TraceInfo trace) {
        Memory constant = findConstant(name, lowerName);

        if (constant == null) {
            int p = name.lastIndexOf(Information.NAMESPACE_SEP_CHAR);

            if (p > -1) // for global scope
            {
                name = name.substring(p + 1);
                lowerName = lowerName.substring(p + 1);
                constant = findConstant(name, lowerName);
            }
        }

        if (constant == null) {
            if (moduleManager.getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                exception(trace, php.runtime.lang.exception.BaseError.class, "Undefined constant \"%s\"", name);
                return Memory.NULL;
            }
            error(trace, E_NOTICE, Messages.ERR_USE_UNDEFINED_CONSTANT, name, name);
            return StringMemory.valueOf(name);
        }

        return constant;
    }

    /*public Memory __getConstant(String name, String lowerName, TraceInfo trace){
        Memory constant = findConstant(name, lowerName);

        if (constant == null){
            error(trace, E_NOTICE, Messages.ERR_USE_UNDEFINED_CONSTANT, name, name);
            int p = name.lastIndexOf(Information.NAMESPACE_SEP_CHAR);

            if (p > -1) // for global scope
                return StringMemory.valueOf(name.substring(p + 1));
            else
                return StringMemory.valueOf(name);
        }

        return constant;
    }*/

    private Memory __import(String path, ArrayMemory locals, TraceInfo trace, String funcName, boolean once, Callback<Void, Void> callback)
            throws Throwable {
        synchronized (moduleManager) {
            if (once && moduleManager.isIncluded(path)) {
                return Memory.TRUE;
            }

            ModuleEntity module = moduleManager.fetchModule(path, path.endsWith(".phb"), moduleManager.getPhpLanguageProfile(trace));

            if (module == null) {
                callback.call(null);
                return Memory.FALSE;
            }

            moduleManager.markIncluded(path);

            CallStackItem stackItem = pushCall(
                    trace, null, new Memory[]{StringMemory.valueOf(path)}, funcName, null, null
            );

            try {
                if (locals == null) {
                    locals = new ArrayMemory();
                }

                return module.include(this, locals);
            } finally {
                popCall();
            }
        }
    }

    public Memory __include(String path) throws Throwable {
        return __include(path, globals, null);
    }

    public Memory __includeOnce(final String path, ArrayMemory locals, final TraceInfo trace)
            throws Throwable {
        return __import(path, locals, trace, "include_once", true, new Callback<Void, Void>() {
            @Override
            public Void call(Void param) {
                warning(trace, Messages.ERR_INCLUDE_FAILED, "include_once", path);
                return null;
            }
        });
    }

    public Memory __include(final String fileName, ArrayMemory locals, final TraceInfo trace)
            throws Throwable {
        return __import(fileName, locals, trace, "include", false, new Callback<Void, Void>() {
            @Override
            public Void call(Void param) {
                warning(trace, Messages.ERR_INCLUDE_FAILED, "include", fileName);
                return null;
            }
        });
    }

    public Memory __require(final String fileName, ArrayMemory locals, final TraceInfo trace)
            throws Throwable {
        return __import(fileName, locals, trace, "require", false, new Callback<Void, Void>() {
            @Override
            public Void call(Void param) {
                error(trace, Messages.ERR_REQUIRE_FAILED.fetch("require", fileName));
                return null;
            }
        });
    }

    public Memory __requireOnce(final String fileName, ArrayMemory locals, final TraceInfo trace)
            throws Throwable {
        return __import(fileName, locals, trace, "require_once", true, new Callback<Void, Void>() {
            @Override
            public Void call(Void param) {
                error(trace, Messages.ERR_REQUIRE_FAILED.fetch("require_once", fileName));
                return null;
            }
        });
    }

    public void registerObjectInGC(IObject object) {
        ClassEntity entity = object.getReflection();

        if (entity != null && entity.methodDestruct != null) {
            try {
                cleanGcObjects();
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }

            synchronized (gcObjects) {
                GcObjectReference reference = new GcObjectReference(object, gcObjectRefQueue);
                gcObjects.add(reference);reference.index = gcObjects.size() - 1;
            }
        }
        runPendingFinalizers();
    }

    public Memory __newObject(String originName, String lowerName, TraceInfo trace, Memory[] args,
                              ClassCallCache cache, int cacheIndex) throws Throwable {
        if (getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5) {
            return php.runtime.invoke.PhpCallFrame.construct(new StringMemory(originName), this, trace, cache, cacheIndex,
                    args == null ? 0 : args.length).invokePrepared(args);
        }
        ClassEntity entity = null;

        boolean cached = false;

        if (cache != null) {
            entity = cache.get(this, cacheIndex);
        }

        if (entity == null) {
            entity = fetchClass(originName, lowerName, true);

            if (entity != null && cache != null) {
                cache.put(this, cacheIndex, entity);
            }
        } else {
            cached = true;
        }

        if (entity == null) {
            error(trace, E_ERROR, Messages.ERR_CLASS_NOT_FOUND.fetch(originName));
            return Memory.NULL;
        }

        IObject object = entity.newObject(this, trace, true, !cached, args);

        registerObjectInGC(object);

        return new ObjectMemory(object);
    }

    public Memory __newObject(String originName, String lowerName, TraceInfo trace, Memory[] args)
            throws Throwable {
        return __newObject(originName, lowerName, trace, args, null, 0);
    }

    private static ForeachIterator invalidIterator = new ForeachIterator(false, false, false) {
        @Override
        protected boolean init() {
            return false;
        }

        @Override
        protected boolean nextValue() {
            return false;
        }

        @Override
        protected boolean prevValue() {
            return false;
        }

        @Override
        public void reset() {
        }
    };

    public ForeachIterator __getIterator(TraceInfo trace, Memory memory, boolean getReferences, boolean getKeyReferences) {
        ForeachIterator iterator = memory.getNewIterator(this, getReferences, getKeyReferences);
        if (iterator == null) {
            warning(trace, "Invalid argument supplied for foreach()");
            return invalidIterator;
        }
        iterator.setTrace(trace);
        return iterator;
    }

    public ClassEntity __getGenerator(String moduleIndex, int index) {
        ModuleEntity moduleEntity = scope.moduleIndexMap.get(moduleIndex);
        if (moduleEntity == null)
            throw new CriticalException("Cannot find the module (" + moduleIndex + ") for getting a generator object");

        return moduleEntity.findGenerator(index);
    }

    public ClassEntity __getAnonymousClass(String moduleIndex, String name) {
        if (php.runtime.PhpOwnershipExecution.current(this) != null)
            throw new IllegalArgumentException("Anonymous declaration lacks its complete ownership protocol");
        ModuleEntity module = scope.moduleIndexMap.get(moduleIndex);
        ClassEntity entity = module == null ? null : module.findClass(name);
        if (entity == null || !entity.isAnonymous())
            throw new CriticalException("Invalid anonymous declaration in module " + moduleIndex);
        synchronized (classMap) {
            ClassEntity present = lookupClass(name, true);
            if (present!=null && entity.isUnresolvedDeclaration() && present.getModule()==module) return present;
            if (present != null && present != entity) throw new CriticalException("Anonymous class identity collision");
            if (present == null) {
                if (entity.isUnresolvedDeclaration()) return __declareClass(moduleIndex,name);
                entity.initEnvironment(this);
                publishClass(name, entity);
            }
        }
        return entity;
    }

    public ClassEntity __defineClass(TraceInfo trace, String moduleIndex, int declarationId) {
        if (php.runtime.PhpOwnershipExecution.current(this) != null)
            throw new IllegalArgumentException("Deferred class declaration lacks its complete ownership protocol");
        ModuleEntity module = scope.moduleIndexMap.get(moduleIndex);
        ClassEntity template = module == null ? null : module.findDeferredClass(declarationId);
        if (module == null || !moduleIndex.equals(module.getInternalName()) || template == null
                || template.getModule() != module || template.getDeclarationId() != declarationId
                || !template.isDeclarationTemplate() || template.isStatic() || template.isAnonymous())
            throw new CriticalException("Invalid deferred class declaration ordinal");
        return defineClassDeclaration(trace, moduleIndex, template);
    }

    public ClassEntity __declareClass(String moduleIndex, String name) {
        if (php.runtime.PhpOwnershipExecution.current(this) != null)
            throw new IllegalArgumentException("Deferred class declaration lacks its complete ownership protocol");
        ModuleEntity module = scope.moduleIndexMap.get(moduleIndex);
        ClassEntity template = module == null ? null : module.findClass(name);
        if (template == null || template.getModule() != module || !template.isUnresolvedDeclaration())
            throw new CriticalException("Invalid deferred class declaration");
        return defineClassDeclaration(template.getTrace(), moduleIndex, template);
    }

    private boolean hasDeclarationCollision(String name, ClassEntity entity) {
        return hasClassCollision(name, entity) || scope.hasClassDeclaration(name);
    }

    private ClassEntity defineClassDeclaration(TraceInfo trace, String moduleIndex, ClassEntity template) {
        String name = template.getName();
        synchronized (classMap) {
            if (hasDeclarationCollision(name, template))
                error(trace, Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(name));
        }
        String pendingKey = "\0class-link:" + moduleIndex + ":"
                + (template.isDeferredDeclaration() ? template.getDeclarationId() : name);
        if (getUserValue(pendingKey, Boolean.class) != null)
            exception(trace, php.runtime.lang.exception.BaseError.class, "Class \"%s\" not found", name);
        setUserValue(pendingKey, Boolean.TRUE);
        try {
            String parentName = template.getDeferredParentName();
            ClassEntity parent = parentName == null ? null
                    : fetchClass(parentName, true, template.getModule().getPhpLanguageProfile());
            if (parentName != null && parent == null)
                exception(trace, php.runtime.lang.exception.BaseError.class, "Class \"%s\" not found", parentName);
            synchronized (classMap) {
                // Parent autoload can itself publish a competing declaration.
                if (hasDeclarationCollision(name, template))
                    error(trace, Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(name));
                ClassEntity bound = php.runtime.reflection.PhpClassDeclaration.bind(template, parent, this);
                if (hasDeclarationCollision(name, bound))
                    error(trace, Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(name));
                bound.initEnvironment(this);
                if (hasDeclarationCollision(name, bound))
                    error(trace, Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(name));
                publishClass(name, bound);
                return bound;
            }
        } finally {
            setUserValue(pendingKey, null);
        }
    }

    public ClassEntity __getClosure(String moduleIndex, int index) {
        ModuleEntity moduleEntity = scope.moduleIndexMap.get(moduleIndex);
        if (moduleEntity == null) {
            throw new CriticalException("Cannot find the module (" + moduleIndex + ") for getting a closure object");
        }

        return moduleEntity.findClosure(index);
    }

    public Memory __getSingletonClosure(String moduleIndex, int index, String selfContextClass) {
        Memory result = scope.moduleIndexMap.get(moduleIndex)
                .findClosure(index)
                .getSingleton(selfContextClass);

        assert result != null;

        return result;
    }

    public Memory __throwException(InvocationTargetException e) {
        Throwable throwable = e.getTargetException();
        if (throwable instanceof FinallyException) {
            return Memory.NULL;
        }

        if (throwable instanceof JPHPException)
            throw (RuntimeException) throwable;
        else {
            JavaReflection.exception(this, throwable);
        }

        return Memory.NULL;
    }

    public void __throwException(BaseBaseException e) {
        __throwException(e, true);
    }

    public void __throwException(BaseBaseException e, boolean clearSilent) {
        if (clearSilent) {
            __clearSilent();
        }

        e.setTraceInfo(this, trace());
        throw e;
    }

    public void __throwException(TraceInfo trace, Memory exception) {
        if (exception.isObject()) {
            IObject object;
            if ((object = exception.toValue(ObjectMemory.class).value) instanceof BaseBaseException) {
                __clearSilent();
                BaseBaseException e = (BaseBaseException) object;
                e.setTraceInfo(this, trace);
                throw e;
            } else {
                triggerError(new FatalException(
                        "Exceptions must be valid objects derived from the Exception base class", trace
                ));
            }
        } else {
            triggerError(new FatalException(
                    "Can only throw objects", trace
            ));
        }
    }

    public void __throwFailedCatch(BaseBaseException e) {
        if (e instanceof FinallyException) {
            return;
        }

        throw e;
    }

    public Memory __throwCatch(BaseBaseException e, String className, String lowerClassName) {
        ClassEntity origin = e.getReflection();
        ClassEntity cause = fetchClass(className, lowerClassName, false);

        if (cause != null && (PhpClassNames.isModern(this) ? origin.isInstanceOfAsciiName(cause.getName()) : origin.isInstanceOf(cause)))
            return new ObjectMemory(e);
        if (PhpClassNames.isModern(this) ? origin.isInstanceOfAsciiName(className) : origin.isInstanceOfLower(lowerClassName))
            return new ObjectMemory(e);
        else
            return Memory.NULL;
    }

    public void __pushSilent() {
        silentFlags.push(errorFlags);
        setErrorFlags(0);
    }

    public void __popSilent() {
        Integer flags = silentFlags.pop();
        setErrorFlags(flags);
    }

    public void __clearSilent() {
        Stack<Integer> silents = silentFlags;
        Integer flags = null;
        while (!silents.empty())
            flags = silents.pop();

        if (flags != null) {
            setErrorFlags(flags);
        }
    }

    public Memory __getMacroClass() {
        CallStackItem item = peekCall(0);
        if (item != null && item.object instanceof Closure && ((Closure) item.object).hasPhp8Binding()) {
            String scope = ((Closure) item.object).getDeclarationScope();
            return scope == null ? Memory.CONST_EMPTY_STRING : StringMemory.valueOf(scope);
        }
        if (item != null && item.clazz != null) {
            if (item.classEntity == null)
                item.classEntity = fetchClass(item.clazz, false);

            if (item.classEntity == null)
                return Memory.CONST_EMPTY_STRING;
            else {
                MethodEntity method = item.classEntity.findMethod(item.function.toLowerCase());
                if (method == null)
                    return Memory.CONST_EMPTY_STRING;
                return new StringMemory(method.getClazz().getName());
            }
        } else
            return Memory.CONST_EMPTY_STRING;
    }

    public void __defineFunction(TraceInfo trace, String moduleInternalName, int index) {
        ModuleEntity module = scope.moduleIndexMap.get(moduleInternalName);
        if (module == null)
            throw new CriticalException("Cannot find module: " + moduleInternalName);

        FunctionEntity function = module.findFunction(index);

        if (functionMap.put(function.getLowerName(), function) != null) {
            triggerError(new FatalException(
                    Messages.ERR_CANNOT_REDECLARE_FUNCTION.fetch(function.getName()),
                    trace
            ));
        }
    }

    public String __shellExecute(String s) {
        if (shellExecHandler != null)
            return shellExecHandler.onExecute(s);
        return "";
    }

    public void die(Memory value) {
        if (value != null) {
            if (!value.isNumber())
                echo(value.toString());

            throw new DieException(value);
        } else
            throw new DieException(Memory.NULL);
    }

    public Memory invokeMethod(TraceInfo trace, IObject object, String name, Memory... args) throws Throwable {
        return ObjectInvokeHelper.invokeMethod(new ObjectMemory(object), name, name.toLowerCase(), this, trace, args);
    }

    public Memory invokeMethod(IObject object, String name, Memory... args) throws Throwable {
        return ObjectInvokeHelper.invokeMethod(new ObjectMemory(object), name, name.toLowerCase(), this, trace(), args);
    }

    public Memory invokeMethodNoThrow(IObject object, String name, Memory... args) {
        try {
            return invokeMethod(object, name, args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            this.catchUncaught(e);
            return Memory.NULL;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public Memory invokeMethod(TraceInfo trace, Memory object, String name, Memory... args) throws Throwable {
        return ObjectInvokeHelper.invokeMethod(object, name, name.toLowerCase(), this, trace, args);
    }

    public Memory invokeMethod(Memory object, String name, Memory... args) throws Throwable {
        return ObjectInvokeHelper.invokeMethod(object, name, name.toLowerCase(), this, trace(), args);
    }

    public String getLateStatic() {
        CallStackItem item = peekCall(0);
        if (item == null || item.clazz == null)
            return "";
        else
            return item.staticClazz != null ? item.staticClazz : item.clazz;
    }

    public IObject getLateObject() {
        CallStackItem item = peekCall(0);
        if (item == null)
            return null;
        else
            return item.object;
    }

    public ClassEntity getLateStaticClass() {
        CallStackItem item = peekCall(0);
        if (item == null || item.clazz == null)
            return null;
        else {
            if (item.staticClassEntity != null)
                return item.staticClassEntity;

            if (item.object instanceof Closure) {
                Closure closure = (Closure) item.object;
                if (closure.hasPhp8Binding()) {
                    String calledClass = closure.getCalledClass();
                    return calledClass == null ? null : fetchClass(calledClass, false);
                }
                Memory self = ((Closure) item.object).getSelf();
                if (self.isObject())
                    return self.toValue(ObjectMemory.class).getReflection();
                else
                    return null;
            }

            return item.staticClassEntity = fetchClass(item.staticClazz != null ? item.staticClazz : item.clazz, false);
        }
    }

    public String getContext() {
        CallStackItem item = peekCall(1);
        return item == null ? "" : item.clazz == null ? "" : item.clazz;
    }

    public ClassEntity __getContextClass(int offset) {
        CallStackItem item = peekCall(offset);
        if (item == null || item.clazz == null)
            return null;
        else {
            if (item.classEntity != null)
                return item.classEntity;

            ClassEntity e = item.classEntity = fetchClass(item.clazz, false);
            if (e == null)
                throw new IllegalStateException("Cannot find '" + item.clazz + "' in the current environment");
            return e;
        }
    }

    public ClassEntity getContextClass() {
        return __getContextClass(1);
    }

    public ClassEntity getLastClassOnStack() {
        int N = getCallStackTop();
        for (int i = 0; i < N; i++) {
            CallStackItem item = peekCall(i);
            if (item != null && item.object instanceof Closure
                    && (((Closure) item.object).hasPhp8Binding() || !((Closure) item.object).getSelf().isObject())) {
                String scope = ((Closure) item.object).getScope();
                return scope == null ? null : fetchClass(scope, false);
            }
            if (item != null && item.clazz != null) {
                if (item.classEntity != null) {
                    if (item.object instanceof Closure) {
                        Memory self = ((Closure) item.object).getSelf();
                        if (self.isObject())
                            return self.toValue(ObjectMemory.class).getReflection();
                        else {
                            return null;
                        }
                    }

                    return item.classEntity;
                }
                ClassEntity e = item.classEntity = fetchClass(item.clazz, false);
                if (e == null)
                    throw new IllegalStateException("Cannot find '" + item.clazz + "' in the current environment");
                return e;
            }
        }
        return null;
    }

    public ClassEntity __getParentClass(TraceInfo trace) {
        if (getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5)
            return php.runtime.invoke.PhpMethodResolver.parentClass(this, trace);
        ClassEntity context = getLastClassOnStack();

        if (context == null) {
            error(trace, "Cannot access parent:: when no class scope is active");
            return null;
        }
        ClassEntity parent = context.getParent();
        if (parent == null) {
            error(trace, "Cannot access parent:: when current class scope has no parent");
            return null;
        }

        return parent;
    }

    public String __getSelf(TraceInfo trace) {
        ClassEntity context = getLastClassOnStack();
        if (context == null) {
            exception(trace, BaseError.class, getModuleManager().getPhpLanguageProfile(trace) == PhpLanguageProfile.PHP_8_5
                    ? "Cannot access \"self\" when no class scope is active" : "Cannot access self:: when no class scope is active");
            return null;
        }
        return context.getName();
    }

    public String __getParent(TraceInfo trace) {
        return __getParentClass(trace).getName();
    }

    public String __getParent(TraceInfo trace, String className) {
        ClassEntity o = fetchClass(className, true);
        if (o == null) {
            error(trace, ErrorType.E_ERROR, Messages.ERR_CLASS_NOT_FOUND, className);
            return null;
        }

        if (o.getParent() == null) {
            error(trace, "Cannot access parent:: when current class scope has no parent");
            return null;
        }

        return o.getParent().getName();
    }

    public void registerAutoloader(SplClassLoader classLoader, boolean prepend) {
        for (SplClassLoader loader : classLoaders)
            if (loader.equals(classLoader))
                return;

        if (prepend) {
            classLoaders.add(0, classLoader);
        } else
            classLoaders.add(classLoader);
    }

    public List<SplClassLoader> getClassLoaders() {
        return classLoaders;
    }

    public boolean unRegisterAutoloader(SplClassLoader classLoader) {
        boolean result = false;
        Iterator<SplClassLoader> iterator = classLoaders.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().equals(classLoader)) {
                result = true;
                iterator.remove();
            }
        }
        return result;
    }

    public void setErrorHandler(ErrorHandler handler) {
        if (ownedErrorHandlersReleased && handler != null && handler.getOwnershipRegistration() != null)
            throw new IllegalStateException("Owned error-handler registrations are finalized");
        if (handler != null && handler.getOwnershipRegistration() != null) {
            if (handler.getClass() != ErrorHandler.class) throw new IllegalArgumentException("Custom handler lacks an exact invocation contract");
            handler.getOwnershipRegistration().install(this);
        }
        if (previousErrorHandlers == null) previousErrorHandlers = new ArrayList<>();
        previousErrorHandlers.add(errorHandler);
        errorHandler = handler;
    }

    /** Restore the last registration; an empty stack stays empty. */
    public void restoreErrorHandler() {
        ErrorHandler removed=errorHandler;
        errorHandler = previousErrorHandlers == null || previousErrorHandlers.isEmpty() ? null
                : previousErrorHandlers.remove(previousErrorHandlers.size() - 1);
        if (removed != errorHandler) releaseErrorHandler(removed);
    }

    private void releaseErrorHandler(ErrorHandler handler) {
        if (handler != null && handler.getOwnershipRegistration() != null) {
            try {handler.getOwnershipRegistration().close();}
            catch (Throwable failure) {Environment.<RuntimeException>throwThreadFailure(failure);}
        }
    }

    /** PHP suspends the active handler while it runs. A replacement remains active. */
    private boolean dispatchErrorHandler(SystemMessage message) {
        ErrorHandler selected=errorHandler;
        if (selected == null) return false;
        errorHandler=null;
        Throwable primary=null;
        try {return selected.onError(this,message);}
        catch (RuntimeException | Error failure) {primary=failure;throw failure;}
        finally {
            if (errorHandler == null) errorHandler=selected;
            else if (errorHandler != selected) {
                try {releaseErrorHandler(selected);}
                catch (Throwable cleanup) {
                    Throwable composed=selected.getOwnershipRegistration() == null ? cleanup
                            : selected.getOwnershipRegistration().composeFailure(primary,cleanup);
                    Environment.<RuntimeException>throwThreadFailure(composed);
                }
            }
        }
    }

    /** Release actual retained callables after shutdown/reporting and before the execution participant closes. */
    /** Pure admission of the existing physical registry; no raw callback is adopted retrospectively. */
    public void requireRuntimeErrorHandlerState(php.runtime.PhpRuntimeOwnership.Authority authority) {
        if (authority.environment()!=this || ownedErrorHandlersReleased)throw new IllegalArgumentException("Error-handler registry is foreign or finalized");
        if (errorHandler!=null && (errorHandler.getOwnershipRegistration()==null || !errorHandler.getOwnershipRegistration().belongsTo(authority)))throw new IllegalArgumentException("Active error handler lacks its actual ordinary FIELD");
        if (previousErrorHandlers!=null)for(ErrorHandler handler:previousErrorHandlers)
            if(handler!=null && (handler.getOwnershipRegistration()==null || !handler.getOwnershipRegistration().belongsTo(authority)))throw new IllegalArgumentException("Previous error handler lacks its actual ordinary FIELD");
    }

    public void releaseOwnedErrorHandlers() throws Throwable {
        if (ownedErrorHandlersReleased) return;
        boolean ownsHandler=errorHandler != null && errorHandler.getOwnershipRegistration() != null;
        if (!ownsHandler && previousErrorHandlers != null)
            for (ErrorHandler handler:previousErrorHandlers)
                if (handler != null && handler.getOwnershipRegistration() != null) {ownsHandler=true;break;}
        if (!ownsHandler) {ownedErrorHandlersReleased=true;return;}
        List<ErrorHandler> releasing=previousErrorHandlers == null ? new ArrayList<>()
                : new ArrayList<>(previousErrorHandlers);
        if (errorHandler != null) releasing.add(errorHandler);
        errorHandler=null;previousErrorHandlers=null;ownedErrorHandlersReleased=true;
        Throwable failure=null;
        for (int i=releasing.size()-1;i>=0;i--) {
            ErrorHandler handler=releasing.get(i);
            try {releaseErrorHandler(handler);}
            catch (Throwable cleanup) {
                failure=handler.getOwnershipRegistration() == null ? cleanup
                        : handler.getOwnershipRegistration().composeFailure(failure,cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    public TraceInfo getTraceAppliedSourceMap(TraceInfo trace) {
        if (trace == null) {
            return null;
        }

        if (trace.getFile() == null) {
            return trace;
        }

        if (trace instanceof SourceMappedTraceInfo) {
            return trace;
        }

        SourceMap sourceMap = sourceMaps.get(trace.getFileName());

        if (sourceMap != null) {
            int sourceLine = sourceMap.getSourceLine(trace.getStartLine() + 1);

            if (sourceLine != trace.getStartLine() && sourceLine != -1) {
                trace = new SourceMappedTraceInfo(
                        trace.getContext(),
                        sourceLine - 1, trace.getEndLine(),
                        trace.getStartPosition(), trace.getEndPosition()
                );
            }
        }

        return trace;
    }

    public void applySourceMap(CallStackItem[] callStack) {
        if (sourceMaps.isEmpty()) {
            return;
        }

        for (CallStackItem stackItem : callStack) {
            stackItem.trace = getTraceAppliedSourceMap(stackItem.trace);
        }
    }

    public CallStack getCallStack() {
        return callStack;
    }

    public void __replaceCallStack(CallStack stack) {
        this.callStack = stack;
    }

    public void registerShutdownFunction(ShutdownHandler handler) {
        shutdownFunctions.add(handler);
    }

    static {
        configurationHandler = new HashMap<String, ConfigChangeHandler>();
        configurationHandler.put("include_path", new ConfigChangeHandler() {
            @Override
            public void onChange(Environment env, Memory value) {
                if (value == null)
                    env.setIncludePaths(Collections.<String>emptySet());
                else {
                    String[] files = StringUtils.split(value.toString(), Constants.PATH_SEPARATOR, 255);
                    Set<String> paths = new HashSet<String>();
                    Collections.addAll(paths, files);

                    env.setIncludePaths(paths);
                }
            }
        });
    }
}
