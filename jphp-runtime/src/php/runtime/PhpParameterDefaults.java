package php.runtime;

import php.runtime.env.Environment;
import php.runtime.reflection.ParameterEntity;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Successful declaration values belong to the actual Environment, including their managed VALUE roots. */
public final class PhpParameterDefaults {
    private PhpParameterDefaults() {}

    public static final class State {
        private final Environment environment;
        private final IdentityHashMap<ParameterEntity, Entry> values = new IdentityHashMap<>();
        private boolean retired;
        public State(Environment environment) { this.environment = java.util.Objects.requireNonNull(environment); }
        private void actual(Environment env) {
            if (environment != env || env.parameterDefaultState(false) != this)
                throw new IllegalArgumentException("Foreign parameter default storage");
            if (retired) throw new IllegalStateException("Parameter default storage has retired");
        }
    }

    /** The immutable value is physically held either by an ordinary entry or one actual typed publisher. */
    public static final class Entry {
        private final State state;
        private final ParameterEntity parameter;
        private final ParameterEntity.DefaultInitializer declaration;
        private Memory ordinary;
        private PhpOwnershipDomain domain;
        private PhpOwnershipFrame frame;
        private PhpOwnershipFramePublisher publisher;
        private Entry(State state, ParameterEntity parameter, Memory value) {
            this.state = state; this.parameter = parameter; this.declaration = parameter.getDefaultInitializer();
            this.ordinary = value;
        }
        private void actual(Environment env) {
            state.actual(env);
            if (!declaration.equals(parameter.getDefaultInitializer()) || !declaration.constantOnly())
                throw new IllegalStateException("Cached parameter declaration changed");
        }
        private Memory raw() { return publisher == null ? ordinary : publisher.current().binding().rawValue(); }
        private void retain(PhpOwnershipValues.Binding binding) throws Throwable {
            binding.requireCertifiedObject();
            if (binding.role() != PhpOwnershipValues.Role.VALUE) throw new IllegalArgumentException("Default cache requires VALUE");
            if (binding.node() == null) return;
            PhpOwnershipFrame created = new PhpOwnershipFrame(binding.domain().graph());
            try {
                var target = binding.domain().heap().framePublisher(created.temporary("constant-parameter-default"));
                try (var publication = target.prepare(target.current(), binding)) { publication.commit(); }
                domain = binding.domain(); frame = created; publisher = target; ordinary = null;
            } catch (Throwable failure) {
                try { created.unwind(failure); } catch (Throwable composed) { throw composed; }
                throw failure;
            }
        }
        public PhpOwnershipValues.Binding binding(PhpOwnershipExecution.Session session) throws Throwable {
            Environment env = session.environment(); actual(env);
            if (PhpOwnershipExecution.current(env) != session) throw new IllegalArgumentException("Foreign default invocation session");
            session.requireGeneratedTarget(parameter);
            if (domain != null && domain != session.domain()) throw new IllegalArgumentException("Default belongs to another heap");
            Memory raw = raw(); var checked = session.value(raw);
            if (publisher == null) retain(checked);
            else if (publisher.current().binding().node() != checked.node())
                throw new IllegalArgumentException("Cached default lost its actual allocation origin");
            return checked;
        }
        public Memory value(Environment env) throws Throwable {
            actual(env); var session = PhpOwnershipExecution.current(env);
            if (session != null) throw new IllegalStateException("Managed defaults require their initializer result publication");
            var authority = env.getRuntimeOwnership();
            if (domain != null && (authority == null || authority.domain() != domain))
                throw new IllegalArgumentException("Managed default requires its originating heap");
            Memory raw = raw();
            if (authority != null) {
                var checked = authority.value(raw, PhpRuntimeOwnership.Role.VALUE);
                var binding = PhpOwnershipValues.bindValue(authority.domain(), raw, checked.node()).requireCertifiedObject();
                if (publisher == null) retain(binding);
                else if (publisher.current().binding().node() != binding.node())
                    throw new IllegalArgumentException("Cached default lost its actual allocation origin");
            }
            return raw;
        }
        private void release() throws Throwable {
            ordinary = null;
            if (frame != null && !frame.isClosed()) {
                // Releasing on another graph carrier requires its existing explicit handoff protocol.
                var cleanup = domain.participant(state.environment);
                try (var execution = cleanup.enter()) {
                    try { frame.close(); } finally { cleanup.close(); }
                }
            }
        }
    }

    public static Entry find(ParameterEntity parameter, Environment env) {
        var declaration = parameter.getDefaultInitializer();
        if (declaration == null || !declaration.constantOnly()) throw new IllegalArgumentException("Not a constant-only default");
        State state = env.parameterDefaultState(false);
        if (state == null) return null;
        state.actual(env); Entry entry = state.values.get(parameter);
        if (entry != null) entry.actual(env);
        return entry;
    }

    public static Entry publish(ParameterEntity parameter, Environment env, Memory value) throws Throwable {
        // Zend leaves array AST defaults executable: [E_STRICT] warns again on the next omitted argument.
        // A scalar constant expression resolves once; a reentrant successful invocation may resolve it first.
        if (value.type == Memory.Type.ARRAY) return null;
        Entry winner = find(parameter, env);
        if (winner != null) return winner;
        State state = env.parameterDefaultState(true); state.actual(env);
        // ARRAY AST results are per-call above; constant scalar/enum values need no extra virtual conversion.
        Entry entry = new Entry(state, parameter, value);
        try {
            var session = PhpOwnershipExecution.current(env);
            if (session != null) entry.binding(session); else entry.value(env);
            state.values.put(parameter, entry); // No callback follows the actual VALUE-root publication.
            return entry;
        } catch (Throwable failure) {
            try { entry.release(); } catch (Throwable cleanup) {
                if (entry.domain != null) throw entry.domain.graph().composeFailure(failure, cleanup);
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static void retire(Environment env) throws Throwable {
        State state = env.parameterDefaultState(false);
        if (state == null || state.retired && state.values.isEmpty()) return;
        if (state.environment != env) throw new IllegalArgumentException("Foreign default cache retirement");
        state.retired = true; Throwable failure = null;
        for (Entry entry : new ArrayList<>(state.values.values())) {
            try { entry.release(); }
            catch (Throwable cleanup) {
                if (entry.domain != null) failure = entry.domain.graph().composeFailure(failure, cleanup);
                else if (failure == null) failure = cleanup; else if (cleanup != failure) failure.addSuppressed(cleanup);
            } finally {
                if (entry.frame == null || entry.frame.isClosed()) state.values.remove(entry.parameter);
            }
        }
        if (failure != null) throw failure;
    }
    public static void requireRetirementCarrier(Environment env) {
        State state = env.parameterDefaultState(false);
        if (state == null) return;
        for (Entry entry : state.values.values())
            if (entry.domain != null && entry.frame != null && !entry.frame.isClosed())
                entry.domain.graph().checkMutationAccess();
    }
    public static boolean hasManagedRoots(Environment env, PhpOwnershipDomain domain) {
        State state = env.parameterDefaultState(false);
        if (state == null) return false;
        for (Entry entry : state.values.values())
            if (entry.domain == domain && entry.frame != null && !entry.frame.isClosed()) return true;
        return false;
    }
}
