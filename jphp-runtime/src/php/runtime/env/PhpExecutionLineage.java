package php.runtime.env;

/** install through Fiber AND ordinary Generator request handoffs. */
public final class PhpExecutionLineage {
    private static final ThreadLocal<Binding> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Token> ROOT = ThreadLocal.withInitial(Token::new);
    private PhpExecutionLineage() {}

    public static Token current() {
        Binding binding = ACTIVE.get();
        return binding == null ? ROOT.get() : binding.lineage;
    }

    public static Binding bind(Token lineage) {
        if (lineage == null) throw new NullPointerException("lineage");
        Binding binding = new Binding(lineage, ACTIVE.get());
        ACTIVE.set(binding);
        return binding;
    }

    /** Runtime identity only. A future domain claim must be weak; no Env/loader ownership here. */
    public static final class Token {
        private java.lang.ref.WeakReference<PhpCallbackDomain> domain;
        private int claims;
        private int nonSuspendingCallbacks;
        private Token() {}
        synchronized void checkDomain(PhpCallbackDomain requested) {
            PhpCallbackDomain active = domain == null ? null : domain.get();
            if (claims != 0 && active != null && active != requested)
                throw new IllegalStateException("Callback execution cannot cross runtime domains");
        }
        synchronized void claim(PhpCallbackDomain requested) {
            PhpCallbackDomain active = domain == null ? null : domain.get();
            if (claims != 0 && active != null && active != requested)
                throw new IllegalStateException("Callback execution cannot cross runtime domains");
            if (active != requested) { domain = new java.lang.ref.WeakReference<>(requested); claims = 0; }
            claims++;
        }
        synchronized void release(PhpCallbackDomain requested) {
            if (domain == null || domain.get() != requested || claims == 0)
                throw new IllegalStateException("Stale callback domain release");
            claims--; // Retain only the weak domain cache; zero claims confer no admission.
        }
    }

    public static boolean fiberAllowed() {
        Token token = current();
        synchronized (token) { return token.nonSuspendingCallbacks == 0; }
    }
    public static CallbackScope nonSuspendingCallback() {
        Token token = current();
        synchronized (token) { token.nonSuspendingCallbacks++; }
        return new CallbackScope(token);
    }
    public static final class CallbackScope implements AutoCloseable {
        private final Token token;
        private final Thread carrier = Thread.currentThread();
        private boolean closed;
        private CallbackScope(Token token) { this.token = token; }
        @Override public void close() {
            if (closed) return;
            if (carrier != Thread.currentThread()) throw new IllegalStateException("Foreign callback capability release");
            synchronized (token) { token.nonSuspendingCallbacks--; }
            closed = true;
        }
    }

    public static final class Binding implements AutoCloseable {
        private final Token lineage;
        private final Binding previous;
        private final Thread carrier = Thread.currentThread();
        private boolean closed;
        private Binding(Token lineage, Binding previous) { this.lineage = lineage; this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            if (carrier != Thread.currentThread() || ACTIVE.get() != this)
                throw new IllegalStateException("Execution lineage bindings must close on their carrier in LIFO order");
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            closed = true;
        }
    }
}
