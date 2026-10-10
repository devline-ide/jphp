package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.reflection.ConstantEntity;
import php.runtime.lang.exception.BaseError;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Environment-local values, expression recursion guards and retryable failures. */
public final class PhpConstantInitializer {
    private record Key(Environment environment, String name) {}
    private static final class State {
        final Map<Environment, Integer> depth = new HashMap<>();
        final Set<Key> references = new HashSet<>();
    }
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();
    private PhpConstantInitializer() {}

    public static void enter(Environment env) {
        State state = STATE.get();
        if (state == null) { state = new State(); STATE.set(state); }
        state.depth.merge(env, 1, Integer::sum);
    }
    public static void leave(Environment env) {
        State state = STATE.get();
        int depth = state.depth.get(env) - 1;
        if (depth == 0) state.depth.remove(env); else state.depth.put(env, depth);
        if (state.depth.isEmpty()) STATE.remove();
    }
    public static Memory resolveReference(ConstantEntity constant, Environment env, TraceInfo trace, String className) {
        State state = STATE.get();
        if (state == null || !state.depth.containsKey(env) || constant.getInitializerMethod() == null)
            return constant.getValue(env, trace);
        Key key = new Key(env, constant.getInternalName());
        if (!state.references.add(key)) {
            env.exception(trace, BaseError.class, "Cannot declare self-referencing constant %s::%s", className, constant.getName());
            return Memory.NULL;
        }
        try { return constant.getValue(env, trace); }
        finally { state.references.remove(key); }
    }
    public static Memory resolve(ConstantEntity constant, Environment env) {
        // Reuse class-registration lock order: an expression may recursively fetch/autoload a class.
        synchronized (env.getClassInitializationLock()) {
            Memory cached = env.getStatic(constant.getInternalName());
            if (cached != null) return cached.toValue();
            Memory value = constant.validateValue(env, constant.evaluateInitializer(env, constant.getClazz()), constant.getClazz(), false).toImmutable();
            return env.getOrCreateStatic(constant.getInternalName(), value).toValue();
        }
    }
}
