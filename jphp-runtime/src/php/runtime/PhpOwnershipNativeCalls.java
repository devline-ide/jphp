package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.core.LangFunctions;
import php.runtime.ext.support.compile.CompileFunction;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.output.serialization.PhpSerialization;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Exact native member contracts. Java argument snapshots never acquire PHP owners. */
public final class PhpOwnershipNativeCalls {
    private PhpOwnershipNativeCalls() {}

    public static final class Capability {
        private final Method member;
        private Capability(Method member) {this.member=member;}
        public Method member() {return member;}
    }

    private static final class Capabilities {
        static final Map<Method,Capability> MEMBERS=members();
        private static Map<Method,Capability> members() {
            Map<Method,Capability> methods=new HashMap<>();
            add(methods,PhpSerialization.class,"serialize",Environment.class,TraceInfo.class,Memory.class);
            add(methods,PhpSerialization.class,"unserialize",Environment.class,TraceInfo.class,Memory.class);
            add(methods,PhpSerialization.class,"unserialize",Environment.class,TraceInfo.class,Memory.class,Memory.class);
            add(methods,php.runtime.ext.core.ArrayFunctions.class,"count",Environment.class,TraceInfo.class,Memory.class);
            add(methods,php.runtime.ext.core.ArrayFunctions.class,"count",Environment.class,TraceInfo.class,Memory.class,int.class);
            add(methods,LangFunctions.class,"set_error_handler",Environment.class,TraceInfo.class,Memory.class);
            add(methods,LangFunctions.class,"set_error_handler",Environment.class,TraceInfo.class,Memory.class,int.class);
            add(methods,LangFunctions.class,"restore_error_handler",Environment.class);
            add(methods,LangFunctions.class,"error_reporting",Environment.class);
            add(methods,LangFunctions.class,"error_reporting",Environment.class,int.class);
            add(methods,LangFunctions.class,"trigger_error",Environment.class,TraceInfo.class,String.class);
            add(methods,LangFunctions.class,"trigger_error",Environment.class,TraceInfo.class,String.class,int.class);
            try {
                Class<?> zend=Class.forName("org.develnext.jphp.zend.ext.standard.ArrayFunctions",false,PhpOwnershipNativeCalls.class.getClassLoader());
                add(methods,zend,"array_key_exists",Environment.class,TraceInfo.class,Memory.class,Memory.class);
            } catch (ClassNotFoundException absent) { /* Optional extension has no capability when absent. */ }
            return Collections.unmodifiableMap(methods);
        }
        private static void add(Map<Method,Capability> methods,Class<?> owner,String name,Class<?>... parameters) {
            try {Method member=owner.getMethod(name,parameters);methods.put(member,new Capability(member));}
            catch (NoSuchMethodException failure) {throw new ExceptionInInitializerError(failure);}
        }
    }

    public static Capability requireCapability(Method member) {
        Capability capability=Capabilities.MEMBERS.get(Objects.requireNonNull(member));
        if (capability == null) throw new IllegalArgumentException("Native member has no complete ownership call contract: " + member);
        return capability;
    }

    /** Called with the actual invocation packet, before converters or the native body. */
    public static Memory invoke(Environment env,TraceInfo trace,CompileFunction.Method method,
                                Memory[] arguments,PhpOwnershipExecution.CallRoots roots) throws Throwable {
        Capability capability=requireCapability(method.method);
        PhpOwnershipExecution.Session session=Objects.requireNonNull(PhpOwnershipExecution.current(env),"Missing explicit native execution session");
        if (session.requireNativeCallRoots(capability.member,arguments) != roots)
            throw new IllegalArgumentException("Native invocation does not own the current exact call packet");
        Class<?>[] types=capability.member.getParameterTypes();
        Object[] passed=new Object[types.length];
        int argument=0;
        for (int i=0;i<types.length;i++) {
            if (types[i] == Environment.class) passed[i]=env;
            else if (types[i] == TraceInfo.class) passed[i]=trace;
            else {
                PhpOwnershipValues.Binding binding=roots.argument(argument);
                if (binding.domain() != session.domain() || binding.role() != PhpOwnershipValues.Role.VALUE)
                    throw new IllegalArgumentException("Native value argument has no matching VALUE packet");
                Memory raw=binding.rawValue();
                if (argument >= arguments.length || arguments[argument] != raw)
                    throw new IllegalArgumentException("Native argument packet does not match the actual argument vector");
                if (types[i] == Memory.class) passed[i]=raw;
                else if (types[i] == int.class) {
                    if (raw.isObject() || raw.isArray() || raw.isResource())
                        throw new IllegalArgumentException("Native integer conversion has no callback contract");
                    passed[i]=raw.toInteger();
                } else if (types[i] == String.class) {
                    if (raw.isObject() || raw.isArray() || raw.isResource())
                        throw new IllegalArgumentException("Native string conversion has no callback contract");
                    passed[i]=raw.toString();
                } else throw new IllegalArgumentException("Native converter is outside the exact member contract");
                argument++;
            }
        }
        if (argument != arguments.length) throw new IllegalArgumentException("Native packet argument count mismatch");
        if (capability.member.getDeclaringClass() == php.runtime.ext.core.ArrayFunctions.class
                && arguments[0] instanceof ObjectMemory)
            throw new IllegalArgumentException("Countable native callback has no admitted invocation contract");
        Memory result;
        try {result=php.runtime.memory.support.MemoryUtils.valueOf(capability.member.invoke(null,passed));}
        catch (InvocationTargetException failure) {throw failure.getCause();}
        roots.stageResult(session.value(result));
        return result;
    }

    /** Admission rechecks this exact host boundary before any generated body effects. */
    public static void requireLiteralBufferedOutput(Environment env) {
        var buffer=env.peekOutputBuffer();
        if (env.getClass() != Environment.class || buffer == null || buffer != env.getDefaultBuffer()
                || buffer.getClass() != php.runtime.output.OutputBuffer.class || buffer.getCallback() != null
                || buffer.isImplicitFlush() || buffer.getChunkSize() != 0
                || env.getDefaultCharset() != java.nio.charset.StandardCharsets.UTF_8)
            throw new IllegalArgumentException("Output has no pure buffered literal contract");
    }

    /** An admitted literal write appends bytes only; it cannot flush or call a user output handler. */
    public static void echoLiteral(Environment env,String value) {
        Objects.requireNonNull(PhpOwnershipExecution.currentEntry(env),"Literal output needs an actual execution entry");
        requireLiteralBufferedOutput(env);
        env.echo(Objects.requireNonNull(value));
    }

    /** Ordinary output uses the exact current frame and cannot flush or enter PHP callbacks. */
    public static void echoRuntime(PhpRuntimeOwnership.Frame frame,Memory value,Environment env) {
        if (frame == null) {env.echo(value);return;}
        requireRuntimeOutput(frame,env);
        env.echo(frame.scalarOutput(value));
    }

    public static void echoLiteralRuntime(PhpRuntimeOwnership.Frame frame,String value,Environment env) {
        if (frame == null) {env.echo(value);return;}
        requireRuntimeOutput(frame,env);
        env.echo(Objects.requireNonNull(value));
    }

    private static void requireRuntimeOutput(PhpRuntimeOwnership.Frame frame,Environment env) {
        var authority=frame.authority();
        if (authority.environment() != env || PhpRuntimeOwnership.current(env) != authority
                || authority.currentFrame() != frame)
            throw new IllegalArgumentException("Output does not identify its actual current ordinary frame");
        requireLiteralBufferedOutput(env);
    }

    /** Tick with no installed handler borrows the actual local table and publishes nothing. */
    public static void tickEmpty(Environment env,TraceInfo trace,php.runtime.memory.ArrayMemory locals) {
        var entry=Objects.requireNonNull(PhpOwnershipExecution.currentEntry(env),"Tick needs an actual execution entry");
        if (env.getClass() != Environment.class || env.getScope().getTickHandler() != null || entry.nullableLocalTable() != locals)
            throw new IllegalArgumentException("Tick lacks an exact empty-handler/local-table contract");
        env.__tick(trace,locals);
    }

    /** A callable registration owns a VALUE independently of its transient native argument. */
    public static final class Registration implements AutoCloseable {
        private final PhpOwnershipExecution.Session session;
        private final PhpRuntimeOwnership.HandlerRegistration runtime;
        private final PhpOwnershipValues.Binding binding;
        private final php.runtime.invoke.Invoker invoker;
        private final PhpOwnershipDomain.Owned retained;
        private boolean closed;
        private boolean installed;
        private Registration(PhpOwnershipExecution.Session session,php.runtime.invoke.Invoker invoker,PhpOwnershipValues.Binding binding) throws Throwable {
            this.session=session;this.runtime=null;this.invoker=invoker;this.binding=binding;this.retained=binding.host();
        }
        private Registration(PhpRuntimeOwnership.HandlerRegistration runtime,php.runtime.invoke.Invoker invoker) {
            this.session=null;this.runtime=runtime;this.invoker=invoker;this.binding=runtime.binding();this.retained=null;
        }
        public boolean isOrdinary(){return runtime!=null;}
        public boolean belongsTo(PhpRuntimeOwnership.Authority authority){return !closed&&runtime!=null&&runtime.belongsTo(authority);}
        public PhpOwnershipValues.Binding binding() {
            if (closed) throw new IllegalStateException("Native callable registration is closed");
            return binding;
        }
        public PhpOwnershipExecution.Session session() {return session;}
        public Memory ordinaryResult(Environment env)throws Throwable{if(runtime==null)throw new IllegalArgumentException("Not an ordinary handler FIELD");return runtime.publishNativeResult(env);}
        private Memory currentErrorResult(Environment env,php.runtime.env.handler.ErrorHandler actual)throws Throwable {
            if (closed || runtime==null || env.getErrorHandler()!=actual || actual.getOwnershipRegistration()!=this)
                throw new IllegalArgumentException("Current handler RESULT has another actual registration");
            requireInvoker(actual.invoker);
            return runtime.publishCurrentNativeResult(env,actual);
        }
        public void requireInvoker(php.runtime.invoke.Invoker actual) {
            if (closed || actual != invoker) throw new IllegalArgumentException("Callable registration differs from its actual invoker");
        }
        /** One retained root represents one physical registration, never two stack positions. */
        public void install(Environment env) {
            if(runtime!=null){if(closed||installed)throw new IllegalArgumentException("Registration is already installed or closed");runtime.install(env);installed=true;return;}
            if (closed || installed || session.environment() != env || PhpOwnershipExecution.current(env) != session)
                throw new IllegalArgumentException("Callable registration is closed, foreign, or already installed");
            installed=true;
        }
        public boolean invokeHandled(php.runtime.invoke.Invoker invoker,Memory[] arguments) throws Throwable {
            requireInvoker(invoker);
            if(runtime!=null)return runtime.invokeHandled(invoker,arguments);
            session.requireInvokerCapability(invoker);
            try (var scope=session.enter()) {
                var roots=session.newCallRoots();
                try {
                    if (invoker.getClass() == php.runtime.invoke.DynamicMethodInvoker.class)
                        roots.stageReceiver(session.value(new ObjectMemory(((php.runtime.invoke.DynamicMethodInvoker)invoker).getObject())));
                    else roots.stageReceiver(binding);
                    for (int i=0;i<arguments.length;i++) roots.send(i,session.value(arguments[i]));
                    Memory result=roots.invokeInvoker(invoker,arguments);
                    if (roots.result() == null || roots.result().rawValue() != result)
                        throw new IllegalArgumentException("Native callback result lacks its actual result owner");
                    boolean handled=result.toValue() != Memory.FALSE;
                    roots.close();
                    return handled;
                } catch (Throwable failure) {roots.unwind(failure);throw failure;}
            }
        }
        public Throwable composeFailure(Throwable primary,Throwable cleanup) {
            if(runtime!=null)return runtime.composeFailure(primary,cleanup);
            try (var scope=session.enter()) {return session.domain().graph().composeFailure(primary,cleanup);}
        }
        @Override public void close() throws Exception {
            if (closed) return;
            if(runtime!=null){closed=true;runtime.close();return;}
            try (var scope=session.enter()) {closed=true;retained.close();}
        }
    }

    /** Observers cannot borrow a handler FIELD through an unadmitted explicit native call. */
    public static Memory currentErrorHandlerResult(Environment env,php.runtime.env.handler.ErrorHandler selected) {
        if (PhpOwnershipExecution.current(env) != null)
            throw new IllegalArgumentException("Error-handler observer lacks an explicit native read/result contract");
        if (env.getErrorHandler() != selected)
            throw new IllegalArgumentException("Error-handler observer changed its actual Environment FIELD");
        var registration=selected==null?null:selected.getOwnershipRegistration();
        if (PhpRuntimeOwnership.current(env) != null) {
            if (registration==null || !registration.isOrdinary())
                throw new IllegalArgumentException("Error-handler observer lacks its actual retained FIELD registration");
            try {return registration.currentErrorResult(env,selected);}
            catch(Throwable failure){return raiseHandlerResult(failure);}
        }
        return selected==null || selected.invoker==null ? Memory.NULL : selected.invokerMemory.toImmutable();
    }

    public static Memory currentExceptionHandlerResult(Environment env,php.runtime.env.handler.ExceptionHandler selected) {
        if (env.getExceptionHandler() != selected)
            throw new IllegalArgumentException("Exception-handler observer changed its actual Environment FIELD");
        if (PhpOwnershipExecution.current(env) != null || PhpRuntimeOwnership.current(env) != null)
            throw new IllegalArgumentException("Exception-handler observer lacks an owned FIELD read/result contract");
        return selected==null || selected.invoker==null ? Memory.NULL : selected.invokerMemory.toImmutable();
    }

    public static Memory previousHandlerResult(Environment env,php.runtime.env.handler.ErrorHandler previous) {
        if(previous==null)return Memory.NULL;
        var registration=previous.getOwnershipRegistration();
        if(registration==null||!registration.isOrdinary())return previous.invokerMemory;
        try{return registration.ordinaryResult(env);}catch(Throwable failure){return raiseHandlerResult(failure);}
    }

    @SuppressWarnings("unchecked") private static <T,E extends Throwable>T raiseHandlerResult(Throwable failure)throws E{throw (E)failure;}

    public static Registration retainHandler(Environment env,php.runtime.invoke.Invoker invoker,Memory actualCallable) throws Throwable {
        PhpOwnershipExecution.Session session=PhpOwnershipExecution.current(env);
        if (session == null) {
            PhpRuntimeOwnership.Authority authority=PhpRuntimeOwnership.current(env);
            return authority==null?null:new Registration(authority.retainErrorHandler(invoker,actualCallable),invoker);
        }
        session.requireInvokerCapability(invoker);
        PhpOwnershipValues.Binding binding=session.value(actualCallable);
        if (binding.role() != PhpOwnershipValues.Role.VALUE) throw new IllegalArgumentException("A native callable registration must own a VALUE");
        return new Registration(session,invoker,binding);
    }
}
