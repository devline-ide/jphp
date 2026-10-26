package php.runtime.ext.core;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.annotation.Runtime;
import php.runtime.annotation.Runtime.Immutable;
import php.runtime.annotation.Runtime.Reference;
import php.runtime.common.AbstractCompiler;
import php.runtime.common.GrammarUtils;
import php.runtime.common.LangMode;
import php.runtime.env.*;
import php.runtime.env.handler.ErrorHandler;
import php.runtime.env.handler.ExceptionHandler;
import php.runtime.env.handler.ShutdownHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.ParseException;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.classes.util.WrapFlow;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.invoke.InvokeHelper;
import php.runtime.invoke.Invoker;
import php.runtime.lang.Closure;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.lang.Resource;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.helper.ObservableMemory;
import php.runtime.reflection.*;
import php.runtime.util.StackTracer;

import java.util.Observable;
import java.util.Observer;
import java.util.ArrayList;
import java.util.Collection;

public class LangFunctions extends FunctionsContainer {
    @Override
    public Collection<CompileFunctionSpec> getFunctionSpecs() {
        Collection<CompileFunctionSpec> result = new ArrayList<>();
        for (CompileFunctionSpec spec : super.getFunctionSpecs()) {
            if (!spec.getName().equals("is_numeric") && !spec.getName().equals("intval")
                    && !spec.getName().equals("floatval") && !spec.getName().equals("doubleval")
                    && !spec.getName().equals("settype")) result.add(spec);
        }
        for (String name : new String[] {"is_numeric", "intval", "floatval", "doubleval"}) {
            CompileFunctionSpec spec = new CompileFunctionSpec(name);
            spec.addMethod(getNative(PhpNumericFunctions.class, name, Environment.class, TraceInfo.class, Memory.class));
            if (name.equals("intval")) spec.addMethod(getNative(PhpNumericFunctions.class, name,
                    Environment.class, TraceInfo.class, Memory.class, int.class));
            result.add(spec);
        }
        CompileFunctionSpec settype = new CompileFunctionSpec("settype");
        settype.addMethod(getNative(PhpNumericFunctions.class, "settype", Environment.class, TraceInfo.class, Memory.class, String.class));
        result.add(settype);
        return result;
    }

    private static String evalErrorMessage(ErrorException e) {
        return e.getMessage() + ", eval()'s code on line " + (e.getTraceInfo().getStartLine() + 1)
                + ", position " + (e.getTraceInfo().getStartPosition() + 1);
    }

    public static Memory eval(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals, String code)
            throws Throwable {
        try {
            return env.eval(code, locals, env.getModuleManager().getPhpLanguageProfile(trace),
                    env.getModuleManager().getOptimizationProfile(trace), env.getModuleManager().getOwnershipMode(trace));
        } catch (ErrorException e) {
            if (e.getType() == ErrorType.E_PARSE) {
                if (env.isHandleErrors(ErrorType.E_PARSE))
                    throw new ParseException(evalErrorMessage(e), trace);
            } else
                env.error(trace, e.getType(), evalErrorMessage(e));
        }

        return Memory.FALSE;
    }

    public static Memory time() {
        return LongMemory.valueOf(System.currentTimeMillis() / 1000);
    }

    public static void sleep(int sec) throws InterruptedException {
        Thread.sleep(sec * 1000);
    }

    public static void usleep(long microsec) throws InterruptedException {
        Thread.sleep(microsec / 1000);
    }

    public static boolean time_nanosleep(long second, int nanosecond) throws InterruptedException {
        Thread.sleep(second * 1000, nanosecond);
        return true;
    }

    public static boolean time_sleep_until(double time) throws InterruptedException {
        long now = System.currentTimeMillis();
        long to = (long) (time * 1000);

        long diff = to - now;
        if (diff >= 0) {
            Thread.sleep(diff);
            return true;
        } else {
            return false;
        }
    }

    public static Memory compact(@Runtime.GetLocals ArrayMemory locals, Memory varName, Memory... varNames) {
        ArrayMemory result = new ArrayMemory();
        Memory value = locals.valueOfIndex(varName).toValue();
        if (value != Memory.UNDEFINED)
            result.refOfIndex(varName).assign(value.toImmutable());

        if (varNames != null) {
            for (Memory el : varNames) {
                value = locals.valueOfIndex(el).toValue();
                if (value != Memory.UNDEFINED)
                    result.refOfIndex(el).assign(value.toImmutable());
            }
        }

        return result.toImmutable();
    }

    public static void register_shutdown_function(Environment env, TraceInfo trace, @Reference Memory handler,
                                                  Memory... args) {
        Invoker invoker = expectingCallback(env, trace, 1, handler);
        if (invoker != null)
            env.registerShutdownFunction(new ShutdownHandler(invoker, args));
    }

    public static int error_reporting(Environment env, int level) {
        int old = env.getErrorFlags();
        env.setErrorFlags(level);
        return old;
    }

    public static int error_reporting(Environment env) {
        return env.getErrorFlags();
    }

    public static Memory error_get_last(Environment env) {
        SystemMessage err = env.getLastMessage();
        if (err == null)
            return Memory.NULL;

        ArrayMemory result = new ArrayMemory();
        result.refOfIndex("type").assign(err.getType().value);
        result.refOfIndex("message").assign(err.getMessage());
        if (err.getTrace() != null && err.getTrace().trace != null) {
            result.refOfIndex("file").assign(err.getTrace().trace.getFileName());
            result.refOfIndex("line").assign(err.getTrace().trace.getStartLine() + 1);
            result.refOfIndex("position").assign(err.getTrace().trace.getStartPosition() + 1);
        }
        return result.toConstant();
    }

    public static boolean trigger_error(Environment env, TraceInfo trace, String message, int type) {
        ErrorType _type = ErrorType.valueOf(type);
        if (_type == null)
            return false;

        env.error(trace, _type, message);
        return true;
    }

    public static boolean user_error(Environment env, TraceInfo trace, String message, int type) {
        return trigger_error(env, trace, message, type);
    }

    public static boolean trigger_error(Environment env, TraceInfo trace, String message) {
        return trigger_error(env, trace, message, ErrorType.E_USER_NOTICE.value);
    }

    public static boolean user_error(Environment env, TraceInfo trace, String message) {
        return trigger_error(env, trace, message);
    }

    public static Memory set_error_handler(Environment env, TraceInfo trace, @Reference Memory handler,
                                           int flags) {
        if (handler.toValue().isNull() && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            ErrorHandler last=env.getErrorHandler();
            env.setErrorHandler(null);
            return php.runtime.PhpOwnershipNativeCalls.previousHandlerResult(env,last);
        }
        Invoker invoker = expectingCallback(env, trace, 1, handler);
        if (invoker != null) {
            ErrorHandler last = env.getErrorHandler();
            Memory callable=handler.toValue().toImmutable();
            php.runtime.PhpOwnershipNativeCalls.Registration registration;
            try {registration=php.runtime.PhpOwnershipNativeCalls.retainHandler(env,invoker,callable);}
            catch (Throwable failure) {throw LangFunctions.<RuntimeException>raiseHandlerFailure(failure);}
            try {env.setErrorHandler(new ErrorHandler(invoker,callable,flags,registration));}
            catch (Throwable failure) {
                if (registration != null) try {registration.close();} catch (Throwable cleanup) {failure=registration.composeFailure(failure,cleanup);}
                throw LangFunctions.<RuntimeException>raiseHandlerFailure(failure);
            }
            return php.runtime.PhpOwnershipNativeCalls.previousHandlerResult(env,last);
        } else
            return Memory.FALSE;
    }

    public static Memory set_error_handler(Environment env, TraceInfo trace, @Reference Memory handler) {
        return set_error_handler(env, trace, handler, ErrorType.E_ALL.value | ErrorType.E_STRICT.value);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException raiseHandlerFailure(Throwable failure) throws T {throw (T)failure;}

    public static boolean restore_error_handler(Environment env) {
        env.restoreErrorHandler();
        return true;
    }

    public static Memory set_exception_handler(Environment env, TraceInfo trace, @Reference Memory handler) {
        if (handler.toValue().isNull() && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            ExceptionHandler last=env.getExceptionHandler();
            env.setExceptionHandler(null);
            return last == null || last.invoker == null ? Memory.NULL : last.invokerMemory;
        }
        Invoker invoker = expectingCallback(env, trace, 1, handler);
        if (invoker != null) {
            ExceptionHandler eh = env.getExceptionHandler();
            env.setExceptionHandler(new ExceptionHandler(invoker, handler.toImmutable()));
            return eh == null || eh.invoker == null ? Memory.NULL : eh.invokerMemory;
        } else
            return Memory.FALSE;
    }

    public static boolean restore_exception_handler(Environment env) {
        env.restoreExceptionHandler();
        return true;
    }

    public static Memory get_defined_vars(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals) {
        return locals.toImmutable();
    }

    public static int extract(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals,
                              @Reference Memory array, int extractType) {
        return extract(env, trace, locals, array, extractType, Memory.NULL);
    }

    public static int extract(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals,
                              @Reference Memory array) {
        return extract(env, trace, locals, array, LangConstants.EXTR_OVERWRITE.toInteger(), Memory.NULL);
    }

    public static int extract(Environment env, TraceInfo trace, @Runtime.GetLocals ArrayMemory locals,
                              @Reference Memory array, int extractType, Memory _prefix) {
        if (expecting(env, trace, 1, array, Memory.Type.ARRAY)) {
            boolean isRefs = (extractType & LangConstants.EXTR_REFS.toInteger()) == LangConstants.EXTR_REFS.toInteger();
            ForeachIterator iterator = array.getNewIterator(env, isRefs, false);
            int count = 0;
            if (extractType == LangConstants.EXTR_PREFIX_ALL.toInteger() || extractType == LangConstants.EXTR_PREFIX_IF_EXISTS.toInteger()
                    || extractType == LangConstants.EXTR_PREFIX_INVALID.toInteger() || extractType == LangConstants.EXTR_PREFIX_SAME.toInteger())
                if (_prefix.isNull()) {
                    env.warning(trace, "extract(): specified extract type requires the prefix parameter");
                    return 0;
                }

            String prefix = _prefix.isNull() ? "" : _prefix.concat("_");
            if (!prefix.isEmpty())
                if (!GrammarUtils.isValidName(prefix)) {
                    env.warning(trace, "extract(): prefix is not a valid identifier");
                    return 0;
                }

            while (iterator.next()) {
                Object key = iterator.getKey();
                String keyS = key.toString();
                String var;
                Memory value = iterator.getValue();

                if (!isRefs)
                    value = value.toImmutable();

                if (extractType == LangConstants.EXTR_OVERWRITE.toInteger()) {
                    if (GrammarUtils.isValidName(keyS)) {
                        locals.refOfIndex(keyS).assign(value);
                        count++;
                    }
                } else if (extractType == LangConstants.EXTR_SKIP.toInteger()) {
                    if (GrammarUtils.isValidName(keyS) && locals.valueOfIndex(keyS).isUndefined()) {
                        locals.refOfIndex(keyS).assign(value);
                        count++;
                    }
                } else if (extractType == LangConstants.EXTR_PREFIX_SAME.toInteger()) {
                    if (!locals.valueOfIndex(keyS).isUndefined()) {
                        var = prefix.concat(keyS);
                        if (GrammarUtils.isValidName(var)) {
                            locals.refOfIndex(var).assign(value);
                            count++;
                        }
                    } else if (GrammarUtils.isValidName(keyS)) {
                        locals.refOfIndex(keyS).assign(value);
                        count++;
                    }
                } else if (extractType == LangConstants.EXTR_PREFIX_ALL.toInteger()) {
                    var = prefix.concat(keyS);
                    if (GrammarUtils.isValidName(var)) {
                        locals.refOfIndex(prefix.concat(keyS)).assign(value);
                        count++;
                    }
                } else if (extractType == LangConstants.EXTR_PREFIX_INVALID.toInteger()) {
                    if (GrammarUtils.isValidName(keyS)) {
                        locals.refOfIndex(keyS).assign(value);
                        count++;
                    } else {
                        var = prefix.concat(keyS);
                        if (GrammarUtils.isValidName(var)) {
                            locals.refOfIndex(var).assign(value);
                            count++;
                        }
                    }
                } else if (extractType == LangConstants.EXTR_IF_EXISTS.toInteger()) {
                    if (GrammarUtils.isValidName(keyS))
                        if (!locals.valueOfIndex(keyS).isUndefined()) {
                            locals.refOfIndex(keyS).assign(value);
                            count++;
                        }
                } else if (extractType == LangConstants.EXTR_PREFIX_IF_EXISTS.toInteger()) {
                    if (!locals.valueOfIndex(keyS).isUndefined()) {
                        var = prefix.concat(keyS);
                        if (GrammarUtils.isValidName(var)) {
                            locals.refOfIndex(var).assign(value);
                            count++;
                        }
                    }
                }
            }

            return count;
        } else {
            return 0;
        }
    }

    private static ClassEntity definedClassScope(Environment env) {
        CallStackItem active=env.peekCall(0);
        // A global function ends lexical class scope; do not search through its caller.
        if(active!=null && active.object instanceof php.runtime.lang.Closure) {
            String scope=((php.runtime.lang.Closure)active.object).getScope();
            return scope==null?null:env.fetchClass(scope,false,php.runtime.common.PhpLanguageProfile.PHP_8_5);
        }
        return env.__getContextClass(0);
    }

    public static boolean defined(Environment env, String name) {
        int separator=name.indexOf("::");
        if(separator<0)return env.findConstant(name)!=null;
        var executing=php.runtime.PhpPropertyWriteContext.current(env);
        TraceInfo trace=executing==null?env.trace():executing.trace;
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            {
                if(separator==0 || name.indexOf('\0')>=0) return false;
                String className=name.substring(0,separator),member=name.substring(separator+2);
                ClassEntity receiver;
                String keyword=className.toLowerCase(java.util.Locale.ROOT);
                if(keyword.equals("self")||keyword.equals("parent")||keyword.equals("static")) {
                    ClassEntity context=definedClassScope(env);
                    if(context==null) {
                        env.exception(trace,php.runtime.lang.exception.BaseError.class,
                                "Cannot access \"%s\" when no class scope is active",keyword);return false;
                    }
                    receiver=keyword.equals("self")?context:keyword.equals("parent")?context.getParent():env.getLateStaticClass();
                    if(receiver==null) {
                        env.exception(trace,php.runtime.lang.exception.BaseError.class,
                                "Cannot access \"parent\" when current class scope has no parent");return false;
                    }
                } else receiver=env.fetchClass(className.startsWith("\\")?className.substring(1):className,
                        true,php.runtime.common.PhpLanguageProfile.PHP_8_5);
                if(receiver==null || receiver.isTrait())return false;
                ConstantEntity constant=receiver.findConstant(member);
                if(constant==null)return false;
                ClassEntity scope=definedClassScope(env),owner=constant.getClazz();
                boolean accessible=constant.isPublic()||scope!=null&&(constant.isPrivate()
                        ?scope.getId()==owner.getId():scope.isInstanceOf(owner)||owner.isInstanceOf(scope));
                if(!accessible)return false;
                // PHP resolves a deferred declaration (and propagates its failure); this is not a read-deprecation site.
                constant.getValue(env,trace);
                return true;
            }
        }
        Memory value = env.findConstant(name);
        return value != null;
    }

    public static boolean define(Environment env, TraceInfo trace, String name, Memory value, boolean caseInSentisise) {
        return env.defineConstant(name, value, !caseInSentisise);
    }

    public static boolean define(Environment env, TraceInfo trace, String name, Memory value) {
        return define(env, trace, name, value, false);
    }

    public static Memory constant(Environment env, TraceInfo trace, String name) {
        int pos;
        if ((pos = name.indexOf("::")) > -1) {
            String className = name.substring(0, pos);
            String constName = name.substring(pos + 2);
            ClassEntity entity = env.fetchClass(className, true);
            if (entity == null)
                return Memory.NULL;

            ConstantEntity constant = entity.findConstant(constName);
            InvokeHelper.checkAccess(env, trace, constant, false);
            return constant == null ? Memory.NULL
                    : env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                        ? php.runtime.PhpConstantInitializer.resolveReference(constant, env, trace, className) : constant.getValue();
        } else {
            Memory value = env.readConstant(name, trace);
            if (value == null)
                return Memory.NULL;
            else
                return value;
        }
    }

    @Immutable
    public static String gettype(Memory memory) {
        switch (memory.getRealType()) {
            case ARRAY:
                return "array";
            case BOOL:
                return "boolean";
            case INT:
                return "integer";
            case DOUBLE:
                return "double";
            case STRING:
                return "string";
            case OBJECT:
                if (memory.isResource())
                    return "resource";

                return "object";
            case NULL:
                return "NULL";
            default:
                return "unknown type";
        }
    }

    public static boolean is_array(@Reference Memory memory) {
        return memory.isArray();
    }

    public static boolean is_iterable(@Reference Memory memory) {
        return memory.isTraversable();
    }

    @Immutable
    public static boolean is_bool(Memory memory) {
        return memory.toValue().type == Memory.Type.BOOL;
    }

    @Immutable
    public static boolean is_double(Memory memory) {
        return memory.toValue().type == Memory.Type.DOUBLE;
    }

    @Immutable
    public static boolean is_float(Memory memory) {
        return memory.toValue().type == Memory.Type.DOUBLE;
    }

    @Immutable
    public static boolean is_int(Memory memory) {
        return memory.toValue().type == Memory.Type.INT;
    }

    @Immutable
    public static boolean is_integer(Memory memory) {
        return memory.toValue().type == Memory.Type.INT;
    }

    @Immutable
    public static boolean is_long(Memory memory) {
        return memory.toValue().type == Memory.Type.INT;
    }

    @Immutable
    public static boolean is_null(Memory memory) {
        return memory.isNull();
    }

    public static boolean is_object(@Reference Memory memory) {
        return memory.isObject();
    }

    @Immutable
    public static boolean is_real(Memory memory) {
        return is_float(memory);
    }

    @Immutable
    public static boolean is_string(Memory memory) {
        return memory.isString();
    }

    @Immutable
    public static boolean is_numeric(Memory memory) {
        return StringMemory.toNumeric(memory.toString(), false, null) != null;
    }

    @Immutable
    public static boolean is_scalar(Memory memory) {
        switch (memory.getRealType()) {
            case BOOL:
            case NULL:
            case INT:
            case DOUBLE:
            case STRING:
                return true;
        }
        return false;
    }

    public static boolean is_resource(@Reference Memory memory) {
        return memory.isResource();
    }

    public static Memory get_resource_type(@Reference Memory memory) {
        memory = memory.toValue();
        if (memory.isObject()) {
            if (((ObjectMemory) memory).value instanceof Resource)
                return new StringMemory(
                        ((Resource) ((ObjectMemory) memory).value).getResourceType()
                );
        }
        return Memory.NULL;
    }

    public static boolean is_callable(Environment env, TraceInfo trace, @Reference Memory memory)
            throws Throwable {
        // optimize
        if (memory.isObject() && memory.toValue(ObjectMemory.class).value instanceof Closure)
            return true;
        Invoker invoker = Invoker.valueOf(env, null, memory);
        return invoker != null && invoker.canAccess(env) == 0;
    }

    @Immutable
    public static boolean boolval(Memory memory) {
        return memory.toBoolean();
    }

    @Immutable
    public static String strval(Memory memory) {
        return memory.toString();
    }

    @Immutable
    public static long intval(Memory memory) {
        return memory.toLong();
    }

    @Immutable
    public static double floatval(Memory memory) {
        return memory.toDouble();
    }

    @Immutable
    public static double doubleval(Memory memory) {
        return memory.toDouble();
    }

    public static boolean settype(@Reference Memory memory, String type) {
        if (memory.isReference()) {
            if ("string".equals(type)) {
                memory.assign(memory.toString());
            } else if ("bool".equals(type) || "boolean".equals(type)) {
                memory.assign(memory.toBoolean());
            } else if ("int".equals(type) || "integer".equals(type)) {
                memory.assign(memory.toLong());
            } else if ("float".equals(type) || "double".equals(type)) {
                memory.assign(memory.toDouble());
            } else if ("null".equals(type)) {
                memory.assign(Memory.NULL);
            } else
                return false;

            return true;
        }
        return false;
    }

    public void debug_zval_dump(Environment env, TraceInfo trace) {
        env.warning(trace, "debug_zval_dump(): unsupported");
    }

    public static Memory func_get_args(Environment env, TraceInfo trace) {
        if (env.getCallStackTop() == 0) {
            return Memory.FALSE;
        }

        Memory[] args = env.peekCall(0).args;

        if (args == null) {
            return new ArrayMemory().toConstant();
        }

        return ArrayMemory.of(args);
    }

    public static Memory func_num_args(Environment env, TraceInfo trace) {
        if (env.getCallStackTop() == 0) {
            return Memory.FALSE;
        }

        Memory[] args = env.peekCall(0).args;

        if (args == null) {
            return Memory.CONST_INT_0;
        }

        return LongMemory.valueOf(args.length);
    }

    public static Memory func_get_arg(Environment env, TraceInfo trace, int argNum) {
        if (env.getCallStackTop() == 0) {
            return Memory.FALSE;
        }
        if (argNum < 0)
            return Memory.FALSE;

        Memory[] args = env.peekCall(0).args;

        if (args == null) {
            return  Memory.FALSE;
        }

        if (argNum < args.length)
            return args[argNum];
        else
            return Memory.FALSE;
    }

    private static Memory _call_user_func(Environment env, TraceInfo trace, Memory function, Memory... args)
            throws Throwable {return _call_user_func(env,trace,function,false,args);}

    private static Memory _call_user_func(Environment env, TraceInfo trace, Memory function, boolean discarded,Memory... args)
            throws Throwable {
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            try (var frame = nativeCallbackFrame(env, trace, function, "call_user_func")) {
                if (!php.runtime.invoke.NativeFunctionContext.isStrict(env, trace, "call_user_func")) {
                    return discarded ? frame.invokeInternalDiscarded(args==null ? new Memory[0] : args)
                            : frame.invokeInternal(args==null ? new Memory[0] : args);
                }
                Invoker invoker = frame.resolvedTarget();
                String name = invoker instanceof php.runtime.invoke.DynamicMethodInvoker
                        && ((php.runtime.invoke.DynamicMethodInvoker) invoker).getObject() instanceof php.runtime.lang.Closure
                        ? php.runtime.invoke.InvokeArgumentHelper.closureCallName(
                                (php.runtime.lang.Closure) ((php.runtime.invoke.DynamicMethodInvoker) invoker).getObject())
                        : invoker instanceof php.runtime.invoke.DynamicMethodInvoker
                        ? ((php.runtime.invoke.DynamicMethodInvoker) invoker).getMethod().getClazzName() + "::"
                            + ((php.runtime.invoke.DynamicMethodInvoker) invoker).getMethod().getName()
                        : invoker instanceof php.runtime.invoke.StaticMethodInvoker
                            ? ((php.runtime.invoke.StaticMethodInvoker) invoker).getMethod().getClazzName() + "::"
                                + ((php.runtime.invoke.StaticMethodInvoker) invoker).getMethod().getName()
                            : invoker.getName();
                args = php.runtime.invoke.InvokeArgumentHelper.prepareIndirectArguments(env, trace, args,
                        invoker.getParameters(), name);
                return discarded ? frame.invokePreparedDiscarded(args) : frame.invokePrepared(args);
            }
        }
        Invoker invoker = expectingCallback(env, trace, 1, function);
        if (invoker == null) return Memory.FALSE;
        invoker.setTrace(trace);
        if(discarded || invoker instanceof php.runtime.invoke.RunnableInvoker) {
            try(var frame=php.runtime.invoke.PhpCallFrame.resolvedInvoker(env,trace,invoker,args==null ? 0 : args.length)) {
                return discarded ? frame.invokePreparedDiscarded(args) : frame.invokePrepared(args);
            }
        }
        return invoker.call(args);
    }

    @Reflection.Signature(value = {
            @Reflection.Arg(value = "callback", type = php.runtime.common.HintType.CALLABLE),
            @Reflection.Arg(value = "args", type = php.runtime.common.HintType.MIXED, variadic = true)
    }, namedVariadic = true, result = @Reflection.Arg(type = php.runtime.common.HintType.MIXED))
    @php.runtime.annotation.PhpNativeResultBridge(discarded="call_user_funcDiscarded")
    public static Memory call_user_func(Environment env, TraceInfo trace, Memory function, Memory... args)
            throws Throwable {return callUserFunc(env,trace,function,
                    false,args);}

    @Reflection.Ignore
    public static Memory call_user_funcDiscarded(Environment env,TraceInfo trace,Memory function,Memory...args)
            throws Throwable {return callUserFunc(env,trace,function,true,args);}

    private static Memory callUserFunc(Environment env,TraceInfo trace,Memory function,boolean discarded,Memory...args)
            throws Throwable {
        Memory[] passed;
        if (args == null) {
            passed = new Memory[]{function};
        } else {
            passed = new Memory[args.length + 1];
            System.arraycopy(args, 0, passed, 1, args.length);
            passed[0] = function;
        }

        env.pushCall(trace, null, passed, "call_user_func", null, null);
        try {
            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                var bound = php.runtime.invoke.BoundArguments.currentNativeVariadic(env, args,
                        NamedNativeMembers.CALL_USER_FUNC);
                if (bound != null && !bound.namedExtras().isEmpty()) {
                    try (var frame = nativeCallbackFrame(env, trace, function, "call_user_func")) {
                        // CUF always forwards VALUEs; a by-reference callback warns and receives a temporary.
                        for (Memory value : args) {
                            frame.preparePositional();
                            frame.sendIndirect(value.toValue().toImmutable(), php.runtime.invoke.PhpCallFrame.VALUE);
                        }
                        for (var entry : bound.namedExtras().entrySet()) {
                            frame.prepareNamed(entry.getKey());
                            frame.sendIndirect(entry.getValue().toValue().toImmutable(), php.runtime.invoke.PhpCallFrame.VALUE);
                        }
                        var actual=frame.finishBinding();
                        return discarded ? frame.invokeBoundDiscarded(actual,true) : frame.invokeBound(actual,true);
                    }
                }
            }
            return _call_user_func(env, trace, function,discarded,
                    env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                            ? php.runtime.invoke.InvokeArgumentHelper.argumentsByValue(args) : args);
        } finally {
            env.popCall();
        }
    }

    private static php.runtime.invoke.PhpCallFrame nativeCallbackFrame(Environment env, TraceInfo trace,
            Memory callback, String api) throws Throwable {
        try {
            return php.runtime.invoke.PhpCallFrame.acquireInternalDispatcherCallable(callback, env, trace);
        } catch (php.runtime.invoke.PhpMethodResolver.InvalidCallback invalid) {
            throw invalid.typeError(env,trace,api+"(): Argument #1 ($callback) must be a valid callback, "
                    +(invalid.getMessage()==null ? "(null)" : invalid.getMessage()));
        }
    }

    private static final class NamedNativeMembers {
        static final java.lang.reflect.Method CALL_USER_FUNC = callUserFunc();
        private static java.lang.reflect.Method callUserFunc() {
            try {return LangFunctions.class.getDeclaredMethod("call_user_func", Environment.class,
                    TraceInfo.class, Memory.class, Memory[].class);}
            catch (NoSuchMethodException impossible) {throw new ExceptionInInitializerError(impossible);}
        }
    }

    @Reflection.Signature(value = {
            @Reflection.Arg(value = "callback", type = php.runtime.common.HintType.CALLABLE),
            @Reflection.Arg(value = "args", type = php.runtime.common.HintType.ARRAY)
    }, result = @Reflection.Arg(type = php.runtime.common.HintType.MIXED))
    @php.runtime.annotation.PhpNativeResultBridge(discarded="call_user_func_arrayDiscarded")
    public static Memory call_user_func_array(Environment env, TraceInfo trace, Memory function, Memory args)
            throws Throwable {return callUserFuncArray(env,trace,function,args,
                    false);}

    @Reflection.Ignore
    public static Memory call_user_func_arrayDiscarded(Environment env,TraceInfo trace,Memory function,Memory args)
            throws Throwable {return callUserFuncArray(env,trace,function,args,true);}

    private static Memory callUserFuncArray(Environment env,TraceInfo trace,Memory function,Memory args,boolean discarded)
            throws Throwable {
        if (expecting(env, trace, 2, args, Memory.Type.ARRAY)) {
            Memory[] passed = new Memory[]{function, args};
            env.pushCall(trace, null, passed, "call_user_func_array", null, null);
            try {
                ArrayMemory values = args.toValue(ArrayMemory.class);
                if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    try (var frame = nativeCallbackFrame(env, trace, function, "call_user_func_array")) {
                        boolean internal=!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,"call_user_func_array");
                        return discarded ? frame.invokeKeyedDiscarded(values,internal) : frame.invokeKeyed(values,internal);
                    }
                }
                return _call_user_func(env, trace, function, values.values(false));
            } finally {
                env.popCall();
            }
        }
        return Memory.FALSE;
    }

    public static Memory debug_backtrace(Environment env, TraceInfo trace, int options, int limit) {
        boolean provideObject = (options & LangConstants.DEBUG_BACKTRACE_PROVIDE_OBJECT.toInteger())
                == LangConstants.DEBUG_BACKTRACE_PROVIDE_OBJECT.toInteger();
        boolean ignoreArgs = (options & LangConstants.DEBUG_BACKTRACE_IGNORE_ARGS.toInteger())
                == LangConstants.DEBUG_BACKTRACE_IGNORE_ARGS.toInteger();

        ArrayMemory result = new ArrayMemory();
        for (int i = 0; i < env.getCallStackTop(); i++) {
            if (limit != 0 && i >= limit)
                break;

            CallStackItem item = env.peekCall(i);
            ArrayMemory el = item.toArray(env,provideObject, ignoreArgs);
            result.add(el);
        }

        return result.toConstant();
    }

    public static Memory debug_backtrace(Environment env, TraceInfo trace, int options) {
        return debug_backtrace(env, trace, options, 0);
    }

    public static Memory debug_backtrace(Environment env, TraceInfo trace) {
        return debug_backtrace(env, trace, LangConstants.DEBUG_BACKTRACE_PROVIDE_OBJECT.toInteger(), 0);
    }


    public static void debug_print_backtrace(Environment env, TraceInfo trace, int options, int limit) {
        boolean provideObject = (options & LangConstants.DEBUG_BACKTRACE_PROVIDE_OBJECT.toInteger())
                == LangConstants.DEBUG_BACKTRACE_PROVIDE_OBJECT.toInteger();
        boolean ignoreArgs = (options & LangConstants.DEBUG_BACKTRACE_IGNORE_ARGS.toInteger())
                == LangConstants.DEBUG_BACKTRACE_IGNORE_ARGS.toInteger();

        StackTracer stackTracer = new StackTracer(env, limit);
        env.echo(stackTracer.toString(!ignoreArgs));
    }

    public static void debug_print_backtrace(Environment env, TraceInfo trace, int options) {
        debug_print_backtrace(env, trace, options, 0);
    }

    public static void debug_print_backtrace(Environment env, TraceInfo trace) {
        debug_print_backtrace(env, trace, LangConstants.DEBUG_BACKTRACE_PROVIDE_OBJECT.toInteger(), 0);
    }

    public static boolean function_exists(Environment env, String name) {
        FunctionEntity function = env.fetchFunction(name);
        return function != null;
    }

    public static boolean class_exists(Environment env, String name, boolean autoload) {
        ClassEntity entity = env.fetchClass(PhpClassNames.lookupName(env, name), autoload);
        return entity != null && entity.isClass();
    }

    public static boolean class_exists(Environment env, String name) {
        return class_exists(env, name, true);
    }

    public static boolean interface_exists(Environment env, String name, boolean autoload) {
        ClassEntity entity = env.fetchClass(PhpClassNames.lookupName(env, name), autoload);
        return entity != null && entity.isInterface();
    }

    public static boolean interface_exists(Environment env, String name) {
        return interface_exists(env, name, true);
    }

    public static boolean trait_exists(Environment env, String name, boolean autoload) {
        ClassEntity entity = env.fetchClass(PhpClassNames.lookupName(env, name), autoload);
        return entity != null && entity.isTrait();
    }

    public static boolean trait_exists(Environment env, String name) {
        return trait_exists(env, name, true);
    }

    public static boolean method_exists(Environment env, Memory clazz, String method) {
        ClassEntity classEntity;
        if (clazz.isObject()) {
            ObjectMemory tmp = clazz.toValue(ObjectMemory.class);
            classEntity = tmp.getReflection();
        } else {
            String name = clazz.toString();
            String nameL = name.toLowerCase();
            classEntity = env.fetchClass(name, nameL, true);
            if (classEntity == null)
                classEntity = env.fetchMagicClass(name, nameL);
        }

        return classEntity != null && classEntity.findMethod(method.toLowerCase()) != null;
    }

    public static Memory property_exists(Environment env, Memory clazz, String property) throws Throwable {
        ClassEntity classEntity;
        IObject object = null;
        boolean isMagic = false;
        if (clazz.isObject()) {
            ObjectMemory tmp = clazz.toValue(ObjectMemory.class);
            classEntity = tmp.getReflection();
            object = tmp.value;
        } else {
            String name = clazz.toString();
            String nameL = name.toLowerCase();

            classEntity = env.fetchClass(name, nameL, true);
            if (classEntity == null) {
                classEntity = env.fetchMagicClass(name, nameL);
                isMagic = true;
            }
        }

        if (classEntity == null) {
            return Memory.FALSE;
        }

        if (object != null) {
            ArrayMemory props = object.getProperties();

            ClassEntity context = env.getLastClassOnStack();
            PropertyEntity entity = classEntity.isInstanceOf(context)
                    ? context.properties.get(property) : classEntity.properties.get(property);

            boolean modern = classEntity.getModule() != null
                    && classEntity.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    || env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern && entity != null) {
                if (!entity.isPrivate() || entity.getClazz() == classEntity || entity.getClazz() == context) return Memory.TRUE;
                // An inherited private declaration is not visible here; an actual plain dynamic
                // bucket with the same name still exists independently of the parent's slot.
                return props != null && props.getByScalar(property) != null ? Memory.TRUE : Memory.FALSE;
            }
            int accessFlags = entity == null ? 0 : entity.canAccess(env);
            if (accessFlags != 0)
                return Memory.FALSE;

            return (props != null && props.getByScalar(entity == null ? property : entity.getSpecificName()) != null)
                    ? Memory.TRUE : Memory.FALSE;
        } else {
            PropertyEntity entity = classEntity.properties.get(property);
            boolean modern = classEntity.getModule() != null
                    && classEntity.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    || env.getModuleManager().getPhpLanguageProfile(env.trace()) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern && entity != null && entity.isPrivate() && entity.getClazz() != classEntity) return Memory.FALSE;
            if (isMagic) {
                int accessFlags = entity == null ? 0 : entity.canAccess(env);
                if (accessFlags != 0)
                    return Memory.FALSE;
            }

            return entity != null ? Memory.TRUE : Memory.FALSE;
        }
    }

    public static Memory is_a(Environment env, TraceInfo trace, Memory object, String className,
                              boolean allowedString) {
        ClassEntity classEntity = null;
        ClassEntity parentClass;

        if (allowedString && !object.isObject()) {
            String name = object.toString();
            String nameL = name.toLowerCase();

            classEntity = env.fetchClass(name, nameL, false);
            if (classEntity == null)
                classEntity = env.fetchMagicClass(name, nameL);

        } else if (expecting(env, trace, 1, object, Memory.Type.OBJECT)) {
            classEntity = object.toValue(ObjectMemory.class).getReflection();
        }
        if (classEntity == null)
            return Memory.FALSE;

        parentClass = env.fetchClass(className, false);
        if (parentClass == null)
            return Memory.FALSE;

        boolean modern = PhpClassNames.isModern(env) || PhpClassNames.isModern(classEntity) || PhpClassNames.isModern(parentClass);
        return (modern ? classEntity.isInstanceOfAsciiName(parentClass.getName()) : classEntity.isInstanceOf(parentClass)) ? Memory.TRUE : Memory.FALSE;
    }

    public static Memory is_a(Environment env, TraceInfo trace, Memory object, String className) {
        return is_a(env, trace, object, className, false);
    }

    public static Memory is_subclass_of(Environment env, TraceInfo trace, Memory object, String className,
                                        boolean allowedString) {
        ClassEntity classEntity = null;
        ClassEntity parentClass;

        if (allowedString && !object.isObject()) {
            String name = object.toString();
            String nameL = name.toLowerCase();

            classEntity = env.fetchClass(name, nameL, true);
            if (classEntity == null)
                classEntity = env.fetchMagicClass(name, nameL);

        } else if (expecting(env, trace, 1, object, Memory.Type.OBJECT)) {
            classEntity = object.toValue(ObjectMemory.class).getReflection();
        }
        parentClass = env.fetchClass(className, true);

        if (classEntity == null) {
            return Memory.NULL;
        } else if (parentClass == null) {
            return Memory.NULL;
        } else {
            if (object.isObject() && object.toValue(ObjectMemory.class).value instanceof Closure)
                return Memory.FALSE;

            boolean modern = PhpClassNames.isModern(env) || PhpClassNames.isModern(classEntity) || PhpClassNames.isModern(parentClass);
            boolean matches = modern ? classEntity.isInstanceOfAsciiName(parentClass.getName()) : classEntity.isInstanceOf(parentClass);
            boolean same = modern ? PhpClassNames.canonicalKey(classEntity.getName()).equals(PhpClassNames.canonicalKey(parentClass.getName()))
                    : classEntity.equals(parentClass);
            return matches && !same ? Memory.TRUE : Memory.FALSE;
        }
    }

    public static Memory is_subclass_of(Environment env, TraceInfo trace, Memory object, String className) {
        return is_subclass_of(env, trace, object, className, true);
    }

    public static Memory get_class(Environment env, TraceInfo trace, Memory object) {
        if (object.isNull()) {
            if (object == Memory.UNDEFINED) {
                return Memory.FALSE;
            }

            CallStackItem item = env.peekCall(0);
            if (item.clazz != null) {
                if (item.classEntity == null)
                    item.classEntity = env.fetchClass(item.clazz, false);

                if (item.classEntity == null)
                    return Memory.FALSE;
                else {
                    MethodEntity method = item.classEntity.findMethod(item.function);
                    if (method == null)
                        return Memory.FALSE;
                    return new StringMemory(method.getClazz().getName());
                }
            }
        } else if (expecting(env, trace, 1, object, Memory.Type.OBJECT)) {
            return new StringMemory(object.toValue(ObjectMemory.class).getReflection().getName());
        }

        return Memory.FALSE;
    }

    public static Memory get_class(Environment env, TraceInfo trace) {
        return get_class(env, trace, Memory.NULL);
    }

    public static Memory get_called_class(Environment env) {
        String name = env.getLateStatic();
        if ((name == null || name.isEmpty()) && env.getModuleManager().getPhpLanguageProfile(env.trace())
                == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            env.exception(env.trace(), php.runtime.lang.exception.BaseError.class,
                    "get_called_class() must be called from within a class");
        }
        return name == null || name.isEmpty() ? Memory.FALSE : new StringMemory(name);
    }

    public static Memory get_class_methods(Environment env, TraceInfo trace, Memory value) {
        ClassEntity entity;
        if (value.isString()) {
            entity = env.fetchClass(value.toString(), true);
        } else if (value.isObject()) {
            entity = value.toValue(ObjectMemory.class).getReflection();
        } else {
            env.warning(
                    trace, "get_class_methods(): Argument 1 must be string or object, %s given",
                    value.getRealType().toString()
            );
            return Memory.NULL;
        }

        if (entity == null)
            return Memory.NULL;

        ClassEntity context = env.getLastClassOnStack();

        ArrayMemory result = new ArrayMemory();
        for (MethodEntity el : entity.getMethods().values()) {
            if (el.canAccess(env, context) == 0)
                result.refOfPush().assign(el.getName());
        }

        return result.toConstant();
    }

    public static Memory get_class_vars(Environment env, TraceInfo trace, Memory value) {
        ClassEntity entity;
        if (value.isString()) {
            entity = env.fetchClass(value.toString(), true);
        } else if (value.isObject()) {
            entity = value.toValue(ObjectMemory.class).getReflection();
        } else {
            env.warning(
                    trace, "get_class_vars(): Argument 1 must be string or object, %s given",
                    value.getRealType().toString()
            );
            return Memory.NULL;
        }

        if (entity == null)
            return Memory.NULL;

        ClassEntity context = env.getLastClassOnStack();

        ArrayMemory result = new ArrayMemory();
        for (PropertyEntity el : entity.getProperties()) {
            if (el.canAccess(env, context) == 0)
                result.refOfIndex(el.getName()).assign(el.hasDefaultValue() ? el.getDefaultValue(env) : Memory.NULL);
        }

        for (PropertyEntity el : entity.getStaticProperties()) {
            if (el.canAccess(env, context) == 0)
                result.refOfIndex(el.getName()).assign(el.hasDefaultValue() ? el.getDefaultValue(env) : Memory.NULL);
        }

        return result.toConstant();
    }

    public static Memory get_object_vars(Environment env, TraceInfo trace, Memory object) {
        if (expecting(env, trace, 1, object, Memory.Type.OBJECT)) {
            ObjectMemory o = new ObjectMemory(php.runtime.PhpLazyObjects.observeUnchecked(env,object.toValue(ObjectMemory.class).value,
                    php.runtime.PhpLazyObjects.Observation.PROPERTIES));
            ArrayMemory props = o.value.getProperties();
            ClassEntity entity = o.getReflection();

            ClassEntity context = env.getLastClassOnStack();
            ForeachIterator iterator = php.runtime.PhpPropertyHookAccess.consumerProperties(o.value,env,trace,context,2);

            ArrayMemory result = new ArrayMemory();
            boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    || entity.getModule() != null && entity.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            boolean convertNumericKeys = modern && props.hasNumericStringKeys();
            while (iterator.next()) {
                if (iterator.getValue().isUndefined() && (modern || props instanceof php.runtime.memory.PhpObjectProperties
                        && ((php.runtime.memory.PhpObjectProperties) props).isExplicitlyUnset(iterator.getKey().toString()))) continue;
                String key = iterator.getKey().toString();
                String name = modern ? key.substring(key.lastIndexOf('\0') + 1) : key;
                ClassEntity owner = entity;
                if (modern && key.startsWith("\0") && key.length() > 1 && key.charAt(1) != '*') {
                    ClassEntity specific = env.fetchClass(key.substring(1, key.lastIndexOf('\0')), false);
                    if (specific != null) owner = specific;
                }
                PropertyEntity prop = owner.findProperty(name);
                if (prop == null || prop.canAccess(env, context) == 0) {
                    if (modern) {
                        Memory stored = iterator.getValue();
                        Object convertedKey = ArrayMemory.toKey(new StringMemory(prop == null ? key : prop.getName()));
                        Memory copied = stored.isReference() ? stored : stored.toImmutable();
                        if (convertNumericKeys) result.put(convertedKey, copied);
                        else result.insertBucket(convertedKey, copied);
                    } else result.refOfIndex(prop == null ? key : prop.getName()).assign(iterator.getValue());
                }
            }
            return result.toConstant();
        } else
            return Memory.NULL;
    }

    public static Memory get_parent_class(Memory object) {
        if (object.isObject()) {
            ClassEntity classEntity = object.toValue(ObjectMemory.class).getReflection().getParent();
            if (classEntity == null)
                return Memory.FALSE;
            else
                return new StringMemory(classEntity.getName());
        } else {
            return Memory.FALSE;
        }
    }

    public static Memory get_parent_class(Environment env) {
        CallStackItem item = env.peekCall(0);
        if (item.clazz != null) {
            if (item.classEntity == null)
                item.classEntity = env.fetchClass(item.clazz, false);

            if (item.classEntity == null)
                return Memory.FALSE;
            else {
                MethodEntity method = item.classEntity.findMethod(item.function);
                if (method == null)
                    return Memory.FALSE;

                ClassEntity parent = method.getClazz().getParent();
                return parent == null ? Memory.FALSE : new StringMemory(parent.getName());
            }
        }
        return Memory.FALSE;
    }

    public static Memory flow(Environment env, Memory result, Memory... others) {
        WrapFlow flow = WrapFlow.of(env, result).toObject(WrapFlow.class);

        if (others != null) {
            for (Memory other : others) {
                flow = flow.append(env, other).toObject(WrapFlow.class);
            }
        }

        return ObjectMemory.valueOf(flow);
    }


    public static Memory observable() {
        return new ObservableMemory();
    }

    public static Memory observable(Memory value) {
        return new ObservableMemory(value);
    }

    public static void addObserver(Environment env, TraceInfo trace, Memory observer, Memory handler) {
        Invoker invoker = expectingCallback(env, trace, 2, handler);

        if (invoker != null) {
            if (observer instanceof ObservableMemory) {
                ((ObservableMemory) observer).addObserver(new ObservableMemory.Observer() {
                    @Override
                    public void update(ObservableMemory observable, Memory oldValue, Memory newValue) {
                        invoker.callNoThrow(newValue, oldValue);
                    }
                });
            } else {
                env.error(trace, ErrorType.E_ERROR, "addObserver(): first argument must be observable");
            }
        }
    }

    /*public static Memory define_package(Environment env, TraceInfo trace, String name, Memory info) {
        if (env.scope.getLangMode() != LangMode.MODERN) {
            env.error(trace, ErrorType.E_NOTICE, "define_package(): Packages are available only in modern language mode");
            return Memory.FALSE;
        }

        if (!expecting(env, trace, 1, info, Memory.Type.ARRAY)) {
            return Memory.FALSE;
        }


        PackageManager manager = env.getPackageManager();

        if (manager.has(name)) {
            env.error(trace, "define_package(): Package '" + name + "' already defined");
            return Memory.FALSE;
        }

        php.runtime.env.Package aPackage = manager.fetch(name, trace);

        for (Memory cls : classes.toValue(ArrayMemory.class)) {
            aPackage.addClass(cls.toString());
        }

        for (Memory func : functions.toValue(ArrayMemory.class)) {
            aPackage.addFunction(func.toString());
        }

        for (Memory c : constants.toValue(ArrayMemory.class)) {
            aPackage.addConstant(c.toString());
        }

        return Memory.TRUE;
    }*/
}
