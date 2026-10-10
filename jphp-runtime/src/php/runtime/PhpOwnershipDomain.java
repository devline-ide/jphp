package php.runtime;

import java.util.*;

/**
 * Explicit heap/execution-domain boundary. Environment, CompileScope and Java thread identity
 * do not determine which participants share PHP values. Live adapters must carry the originating
 * domain/node stamp; registering an opaque raw identity is not proof of foreign-value ownership.
 * This API does not activate compiler/container accounting or any existing destruction path.
 */
public final class PhpOwnershipDomain {
    public interface Lifecycle {
        default void destruct(PhpOwnership.Node node, Object cleanupContext) throws Throwable {}
        default void destroy(PhpOwnership.Node node, Object cleanupContext) throws Throwable {}
    }
    private static final Lifecycle NO_LIFECYCLE = new Lifecycle() {};
    private final PhpOwnership graph;
    private final IdentityHashMap<PhpOwnership.Node, Lifecycle> objectLifecycles = new IdentityHashMap<>();
    private Execution execution;
    private PhpOwnershipHeap heap;

    public PhpOwnershipDomain() {graph = new PhpOwnership();}
    public PhpOwnershipDomain(PhpOwnership.FailureComposition composition) {graph = new PhpOwnership(composition);}
    /** Low-level graph access for certified adapters; it does not certify raw Memory values. */
    public PhpOwnership graph() {return graph;}
    /** One address/revision authority per domain; no compiler or container activation. */
    public PhpOwnershipHeap heap() {
        graph.checkMutationAccess();
        if (heap == null) new PhpOwnershipHeap(this);
        return heap;
    }
    void attachHeap(PhpOwnershipHeap attaching) {
        graph.checkMutationAccess();
        if (heap != null) throw new IllegalStateException("Ownership domain already has a heap adapter");
        heap=Objects.requireNonNull(attaching);
    }
    public Participant participant(Object cleanupContext) {
        graph.checkMutationAccess();return new Participant(Objects.requireNonNull(cleanupContext));
    }
    public PhpOwnership.Node object(Object identity, Lifecycle lifecycle) {
        requireExecution();Objects.requireNonNull(identity);
        Lifecycle selected = lifecycle == null ? NO_LIFECYCLE : lifecycle;
        PhpOwnership.Node existing = graph.find(identity);
        if (existing != null) {
            if (existing.kind() != PhpOwnership.Kind.OBJECT || objectLifecycles.get(existing) != selected)
                throw new IllegalArgumentException("Object identity has another kind or lifecycle adapter");
            return existing;
        }
        PhpOwnership.Node node = graph.object(identity, new PhpOwnership.Lifecycle() {
            @Override public void destruct(PhpOwnership.Node target) throws Throwable {
                selected.destruct(target, currentContext());
            }
            @Override public void destroy(PhpOwnership.Node target) throws Throwable {
                try {selected.destroy(target, currentContext());}
                finally {objectLifecycles.remove(target);}
            }
        });
        objectLifecycles.put(node, selected);return node;
    }
    /** The adapter supplies canonical CELL identity explicitly; virtual Memory accessors are never called. */
    public PhpOwnership.Node canonicalCell(Object explicitCanonicalIdentity) {
        requireExecution();return graph.canonicalCell(explicitCanonicalIdentity);
    }
    public PhpOwnership.Node storage(Object stableBackingIdentity) {
        requireExecution();return graph.storage(stableBackingIdentity);
    }
    public Object currentContext() {requireExecution();return execution.participant.cleanupContext;}
    public int collectCycles() throws Throwable {requireExecution();return graph.collectCycles();}
    public OwnedValue hostValue(PhpOwnership.Node node, Object rawValue) throws Throwable {
        return value(null, node, rawValue);
    }
    public OwnedCell hostCell(PhpOwnership.Node node, Object explicitCellRaw) throws Throwable {
        return cell(null, node, explicitCellRaw);
    }
    /** Context scopes must be closed before passing execution to another thread. */
    public PhpOwnership.Handoff handoff() {
        graph.checkMutationAccess();
        if (execution != null) throw new IllegalStateException("Close execution scopes before domain handoff");
        return graph.handoff();
    }
    private void requireExecution() {
        graph.checkMutationAccess();
        if (execution == null) throw new IllegalStateException("An explicit participant execution is required");
    }
    private void validate(PhpOwnership.Node node, boolean cell) {
        requireExecution();
        if (node == null) {
            if (cell) throw new IllegalArgumentException("OwnedCell requires an explicit canonical CELL node");
            return;
        }
        if (node.graph() != graph) throw new IllegalArgumentException("Ownership value belongs to another domain");
        if (node.state() == PhpOwnership.State.DISPOSED) throw new IllegalStateException("Ownership value was disposed");
        if (cell != (node.kind() == PhpOwnership.Kind.CELL))
            throw new IllegalArgumentException(cell ? "OwnedCell requires a CELL node" : "Use OwnedCell for canonical CELL ownership");
    }
    private OwnedValue value(Participant owner, PhpOwnership.Node node, Object raw) throws Throwable {
        validate(node, false);if (owner != null) owner.open();
        OwnedValue handle = new OwnedValue(owner, graph.root(), raw);acquire(handle, node);return handle;
    }
    private OwnedCell cell(Participant owner, PhpOwnership.Node node, Object raw) throws Throwable {
        validate(node, true);if (owner != null) owner.open();
        OwnedCell handle = new OwnedCell(owner, graph.root(), raw);acquire(handle, node);return handle;
    }
    private void acquire(Owned handle, PhpOwnership.Node node) throws Throwable {
        try {
            if (handle.owner != null) handle.owner.handles.add(handle);
            handle.root.replace(node);
        } catch (Throwable failure) {
            try {handle.close();} catch (Throwable cleanup) {failure = graph.composeFailure(failure, cleanup);}
            throw failure;
        }
    }

    /** Each participant owns its own handles, while explicit host handles outlive participant shutdown. */
    public final class Participant implements AutoCloseable {
        private final Object cleanupContext;
        private final LinkedHashSet<Owned> handles = new LinkedHashSet<>();
        private boolean closed;
        private Participant(Object cleanupContext) {this.cleanupContext = cleanupContext;}
        public PhpOwnershipDomain domain() {return PhpOwnershipDomain.this;}
        public Object cleanupContext() {return cleanupContext;}
        public boolean isClosed() {return closed;}
        private void open() {
            graph.checkMutationAccess();if (closed) throw new IllegalStateException("Ownership participant is closed");
        }
        public Execution enter() {
            open();Execution entered = new Execution(this, execution);execution = entered;return entered;
        }
        public OwnedValue ownValue(PhpOwnership.Node node, Object rawValue) throws Throwable {
            open();return value(this, node, rawValue);
        }
        public OwnedCell ownCell(PhpOwnership.Node node, Object explicitCellRaw) throws Throwable {
            open();return cell(this, node, explicitCellRaw);
        }
        public void run(PhpOwnership.Work work) throws Throwable {
            Objects.requireNonNull(work);try (Execution ignored = enter()) {work.run();}
        }
        @Override public void close() throws Exception {finish(null);}
        /** Release all participant roots even after failure, preserving the pending exception via the graph policy. */
        public void unwind(Throwable primary) throws Exception {finish(Objects.requireNonNull(primary));}
        private void finish(Throwable primary) throws Exception {
            graph.checkMutationAccess();Throwable failure = primary;
            if (!closed) {
                List<Owned> releasing = new ArrayList<>(handles);
                try (Execution ignored = enter()) {
                    closed = true;
                    for (int i = releasing.size() - 1; i >= 0; i--) {
                        try {releasing.get(i).close();}
                        catch (Throwable cleanup) {failure = graph.composeFailure(failure, cleanup);}
                    }
                }
            }
            if (failure != null) PhpOwnershipDomain.<Exception>raise(failure);
        }
    }

    /** LIFO, execution-thread-bound selection of the releasing PHP environment/adapter context. */
    public final class Execution implements AutoCloseable {
        private final Participant participant;
        private final Execution previous;
        private boolean closed;
        private Execution(Participant participant, Execution previous) {this.participant = participant;this.previous = previous;}
        public Participant participant() {return participant;}
        public boolean isClosed() {return closed;}
        @Override public void close() {
            graph.checkMutationAccess();if (closed) return;
            if (execution != this) throw new IllegalStateException("Ownership executions must close in LIFO order");
            closed = true;execution = previous;
        }
    }

    /** An explicit root, not a count of Java wrappers. Forgotten host handles require explicit host cleanup. */
    public abstract class Owned implements AutoCloseable {
        private Participant owner;
        private final PhpOwnership.Slot root;
        private Object raw;
        private boolean closed;
        private Owned(Participant owner, PhpOwnership.Slot root, Object raw) {this.owner = owner;this.root = root;this.raw = raw;}
        public PhpOwnershipDomain domain() {return PhpOwnershipDomain.this;}
        public PhpOwnership.Node node() {graph.checkExecutionAccess();return root.target();}
        public Object rawValue() {graph.checkExecutionAccess();return raw;}
        public Participant participant() {graph.checkExecutionAccess();return owner;}
        public boolean isClosed() {return closed;}
        private void writable() {
            requireExecution();if (closed) throw new IllegalStateException("Ownership handle is closed");
        }
        /** Move custody of this same linear handle, with no extra retain or zero-owner interval. */
        protected final void moveTo(Participant destination) {
            writable();
            if (destination != null) {
                if (destination.domain() != PhpOwnershipDomain.this)
                    throw new IllegalArgumentException("Ownership participant belongs to another domain");
                destination.open();
            }
            if (owner == destination) return;
            if (destination != null) destination.handles.add(this);
            if (owner != null) owner.handles.remove(this);
            owner = destination;
        }
        @Override public void close() throws Exception {
            graph.checkMutationAccess();if (closed) return;requireExecution();
            closed = true;raw = null;
            if (owner != null) owner.handles.remove(this);
            owner = null;
            try {root.close();} catch (Throwable failure) {PhpOwnershipDomain.<Exception>raise(failure);}
        }
        public final void close(Participant cleanupParticipant) throws Exception {
            Objects.requireNonNull(cleanupParticipant);
            if (cleanupParticipant.domain() != PhpOwnershipDomain.this)
                throw new IllegalArgumentException("Cleanup participant belongs to another domain");
            try (Execution ignored = cleanupParticipant.enter()) {close();}
        }
    }
    public final class OwnedValue extends Owned {
        private OwnedValue(Participant owner, PhpOwnership.Slot root, Object raw) {super(owner, root, raw);}
        public OwnedValue transferTo(Participant destination) {moveTo(Objects.requireNonNull(destination));return this;}
        public OwnedValue detachToHost() {moveTo(null);return this;}
    }
    public final class OwnedCell extends Owned {
        private OwnedCell(Participant owner, PhpOwnership.Slot root, Object raw) {super(owner, root, raw);}
        public OwnedCell transferTo(Participant destination) {moveTo(Objects.requireNonNull(destination));return this;}
        public OwnedCell detachToHost() {moveTo(null);return this;}
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raise(Throwable failure) throws T {throw (T) failure;}
}
