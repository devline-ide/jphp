package php.runtime;

import php.runtime.memory.ReferenceMemory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Explicit PHP ownership graph. This infrastructure does not inspect Java reachability,
 * detach property guards, or replace the existing runtime's destruction paths.
 * A scheduler serializes execution using explicit, single-use handoff tickets.
 */
public final class PhpOwnership {
    public enum Kind { OBJECT, CELL, STORAGE }
    public enum State { LIVE, FINALIZING, DISPOSED }

    public interface Lifecycle {
        default void destruct(Node node) throws Throwable {}
        default void destroy(Node node) throws Throwable {}
    }
    @FunctionalInterface public interface Work { void run() throws Throwable; }
    /** An adapter must preserve both failures; PHP previous chaining is not inferred from suppression. */
    @FunctionalInterface public interface FailureComposition { Throwable compose(Throwable pending,Throwable cleanup); }
    private static final Lifecycle NO_LIFECYCLE=new Lifecycle() {};

    private volatile Thread interpreter=Thread.currentThread();
    private final Object executionLock=new Object();
    private Handoff pendingHandoff;
    private Publication activePublication;
    private final FailureComposition failureComposition;
    private final IdentityHashMap<Object,Node> identities=new IdentityHashMap<>();
    private final LinkedHashSet<Node> nodes=new LinkedHashSet<>();
    private final LinkedHashSet<Slot> roots=new LinkedHashSet<>();
    private final ArrayDeque<Node> zeroes=new ArrayDeque<>();
    // Tombstones prevent re-registering disposed identities without retaining them.
    // Weak references only remove bookkeeping; they never trigger PHP destruction.
    private final ReferenceQueue<Object> disposedQueue=new ReferenceQueue<>();
    private final HashSet<IdentityKey> disposed=new HashSet<>();
    private boolean processing;
    private int deferred,composing;

    public PhpOwnership() {this(PhpOwnership::javaFailure);}
    public PhpOwnership(FailureComposition composition) {failureComposition=Objects.requireNonNull(composition);}

    public Node object(Object identity, Lifecycle lifecycle) {return node(Kind.OBJECT,identity,lifecycle);}
    /** Identity is already canonical: this entry point never reads or normalizes Memory. */
    public Node canonicalCell(Object identity) {return node(Kind.CELL,identity,null);}
    /** Plain ReferenceMemory convenience only; live adapters classify structural carriers explicitly. */
    @Deprecated
    public Node cell(ReferenceMemory reference) {
        thread();Objects.requireNonNull(reference);
        Set<ReferenceMemory> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while (true) {
            if (reference.getClass() != ReferenceMemory.class || !seen.add(reference))
                throw new IllegalArgumentException("CELL identity requires an explicit canonical cell");
            Memory value=reference.getValue();
            if (!(value instanceof ReferenceMemory)) return canonicalCell(reference);
            reference=(ReferenceMemory)value;
        }
    }
    public Node storage(Object backingStorage) {return node(Kind.STORAGE,backingStorage,null);}

    /** Register once per heap identity. Allocation must acquire its temporary/root owner before collection. */
    public Node node(Kind kind, Object identity, Lifecycle lifecycle) {
        thread();Objects.requireNonNull(kind);Objects.requireNonNull(identity);
        reapTombstones();
        Node existing=identities.get(identity);
        if (existing != null) {
            if (existing.kind != kind || lifecycle != null && lifecycle != existing.lifecycle)
                throw new IllegalArgumentException("Ownership identity has a different kind or lifecycle");
            return existing;
        }
        if (disposed.contains(new IdentityKey(identity,null)))
            throw new IllegalStateException("Ownership identity was disposed");
        Node created=new Node(kind,identity,lifecycle == null ? NO_LIFECYCLE : lifecycle);
        identities.put(identity,created);nodes.add(created);
        return created;
    }

    public Node find(Object identity) {thread();return identities.get(identity);}
    public int liveNodes() {thread();return nodes.size();}
    public Slot root() {thread();Slot slot=new Slot(null);roots.add(slot);return slot;}
    public Slot edge(Node owner) {
        thread();valid(Objects.requireNonNull(owner));Slot slot=new Slot(owner);owner.outgoing.add(slot);return slot;
    }
    public Pin pin(Node target) throws Throwable {
        thread();Slot root=root();
        try {root.replace(target);return new Pin(root);}
        catch (Throwable failure) {
            try {root.close();} catch (Throwable cleanup) {failure=merge(failure,cleanup);}
            throw failure;
        }
    }

    /** Check domain ownership only; publication tokens use this to commit already validated state. */
    public void checkExecutionAccess() {
        if (Thread.currentThread() != interpreter) throw new IllegalStateException("Ownership graph belongs to its execution thread");
    }
    public void checkMutationAccess() {thread();}

    /** Suspend at a quiescent boundary; the next thread must explicitly claim this ticket. */
    public Handoff handoff() {
        thread();
        if (processing || deferred != 0 || composing != 0) throw new IllegalStateException("Ownership execution cannot be handed off during cleanup or mutation");
        synchronized (executionLock) {
            checkExecutionAccess();
            Handoff ticket=new Handoff();pendingHandoff=ticket;interpreter=null;return ticket;
        }
    }
    public final class Handoff {
        private volatile boolean claimed;
        private Handoff() {}
        public boolean isClaimed() {return claimed;}
        public void claim() {
            synchronized (executionLock) {
                if (claimed || pendingHandoff != this || interpreter != null)
                    throw new IllegalStateException("Ownership handoff ticket was consumed");
                claimed=true;pendingHandoff=null;interpreter=Thread.currentThread();
            }
        }
    }

    /** Explicit adapter policy; a faulty adapter cannot erase the original failures. */
    public Throwable composeFailure(Throwable pending,Throwable cleanup) {
        thread();
        if (pending == null) return cleanup;
        if (cleanup == null || pending == cleanup) return pending;
        composing++;
        try {return Objects.requireNonNull(failureComposition.compose(pending,cleanup),"Failure composition returned null");}
        catch (RuntimeException | Error adapterFailure) {
            javaFailure(pending,cleanup);return javaFailure(pending,adapterFailure);
        } finally {composing--;}
    }
    private void runPublication(Publication publication,Work rawPublication) throws Throwable {
        Throwable failure=null;
        try {rawPublication.run();publication.commit();} catch (Throwable error) {failure=error;}
        try {publication.close();} catch (Throwable cleanup) {failure=merge(failure,cleanup);}
        if (failure != null) throw failure;
    }

    /** Delay destructors until a validated storage/edge mutation has fully committed. This is not a rollback transaction. */
    public void deferDestruction(Work work) throws Throwable {
        thread();Objects.requireNonNull(work);Throwable failure=null;
        deferred++;
        try {work.run();} catch (Throwable error) {failure=error;}
        finally {deferred--;}
        if (deferred == 0) {
            try {drain();} catch (Throwable error) {failure=merge(failure,error);}
        }
        if (failure != null) throw failure;
    }

    /** Returns graph nodes reclaimed, not Zend's public gc_collect_cycles counter. Reentrant collection returns zero. */
    public int collectCycles() throws Throwable {
        thread();if (processing || deferred != 0) return 0;
        processing=true;Throwable failure=null;int collected=0;
        try {
            List<Throwable> errors=new ArrayList<>();drainZeroes(errors);
            Set<Node> reachable=reachable();List<Node> candidates=new ArrayList<>();
            for (Node node : nodes) if (!reachable.contains(node)) candidates.add(node);
            // PHP runs all destructors of the candidate cycle before checking resurrection.
            for (Node node : candidates) if (node.state != State.DISPOSED) destruct(node,errors);
            reachable=reachable();List<Node> doomed=new ArrayList<>();
            for (Node node : candidates) if (node.state != State.DISPOSED && !reachable.contains(node)) doomed.add(node);
            // Mark the component first: outgoing releases cannot run its destructors a second time.
            for (Node node : doomed) node.state=State.DISPOSED;
            for (Node node : doomed) cleanup(node,errors);
            collected=doomed.size();drainZeroes(errors);
            for (Throwable error : errors) failure=merge(failure,error);
        } finally {processing=false;}
        if (failure != null) throw failure;
        return collected;
    }

    private Set<Node> reachable() {
        Set<Node> marked=Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Node> pending=new ArrayDeque<>();
        for (Slot root : roots) if (root.target != null && marked.add(root.target)) pending.add(root.target);
        while (!pending.isEmpty()) for (Slot edge : pending.remove().outgoing)
            if (edge.target != null && marked.add(edge.target)) pending.add(edge.target);
        return marked;
    }

    private void destruct(Node node, List<Throwable> errors) {
        if (node.kind != Kind.OBJECT || node.destructorStarted) return;
        node.destructorStarted=true;node.state=State.FINALIZING;
        try {node.lifecycle.destruct(node);} catch (Throwable failure) {errors.add(failure);}
        finally {node.state=State.LIVE;}
    }

    private void cleanup(Node node, List<Throwable> errors) {
        try {node.lifecycle.destroy(node);} catch (Throwable failure) {errors.add(failure);}
        finally {
            for (Slot edge : new ArrayList<>(node.outgoing)) {
                edge.closed=true;Node old=edge.target;edge.target=null;
                if (old != null) release(old);
            }
            node.outgoing.clear();nodes.remove(node);identities.remove(node.identity);
            disposed.add(new IdentityKey(node.identity,disposedQueue));
        }
    }

    private void drainZeroes(List<Throwable> errors) {
        while (!zeroes.isEmpty()) {
            Node node=zeroes.remove();node.queued=false;
            if (node.state != State.LIVE || node.incoming != 0) continue;
            destruct(node,errors);
            if (node.incoming != 0) continue; // __destruct resurrected a real owner.
            node.state=State.DISPOSED;cleanup(node,errors);
        }
    }
    private void drain() throws Throwable {
        if (deferred != 0) return;
        boolean previous=processing;processing=true;List<Throwable> errors=new ArrayList<>();
        try {drainZeroes(errors);} finally {processing=previous;}
        Throwable failure=null;for (Throwable error : errors) failure=merge(failure,error);
        if (failure != null) throw failure;
    }
    private void retain(Node node) {
        if (node != null) {
            if (node.incoming == Long.MAX_VALUE) throw new IllegalStateException("Ownership count overflow");
            node.incoming++;
        }
    }
    private void release(Node node) {
        if (node.incoming <= 0) throw new IllegalStateException("Ownership count underflow");
        node.incoming--;
        if (node.incoming == 0 && node.state != State.DISPOSED && !node.queued) {
            node.queued=true;zeroes.add(node);
        }
    }
    private void valid(Node node) {
        if (node == null) return;
        if (node.graph() != this) throw new IllegalArgumentException("Ownership node belongs to another graph");
        if (node.state == State.DISPOSED) throw new IllegalStateException("Ownership node was disposed");
    }
    private void thread() {
        checkExecutionAccess();
        if (activePublication != null) throw new IllegalStateException("Ownership mutation is barred during raw publication");
    }
    private void prequeue(Node node) {
        if (node != null && !node.queued) {zeroes.add(node);node.queued=true;}
    }
    private void reapTombstones() {Object key;while ((key=disposedQueue.poll()) != null) disposed.remove(key);}
    private Throwable merge(Throwable first,Throwable next) {return composeFailure(first,next);}
    private static Throwable javaFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    public final class Node {
        private final Kind kind;
        private final Object identity;
        private final Lifecycle lifecycle;
        private final LinkedHashSet<Slot> outgoing=new LinkedHashSet<>();
        private long incoming;
        private State state=State.LIVE;
        private boolean destructorStarted,queued;
        private Node(Kind kind,Object identity,Lifecycle lifecycle) {this.kind=kind;this.identity=identity;this.lifecycle=lifecycle;}
        public PhpOwnership graph() {return PhpOwnership.this;}
        public Kind kind() {return kind;}
        public Object identity() {return identity;}
        public State state() {return state;}
        public long incoming() {return incoming;}
        public boolean destructorStarted() {return destructorStarted;}
        public List<Slot> outgoing() {return Collections.unmodifiableList(new ArrayList<>(outgoing));}
    }

    public final class Slot {
        private final Node owner;
        private Node target;
        private boolean closed;
        private Slot(Node owner) {this.owner=owner;}
        public Node target() {return target;}
        public Node owner() {return owner;}
        public boolean isClosed() {return closed;}
        private void writable() {
            thread();if (closed) throw new IllegalStateException("Ownership slot is closed");valid(owner);
        }
        /** Prepare before a pure raw store; commit afterward without callbacks or validation in between. */
        public Publication preparePublication(Node value) {
            writable();valid(value);Publication publication=new Publication(this,null,value);
            publication.begin();return publication;
        }
        /** Retire the physical holder after its raw unlink, before any zero-owner callback. */
        public Publication prepareRetirement() {
            writable();Publication publication=new Publication(this,null,null);
            publication.retiring=true;publication.begin();return publication;
        }
        public Publication prepareTransferTo(Slot destination) {
            writable();Objects.requireNonNull(destination).writable();
            if (destination.graph() != graph()) throw new IllegalArgumentException("Ownership slots belong to different graphs");
            valid(target);Publication publication=new Publication(destination,destination == this ? null : this,target);
            publication.begin();return publication;
        }
        /** Callback performs validated nonthrowing raw stores; abort is allowed only before storing. */
        public void replacePublished(Node value,Work rawPublication) throws Throwable {
            Objects.requireNonNull(rawPublication);runPublication(preparePublication(value),rawPublication);
        }
        public void transferPublishedTo(Slot destination,Work rawPublication) throws Throwable {
            Objects.requireNonNull(rawPublication);runPublication(prepareTransferTo(destination),rawPublication);
        }
        public void replace(Node value) throws Throwable {
            writable();valid(value);if (target == value) return;
            retain(value);Node old=target;target=value;if (old != null) release(old);drain();
        }
        /** No ownership mutation from this operation precedes validation. Validation's own side effects remain. */
        public void replaceChecked(Node value, Work validation) throws Throwable {
            writable();valid(value);Objects.requireNonNull(validation).run();replace(value);
        }
        /** Move a slot's owner edge without a zero-owner gap, including aliases and a replaced destination. */
        public void transferTo(Slot destination) throws Throwable {
            writable();Objects.requireNonNull(destination).writable();
            if (destination.graph() != graph()) throw new IllegalArgumentException("Ownership slots belong to different graphs");
            if (destination == this) return;
            valid(target);Node moving=target,old=destination.target;
            retain(moving);destination.target=moving;target=null;
            if (old != null) release(old);if (moving != null) release(moving);drain();
        }
        private PhpOwnership graph() {return PhpOwnership.this;}
        public void close() throws Throwable {
            thread();if (closed) return;closed=true;
            Node old=target;target=null;
            if (owner == null) roots.remove(this);else owner.outgoing.remove(this);
            if (old != null) release(old);drain();
        }
    }

    /** Single-use barrier for actual ASTORE/cell/container stores. Abort never rolls back a raw store. */
    public final class Publication implements AutoCloseable {
        private final Slot destination,source;
        private final Node value,old;
        private boolean finished,committed,retiring;
        private Publication(Slot destination,Slot source,Node value) {
            this.destination=destination;this.source=source;this.value=value;old=destination.target;
        }
        private void begin() {
            // Validation/allocation, including possible zero-queue growth, precedes the raw section.
            if (deferred == Integer.MAX_VALUE) throw new IllegalStateException("Ownership defer depth overflow");
            prequeue(old);prequeue(value);retain(value);deferred++;activePublication=this;
        }
        public boolean isCommitted() {return committed;}
        public boolean isClosed() {return finished;}
        public void commit() throws Throwable {
            checkExecutionAccess();
            if (finished || activePublication != this) throw new IllegalStateException("Ownership publication was consumed");
            destination.target=value;if (source != null) source.target=null;
            if (retiring) {
                destination.closed=true;
                if (destination.owner == null) roots.remove(destination);else destination.owner.outgoing.remove(destination);
            }
            if (old != null) release(old);
            if (source != null && value != null) release(value);
            committed=true;finish();
        }
        private void finish() throws Throwable {
            finished=true;activePublication=null;deferred--;
            if (deferred == 0) drain();
        }
        @Override public void close() throws Exception {
            checkExecutionAccess();if (finished) return;
            if (activePublication != this) throw new IllegalStateException("Ownership publication is not active");
            if (value != null) release(value);
            try {finish();}
            catch (Exception error) {throw error;}
            catch (Error error) {throw error;}
            catch (Throwable error) {PhpOwnership.<Exception>throwPublicationFailure(error);}
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwPublicationFailure(Throwable failure) throws T {throw (T)failure;}

    /** A Java holder has a real root edge; close is scheduled on the execution domain's current owner. */
    public final class Pin implements AutoCloseable {
        private final Slot slot;
        private Pin(Slot slot) {this.slot=slot;}
        public Node target() {return slot.target();}
        @Override public void close() throws Exception {
            try {slot.close();}
            catch (Exception error) {throw error;}
            catch (Error error) {throw error;}
            catch (Throwable error) {throw new RuntimeException(error);}
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;
        IdentityKey(Object identity,ReferenceQueue<Object> queue) {super(identity,queue);hash=System.identityHashCode(identity);}
        @Override public int hashCode() {return hash;}
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof IdentityKey && get() != null && get() == ((IdentityKey)other).get();
        }
    }
}
