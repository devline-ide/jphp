package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.reflection.ParameterEntity;
import java.util.*;

/** Separate keyed binding metadata, scoped to exact Environment/target/vector identity. */
public final class BoundArguments {
    private static final ThreadLocal<ArrayDeque<Scope>> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<NativeScope>> NATIVE_CURRENT = new ThreadLocal<>();
    private final Object owner;
    private final Thread thread = Thread.currentThread();
    private final Environment env;
    private final PhpArgumentBinder.Signature signature;
    private final Memory[] arguments;
    private final Memory[] sent;
    private final boolean[] supplied;
    private final LinkedHashMap<String, Memory> named;
    private boolean entered, prepared, released, nativeEntered;
    private Memory[] preparedVector;

    BoundArguments(Object owner, Environment env, PhpArgumentBinder.Signature signature, Memory[] arguments,
                   boolean[] supplied, LinkedHashMap<String, Memory> named) {
        this.owner = owner;this.env = env;this.signature = signature;this.arguments = arguments;this.sent = arguments.clone();
        this.supplied = supplied;this.named = named;
    }
    public PhpArgumentBinder.Signature signature() {return signature;}
    public Memory[] arguments() {return arguments;}
    public Map<String, Memory> namedExtras() {return Collections.unmodifiableMap(named);}
    public boolean supplied(int index) {return index >= 0 && index < supplied.length && supplied[index];}
    public int suppliedCount() {return arguments.length;}
    public void requireOwner(Object actual, Environment environment) {
        if (owner != actual || env != environment || thread != Thread.currentThread() || released)
            throw new IllegalArgumentException("Foreign bound invocation");
    }
    Scope enter(Object actual, Environment environment) {
        requireOwner(actual, environment);
        if (entered) throw new IllegalStateException("Bound invocation is one-shot");
        for (int i = 0; i < arguments.length; i++)
            if (arguments[i] != sent[i]) throw new IllegalArgumentException("Bound vector changed after its SENDs");
        entered = true;var scope = new Scope(this);
        var stack = CURRENT.get();if (stack == null) {stack = new ArrayDeque<>();CURRENT.set(stack);}
        stack.push(scope);return scope;
    }
    void requirePreparation(ParameterEntity[] parameters) {
        if (current(env, arguments, signature.target()) != this || !sameParameters(parameters))
            throw new IllegalArgumentException("Argument preparation has no exact bound target scope");
        if (prepared) throw new IllegalStateException("Bound arguments were prepared twice");
    }
    public static BoundArguments current(Environment env, Memory[] exactVector, Object exactTarget) {
        var stack = CURRENT.get();
        if (stack == null || stack.isEmpty()) return null;
        var bound = stack.peek().bound;
        return bound.env == env && exactTarget == bound.signature.target() && exactVector == bound.arguments ? bound : null;
    }
    private boolean sameParameters(ParameterEntity[] parameters) {
        var expected = signature.parameters();
        if (parameters == expected) return true;
        if (parameters == null || parameters.length != expected.length) return false;
        for (int i = 0; i < parameters.length; i++) if (parameters[i] != expected[i]) return false;
        return true;
    }
    void prepared(Memory[] vector) {prepared = true;preparedVector = vector;}
    void updateCallArgument(int index, Memory value) {if (index < arguments.length) arguments[index] = value;}

    void release() {
        if (released) return;
        var stack = CURRENT.get();
        if (stack != null) for (var scope : stack)
            if (scope.bound == this) throw new IllegalStateException("Bound context is still active");
        released = true;Arrays.fill(arguments, null);Arrays.fill(sent, null);
        if (preparedVector != null && preparedVector != arguments) Arrays.fill(preparedVector, null);
        named.clear();preparedVector = null;
    }

    /** Invocation boundary hook: no name/arity/ambient alias inference. */
    public static Memory[] callArguments(Environment env, Memory[] exactVector, Object exactTarget) {
        var stack = CURRENT.get();
        if (stack != null && !stack.isEmpty()) {
            var bound = stack.peek().bound;
            if (bound.env == env && exactTarget == bound.signature.target()
                    && (exactVector == bound.arguments || exactVector == bound.preparedVector))
                return bound.arguments;
        }
        return exactVector;
    }
    /** Only the actual reflected native dispatcher may attach its newly constructed Java tail. */
    public static NativeScope enterNativeVariadic(Environment environment, Memory[] exactInput,
            php.runtime.reflection.CompileFunctionEntity function, java.lang.reflect.Method selected,
            Memory[] actualTail) {
        var stack = CURRENT.get();
        if (stack == null || stack.isEmpty()) return null;
        var bound = stack.peek().bound;
        if (bound.env != environment || bound.signature.target() != function
                || exactInput != bound.arguments && exactInput != bound.preparedVector) return null;
        var declaration = function.getPhpSignature();
        if (declaration == null || !declaration.namedVariadic()) return null;
        var variadic = bound.signature.variadic();
        var actual = function.getCompileFunction().find(exactInput.length);
        if (!bound.signature.completeNames() || !bound.prepared || bound.released
                || bound.thread != Thread.currentThread() || !bound.sameParameters(function.getParameters())
                || variadic == null || variadic.isReference() || actual == null || !actual.method.equals(selected)
                || !declaration.equals(selected.getAnnotation(php.runtime.annotation.Reflection.Signature.class)))
            throw new IllegalArgumentException("Native variadic invocation has no exact bound declaration");
        int fixed = bound.signature.fixedCount();
        int javaTail = actual.parameterTypes.length - 1;
        if (javaTail < 0 || actual.parameterTypes[javaTail] != Memory[].class || !actual.isVarArg()
                || actual.references[javaTail] || actual.mutableValues[javaTail]
                || exactInput.length < fixed || actualTail == null || actualTail.length != exactInput.length - fixed)
            throw new IllegalArgumentException("Native variadic invocation has no exact Java tail");
        int head = 0;
        for (int i = 0; i < javaTail; i++) {
            var type = actual.parameterTypes[i];
            if (type == Environment.class || type == php.runtime.env.TraceInfo.class) continue;
            if (type == Memory[].class) throw new IllegalArgumentException("Multiple native argument tails");
            head++;
        }
        if (head != fixed) throw new IllegalArgumentException("Native PHP and Java fixed heads differ");
        if (bound.nativeEntered) throw new IllegalStateException("Native variadic invocation is one-shot");
        bound.nativeEntered=true;
        var scope = new NativeScope(bound, selected, actualTail);
        var nativeStack = NATIVE_CURRENT.get();
        if (nativeStack == null) {nativeStack = new ArrayDeque<>();NATIVE_CURRENT.set(nativeStack);}
        nativeStack.push(scope);return scope;
    }

    /** Exact body-member and Java-array identity; names and ordinary array wrappers are not witnesses. */
    public static BoundArguments currentNativeVariadic(Environment environment, Memory[] actualTail,
                                                       java.lang.reflect.Method bodyMember) {
        var nativeStack = NATIVE_CURRENT.get();var stack = CURRENT.get();
        if (nativeStack == null || nativeStack.isEmpty() || stack == null || stack.isEmpty()) return null;
        var scope = nativeStack.peek();var bound = scope.bound;
        return scope.thread == Thread.currentThread() && scope.tail == actualTail && scope.member.equals(bodyMember)
                && bound.env == environment && stack.peek().bound == bound && !bound.released ? bound : null;
    }
    public static final class NativeScope implements AutoCloseable {
        private final BoundArguments bound;
        private final java.lang.reflect.Method member;
        private final Memory[] tail;
        private final Thread thread = Thread.currentThread();
        private boolean closed;
        private NativeScope(BoundArguments bound, java.lang.reflect.Method member, Memory[] tail) {
            this.bound=bound;this.member=member;this.tail=tail;
        }
        @Override public void close() {
            var nativeStack=NATIVE_CURRENT.get();var stack=CURRENT.get();
            if (closed || thread != Thread.currentThread() || nativeStack == null || nativeStack.isEmpty()
                    || nativeStack.peek() != this || stack == null || stack.isEmpty() || stack.peek().bound != bound)
                throw new IllegalStateException("Foreign or unbalanced native variadic scope");
            closed=true;nativeStack.pop();if (nativeStack.isEmpty()) NATIVE_CURRENT.remove();
        }
    }
    public static final class Scope implements AutoCloseable {
        private final BoundArguments bound;
        private final Thread thread = Thread.currentThread();
        private boolean closed;
        private Scope(BoundArguments bound) {this.bound = bound;}
        @Override public void close() {
            var stack = CURRENT.get();
            if (closed || thread != Thread.currentThread() || stack == null || stack.isEmpty() || stack.peek() != this)
                throw new IllegalStateException("Foreign or unbalanced bound invocation scope");
            var nativeStack = NATIVE_CURRENT.get();
            if (nativeStack != null) for (var scope : nativeStack)
                if (scope.bound == bound) throw new IllegalStateException("Native variadic context is still active");
            closed = true;stack.pop();if (stack.isEmpty()) CURRENT.remove();
        }
    }
}
