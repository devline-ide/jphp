package php.runtime;

import java.util.Objects;

/**
 * One publisher for one concrete semantic frame slot, interned by its domain heap.
 * The caller performs the actual validated raw store between prepare and commit.
 * This bridge does not infer reference/address semantics or activate compiler roots.
 */
public final class PhpOwnershipFramePublisher implements PhpOwnershipHeap.Publisher {
    private final PhpOwnershipHeap heap;
    private final PhpOwnershipFrame.Slot slot;
    private PhpOwnershipHeap.Snapshot snapshot;

    PhpOwnershipFramePublisher(PhpOwnershipHeap heap, PhpOwnershipFrame.Slot slot) {
        this.heap=Objects.requireNonNull(heap);this.slot=Objects.requireNonNull(slot);
        if (slot.frame().graph() != heap.domain().graph())
            throw new IllegalArgumentException("Ownership frame belongs to another domain");
        writable();
        if (slot.rawValue() != null || slot.node() != null)
            throw new IllegalArgumentException("Ownership frame publisher requires an empty slot");
        snapshot=new PhpOwnershipHeap.Snapshot(this,null,0);
        slot.attachOwnershipPublisher(this);
    }

    @Override public PhpOwnershipDomain domain() {return heap.domain();}
    public PhpOwnershipFrame.Slot slot() {return slot;}

    @Override public PhpOwnershipHeap.Snapshot current() {
        writable();consistent();return snapshot;
    }

    private void writable() {
        domain().currentContext();
        if (slot.isClosed() || slot.frame().isClosed())
            throw new IllegalStateException("Ownership frame publisher is closed");
    }

    private void consistent() {
        var binding=snapshot.binding();
        Object raw=binding == null ? null : binding.rawValue();
        var node=binding == null ? null : binding.node();
        if (slot.rawValue() != raw || slot.node() != node)
            throw new IllegalStateException("Ownership frame slot changed outside its publisher");
    }

    private void expected(PhpOwnershipHeap.Snapshot expected) {
        writable();
        if (expected == null || expected.publisher() != this || expected != snapshot)
            throw new IllegalArgumentException("Ownership frame snapshot is stale or belongs to another publisher");
        consistent();
    }

    private long nextRevision() {
        if (snapshot.revision() == Long.MAX_VALUE)
            throw new IllegalStateException("Ownership frame publisher revision overflow");
        return snapshot.revision()+1;
    }

    @Override public PhpOwnershipHeap.Publication prepare(PhpOwnershipHeap.Snapshot expected,
                                                         PhpOwnershipValues.Binding next) {
        expected(expected);Objects.requireNonNull(next);
        if (next.domain() != domain())
            throw new IllegalArgumentException("Ownership value belongs to another domain");
        // Every allocation, accessor and validation precedes the raw publication barrier.
        var raw=next.rawValue();var node=next.node();
        var nextSnapshot=new PhpOwnershipHeap.Snapshot(this,next,nextRevision());
        var publication=new Publication(this,null,snapshot,null,nextSnapshot,null);
        publication.delegate=slot.prepareCommit(node,raw);
        return publication;
    }

    /** Move one root, optionally publishing a distinct Java view of the same PHP value. */
    public PhpOwnershipHeap.Publication prepareTransferTo(PhpOwnershipHeap.Snapshot expectedSource,
            PhpOwnershipFramePublisher destination, PhpOwnershipHeap.Snapshot expectedDestination,
            Memory actualPublishedRawView) {
        expected(expectedSource);Objects.requireNonNull(destination);
        if (destination.domain() != domain())
            throw new IllegalArgumentException("Ownership destination belongs to another domain");
        destination.expected(expectedDestination);
        var binding=snapshot.binding();
        if (binding == null) throw new IllegalStateException("Ownership transfer source is empty");
        var next=binding.view(Objects.requireNonNull(actualPublishedRawView));
        var raw=next.rawValue();
        var nextDestination=new PhpOwnershipHeap.Snapshot(destination,next,destination.nextRevision());
        var nextSource=destination == this ? null : new PhpOwnershipHeap.Snapshot(this,null,nextRevision());
        var publication=new Publication(destination,destination == this ? null : this,
                destination.snapshot,destination == this ? null : snapshot,nextDestination,nextSource);
        publication.delegate=slot.prepareTransferTo(destination.slot,raw);
        return publication;
    }

    /** Package-private custody transfer: the destination adapter owns the actual raw store token. */
    PhpOwnershipFrame.Publication prepareTransferToEdge(PhpOwnershipHeap.Snapshot expected,PhpOwnership.Slot edge) {
        expected(expected);if (snapshot.binding() == null) throw new IllegalStateException("Ownership transfer source is empty");
        return slot.prepareTransferToEdge(edge);
    }
    PhpOwnershipFrame.Publication prepareTransferFromEdge(PhpOwnershipHeap.Snapshot expected,PhpOwnership.Slot edge,Memory raw) {
        expected(expected);if (snapshot.binding() != null) throw new IllegalArgumentException("Cleanup destination is not empty");
        return slot.prepareTransferFromEdge(edge,raw);
    }
    void publishTransferredSnapshot(PhpOwnershipHeap.Snapshot next) {snapshot=next;}
    private static final class Publication implements PhpOwnershipHeap.Publication {
        private final PhpOwnershipFramePublisher destination,source;
        private final PhpOwnershipHeap.Snapshot oldDestination,oldSource,nextDestination,nextSource;
        private PhpOwnershipFrame.Publication delegate;
        private boolean finished;

        private Publication(PhpOwnershipFramePublisher destination,PhpOwnershipFramePublisher source,
                PhpOwnershipHeap.Snapshot oldDestination,PhpOwnershipHeap.Snapshot oldSource,
                PhpOwnershipHeap.Snapshot nextDestination,PhpOwnershipHeap.Snapshot nextSource) {
            this.destination=destination;this.source=source;
            this.oldDestination=oldDestination;this.oldSource=oldSource;
            this.nextDestination=nextDestination;this.nextSource=nextSource;
        }

        @Override public boolean isCommitted() {return delegate.isCommitted();}

        @Override public void commit() throws Throwable {
            if (finished || delegate.isClosed())
                throw new IllegalStateException("Ownership frame publication was consumed");
            // No accessors/callbacks/allocation between the actual raw store and this delegate commit.
            destination.snapshot=nextDestination;
            if (source != null) source.snapshot=nextSource;
            finished=true;
            try {delegate.commit();}
            finally {
                // A destructor failure is already committed; never overwrite a reentrant publication.
                if (!delegate.isCommitted()) {
                    destination.snapshot=oldDestination;
                    if (source != null) source.snapshot=oldSource;
                }
            }
        }

        @Override public void close() throws Exception {
            if (finished) return;
            try {delegate.close();} finally {finished=true;}
        }
    }
}
