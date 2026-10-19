package php.runtime.memory.helper;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ReferenceMemory;

public class ConstantMemory extends ReferenceMemory {
    private final String name;
    private final String lowerName;
    private final boolean declaredNativeDefault;

    public ConstantMemory(String name){
        super();
        this.name = name;
        this.lowerName = name.toLowerCase();
        this.declaredNativeDefault = false;
    }

    /** Explicit native declaration metadata; the Environment still resolves the symbol at invocation. */
    public ConstantMemory(String name, Memory declaredScalar) {
        super(requireNativeDefault(name, declaredScalar));
        this.name = name;
        this.lowerName = name.toLowerCase();
        this.declaredNativeDefault = true;
    }

    private static long nativeDefaultValue(String name) {
        if ("SORT_REGULAR".equals(name)) return 0;
        if ("PHP_INT_MAX".equals(name)) return Long.MAX_VALUE;
        throw new IllegalArgumentException("Unsupported native constant default declaration");
    }

    private static Memory requireNativeDefault(String name, Memory value) {
        long expected = nativeDefaultValue(name);
        if (value == null
                || value.getClass() != php.runtime.memory.LongMemory.class || value.toLong() != expected)
            throw new IllegalArgumentException("Unsupported native constant default declaration");
        return new php.runtime.memory.LongMemory(expected);
    }

    @Override
    public Memory toImmutable(Environment env, TraceInfo trace) {
        Memory resolved = env.__getConstant(name, lowerName, trace);
        long expected = declaredNativeDefault ? nativeDefaultValue(name) : 0;
        if (declaredNativeDefault && (resolved == null
                || resolved.getClass() != php.runtime.memory.LongMemory.class || resolved.toLong() != expected
                || getRawValue().getClass() != php.runtime.memory.LongMemory.class || getRawValue().toLong() != expected))
            throw new IllegalArgumentException("Native constant default disagrees with its declaration");
        return resolved;
    }

    public String getName() {
        return name;
    }

    @Override
    public Memory toValue() {
        return this;
    }
}
