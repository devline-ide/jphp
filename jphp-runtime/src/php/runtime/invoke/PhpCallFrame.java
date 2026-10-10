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
public final class PhpCallFrame {
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
    private php.runtime.memory.ObjectMemory constructedObject;

    private PhpCallFrame(Environment env, TraceInfo trace, FunctionEntity function, Invoker invoker, int capacity) {
        this.env = env;
        this.trace = trace;
        this.function = function;
        this.invoker = invoker;
        this.arguments = new Memory[capacity];
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

    public static PhpCallFrame method(Memory receiver, Memory name, Environment env, TraceInfo trace, int capacity) {
        return new PhpCallFrame(env, trace, null, PhpMethodResolver.instance(receiver, name, env, trace), capacity);
    }

    public static PhpCallFrame staticMethod(Environment env, TraceInfo trace, String className,
                                             Memory name, MethodCallCache cache, int index,
                                             boolean forwarding, int capacity) {
        return staticResolved(env, trace, PhpMethodResolver.resolveClass(new php.runtime.memory.StringMemory(className), env, trace),
                name, cache, index, forwarding, capacity);
    }

    public static PhpCallFrame staticResolved(Environment env, TraceInfo trace, php.runtime.reflection.ClassEntity clazz,
                                               Memory name, MethodCallCache cache, int index, boolean forwarding, int capacity) {
        return new PhpCallFrame(env, trace, null, PhpMethodResolver.stat(clazz, name, env, trace, cache, index, forwarding), capacity);
    }

    public static PhpCallFrame construct(Memory name, Environment env, TraceInfo trace, ClassCallCache cache,
                                          int index, int capacity) throws Throwable {
        var clazz = cache == null ? null : cache.get(env, index);
        if (clazz == null) {
            clazz = PhpMethodResolver.resolveClass(name, env, trace);
            if (cache != null) cache.put(env, index, clazz);
        }
        clazz.checkCanInstance(env, trace);
        // Access must be checked before allocating properties and before any arguments.
        PhpMethodResolver.checkConstructor(clazz, env, trace);
        var object = clazz.newObject(env, trace, false, false);
        Invoker constructor = clazz.methodConstruct == null ? null : new DynamicMethodInvoker(env, trace, object, clazz.methodConstruct);
        var frame = new PhpCallFrame(env, trace, null, constructor, capacity);
        frame.constructedObject = new php.runtime.memory.ObjectMemory(object);
        return frame;
    }

    public static PhpCallFrame function(Environment env, TraceInfo trace, String lowerName, String name,
                                         FunctionCallCache cache, int cacheIndex, int capacity) {
        return new PhpCallFrame(env, trace,
                InvokeHelper.resolveFunction(env, trace, lowerName, name, cache, cacheIndex), null, capacity);
    }

    public static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity) {
        return callable(callable, env, trace, capacity, false, false);
    }

    public static PhpCallFrame acquireCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable, env, trace, 0, true, false);
    }

    public static PhpCallFrame acquireFromCallable(Memory callable, Environment env, TraceInfo trace) {
        return callable(callable, env, trace, 0, true, true);
    }

    private static PhpCallFrame callable(Memory callable, Environment env, TraceInfo trace, int capacity, boolean acquisition, boolean callerScope) {
        var scope = env.getLastClassOnStack();
        php.runtime.lang.IObject object = env.getLateObject();
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
            if (function == null) env.exception(trace, BaseError.class,
                    "Call to undefined function %s()", value.toString());
            return new PhpCallFrame(env, trace, function, null, capacity);
        }
        if (value.isArray()) {
            ArrayMemory array = value.toValue(ArrayMemory.class);
            if (array.size() != 2) env.exception(trace, BaseError.class, "Array callback must have exactly two elements");
            if (array.valueOfIndex(0).isUndefined() || array.valueOfIndex(1).isUndefined())
                env.exception(trace, BaseError.class, "Array callback has to contain indices 0 and 1");
            if (!array.valueOfIndex(1).isString())
                env.exception(trace, BaseError.class, "Second array member is not a valid method");
            Memory receiver = array.valueOfIndex(0);
            if (!receiver.isString() && !receiver.isObject())
                env.exception(trace, BaseError.class, "First array member is not a valid class name or object");
            Invoker target = receiver.isObject()
                    ? PhpMethodResolver.instance(receiver, array.valueOfIndex(1), env, trace, scope)
                    : acquisition ? PhpMethodResolver.acquireStatic(PhpMethodResolver.resolveClass(receiver, env, trace), array.valueOfIndex(1), env, trace, scope, object)
                    : PhpMethodResolver.staticCallable(PhpMethodResolver.resolveClass(receiver, env, trace), array.valueOfIndex(1), env, trace);
            return new PhpCallFrame(env, trace, null, target, capacity);
        }
        if (value.isString()) {
            String text = value.toString();
            int separator = text.lastIndexOf("::");
            var clazz = PhpMethodResolver.resolveClass(new php.runtime.memory.StringMemory(text.substring(0, separator)), env, trace);
            var target = acquisition ? PhpMethodResolver.acquireStatic(clazz, new php.runtime.memory.StringMemory(text.substring(separator + 2)), env, trace, scope, object)
                    : PhpMethodResolver.staticCallable(clazz, new php.runtime.memory.StringMemory(text.substring(separator + 2)), env, trace);
            return new PhpCallFrame(env, trace, null, target, capacity);
        }
        if (!value.isObject()) env.exception(trace, BaseError.class, "Value of type %s is not callable",
                php.runtime.reflection.support.PhpTypeDeclaration.valueType(value));
        // An invokable object bypasses visibility of __invoke; named object callbacks do not.
        Invoker invoker = Invoker.valueOf(env, null, value);
        if (invoker == null) env.exception(trace, BaseError.class, "Object of type %s is not callable",
                value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName());
        invoker.setTrace(trace);
        return new PhpCallFrame(env, trace, null, invoker, capacity);
    }

    public Memory capture() {
        return PhpCallableFactory.capture(env, trace, function, invoker);
    }

    private ParameterEntity parameter() {
        if (parameters == null || parameters.length == 0) return null;
        if (size < parameters.length) return parameters[size];
        ParameterEntity last = parameters[parameters.length - 1];
        return last.isVariadic() ? last : null;
    }

    /** Legacy @Reference also marks borrowed zero-copy inputs; it is not always PHP SEND_REF. */
    public static boolean nativeArgumentByReference(CompileFunction.Method method, int javaIndex) {
        if (!method.references[javaIndex]) return false;
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

    public static Memory borrowNativeArgument(Memory value) {
        value=value.toValue();
        Memory snapshot=value.isArray() ? value.toValue(ArrayMemory.class).toImmutableForNativeRead() : value.toImmutable();
        return new ReferenceMemory(snapshot);
    }

    private int nativeParameterIndex() {
        if (nativeMethod == null) return -1;
        int index = 0;
        for (int i = 0; i < nativeMethod.parameterTypes.length; i++) {
            Class<?> type = nativeMethod.parameterTypes[i];
            if (type == Environment.class || type == TraceInfo.class
                    || nativeMethod.isPresentAnnotationOfParam(i, php.runtime.annotation.Runtime.GetLocals.class)) continue;
            if (index == size || type == Memory[].class) return i;
            index++;
        }
        return -1;
    }

    private boolean nativeReference() {
        int index=nativeParameterIndex();
        return index >= 0 && nativeArgumentByReference(nativeMethod,index);
    }

    private boolean nativeBorrowedReference() {
        int index=nativeParameterIndex();
        return index >= 0 && nativeMethod.references[index] && !nativeArgumentByReference(nativeMethod,index);
    }

    public void send(Memory value, int sourceKind) {
        if (value instanceof VariadicMemory) {
            sendSpread(value);
            return;
        }
        ParameterEntity parameter = parameter();
        if (parameter != null && parameter.isReference() || nativeReference())
            ReferenceMemory.rejectStringOffsetReference(value, env, trace);
        if (parameter != null && parameter.isReference()) {
            if (sourceKind == VALUE || (sourceKind == VARIABLE && !value.isReference())) {
                InvokeArgumentHelper.referenceArgumentError(env, trace, parameter, size, originClass, originMethod);
            }
            if (!value.isReference()) {
                env.error(trace, ErrorType.E_NOTICE, "Only variables should be passed by reference");
                value = new ReferenceMemory(value.toImmutable());
            }
            value = ReferenceMemory.exportReference(value);
        } else if (nativeReference() && value.isReference()) {
            value = referenceNativeArgument(value);
        } else if (nativeBorrowedReference()) {
            // Old native bodies expect a wrapper even for read-only PHP parameters.
            // Capture the already-read value, never export the caller's guarded cell.
            value = borrowNativeArgument(value);
        } else {
            value = value.toImmutable();
        }
        append(value);
    }

    private void append(Memory value) {
        if (size == arguments.length) arguments = Arrays.copyOf(arguments, Math.max(4, size * 2));
        arguments[size++] = value;
    }

    private void sendSpread(Memory value) {
        boolean array = value.isArray();
        ForeachIterator iterator = value.getNewIterator(env, false, false);
        if (iterator == null) {
            env.exception(trace, php.runtime.lang.exception.BaseTypeError.class, "Only arrays and Traversables can be unpacked");
            return;
        }
        while (iterator.next()) {
            Memory element = iterator.getValue();
            ParameterEntity parameter = parameter();
            if (array && (parameter != null && parameter.isReference() || nativeReference())) {
                element = value.toValue(ArrayMemory.class).refOfIndexAsShortcut(trace, iterator.getMemoryKey());
            }
            if (!array && parameter != null && parameter.isReference()) {
                env.warning(trace, "Cannot pass by-reference argument %d of %s() by unpacking a Traversable, passing by-value instead",
                        size + 1, originMethod == null ? originClass : originClass + "::" + originMethod);
                element = new ReferenceMemory(element.toImmutable());
            }
            send(element, VARIABLE);
        }
    }

    public Memory invoke() throws Throwable {
        Memory[] passed = size == arguments.length ? arguments : Arrays.copyOf(arguments, size);
        return invokePrepared(passed);
    }

    // For runtime APIs whose argument expressions have already been evaluated.
    public Memory invokePrepared(Memory[] passed) throws Throwable {
        Memory result = function != null ? InvokeHelper.call(env, trace, function, passed)
                : invoker == null ? Memory.NULL : invoker.call(passed);
        if (constructedObject != null) {
            env.registerObjectInGC(constructedObject.value);
            return constructedObject;
        }
        return result;
    }
}
