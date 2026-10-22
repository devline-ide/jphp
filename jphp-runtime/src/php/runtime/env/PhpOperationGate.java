package php.runtime.env;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** no Java monitor is held by a returned lease. */
public final class PhpOperationGate {
    public enum Reentry { SAME_LINEAGE, SAME_CARRIER, REJECT }
    private Object owner;
    private int leases;
    // All admission and participant waits use this same monitor/counter.
    private int waiters;
    synchronized void awaitChange() throws InterruptedException {
        waiters++;
        try { wait(); } finally { waiters--; }
    }
    void signalChange() {
        assert Thread.holdsLock(this);
        if (waiters != 0) notifyAll();
    }
    // Explicit suspended reservation metadata; the ticket and strong carrier remain on its caller stack.
    private boolean lifecycleParked;
    private WeakReference<Thread> lifecycleCarrier;
    private int lifecycleDepth;
    private long lifecycleEpoch;
    // Identity comparison avoids arbitrary Thread-subclass hashCode/equals callbacks.
    private static final class Carrier {
        final WeakReference<Thread> thread;
        int depth;
        int includeBodies;
        Carrier(Thread thread) { this.thread = new WeakReference<>(thread); }
    }
    // A live Env/domain must not strongly retain a parked orphan's carrier/Fiber.
    private final List<Carrier> carriers = new ArrayList<>();
    private static final int MAX_IDLE_CARRIERS = 8;
    private Carrier carrier(Thread thread) {
        for (int index = carriers.size() - 1; index >= 0; index--) {
            Carrier carrier = carriers.get(index); Thread actual = carrier.thread.get();
            if (actual == null) carriers.remove(index); else if (actual == thread) return carrier;
        }
        return null;
    }
    private boolean activeCarrier(Thread thread) {
        Carrier carrier = carrier(thread);
        return carrier != null && carrier.depth != 0;
    }
    private void trimIdleCarriers(Carrier preferred) {
        if (carriers.size() <= MAX_IDLE_CARRIERS) return;
        int idle = 1; // Keep the just-released hot carrier when possible.
        for (int index = carriers.size() - 1; index >= 0; index--) {
            Carrier carrier = carriers.get(index);
            if (carrier == preferred) continue;
            if (carrier.thread.get() == null || carrier.depth == 0 && idle++ >= MAX_IDLE_CARRIERS)
                carriers.remove(index);
        }
    }
    private Carrier admit(Thread thread) {
        Carrier carrier = carrier(thread);
        if (carrier == null) { carrier = new Carrier(thread); carriers.add(carrier); }
        carrier.depth++;
        return carrier;
    }

    /** Caller-held lexical output admission. Public Lease identities are never reused. */
    public static final class OutputFrame {
        private final PhpOperationGate gate;
        private long generation;
        private Object lineage;
        private Carrier carrier;
        private boolean active;
        private OutputFrame(PhpOperationGate gate) { this.gate = gate; }
    }
    public OutputFrame newOutputFrame() { return new OutputFrame(this); }
    public long enterOutputFrame(OutputFrame frame, Object lineage, Reentry reentry) {
        if (frame == null || lineage == null || reentry == null) throw new NullPointerException();
        if (frame.gate != this) throw new IllegalArgumentException("Output frame belongs to another operation gate");
        Thread thread = Thread.currentThread();boolean interrupted = false;
        try {
            synchronized (this) {
                if (frame.active || frame.generation == Long.MAX_VALUE)
                    throw new IllegalStateException("Output frame is active or exhausted");
                rejectParkedCarrier(thread);
                while (owner != null) {
                    if (owner == lineage) {
                        if (reentry == Reentry.SAME_LINEAGE
                                || reentry == Reentry.SAME_CARRIER && activeCarrier(thread)) break;
                        throw new OverlapException("Operation overlaps its active execution lineage");
                    }
                    if (activeCarrier(thread))
                        throw new OverlapException("Carrier changed execution lineage inside an active operation");
                    try { awaitChange(); } catch (InterruptedException pending) { interrupted = true; }
                }
                if (frame.active || frame.generation == Long.MAX_VALUE)
                    throw new IllegalStateException("Output frame is active or exhausted");
                owner = lineage;leases++;
                frame.carrier = admit(thread);frame.lineage = lineage;frame.active = true;
                return ++frame.generation;
            }
        } finally { if (interrupted) thread.interrupt(); }
    }
    public void closeOutputFrame(OutputFrame frame, long generation) {
        if (frame == null || frame.gate != this) throw new IllegalArgumentException("Foreign output frame");
        synchronized (this) {
            if (!frame.active || frame.generation != generation)
                throw new IllegalStateException("Stale output frame generation");
            Carrier state = frame.carrier;
            if (state.thread.get() != Thread.currentThread())
                throw new IllegalStateException("Foreign output frame release");
            if (owner != frame.lineage || state.depth == 0 || carrier(Thread.currentThread()) != state)
                throw new IllegalStateException("Output frame is parked or no longer owns admission");
            if (--state.depth == 0) trimIdleCarriers(state);
            if (--leases == 0) { owner = null;signalChange(); }
            frame.active = false;frame.lineage = null;frame.carrier = null;
        }
    }

    public Lease enter(Object lineage, Reentry reentry) {
        if (lineage == null || reentry == null) throw new NullPointerException();
        Thread carrier = Thread.currentThread();
        boolean interrupted = false;
        try {
            synchronized (this) {
                rejectParkedCarrier(carrier);
                while (owner != null) {
                    if (owner == lineage) {
                        if (reentry == Reentry.SAME_LINEAGE
                                || reentry == Reentry.SAME_CARRIER && activeCarrier(carrier)) break;
                        throw new OverlapException("Operation overlaps its active execution lineage");
                    }
                    if (activeCarrier(carrier))
                        throw new OverlapException("Carrier changed execution lineage inside an active operation");
                    try { awaitChange(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                owner = lineage;
                leases++;
                admit(carrier);
                return new Lease(lineage, carrier);
            }
        } finally { if (interrupted) carrier.interrupt(); }
    }

    /** Retirement must not wait for the foreign parked callback it intends to close. */
    public synchronized Lease tryEnter(Object lineage, Reentry reentry) {
        if (lineage == null || reentry == null) throw new NullPointerException();
        Thread carrier = Thread.currentThread();
        rejectParkedCarrier(carrier);
        if (owner != null && owner != lineage) {
            if (activeCarrier(carrier))
                throw new OverlapException("Carrier changed execution lineage inside an active operation");
            return null;
        }
        if (owner == lineage && reentry != Reentry.SAME_LINEAGE
                && !(reentry == Reentry.SAME_CARRIER && activeCarrier(carrier)))
            throw new OverlapException("Operation overlaps its active execution lineage");
        owner = lineage; leases++;
        admit(carrier);
        return new Lease(lineage, carrier);
    }

    private void rejectParkedCarrier(Thread carrier) {
        if (lifecycleParked && lifecycleCarrier.get() == carrier)
            throw new OverlapException("Parked lifecycle carrier cannot enter PHP admission");
    }

    /** Only a quiescent include-body stack may lend admission to an independent host lifecycle. */
    public synchronized HostLifecyclePark parkForHostLifecycle(Object lineage) {
        if (lineage == null) throw new NullPointerException("lineage");
        Thread thread = Thread.currentThread();
        if (lifecycleParked)
            throw new OverlapException("Another carrier already parked host lifecycle admission");
        Carrier state = carrier(thread);
        if (owner != null && (owner != lineage || state == null || state.depth != leases
                || state.includeBodies != leases))
            throw new OverlapException("Host lifecycle requires only this carrier's include-body admission");
        if (owner == null && (leases != 0 || state != null && state.depth != 0))
            throw new IllegalStateException("Idle callback admission has inconsistent lease accounting");
        int depth = leases;
        long epoch = ++lifecycleEpoch;
        HostLifecyclePark ticket = new HostLifecyclePark(lineage, thread, depth, epoch);
        lifecycleCarrier = new WeakReference<>(thread);
        lifecycleDepth = depth;
        lifecycleParked = true;
        if (state != null) carriers.remove(state);
        leases = 0;
        owner = null;
        signalChange();
        return ticket;
    }

    public final class HostLifecyclePark implements AutoCloseable {
        private final Object lineage;
        private final Thread carrier;
        private final int depth;
        private final long epoch;
        private boolean closed;
        private HostLifecyclePark(Object lineage, Thread carrier, int depth, long epoch) {
            this.lineage = lineage; this.carrier = carrier; this.depth = depth; this.epoch = epoch;
        }
        /** Restore once, on the original stack; interruption never resumes PHP before restoration. */
        @Override public void close() throws InterruptedException {
            InterruptedException interrupted = null;
            synchronized (PhpOperationGate.this) {
                if (closed) return;
                if (Thread.currentThread() != carrier || !lifecycleParked || lifecycleEpoch != epoch
                        || lifecycleCarrier.get() != carrier || lifecycleDepth != depth)
                    throw new IllegalStateException("Stale or foreign host lifecycle park restoration");
                while (owner != null) {
                    try { PhpOperationGate.this.awaitChange(); }
                    catch (InterruptedException pending) {
                        if (interrupted == null) interrupted = pending;
                        else if (interrupted != pending) interrupted.addSuppressed(pending);
                    }
                }
                if (depth != 0) {
                    Carrier restored = new Carrier(carrier);
                    restored.depth = depth; restored.includeBodies = depth;
                    carriers.add(restored);
                    owner = lineage; leases = depth;
                }
                lifecycleParked = false; lifecycleCarrier = null; lifecycleDepth = 0;
                closed = true;
                PhpOperationGate.this.signalChange();
            }
            if (interrupted != null) { carrier.interrupt(); throw interrupted; }
        }
    }

    public synchronized boolean isOwnedBy(Object lineage) { return owner == lineage; }

    /** A live same-carrier lease already protects nested internal work; idle cache entries do not. */
    public synchronized boolean isOwnedByCarrier(Object lineage) {
        return owner == lineage && activeCarrier(Thread.currentThread());
    }

    public static final class OverlapException extends IllegalStateException {
        private OverlapException(String message) { super(message); }
    }

    public final class Lease implements AutoCloseable {
        private final Object lineage;
        private final Thread carrier;
        private boolean includeBody, closed;
        private Lease(Object lineage, Thread carrier) { this.lineage = lineage; this.carrier = carrier; }
        /** Downgrade the same retained transaction only after fetch/publication has completed. */
        public void includeBody() {
            synchronized (PhpOperationGate.this) {
                Carrier state = carrier(carrier);
                if (closed || Thread.currentThread() != carrier || owner != lineage || state == null || state.depth == 0)
                    throw new IllegalStateException("Include-body admission lacks its active carrier lease");
                if (!includeBody) { includeBody = true; state.includeBodies++; }
            }
        }
        @Override public void close() {
            synchronized (PhpOperationGate.this) {
                if (closed) return;
                if (carrier != Thread.currentThread()) throw new IllegalStateException("Foreign operation lease release");
                Carrier state = carrier(carrier);
                if (owner != lineage || state == null || state.depth == 0
                        || includeBody && state.includeBodies == 0)
                    throw new IllegalStateException("Operation lease is parked or no longer owns admission");
                if (includeBody) state.includeBodies--;
                if (--state.depth == 0) trimIdleCarriers(state);
                closed = true;
                if (--leases == 0) { owner = null; PhpOperationGate.this.signalChange(); }
            }
        }
    }
}
