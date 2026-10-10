package php.runtime;

import java.util.Objects;

/**
 * Address authority for explicitly certified Memory holders. A snapshot belongs
 * to one actual publisher and revision; a borrowed value is not an address.
 * This bridge does not enable implicit compiler/container ownership accounting.
 */
public final class PhpOwnershipHeap {
    public interface Publisher {
        PhpOwnershipDomain domain();
        Snapshot current();
        Publication prepare(Snapshot expected, PhpOwnershipValues.Binding next);
    }

    /** Prepared before the no-callback raw-store/commit interval. */
    public static final class Snapshot {
        private final Publisher publisher;
        private final PhpOwnershipValues.Binding binding;
        private final long revision;
        Snapshot(Publisher publisher, PhpOwnershipValues.Binding binding, long revision) {
            this.publisher=Objects.requireNonNull(publisher);
            if (revision < 0) throw new IllegalArgumentException("Negative ownership address revision");
            this.binding=binding;this.revision=revision;
        }
        public Publisher publisher() {return publisher;}
        /** Null represents an empty holder, not PHP null. */
        public PhpOwnershipValues.Binding binding() {return binding;}
        public long revision() {return revision;}
    }

    public interface Publication extends AutoCloseable {
        void commit() throws Throwable;
        @Override void close() throws Exception;
        boolean isCommitted();
    }

    private final PhpOwnershipDomain domain;
    PhpOwnershipHeap(PhpOwnershipDomain domain) {
        this.domain=Objects.requireNonNull(domain);domain.attachHeap(this);
    }
    public PhpOwnershipDomain domain() {return domain;}

    /**
     * Intern on the slot itself so closed call frames are not retained by a
     * domain-wide registry. A second adapter cannot give the same slot another
     * revision history. Existing raw slots require explicit certified import.
     */
    public PhpOwnershipFramePublisher framePublisher(PhpOwnershipFrame.Slot slot) {
        domain.currentContext();Objects.requireNonNull(slot);
        if (slot.frame().graph() != domain.graph())
            throw new IllegalArgumentException("Ownership frame belongs to another domain");
        if (slot.isClosed() || slot.frame().isClosed())
            throw new IllegalStateException("Ownership frame slot is closed");
        PhpOwnershipFramePublisher publisher=slot.ownershipPublisher();
        if (publisher == null) publisher=new PhpOwnershipFramePublisher(this,slot);
        return publisher;
    }
}
