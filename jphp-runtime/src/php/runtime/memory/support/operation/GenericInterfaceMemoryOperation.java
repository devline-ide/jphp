package php.runtime.memory.support.operation;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.BaseWrapper;
import php.runtime.memory.support.MemoryOperation;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/** Resolves erased fluent T returns only through explicitly registered concrete wrappers. */
public class GenericInterfaceMemoryOperation extends MemoryOperation<Object> {
    private final Class<?> type;

    public GenericInterfaceMemoryOperation(Class<?> type) { this.type = type; }

    public static boolean supports(Class<?> type, Type genericType) {
        return type.isInterface() && genericType instanceof TypeVariable &&
                wrappers.keySet().stream().anyMatch(candidate -> type != candidate && type.isAssignableFrom(candidate));
    }

    @Override public Class<?>[] getOperationClasses() { return new Class<?>[0]; }

    @Override
    public Object convert(Environment env, TraceInfo trace, Memory value) {
        if (value.isNull()) return null;
        Object result = value.toObject(BaseWrapper.class).getWrappedObject();
        if (!type.isInstance(result)) throw new IllegalArgumentException("Expected a registered wrapper of " + type.getName());
        return result;
    }

    @Override
    public Memory unconvert(Environment env, TraceInfo trace, Object value) throws Throwable {
        if (value == null) return Memory.NULL;
        Class<?> selected = null;
        for (Class<?> candidate : wrappers.keySet()) {
            if (candidate == type || !type.isAssignableFrom(candidate) || !candidate.isInstance(value)) continue;
            if (selected == null || selected.isAssignableFrom(candidate)) selected = candidate;
            else if (!candidate.isAssignableFrom(selected)) throw new IllegalArgumentException("Ambiguous generic wrapper for " + type.getName());
        }
        if (selected == null) throw new IllegalArgumentException("No registered generic wrapper for " + type.getName());
        return MemoryOperation.get(selected, null).unconvert(env, trace, value);
    }
}
