package php.runtime;

import java.util.*;

/**
 * Dormant bridge between interpreter frame slots and an explicit ownership graph.
 * Callers supply registered nodes and their raw values; no Memory/storage identity
 * is inferred here. Compiler, Environment and container integration is separate.
 */
public final class PhpOwnershipFrame {
    public enum Role { LOCAL, EVAL_TEMP, RECEIVER, ARGUMENT }

    private final PhpOwnership graph;
    private Publication pendingPublication;
    private final EnumMap<Role,Map<String,Slot>> named=new EnumMap<>(Role.class);
    private final List<Slot> created=new ArrayList<>();
    private boolean closed;

    public PhpOwnershipFrame(PhpOwnership graph) {
        this.graph=Objects.requireNonNull(graph);
        graph.liveNodes(); // Reject construction outside the current execution domain.
    }

    public PhpOwnership graph() {return graph;}
    public boolean isClosed() {return closed;}
    public Slot local(String name) {return slot(Role.LOCAL,name);}
    public Slot temporary(String name) {return slot(Role.EVAL_TEMP,name);}
    public Slot receiver(String name) {return slot(Role.RECEIVER,name);}
    public Slot argument(String name) {return slot(Role.ARGUMENT,name);}

    /** A role/name identifies one stable root slot for the lifetime of this frame. */
    public Slot slot(Role role,String name) {
        writable();Objects.requireNonNull(role);Objects.requireNonNull(name);
        Map<String,Slot> slots=named.computeIfAbsent(role,key->new LinkedHashMap<>());
        Slot existing=slots.get(name);
        if (existing != null) return existing;
        Slot slot=new Slot(role,name,graph.root());slots.put(name,slot);created.add(slot);return slot;
    }

    /** Release every root in reverse creation order, including after a destructor fails. */
    public void close() throws Throwable {finish(null);}

    /** Compose cleanup failures in unwind order using the graph adapter policy. */
    public void unwind(Throwable primary) throws Throwable {finish(Objects.requireNonNull(primary));}

    private void finish(Throwable primary) throws Throwable {
        thread();Throwable failure=primary;
        if (!closed) {
            if (pendingPublication == null) graph.checkMutationAccess();
            closed=true;
            if (pendingPublication != null) {
                try {pendingPublication.close();} catch (Throwable error) {failure=merge(failure,error);}
            }
            for (int i=created.size()-1;i>=0;i--) {
                try {created.get(i).close();} catch (Throwable error) {failure=merge(failure,error);}
            }
        }
        if (failure != null) throw failure;
    }

    private void writable() {
        graph.checkMutationAccess();if (closed) throw new IllegalStateException("Ownership frame is closed");
    }
    private void thread() {
        graph.checkExecutionAccess();
    }
    private void valid(PhpOwnership.Node node) {
        if (node == null) return;
        if (node.graph() != graph) throw new IllegalArgumentException("Ownership node belongs to another graph");
        if (node.state() == PhpOwnership.State.DISPOSED) throw new IllegalStateException("Ownership node was disposed");
    }
    private Throwable merge(Throwable first,Throwable next) {return graph.composeFailure(first,next);}

    private void publish(Publication publication,PhpOwnership.Work rawPublication) throws Throwable {
        Throwable failure=null;
        try {rawPublication.run();publication.commit();} catch (Throwable error) {failure=error;}
        try {publication.close();} catch (Throwable cleanup) {failure=merge(failure,cleanup);}
        if (failure != null) throw failure;
    }

    /** A pending raw publication is tracked by its source frame and aborted first during unwind. */
    public final class Publication implements AutoCloseable {
        private final Slot source,destination;
        private final Object value;
        private PhpOwnership.Publication ownership;
        private boolean finished,committed;
        private Publication(Slot source,Slot destination,Object value) {this.source=source;this.destination=destination;this.value=value;}
        public boolean isCommitted() {return committed;}
        public boolean isClosed() {return finished;}
        public void commit() throws Throwable {
            thread();if (finished) throw new IllegalStateException("Ownership frame publication was consumed");
            if (destination != null) destination.rawValue=value;
            if (source != null && source != destination) source.rawValue=null;
            finished=true;committed=true;pendingPublication=null;
            ownership.commit(); // Its barrier/state is cleared before deferred PHP callbacks run.
        }
        @Override public void close() throws Exception {
            thread();if (finished) return;finished=true;pendingPublication=null;ownership.close();
        }
    }

    public final class Slot {
        private final Role role;
        private final String name;
        private final PhpOwnership.Slot root;
        private Object rawValue;
        private PhpOwnershipFramePublisher ownershipPublisher;
        private boolean closed;

        private Slot(Role role,String name,PhpOwnership.Slot root) {this.role=role;this.name=name;this.root=root;}
        public PhpOwnershipFrame frame() {return PhpOwnershipFrame.this;}
        public Role role() {return role;}
        public String name() {return name;}
        public Object rawValue() {return rawValue;}
        public PhpOwnership.Node node() {return root.target();}
        public boolean isClosed() {return closed;}
        PhpOwnershipFramePublisher ownershipPublisher() {return ownershipPublisher;}
        void attachOwnershipPublisher(PhpOwnershipFramePublisher attaching) {
            writable();
            if (ownershipPublisher != null)
                throw new IllegalStateException("Ownership frame slot already has a publisher");
            ownershipPublisher=Objects.requireNonNull(attaching);
        }

        private void writable() {
            PhpOwnershipFrame.this.writable();
            if (closed) throw new IllegalStateException("Ownership frame slot is closed");
        }

        /** Prepare before the actual ASTORE/cell store; scalar values have a null node. */
        public Publication prepareCommit(PhpOwnership.Node node,Object actualPublishedRaw) {
            writable();valid(node);
            Publication publication=new Publication(null,this,actualPublishedRaw);
            publication.ownership=root.preparePublication(node);pendingPublication=publication;return publication;
        }
        /** Transfer may publish a different immutable Java view of the same owned PHP value. */
        public Publication prepareTransferTo(Slot destination,Object actualPublishedRaw) {
            writable();Objects.requireNonNull(destination).writable();
            if (destination.frame().graph != graph) throw new IllegalArgumentException("Ownership frames belong to different graphs");
            Publication publication=new Publication(this,destination,actualPublishedRaw);
            publication.ownership=root.prepareTransferTo(destination.root);pendingPublication=publication;return publication;
        }
        /** Exact adapter bridge; only a certified physical publisher supplies the destination edge. */
        Publication prepareTransferToEdge(PhpOwnership.Slot destination) {
            writable();Objects.requireNonNull(destination);
            if (destination.owner() == null || destination.owner().graph() != graph)
                throw new IllegalArgumentException("Ownership destination is not this graph's physical edge");
            Publication publication=new Publication(this,null,null);
            publication.ownership=root.prepareTransferTo(destination);pendingPublication=publication;return publication;
        }
        Publication prepareTransferFromEdge(PhpOwnership.Slot source,Object raw) {
            writable();Objects.requireNonNull(source);
            if (source.owner() == null || source.owner().graph() != graph || rawValue != null || root.target() != null)
                throw new IllegalArgumentException("Cleanup destination or physical source is invalid");
            Publication publication=new Publication(null,this,raw);
            publication.ownership=source.prepareTransferTo(root);pendingPublication=publication;return publication;
        }
        /** Internal mirror only; live adapters use a raw callback or the explicit prepare/store/commit API. */
        public void commit(PhpOwnership.Node node,Object value) throws Throwable {commit(node,value,()->{});}
        public void commit(PhpOwnership.Node node,Object value,PhpOwnership.Work rawPublication) throws Throwable {
            Objects.requireNonNull(rawPublication);publish(prepareCommit(node,value),rawPublication);
        }
        /** Validation's own side effects keep normal timing; protection/publication starts afterward. */
        public void commitChecked(PhpOwnership.Node node,Object value,PhpOwnership.Work validation) throws Throwable {
            commitChecked(node,value,validation,()->{});
        }
        public void commitChecked(PhpOwnership.Node node,Object value,PhpOwnership.Work validation,PhpOwnership.Work rawPublication) throws Throwable {
            writable();valid(node);Objects.requireNonNull(validation).run();commit(node,value,rawPublication);
        }
        /** Transfer into the caller before closing the callee, after any pending finally has completed. */
        public void transferTo(Slot destination) throws Throwable {
            if (destination == this) {writable();return;}
            transferTo(destination,rawValue,()->{});
        }
        public void transferTo(Slot destination,Object actualPublishedRaw,PhpOwnership.Work rawPublication) throws Throwable {
            Objects.requireNonNull(rawPublication);publish(prepareTransferTo(destination,actualPublishedRaw),rawPublication);
        }

        /** Clearing raw storage precedes callbacks caused by releasing its owner edge. */
        public void close() throws Throwable {
            graph.checkMutationAccess();if (closed) return;closed=true;rawValue=null;root.close();
        }
    }
}
