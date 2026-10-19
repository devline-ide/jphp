package php.runtime.ext.support.compile;

import php.runtime.ext.support.Extension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class CompileFunctionSpec {
    protected String name;
    protected boolean asImmutable;
    protected String lowerName;
    protected List<Method> methods = new LinkedList<>();

    public CompileFunctionSpec(String name) {
        this(name, false);
    }

    public CompileFunctionSpec(String name, boolean asImmutable) {
        this.name = name;
        this.asImmutable = asImmutable;
        this.lowerName = name.toLowerCase();
    }

    public void addMethod(Method method) {
        methods.add(method);
    }

    /** Explicit PHP declaration metadata, independent of the Java transport signature. */
    public php.runtime.annotation.Reflection.Signature getSignature() {
        php.runtime.annotation.Reflection.Signature signature = null;
        for (Method method : methods) {
            var declared = method.getAnnotation(php.runtime.annotation.Reflection.Signature.class);
            if (declared == null) continue;
            if (signature != null && !signature.equals(declared))
                throw new IllegalArgumentException("Inconsistent PHP signatures for " + name);
            signature = declared;
        }
        return signature;
    }

    /** Exact native declaration identity; reflection enumeration order is irrelevant. */
    public boolean isSameNativeDeclaration(CompileFunctionSpec other) {
        if (other == null || !name.equalsIgnoreCase(other.name) || asImmutable != other.asImmutable
                || methods.size() != other.methods.size()) return false;
        List<Method> remaining = new ArrayList<>(other.methods);
        for (Method method : methods) if (!remaining.remove(method)) return false;
        return remaining.isEmpty();
    }

    public String getName() {
        return name;
    }

    public String getLowerName() {
        return lowerName;
    }

    public CompileFunction toFunction() {
        CompileFunction function = new CompileFunction(name);

        for (Method method : methods) {
            function.addMethod(method, asImmutable);
        }

        return function;
    }
}
