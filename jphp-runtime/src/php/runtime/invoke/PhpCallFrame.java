package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.compile.CompileFunction;
import php.runtime.invoke.cache.FunctionCallCache;
import php.runtime.invoke.cache.MethodCallCache;
import php.runtime.invoke.cache.ClassCallCache;
import php.runtime.lang.Closure;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.helper.VariadicMemory;
import php.runtime.reflection.CompileFunctionEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.ParameterEntity;

import java.util.Arrays;

/** A single PHP call's SEND state; nested calls never share mutable argument state. */
public final class PhpCallFrame implements AutoCloseable {
    public static final int VARIABLE = 0;
    public static final int CALL_RESULT = 1;
    public static final int VALUE = 2;

    private final Environment env;
    private final TraceInfo trace;
    private final FunctionEntity function;
    private final Invoker invoker;
    private final ParameterEntity[] parameters;
    private final CompileFunction.Method nativeMethod;
    private final String originClass;
    private final String originMethod;
    private Memory[] arguments;
    private int size;
    private final int capacity;
    private final CompileFunctionEntity nativeTarget;
    private PhpArgumentBinder binder;
    private BoundArguments bound;
    private boolean closed, invoked;
    private php.runtime.memory.ObjectMemory constructedObject;
    private final php.runtime.PhpOwnershipExecution.CallRoots ownershipRoots;
    private php.runtime.PhpOwnershipExecution.Entry ownershipEntry;

    private PhpCallFrame(Environment env, TraceInfo trace, FunctionEntity function, Invoker invoker, int capacity) {
        this(env,trace,function,invoker,capacity,null);
    }
    private PhpCallFrame(Environment env, TraceInfo trace, FunctionEntity function, Invoker invoker, int capacity,
                         php.runtime.PhpOwnershipExecution.CallRoots ownershipRoots) {
        if (php.runtime.PhpOwnershipExecution.current(env) != null && ownershipRoots == null)
            throw new IllegalArgumentException("Active call needs its actual pre-evaluation ownership packet");
        env.runPendingFinalizers();
        this.ownershipRoots=ownershipRoots;
        this.env = env;
        this.trace = trace;
        this.function = function;
        this.invoker = invoker;
        this.arguments = new Memory[capacity];
        this.capacity = capacity;
        boolean magic = invoker instanceof MagicDynamicMethodInvoker || invoker instanceof MagicStaticMethodInvoker;
        this.parameters = magic || invoker == null && function == null ? null
                : function != null ? function.getParameters()
                : invoker instanceof DynamicMethodInvoker ? ((DynamicMethodInvoker) invoker).getMethod().getParameters(capacity)
                : invoker instanceof StaticMethodInvoker ? ((StaticMethodInvoker) invoker).getMethod().getParameters(capacity)
                : invoker.getParameters();
        FunctionEntity nativeTarget = function;
        if (invoker instanceof DynamicMethodInvoker
                && ((DynamicMethodInvoker) invoker).getObject() instanceof PhpCallableFactory.CapturedClosure)
            nativeTarget = ((PhpCallableFactory.CapturedClosure) ((DynamicMethodInvoker) invoker).getObject()).getFunction();
        this.nativeMethod = nativeTarget instanceof CompileFunctionEntity
                ? ((CompileFunctionEntity) nativeTarget).getCompileFunction().find(capacity) : null;
        this.nativeTarget = nativeTarget instanceof CompileFunctionEntity ? (CompileFunctionEntity) nativeTarget : null;
        if (function != null) {
            originClass = function.getName();
            originMethod = null;
        } else if (invoker instanceof DynamicMethodInvoker) {
            DynamicMethodInvoker dynamic = (DynamicMethodInvoker) invoker;
            if (dynamic.getObject() instanceof Closure) {
                originClass = InvokeArgumentHelper.closureCallName((Closure) dynamic.getObject());
                originMethod = null;
            } else {
                originClass = dynamic.getMethod().getClazzName();
                originMethod = dynamic.getMethod().getName();
            }
        } else if (invoker instanceof StaticMethodInvoker) {
            originClass = ((StaticMethodInvoker) invoker).getMethod().getClazzName();
            originMethod = ((StaticMethodInvoker) invoker).getMethod().getName();
        } else {
            originClass = invoker == null ? "" : invoker.getName();
            originMethod = null;
        }
    }

    private static void requirePassiveResolution(Environment env) {
        if (php.runtime.PhpOwnershipExecution.current(env) != null)
            throw new IllegalArgumentException("Callee resolution lacks its complete ownership EVAL/receiver protocol");
    }
    public static PhpCallFrame ownedFunction(Environment env,TraceInfo trace,FunctionEntity function,
            php.runtime.PhpOwnershipExecution.CallRoots roots,int capacity) {
        if (roots.session() != php.runtime.PhpOwnershipExecution.current(env) || roots.size() != 0)
            throw new IllegalArgumentException("Foreign or already populated call packet");
        roots.session().requireFunctionCapability(function,capacity);
        return new PhpCallFrame(env,trace,function,null,capacity,roots);
    }
    public static PhpCallFrame ownedInvoker(Environment env,TraceInfo trace,Invoker invoker,
            php.runtime.PhpOwnershipExecution.CallRoots roots,int capacity) {
        if (roots.session() != php.runtime.PhpOwnershipExecution.current(env) || roots.size() != 0)
            throw new IllegalArgumentException("Foreign or already populated call packet");
        roots.session().requireInvokerCapability(invoker);
        return new PhpCallFrame(env,trace,null,invoker,capacity,roots);
    }
    public void sendOwned(php.runtime.PhpOwnershipValues.Binding binding) throws Throwable {
        if (ownershipRoots == null) throw new IllegalStateException("Call has no explicit SEND packet");
        ParameterEntity parameter=parameter();
        boolean reference=parameter != null && parameter.isReference();
        if (binding.role() != (reference ? php.runtime.PhpOwnershipValues.Role.CELL : php.runtime.PhpOwnershipValues.Role.VALUE))
            throw new IllegalArgumentException("SEND role differs from the exact callee parameter");
        Memory raw=binding.rawValue();ownershipRoots.send(size,binding);append(raw);
    }

    public static PhpCallFrame method(Memory receiver, Memory name, Environment env, TraceInfo trace, int capacity) {
        requirePassiveResolution(env);
        return new PhpCallFrame(env, trace, null, PhpMethodResolver.instance(receiver, name, env, trace), capacity);
    }

    public static PhpCallFrame staticMethod(Environment env, TraceInfo trace, String className,
                                             Memory name, MethodCallCache cache, int index,
                                             boolean forwarding, int capacity) {
        requirePassiveResolution(env);
        return staticResolved(env, trace, PhpMethodResolver.resolveClass(new php.runtime.memory.StringMemory(className), env, trace),
                name, cache, index, forwarding, capacity);
    }

    public static PhpCallFrame staticResolved(Environment env, TraceInfo trace, php.runtime.reflection.ClassEntity clazz,
                                               Memory name, MethodCallCache cache, int index, boolean forwarding, int capacity) {
        requirePassiveResolution(env);
        return new PhpCallFrame(env, trace, null, PhpMethodResolver.stat(clazz, name, env, trace, cache, index, forwarding), capacity);
    }

    public static PhpCallFrame construct(Memory name, Environment env, TraceInfo trace, ClassCallCache cache,
                                          int index, int capacity) throws Throwable {
        requirePassiveResolution(env);
        var clazz = cache == null ? null : cache.get(env, index);
        if (clazz == null) {
            clazz = PhpMethodResolver.resolveClass(name, env, trace);
            if (cache != null) cache.put(env, index, clazz);
        }
        return constructResolved(clazz, env, trace, capacity);
    }

    public static PhpCallFrame constructResolved(php.runtime.reflection.ClassEntity clazz,
                                                  Environment env, TraceInfo trace, int capacity) throws Throwable {
        requirePassiveResolution(env);
        clazz.checkCanInstance(env, trace);
        // Access must be checked before allocating properties and before any arguments.
        PhpMethodResolver.checkConstructor(clazz, env, trace);
        var object = clazz.newObject(env, trace, false, false);
        PhpObjectLifecycle.beginConstruction(object);
        Invoker constructor = clazz.methodConstruct == null ? null : new DynamicMethodInvoker(env, trace, object, clazz.methodConstruct);
        var frame = new PhpCallFrame(env, trace, null, constructor, capacity);
        frame.constructedObject = new php.runtime.memory.ObjectMemory(object);
        return frame;
    }

    public static PhpCallFrame resolvedFunction(Environment env, TraceInfo trace, FunctionEntity target, int capacity) {
        requirePassiveResolution(env);
        return new PhpCallFrame(env, trace, java.util.Objects.requireNonNull(target), null, capacity);
    }
    public static PhpCallFrame resolvedInvoker(Environment env, TraceInfo trace, Invoker target, int capacity) {
        requirePassiveResolution(env);
        if (target == null || target.getEnvironment() != env) throw new IllegalArgumentException("Foreign resolved invoker");
        if (target instanceof FunctionInvoker function)
            return new PhpCallFrame(env, trace, function.getFunction(), null, capacity);
        return new PhpCallFrame(env, trace, null, target, capacity);
    }

    public static PhpCallFrame function(Environment env, TraceInfo trace, String lowerName, String name,
                                         FunctionCallCache cache, int cacheIndex, int capacity) {
        requirePassiveResolution(env);
        return new PhpCallFrame(env, trace,
                InvokeHelper.resolveFunction(env, trace, lowerName, name, cache, cacheIndex), null, capacity);
    }

    public static PhpCallFrame functionWithOwnership(Environment env,TraceInfo trace,String lowerName,String name,
            FunctionCallCache cache,int cacheIndex,int capacity,php.runtime.PhpOwnershipExecution.Entry entry,int callId) {
        if(entry==null)return function(env,trace,lowerName,name,cache,cacheIndex,capacity);
        if(entry.session()!=php.runtime.PhpOwnershipExecution.current(env))throw new IllegalArgumentException("Foreign invocation entry");
        var roots=entry.openFunctionCall(callId,name,capacity);
        var frame=ownedFunction(env,trace,entry.functionTarget(name,capacity),roots,capacity);frame.ownershipEntry=entry;return frame;
    }
    public void sendCompiler(Memory raw,int sourceKind)throws Throwable {sendCompiler(raw,sourceKind,ownershipEntry,-1);}
    public void sendCompiler(Memory raw,int sourceKind,php.runtime.PhpOwnershipExecution.Entry entry,int evalId)throws Throwable {
        if(ownershipEntry==null){if(entry!=null)throw new IllegalArgumentException("Foreign SEND entry");send(raw,sourceKind);return;}
        if(entry!=ownershipEntry || raw instanceof VariadicMemory || sourceKind<VARIABLE || sourceKind>VALUE)throw new IllegalArgumentException("SEND lacks its exact VALUE producer protocol");
        // The compiler passes a semantic VALUE read; an address/canonical CELL cannot be inferred here.
        entry.session().value(raw);
        Memory snapshot=raw.toImmutable();entry.sendCallArgument(ownershipRoots,size,snapshot,evalId);append(snapshot);
    }
    public Memory invokeToEval(php.runtime.PhpOwnershipExecution.Entry entry,int evalId)throws Throwable {return invokeToEval(entry,evalId,false);}
    public Memory invokeToEvalDiscarded(php.runtime.PhpOwnershipExecution.Entry entry,int evalId)throws Throwable {return invokeToEval(entry,evalId,true);}
    private Memory invokeToEval(php.runtime.PhpOwnershipExecution.Entry entry,int evalId,boolean discarded)throws Throwable {
        if(ownershipEntry==null){if(entry!=null)throw new IllegalArgumentException("Foreign result entry");return invokeWithResultUse(discarded);}
        if(entry!=ownershipEntry)throw new IllegalArgumentException("Foreign result entry");
        Memory result=invokeWithResultUse(discarded);return entry.finishCallToEval(ownershipRoots,evalId,result);
    }

    public static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity) {
        return callable(callable, env, trace, capacity, false, false);
    }

    public static PhpCallFrame acquireCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable, env, trace, 0, true, false);
    }

    public static PhpCallFrame acquireInternalCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable,env,trace,0,true,false,true);
    }

    public static PhpCallFrame acquireInternalFromCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable,env,trace,0,true,true,true);
    }

    /** Native validating APIs replace PHP acquisition failures while retaining their previous chain. */
    public static PhpCallFrame acquireInternalDispatcherCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable,env,trace,0,true,true,true,true);
    }

    /** Closure capture has static string-magic lookup while class-array forms retain caller object lookup. */
    public static PhpCallFrame acquireInternalClosureCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable,env,trace,0,true,true,true,true,true);
    }

    /** Fiber and native sorting retain a single target, but relative magic has a diagnostic at every invoke. */
    public static PhpCallFrame acquireInternalRetainedCallable(Memory callable, Environment env, TraceInfo trace) {
        var frame=callable(callable,env,trace,0,true,true,true,false,true);
        Memory value=callable.toValue();
        if (!value.isString() || !(frame.invoker instanceof MagicStaticMethodInvoker || frame.invoker instanceof MagicDynamicMethodInvoker)) return frame;
        String text=value.toString();int separator=text.lastIndexOf("::");
        if(separator<0)return frame;
        String keyword=text.substring(0,separator);
        if(!"self".equalsIgnoreCase(keyword)&&!"parent".equalsIgnoreCase(keyword)&&!"static".equalsIgnoreCase(keyword))return frame;
        var warning=new RelativeCallbackWarning(keyword,text);
        Invoker selected=frame.invoker instanceof MagicStaticMethodInvoker statik
                ? new RetainedStaticMagic(env,trace,statik,warning)
                : new RetainedDynamicMagic(env,trace,(MagicDynamicMethodInvoker)frame.invoker,warning);
        frame.close();return resolvedInvoker(env,trace,selected,0);
    }
    private record RelativeCallbackWarning(String keyword,String callable) {
        private void before(Environment env,TraceInfo trace)throws Throwable {
            try {
                env.error(trace,ErrorType.E_DEPRECATED,"Use of \"%s\" in callables is deprecated",keyword);
            } catch(php.runtime.lang.exception.BaseBaseException pending) {
                if(pending.getReflection()==null)throw pending;
                try {
                    env.exception(trace,BaseError.class,"Invalid callback %s, (null)",callable);
                } catch(BaseError replacement) {
                    var entry=php.runtime.PhpOwnershipExecution.currentEntry(env);
                    throw entry==null ? InvokeHelper.chainFinallyException(replacement,pending) : entry.chainThrown(replacement,pending);
                }
            }
        }
    }
    private static final class RetainedStaticMagic extends MagicStaticMethodInvoker {
        private final RelativeCallbackWarning warning;
        private RetainedStaticMagic(Environment env,TraceInfo trace,MagicStaticMethodInvoker target,RelativeCallbackWarning warning) {
            super(env,trace,target.getCalledClass(),target.getMethod(),target.methodName.toString());this.warning=warning;
        }
        @Override protected Memory invoke(Memory...args)throws Throwable {warning.before(env,trace);return super.invoke(args);}
    }
    private static final class RetainedDynamicMagic extends MagicDynamicMethodInvoker {
        private final RelativeCallbackWarning warning;
        private RetainedDynamicMagic(Environment env,TraceInfo trace,MagicDynamicMethodInvoker target,RelativeCallbackWarning warning) {
            super(env,trace,target.getObject(),target.getMethod(),target.methodName.toString());this.warning=warning;
        }
        @Override protected Memory invoke(Memory...args)throws Throwable {warning.before(env,trace);return super.invoke(args);}
    }
    /** Discarded invocation bypasses Invoker.invoke, so preserve its retained callback diagnostic here too. */
    public static void beforeRetainedDiscardedInvocation(Invoker target)throws Throwable {
        if(target instanceof RetainedStaticMagic statik)statik.warning.before(target.getEnvironment(),target.trace);
        else if(target instanceof RetainedDynamicMagic dynamic)dynamic.warning.before(target.getEnvironment(),target.trace);
    }

    public static PhpCallFrame acquireFromCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable, env, trace, 0, true, true);
    }

    private static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity, boolean acquisition, boolean callerScope) {
        return callable(callable,env,trace,capacity,acquisition,callerScope,false);
    }
    private static void invalidCallable(boolean internal,String reason,Environment env,TraceInfo trace,String message,Object... args) {
        if(internal)PhpMethodResolver.rejectCallback(reason);
        env.exception(trace,BaseError.class,message,args);
    }
    private static Invoker internalCallbackStatic(Memory receiver, Memory method, Environment env, TraceInfo trace,
            php.runtime.reflection.ClassEntity scope, php.runtime.lang.IObject object, boolean callerScope, boolean quietAutoload, boolean arrayCallback, boolean closureCapture) {
        String name = receiver.toString();
        boolean relative = callerScope && ("self".equalsIgnoreCase(name) || "parent".equalsIgnoreCase(name)
                || "static".equalsIgnoreCase(name));
        var caller = env.peekCall(1);
        String called = caller == null ? null : caller.object instanceof Closure
                ? ((Closure) caller.object).getCalledClass() : caller.staticClazz != null ? caller.staticClazz : caller.clazz;
        var calledClass = called == null ? null : env.fetchClass(called, false);
        php.runtime.reflection.ClassEntity clazz;
        if (relative) {
            String keyword = name.toLowerCase(java.util.Locale.ROOT);
            if (scope == null) PhpMethodResolver.rejectCallback("cannot access \"" + keyword + "\" when no class scope is active");
            clazz = "parent".equals(keyword) ? scope.getParent() : "static".equals(keyword) ? calledClass : scope;
            if (clazz == null) PhpMethodResolver.rejectCallback("cannot access \"" + keyword + "\" when current class scope has no parent");
            try {
                env.error(trace, ErrorType.E_DEPRECATED, "Use of \"%s\" in callables is deprecated", name);
            } catch (php.runtime.lang.exception.BaseBaseException failure) {
                if (!quietAutoload || failure.getReflection()==null) throw failure;
                PhpMethodResolver.rejectCallback(null,failure);
            }
        } else {
            try {
                clazz = PhpMethodResolver.resolveCallbackClass(receiver, env, trace);
            } catch (php.runtime.lang.exception.BaseBaseException failure) {
                if (!quietAutoload || failure.getReflection()==null) throw failure;
                // A PHP autoloader failure rejects this acquisition, even if it defined the class first.
                PhpMethodResolver.rejectCallback("class \"" + name + "\" not found",failure);
                return null;
            }
        }
        Invoker target = arrayCallback ? PhpMethodResolver.internalArrayStatic(clazz,method,env,trace,scope,object,relative,closureCapture)
                : closureCapture ? PhpMethodResolver.internalCaptureStatic(clazz,method,env,trace,scope,object,relative)
                : PhpMethodResolver.internalStatic(clazz,method,env,trace,scope,object,relative);
        // A compatible caller object forwards its runtime class; relative forms also forward static scope.
        if (target instanceof StaticMethodInvoker statik) {
            var forwarded = object != null && object.getReflection().isInstanceOf(clazz)
                    && (relative || scope != null && scope.isInstanceOf(clazz) || target instanceof MagicStaticMethodInvoker)
                    ? object.getReflection() : relative && calledClass != null && calledClass.isInstanceOf(clazz) ? calledClass : clazz;
            if (forwarded != clazz) target = target instanceof MagicStaticMethodInvoker magic
                    ? new MagicStaticMethodInvoker(env,trace,forwarded.getName(),statik.getMethod(),magic.methodName.toString())
                    : new StaticMethodInvoker(env,trace,forwarded.getName(),statik.getMethod());
        }
        return target;
    }

    private static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity, boolean acquisition, boolean callerScope,boolean internal) {
        return callable(callable,env,trace,capacity,acquisition,callerScope,internal,false);
    }
    private static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity, boolean acquisition, boolean callerScope,boolean internal,boolean quietAutoload) {
        return callable(callable,env,trace,capacity,acquisition,callerScope,internal,quietAutoload,false);
    }
    private static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity, boolean acquisition, boolean callerScope,boolean internal,boolean quietAutoload,boolean closureCapture) {
        requirePassiveResolution(env);
        var scope = env.getLastClassOnStack();
        php.runtime.lang.IObject object = internal ? PhpMethodResolver.lateObject(env) : env.getLateObject();
        if (callerScope) {
            var caller = env.peekCall(1);
            object = caller == null ? null : caller.object;
            if (object instanceof Closure) {
                String name = ((Closure) object).getScope();
                scope = name == null ? null : env.fetchClass(name, false);
                Memory self = ((Closure) object).getSelf();
                object = self.isObject() ? self.toValue(php.runtime.memory.ObjectMemory.class).value : null;
            } else scope = caller == null || caller.clazz == null ? null : env.fetchClass(caller.clazz, false);
        }
        Memory value = callable.toValue();
        if (value.isString() && !value.toString().contains("::")) {
            FunctionEntity function = env.fetchFunction(value.toString());
            if (function == null) invalidCallable(internal,"function \""+value+"\" not found or invalid function name",env,trace,
                    "Call to undefined function %s()",value.toString());
            return new PhpCallFrame(env, trace, function, null, capacity);
        }
        if (value.isArray()) {
            ArrayMemory array = value.toValue(ArrayMemory.class);
            if (array.size() != 2) invalidCallable(internal,"array callback must have exactly two members",env,trace,"Array callback must have exactly two elements");
            if (array.valueOfIndex(0).isUndefined() || array.valueOfIndex(1).isUndefined())
                invalidCallable(internal,"array callback has to contain indices 0 and 1",env,trace,"Array callback has to contain indices 0 and 1");
            Memory receiver = array.valueOfIndex(0);
            if (!receiver.isString() && !receiver.isObject())
                invalidCallable(internal,"first array member is not a valid class name or object",env,trace,"First array member is not a valid class name or object");
            if (!array.valueOfIndex(1).isString())
                invalidCallable(internal,"second array member is not a valid method",env,trace,"Second array member is not a valid method");
            Invoker target = internal
                    ? receiver.isObject() ? PhpMethodResolver.internalInstance(receiver,array.valueOfIndex(1),env,trace,scope)
                    : internalCallbackStatic(receiver,array.valueOfIndex(1),env,trace,scope,object,callerScope,quietAutoload,true,closureCapture)
                    : receiver.isObject() ? PhpMethodResolver.instance(receiver,array.valueOfIndex(1),env,trace,scope)
                    : acquisition ? PhpMethodResolver.acquireStatic(PhpMethodResolver.resolveClass(receiver,env,trace),array.valueOfIndex(1),env,trace,scope,object)
                    : PhpMethodResolver.staticCallable(PhpMethodResolver.resolveClass(receiver,env,trace),array.valueOfIndex(1),env,trace);
            return new PhpCallFrame(env, trace, null, target, capacity);
        }
        if (value.isString()) {
            String text = value.toString();
            int separator = text.lastIndexOf("::");
            var className=new php.runtime.memory.StringMemory(text.substring(0,separator));
            var methodName=new php.runtime.memory.StringMemory(text.substring(separator+2));
            Invoker target;
            if (internal) target = internalCallbackStatic(className,methodName,env,trace,scope,object,callerScope,quietAutoload,false,closureCapture);
            else {
                var clazz = PhpMethodResolver.resolveClass(className,env,trace);
                target = acquisition ? PhpMethodResolver.acquireStatic(clazz,methodName,env,trace,scope,object)
                        : PhpMethodResolver.staticCallable(clazz,methodName,env,trace);
            }
            return new PhpCallFrame(env, trace, null, target, capacity);
        }
        if (!value.isObject()) invalidCallable(internal,"no array or string given",env,trace,"Value of type %s is not callable",
                php.runtime.reflection.support.PhpTypeDeclaration.valueType(value));
        // An invokable object bypasses visibility of __invoke; named object callbacks do not.
        Invoker invoker = Invoker.valueOf(env, null, value);
        if (invoker == null) invalidCallable(internal,"no array or string given",env,trace,"Object of type %s is not callable",
                value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName());
        invoker.setTrace(trace);
        return new PhpCallFrame(env, trace, null, invoker, capacity);
    }

    /** Retain the already resolved target without acquiring the callback a second time. */
    public Invoker resolvedTarget() {
        openSend();
        return function == null ? invoker : new FunctionInvoker(env,trace,function);
    }

    public Memory capture() {
        return PhpCallableFactory.capture(env, trace, function, invoker);
    }

    private void openSend() {
        if (closed || invoked) throw new IllegalStateException("Call SEND scope has closed");
    }
    private PhpArgumentBinder binding() {
        openSend();
        if (ownershipRoots != null)
            throw new IllegalArgumentException("Named/unpacked calls lack a complete certified SEND descriptor");
        if (binder == null) {
            var signature = function == null && invoker == null && constructedObject != null
                    ? new PhpArgumentBinder.Signature(constructedObject.value.getReflection(), new ParameterEntity[0], true, false)
                    : PhpNamedArgumentTarget.resolve(function, invoker, parameters, capacity);
            binder = new PhpArgumentBinder(this, env, trace, signature,
                    originMethod == null ? originClass : originClass + "::" + originMethod);
            // Existing positional SENDs already contain snapshots/exports; never evaluate or send them again.
            for (int i = 0; i < size; i++) {binder.preparePositional();binder.send(arguments[i]);}
        }
        return binder;
    }
    /** Prepare before evaluating the expression; lookup errors are committed only by its SEND. */
    public void prepareNamed(String name) {binding().prepareNamed(name);}
    public void preparePositional() {
        openSend();if (binder != null) binder.preparePositional();
    }
    public BoundArguments finishBinding() {
        if (bound == null) bound = binding().finish();
        return bound;
    }

    private ParameterEntity parameter() {
        if (binder != null) {
            if (!binder.hasPendingDestination()) binder.preparePositional();
            return binder.parameter();
        }
        if (parameters == null || parameters.length == 0) return null;
        if (size < parameters.length) return parameters[size];
        ParameterEntity last = parameters[parameters.length - 1];
        return last.isVariadic() ? last : null;
    }

    /** Legacy @Reference also marks borrowed zero-copy inputs; it is not always PHP SEND_REF. */
    public static boolean nativeArgumentByReference(CompileFunction.Method method, int javaIndex) {
        if (!method.references[javaIndex]) return false;
        var declaration=method.method.getAnnotation(php.runtime.annotation.Reflection.Signature.class);
        if (declaration != null && java.util.Arrays.stream(declaration.value()).noneMatch(
                php.runtime.annotation.Reflection.Arg::reference)) return false;
        String owner=method.method.getDeclaringClass().getName();
        if (owner.equals("php.runtime.ext.core.LangFunctions")
                || owner.equals("org.develnext.jphp.zend.ext.standard.ArrayFunctions")) {
            switch (method.method.getName()) {
                case "in_array": case "array_key_exists": case "key_exists":
                case "current": case "key": case "pos":
                case "is_array": case "is_iterable": case "is_object": case "is_resource":
                case "get_resource_type": case "is_callable":
                case "set_error_handler": case "set_exception_handler": case "register_shutdown_function":
                    return false;
                default: break;
            }
        }
        return true;
    }

    public boolean nextArgumentIsReference() {
        ParameterEntity parameter=parameter();
        if (binder != null) {
            if (binder.hasPendingError()) return false;
            if (binder.signature().completeNames()) return parameter != null && parameter.isReference();
        }
        return parameter != null && parameter.isReference() || nativeReference();
    }

    public static Memory argumentProperty(Memory receiver, String property, Environment env, TraceInfo trace,
                                         php.runtime.invoke.cache.PropertyCallCache cache, int index, boolean byReference)
            throws Throwable {
        if (!byReference) return ObjectInvokeHelper.getProperty(receiver,property,env,trace,cache,index);
        Memory value=receiver.toValue();
        if (!value.isObject()) {
            String type=value.isNull() || value.isUndefined() ? "null"
                    : value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false")
                    : php.runtime.reflection.support.PhpTypeDeclaration.valueType(value);
            env.exception(trace,BaseError.class,"Attempt to modify property \"%s\" on %s",property,type);
        }
        return ObjectInvokeHelper.getRefProperty(receiver,property,env,trace,cache,index);
    }

    public static Memory argumentStaticProperty(String clazz, String lowerClazz, String property,
                                               Environment env, TraceInfo trace,
                                               php.runtime.invoke.cache.PropertyCallCache cache, int index,
                                               boolean lateStatic, boolean byReference) throws Throwable {
        return byReference ? ObjectInvokeHelper.getStaticProperty(clazz,lowerClazz,property,env,trace,cache,index,lateStatic)
                : ObjectInvokeHelper.readStaticProperty(clazz,lowerClazz,property,env,trace,cache,index,lateStatic);
    }

    public static Memory argumentArrayProperty(Memory receiver, String property, Environment env, TraceInfo trace,
                                              php.runtime.invoke.cache.PropertyCallCache cache, int index, boolean byReference) {
        return new ArrayArgumentProperty(receiver, null, null, property, env, trace, cache, index, false, byReference);
    }

    public static Memory argumentArrayStaticProperty(String clazz, String lowerClazz, String property, Environment env,
                                                     TraceInfo trace, php.runtime.invoke.cache.PropertyCallCache cache,
                                                     int index, boolean lateStatic, boolean byReference) {
        return new ArrayArgumentProperty(null, clazz, lowerClazz, property, env, trace, cache, index, lateStatic, byReference);
    }

    // Opaque capture: FETCH_OBJ/STATIC_PROP_FUNC_ARG resolves only after the offset expression.
    private static final class ArrayArgumentProperty extends ReferenceMemory {
        private final Memory receiver;
        private final String clazz, lowerClazz, property;
        private final Environment env;
        private final TraceInfo trace;
        private final php.runtime.invoke.cache.PropertyCallCache cache;
        private final int index;
        private final boolean lateStatic, byReference;
        private ArrayArgumentProperty(Memory receiver, String clazz, String lowerClazz, String property, Environment env,
                                      TraceInfo trace, php.runtime.invoke.cache.PropertyCallCache cache, int index,
                                      boolean lateStatic, boolean byReference) {
            this.receiver=receiver;this.clazz=clazz;this.lowerClazz=lowerClazz;this.property=property;
            this.env=env;this.trace=trace;this.cache=cache;this.index=index;this.lateStatic=lateStatic;this.byReference=byReference;
        }
        private Memory resolve() throws Throwable {
            return clazz == null ? argumentProperty(receiver,property,env,trace,cache,index,byReference)
                    : argumentStaticProperty(clazz,lowerClazz,property,env,trace,cache,index,lateStatic,byReference);
        }
    }

    public static Memory argumentIndex(Memory root, Memory key, Environment env, TraceInfo trace, boolean byReference) throws Throwable {
        if (root instanceof ArrayArgumentProperty) root=((ArrayArgumentProperty)root).resolve();
        key=key.toValue();
        Memory value = root.toValue();
        if (!byReference) {
            key=argumentOffsetKey(value,key,env,trace);
            if (value.isArray()) {
                Object scalar=ArrayMemory.toKey(key);
                if (value.toValue(ArrayMemory.class).getByScalar(scalar) == null) {
                    env.error(trace,ErrorType.E_WARNING,scalar instanceof String
                            ? "Undefined array key \"%s\"" : "Undefined array key %s",scalar);
                    return Memory.NULL;
                }
            } else if (!value.isString() && !value.isObject()) {
                String type=value.isNull() || value.isUndefined() ? "null"
                        : value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false")
                        : php.runtime.reflection.support.PhpTypeDeclaration.valueType(value);
                env.error(trace,ErrorType.E_WARNING,"Trying to access array offset on %s",type);
                return Memory.NULL;
            }
            if (value.isString()) return php.runtime.PhpStringOffsets.read(root,trace,key,env);
            return root.valueOfIndex(trace,key);
        }
        if (root instanceof php.runtime.memory.CharMemory)
            env.exception(trace, BaseError.class, "Cannot use string offset as an array");
        if (value.isNull() || value.isUndefined() || value.getRealType() == Memory.Type.BOOL && !value.toBoolean()) {
            if (!value.isNull() && !value.isUndefined()) env.error(trace, ErrorType.E_DEPRECATED,
                    "Automatic conversion of false to array is deprecated");
            if (root instanceof ReferenceMemory) ((ReferenceMemory) root).initializeArray();
            else root.assign(new ArrayMemory());
            value = root.toValue();
        }
        key=argumentOffsetKey(value,key,env,trace);
        if (value.isObject() && !(value.toValue(php.runtime.memory.ObjectMemory.class).value instanceof php.runtime.lang.spl.ArrayAccess))
            env.exception(trace, BaseError.class, "Cannot use object of type %s as array",
                    php.runtime.reflection.support.PhpTypeDeclaration.valueType(value));
        if (!value.isArray() && !value.isString() && !value.isObject())
            env.exception(trace, BaseError.class, "Cannot use a scalar value as an array");
        if (value.isString()) return php.runtime.PhpStringOffsets.reference(root,trace,key,env);
        return root.refOfIndex(trace, key);
    }

    private static Memory argumentOffsetKey(Memory container, Memory key, Environment env, TraceInfo trace) {
        if (container.isArray()) {
            if (key.isArray() || key.isObject()) env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,
                    "Cannot access offset of type %s on array",php.runtime.reflection.support.PhpTypeDeclaration.valueType(key));
            if (key.isNull() || key.isUndefined()) {
                env.error(trace,ErrorType.E_DEPRECATED,"Using null as an array offset is deprecated, use an empty string instead");
                return php.runtime.memory.StringMemory.valueOf("");
            }
        } else if (container.isString() && (key.isNull() || key.isUndefined())) {
            env.error(trace,ErrorType.E_WARNING,"String offset cast occurred");
            return Memory.CONST_INT_0;
        }
        return key;
    }

    public static Memory argumentAppend(Memory root, Environment env, TraceInfo trace, boolean byReference) throws Throwable {
        if (root instanceof ArrayArgumentProperty) root=((ArrayArgumentProperty)root).resolve();
        if (!byReference) env.exception(trace, BaseError.class, "Cannot use [] for reading");
        if (root instanceof php.runtime.memory.CharMemory)
            env.exception(trace, BaseError.class, "Cannot use string offset as an array");
        return ArrayMemory.appendReferencePhp8(root, env, trace);
    }

    public static Memory referenceNativeArgument(Memory value) {
        Memory reference=ReferenceMemory.exportReference(value);
        if (reference.isArray()) reference.toValue(ArrayMemory.class).getCurrentIteratorForNative();
        return reference;
    }

    public static Memory borrowNativeFastArgument(Memory value) {
        // A direct known-native SEND may retain an existing scalar CELL until parsing.
        // Dynamic by-value SENDs instead snapshot it in borrowNativeArgument below.
        if (value instanceof ReferenceMemory reference) {
            var cell=reference.getReference();
            if (cell.isCanonicalReferenceCell() && !cell.toValue().isArray()) return cell;
        }
        return borrowNativeArgument(value);
    }

    public static Memory borrowNativeArgument(Memory value) {
        value=value.toValue();
        Memory snapshot=value.isArray() ? value.toValue(ArrayMemory.class).toImmutableForNativeRead() : value.toImmutable();
        return new ReferenceMemory(snapshot);
    }

    private CompileFunction.Method argumentNativeMethod() {
        if (binder == null || !binder.signature().completeNames() || nativeTarget == null) return nativeMethod;
        return nativeTarget.getCompileFunction().find(Math.max(binder.signature().fixedCount(),
                nativeTarget.getCompileFunction().getMinArgs()));
    }

    private int nativeParameterIndex() {
        CompileFunction.Method selected = argumentNativeMethod();
        if (selected == null) return -1;
        int destination = binder == null ? size : binder.parameterIndex();
        int index = 0;
        for (int i = 0; i < selected.parameterTypes.length; i++) {
            Class<?> type = selected.parameterTypes[i];
            if (type == Environment.class || type == TraceInfo.class
                    || selected.isPresentAnnotationOfParam(i, php.runtime.annotation.Runtime.GetLocals.class)) continue;
            if (index == destination || type == Memory[].class) return i;
            index++;
        }
        return -1;
    }

    private boolean nativeReference() {
        if (binder != null && binder.signature().completeNames()) {
            var parameter = parameter();return parameter != null && parameter.isReference();
        }
        int index=nativeParameterIndex();
        return index >= 0 && nativeArgumentByReference(nativeMethod,index);
    }

    private boolean nativeBorrowedReference() {
        int index=nativeParameterIndex();
        var selected = argumentNativeMethod();
        return index >= 0 && selected.references[index] && !nativeReference();
    }

    public void send(Memory value, int sourceKind) {
        openSend();
        if (ownershipRoots != null) throw new IllegalArgumentException("Owned SEND requires its explicit semantic binding");
        if (value instanceof VariadicMemory) {
            sendSpread(value);
            return;
        }
        ParameterEntity parameter = parameter();
        if (binder != null) binder.validateSend();
        if (parameter != null && parameter.isReference() || nativeReference())
            ReferenceMemory.rejectStringOffsetReference(value, env, trace);
        if (parameter != null && parameter.isReference()) {
            if (sourceKind == VALUE || (sourceKind == VARIABLE && !value.isReference())) {
                InvokeArgumentHelper.referenceArgumentError(env, trace, parameter,
                        binder == null ? size : binder.parameterIndex(), originClass, originMethod);
            }
            if (!value.isReference()) {
                env.error(trace, ErrorType.E_NOTICE, "Only variables should be passed by reference");
                value = new ReferenceMemory(value.toImmutable());
            }
            value = php.runtime.PhpPrimitiveCellRuntime.exportSend(this,value);
        } else if (nativeReference() && value.isReference()) {
            value = referenceNativeArgument(value);
        } else if (nativeBorrowedReference()) {
            // Old native bodies expect a wrapper even for read-only PHP parameters.
            // Dynamic native SEND captures a VALUE; it never promotes or retains a CELL.
            value = borrowNativeArgument(value);
        } else {
            value = value.toImmutable();
        }
        append(value);
    }

    /** Internal indirect calls warn/use a temporary; direct source calls retain SEND_REF errors. */
    public void sendIndirect(Memory value, int sourceKind) {
        openSend();
        if (sourceKind < VARIABLE || sourceKind > VALUE) throw new IllegalArgumentException("Unknown SEND source kind");
        var parameter = parameter();
        if (binder != null) binder.validateSend();
        if (parameter != null && parameter.isReference() && !value.isReference()) {
            int index = binder == null ? size : binder.parameterIndex();
            if (parameter.isVariadic())
                env.warning(trace, "%s(): Argument #%d must be passed by reference, value given",
                        originMethod == null ? originClass : originClass + "::" + originMethod, index + 1);
            else env.warning(trace, "%s(): Argument #%d ($%s) must be passed by reference, value given",
                        originMethod == null ? originClass : originClass + "::" + originMethod,
                        index + 1, parameter.getName());
            value = new ReferenceMemory(value.toImmutable());
        }
        send(value, VARIABLE);
    }

    /** A keyed runtime argument array is already evaluated; iteration/SEND still follows actual order. */
    public Memory invokeKeyed(ArrayMemory values, boolean internalCall) throws Throwable {return invokeKeyed(values,internalCall,false);}
    public Memory invokeKeyedDiscarded(ArrayMemory values,boolean internalCall) throws Throwable {return invokeKeyed(values,internalCall,true);}
    private Memory invokeKeyed(ArrayMemory values, boolean internalCall,boolean discarded) throws Throwable {
        try {
            binding().beginKeyed();
            var iterator = values.getNewIterator(env, false, false);
            while (iterator.next()) {
                binder.prepareKeyed(iterator.getMemoryKey());
                Memory value = iterator.getValue();
                var parameter = parameter();binder.validateSend();
                // An indirect array element must already be a reference; do not promote every bucket.
                if (parameter != null && parameter.isReference()) {
                    Memory carrier = values.getByScalar(ArrayMemory.toKey(iterator.getMemoryKey()));
                    if (carrier instanceof ReferenceMemory reference && reference.getReference().isCanonicalReferenceCell())
                        value = ReferenceMemory.exportReference(reference);
                }
                sendIndirect(value, VARIABLE);
            }
            return invokeBound(finishBinding(), internalCall,discarded);
        } catch (Throwable failure) {throw fail(failure);}
    }

    private void append(Memory value) {
        if (binder != null) binder.send(value);
        if (size == arguments.length) arguments = Arrays.copyOf(arguments, Math.max(4, size * 2));
        arguments[size++] = value;
    }

    public void sendSpread(Memory value) {
        openSend();
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            binding().beginUnpack();
        boolean array = value.isArray();
        ForeachIterator iterator = value.getNewIterator(env, false, false);
        if (iterator == null) {
            String message = "Only arrays and Traversables can be unpacked";
            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                message += ", " + php.runtime.reflection.support.PhpTypeDeclaration.valueType(value) + " given";
            env.exception(trace, php.runtime.lang.exception.BaseTypeError.class, message);
            return;
        }
        while (iterator.next()) {
            if (binder != null) binder.prepareUnpacked(iterator.getMemoryKey());
            Memory element = iterator.getValue();
            ParameterEntity parameter = parameter();
            if (binder != null) binder.validateSend();
            if (array && (parameter != null && parameter.isReference() || nativeReference())) {
                element = value.toValue(ArrayMemory.class).refOfIndexAsShortcut(trace, iterator.getMemoryKey());
            }
            if (!array && parameter != null && parameter.isReference()) {
                env.warning(trace, "Cannot pass by-reference argument %d of %s() by unpacking a Traversable, passing by-value instead",
                        (binder == null ? size : binder.parameterIndex()) + 1,
                        originMethod == null ? originClass : originClass + "::" + originMethod);
                element = new ReferenceMemory(element.toImmutable());
            }
            send(element, VARIABLE);
        }
    }

    private static final ThreadLocal<PhpCallFrame> PRIMITIVE_INVOCATION=new ThreadLocal<>();
    /** Exact active invoker, retained in the already allocated call frame; no per-call custody allocation. */
    public static boolean primitiveReturnTarget(Closure closure) {
        var actual=PRIMITIVE_INVOCATION.get();
        return actual!=null&&actual.invoker instanceof DynamicMethodInvoker dynamic&&dynamic.getObject()==closure
                &&actual.capacity==0&&actual.parameters!=null&&actual.parameters.length==0
                &&!(closure instanceof PhpCallableFactory.CapturedClosure);
    }
    /** First promotion needs the actual reference-return closure before a Session exists; other calls tag only an active certified by-reference import. */
    private boolean needsPrimitiveInvocation(){
        if(invoker instanceof DynamicMethodInvoker dynamic){
            var target=dynamic.getMethod();
            if(target!=null&&target.isReturnReference()&&capacity==0&&parameters!=null
                    &&parameters.length==0&&dynamic.getObject() instanceof Closure)return true;
        }
        return function!=null&&capacity>0&&needsPrimitiveFunctionInvocation();
    }
    private boolean needsPrimitiveFunctionInvocation(){
        if(function.getClass()!=FunctionEntity.class||function.isReturnReference()
                ||parameters==null||parameters.length!=capacity)return false;
        for(var parameter:parameters)if(!parameter.isReference()||parameter.isVariadic()||parameter.getTypeChecker()!=null
                ||parameter.getDefaultInitializer()!=null||parameter.getDefaultValue()!=null)return false;
        return php.runtime.PhpPrimitiveCellRuntime.active(env);
    }
    /** Only eligible calls reenter the original body under the sparse invocation tag. */
    private Memory invokePrimitiveCold(Memory[] passed,int route)throws Throwable {return invokePrimitiveCold(passed,route,false);}
    private Memory invokePrimitiveCold(Memory[] passed,int route,boolean discarded)throws Throwable {
        PhpCallFrame previous=PRIMITIVE_INVOCATION.get();PRIMITIVE_INVOCATION.set(this);
        try {
            if(route==0)return invokeInternalBound(passed,discarded);
            if(route==1)return invokeInternal(passed,discarded);
            return invokePrepared(passed,discarded);
        }finally{restorePrimitiveInvocation(previous);}
    }

    private static void restorePrimitiveInvocation(PhpCallFrame previous) {
        PRIMITIVE_INVOCATION.set(previous);
    }

    public static PhpCallFrame primitiveInvocation(){return PRIMITIVE_INVOCATION.get();}
    public int primitiveSendIndex(){return size;}
    public boolean primitivePositionalSend(){return !closed&&!invoked&&binder==null&&size<capacity;}
    public ParameterEntity primitiveParameter(int index){return parameters!=null&&index>=0&&index<parameters.length?parameters[index]:null;}
    public php.runtime.PhpPrimitiveCellPlan.Member primitiveFunctionMember(){
        if(function==null||function.getClass()!=FunctionEntity.class||function.isInternal()||function.isReturnReference()
                ||function.getReturnTypeChecker()!=null||function.getNativeMethod()==null||function.getNativeClazz()!=function.getNativeMethod().getDeclaringClass()
                ||capacity==0||parameters==null||parameters.length!=capacity)return null;
        for(var parameter:parameters)if(!parameter.isReference()||parameter.isVariadic()||parameter.getTypeChecker()!=null
                ||parameter.getDefaultInitializer()!=null||parameter.getDefaultValue()!=null)return null;
        var method=function.getNativeMethod();
        var member=php.runtime.PhpPrimitiveCellPlan.closedMember(method.getDeclaringClass(),method.getName(),php.runtime.PhpOwnershipExecution.descriptor(method));
        return member!=null&&member.referenceArguments(capacity)?member:null;
    }
    private void primitiveBoundary() {
        if (!php.runtime.PhpPrimitiveCellRuntime.active(env)) return;
        boolean tracked = primitiveFunctionMember()!=null || invoker instanceof DynamicMethodInvoker dynamic
                && dynamic.getObject() instanceof Closure closure && !(closure instanceof PhpCallableFactory.CapturedClosure)
                && capacity==0 && parameters!=null && parameters.length==0
                && closure.getUses().length==0 && closure.getSelf().isNull()
                && dynamic.getMethod().getReturnTypeChecker()==null
                && php.runtime.PhpPrimitiveCellPlan.hasClosedMember(dynamic.getMethod().getNativeMethod().getDeclaringClass(),
                    dynamic.getMethod().getNativeMethod().getName(),php.runtime.PhpOwnershipExecution.descriptor(dynamic.getMethod().getNativeMethod()));
        if (!tracked) php.runtime.PhpPrimitiveCellRuntime.opaque(env);
    }
    public Memory invoke() throws Throwable {return invokeWithResultUse(false);}
    public Memory invokeDiscarded() throws Throwable {return invokeWithResultUse(true);}
    private Memory invokeWithResultUse(boolean discarded) throws Throwable {
        primitiveBoundary();
        if (binder != null) return invokeBound(finishBinding(),false,discarded);
        openSend();invoked = true;
        Memory[] passed = size == arguments.length ? arguments : Arrays.copyOf(arguments, size);
        try {return invokePrepared(passed,discarded);} finally {close();}
    }

    public Memory invokeBound(BoundArguments actual) throws Throwable {return invokeBound(actual, false);}
    public Memory invokeBound(BoundArguments actual, boolean internalCall) throws Throwable {return invokeBound(actual,internalCall,false);}
    public Memory invokeBoundDiscarded(BoundArguments actual,boolean internalCall) throws Throwable {return invokeBound(actual,internalCall,true);}
    private Memory invokeBound(BoundArguments actual, boolean internalCall,boolean discarded) throws Throwable {
        primitiveBoundary();
        actual.requireOwner(this, env);
        if (actual != bound || closed || invoked) throw new IllegalArgumentException("Foreign or reused bound call");
        invoked = true;
        try (var scope = actual.enter(this, env)) {
            if (invoker instanceof DynamicMethodInvoker dynamic
                    && dynamic.getObject() instanceof PhpCallableFactory.CapturedClosure captured)
                return captured.invokeBound(env, trace, actual, internalCall,discarded);
            return internalCall ? invokeInternalBound(actual.arguments(),discarded) : invokePrepared(actual.arguments(),discarded);
        } finally {close();}
    }

    /** Compiler argument-expression failure: preserve its exact primary and construction transport. */
    public Throwable fail(Throwable failure) {
        if (constructedObject != null && !invoked) PhpObjectLifecycle.failConstruction(constructedObject.value, failure);
        close();return failure;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (bound != null) bound.release();
        if (binder != null) binder.release();
        php.runtime.PhpPrimitiveCellRuntime.callClosed(this);
        Arrays.fill(arguments, null);size = 0;binder = null;bound = null;constructedObject = null;
    }

    /** Convert foreign callback failures before they cross generated PHP catch/finally tables. */
    private Memory invokeFallback(Memory[] passed,boolean discarded,boolean internal) throws Throwable {
        try {
            return discarded ? PhpNoDiscardInvocation.invokeDiscarded(env,trace,invoker,passed,internal)
                    : invoker == null ? Memory.NULL : invoker.call(passed);
        } catch (Throwable failure) {
            if (invoker instanceof RunnableInvoker)
                return env.__throwException(new java.lang.reflect.InvocationTargetException(failure));
            throw failure;
        }
    }

    private Memory invokeInternalBound(Memory[] passed) throws Throwable {return invokeInternalBound(passed,false);}
    private Memory invokeInternalBound(Memory[] passed,boolean discarded) throws Throwable {
        if(needsPrimitiveInvocation()&&PRIMITIVE_INVOCATION.get()!=this)return invokePrimitiveCold(passed,0,discarded);
        if (function instanceof CompileFunctionEntity nativeFunction) {
            var compiled = nativeFunction.getCompileFunction();
            int min = compiled.getMinArgs(), max = compiled.getMaxArgs();
            if (passed.length < min || passed.length > max) {
                int count = passed.length < min ? min : max;
                String range = min == max ? "exactly " + count : passed.length < min ? "at least " + count : "at most " + count;
                env.exception(trace, php.runtime.lang.exception.BaseArgumentCountError.class,
                        "%s() expects %s argument%s, %d given", function.getName(), range, count == 1 ? "" : "s", passed.length);
            }
        }
        Memory result;
        try {
            if (function != null) result = InvokeHelper.call(env, trace, function, passed, true,discarded);
            else if(discarded)result=invokeFallback(passed,true,true);
            else if (invoker instanceof MagicDynamicMethodInvoker || invoker instanceof MagicStaticMethodInvoker)
                result = invoker.call(passed);
            else if (invoker instanceof DynamicMethodInvoker dynamic)
                result = ObjectInvokeHelper.invokeMethod(dynamic.getObject(), dynamic.getMethod(), env, trace, passed, false, true);
            else if (invoker instanceof StaticMethodInvoker statik)
                result = InvokeHelper.callStatic(env, trace, statik.getMethod(), statik.getCalledClass(), passed, false, true);
            else result = invokeFallback(passed,false,true);
        } catch (Throwable failure) {
            if (constructedObject != null) PhpObjectLifecycle.failConstruction(constructedObject.value, failure);
            throw failure;
        }
        if (constructedObject != null) {
            PhpObjectLifecycle.completeConstruction(constructedObject.value);
            env.registerObjectInGC(constructedObject.value);
            return constructedObject;
        }
        return result;
    }

    // For runtime APIs whose argument expressions have already been evaluated.
    /** Native APIs invoke callbacks with internal argument coercion and by-value SENDs. */
    public Memory invokeInternal(Memory[] passed) throws Throwable {return invokeInternal(passed,false);}
    public Memory invokeInternalDiscarded(Memory[] passed) throws Throwable {return invokeInternal(passed,true);}
    private Memory invokeInternal(Memory[] passed,boolean discarded) throws Throwable {
        if(needsPrimitiveInvocation()&&PRIMITIVE_INVOCATION.get()!=this)return invokePrimitiveCold(passed,1,discarded);
        if (ownershipRoots != null)
            throw new IllegalArgumentException("Internal callback requires its complete actual SEND protocol");
        if (function instanceof CompileFunctionEntity nativeFunction) {
            var compiled=nativeFunction.getCompileFunction();
            int min=compiled.getMinArgs(),max=compiled.getMaxArgs();
            if(passed.length<min || passed.length>max) {
                int count=passed.length<min?min:max;
                String range=min==max?"exactly "+count:passed.length<min?"at least "+count:"at most "+count;
                env.exception(trace,php.runtime.lang.exception.BaseArgumentCountError.class,
                        "%s() expects %s argument%s, %d given",function.getName(),range,count==1?"":"s",passed.length);
            }
        }
        passed=InvokeArgumentHelper.prepareIndirectArguments(env,trace,passed,parameters,
                originMethod == null ? originClass : originClass + "::" + originMethod);
        if (function != null) return InvokeHelper.call(env,trace,function,passed,true,discarded);
        if(discarded)return invokeFallback(passed,true,true);
        if (invoker instanceof MagicDynamicMethodInvoker || invoker instanceof MagicStaticMethodInvoker)
            return invoker.call(passed);
        if (invoker instanceof DynamicMethodInvoker dynamic) {
            var target=dynamic.getObject() instanceof PhpCallableFactory.CapturedClosure
                    ? ((PhpCallableFactory.CapturedClosure)dynamic.getObject()).getFunction() : null;
            var context=target instanceof CompileFunctionEntity ? NativeFunctionContext.enterWeak(env,trace,target.getName()) : null;
            try {return ObjectInvokeHelper.invokeMethod(dynamic.getObject(),dynamic.getMethod(),env,trace,passed,false,true);}
            finally {if(context != null) context.close();}
        }
        if (invoker instanceof StaticMethodInvoker statik)
            return InvokeHelper.callStatic(env,trace,statik.getMethod(),statik.getCalledClass(),passed,false,true);
        return invokeFallback(passed,false,true);
    }

    public Memory invokePrepared(Memory[] passed) throws Throwable {return invokePrepared(passed,false);}
    public Memory invokePreparedDiscarded(Memory[] passed) throws Throwable {return invokePrepared(passed,true);}
    private Memory invokePrepared(Memory[] passed,boolean discarded) throws Throwable {
        if(needsPrimitiveInvocation()&&PRIMITIVE_INVOCATION.get()!=this)return invokePrimitiveCold(passed,2,discarded);
        primitiveBoundary();
        if (ownershipRoots != null) {
            if (function != null) {
                Memory[] actual=ownershipRoots.rawArguments();
                if (passed.length != actual.length) throw new IllegalArgumentException("Prepared vector differs from actual SEND roots");
                for (int i=0;i<actual.length;i++) if (passed[i] != actual[i]) throw new IllegalArgumentException("Prepared argument is not the published SEND value");
                return ownershipRoots.invokeFunction(function,trace,discarded);
            }
            return ownershipRoots.invokeInvoker(invoker,passed,trace,discarded);
        }
        Memory result;
        try {
            if(constructedObject!=null && constructedObject.value instanceof php.runtime.lang.exception.BaseBaseException exception
                    && env.getModuleManager().getPhpLanguageProfile(trace)==php.runtime.common.PhpLanguageProfile.PHP_8_5)
                exception.setTraceInfo(env,trace);
            result = function != null ? InvokeHelper.call(env, trace, function, passed,false,discarded)
                    : invokeFallback(passed,discarded,false);
        } catch (Throwable failure) {
            if (constructedObject != null) PhpObjectLifecycle.failConstruction(constructedObject.value, failure);
            throw failure;
        }
        if (constructedObject != null) {
            PhpObjectLifecycle.completeConstruction(constructedObject.value);
            env.registerObjectInGC(constructedObject.value);
            return constructedObject;
        }
        return result;
    }
}
