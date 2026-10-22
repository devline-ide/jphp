package php.runtime.invoke;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;

/** Internal invocation coercion applies only to its exact native target and call site. */
public final class NativeFunctionContext {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private NativeFunctionContext() {}

    public static final class Frame implements AutoCloseable {
        private final Environment environment;
        private final TraceInfo trace;
        private final String function;
        private final Frame previous;
        private Frame(Environment environment, TraceInfo trace, String function, Frame previous) {
            this.environment = environment; this.trace = trace; this.function = function; this.previous = previous;
        }
        @Override public void close() {
            if (CURRENT.get() != this) throw new IllegalStateException("Unbalanced native function context");
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static Frame enterWeak(Environment env, TraceInfo trace, String function) {
        var frame = new Frame(env, trace, function, CURRENT.get());
        CURRENT.set(frame);
        return frame;
    }

    /** Reflected callback dispatch has PHP internal coercion; optimized source calls keep caller strictness. */
    public static Frame enterCallbackDispatcher(Environment env, TraceInfo trace, java.lang.reflect.Method actual) {
        if (!actual.equals(CallbackMembers.CUF) && !actual.equals(CallbackMembers.CUFA)) return null;
        return enterWeak(env, trace, actual.getName());
    }

    private static final class CallbackMembers {
        static final java.lang.reflect.Method CUF = member("call_user_func", php.runtime.Memory[].class);
        static final java.lang.reflect.Method CUFA = member("call_user_func_array", php.runtime.Memory.class);
        private static java.lang.reflect.Method member(String name, Class<?> last) {
            try {return php.runtime.ext.core.LangFunctions.class.getDeclaredMethod(name,
                    Environment.class, TraceInfo.class, php.runtime.Memory.class, last);}
            catch (NoSuchMethodException impossible) {throw new ExceptionInInitializerError(impossible);}
        }
    }

    /** Distinguish a reflected/internal dispatch from a direct source invocation at this exact call site. */
    public static boolean isInternalCall(Environment env, TraceInfo trace, String function) {
        Frame frame = CURRENT.get();
        return frame != null && frame.environment == env && frame.trace == trace && frame.function.equals(function);
    }

    public static boolean isStrict(Environment env, TraceInfo trace, String function) {
        if (isInternalCall(env, trace, function)) return false;
        var module = env.getModuleManager().findModule(trace);
        return module != null && module.isStrictTypes();
    }
}
