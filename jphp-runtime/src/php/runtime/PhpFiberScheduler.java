package php.runtime;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import php.runtime.env.Environment;
import php.runtime.lang.PhpFiber;

/** Lazily created per-Env Fiber metadata. No global or strong suspended-owner registry. */
public final class PhpFiberScheduler {
    public static final class Registration {
        private final WeakReference<PhpFiber> owner;
        private boolean retired;
        private Registration(PhpFiber owner) {this.owner=new WeakReference<>(owner);}
    }
    private final Environment environment;
    private final List<Registration> registrations=new ArrayList<>();
    private Thread active;
    private boolean closing;
    private Thread retirementCaller;
    public PhpFiberScheduler(Environment environment) {this.environment=environment;}
    public synchronized Registration register(PhpFiber owner) {
        if(closing)throw new IllegalStateException("Fiber scheduler is retiring");
        if(owner.getEnvironment()!=environment)throw new IllegalArgumentException("Foreign Fiber Environment");
        var r=new Registration(owner);registrations.add(r);return r;
    }
    public final class CallerLease implements AutoCloseable {
        private final Thread caller,previous;private boolean closed;
        private CallerLease(Thread caller,Thread previous) {this.caller=caller;this.previous=previous;}
        @Override public void close() {
            synchronized(PhpFiberScheduler.this) {
                if(closed)return;
                if(Thread.currentThread()!=caller || active!=caller)
                    throw new IllegalStateException("Fiber caller lease cannot close before exact segment restoration");
                active=previous;closed=true;
            }
        }
    }
    public synchronized CallerLease enterCaller() {
        Thread caller=Thread.currentThread();
        if(closing && caller!=retirementCaller)throw new IllegalStateException("Fiber scheduler is retiring");
        if(active!=null && active!=caller)throw new IllegalStateException("Environment already has another active Fiber segment");
        var lease=new CallerLease(caller,active);active=caller;return lease;
    }
    public synchronized boolean isActiveThread(Thread thread) {return active==thread;}
    public synchronized void switchThread(Thread expected,Thread next) {
        if(active!=expected)throw new IllegalStateException("Stale Fiber segment handoff");
        active=next;
    }
    public synchronized void retire(Registration registration) {
        if(!registrations.contains(registration))throw new IllegalArgumentException("Foreign Fiber retirement registration");
        registration.retired=true;registrations.remove(registration);notifyAll();
    }
    /** Before Environment's irreversible finalization bit. Missing finalizer enqueue is retryable. */
    public void prepareEnvironmentRetirement() throws Throwable {
        if(PhpFiber.current(environment)!=null)throw new IllegalStateException("Environment retirement cannot run inside a Fiber");
        List<Registration> snapshot;
        synchronized(this) {
            if(active!=null && active!=Thread.currentThread())
                throw new IllegalStateException("Environment retirement cannot race an active Fiber carrier");
            if(closing)throw new IllegalStateException("Fiber scheduler already has an active retirement owner");
            closing=true;retirementCaller=Thread.currentThread();snapshot=new ArrayList<>(registrations);
        }
        boolean complete=false;
        try {
            for(var registration:snapshot) {
                PhpFiber owner=registration.owner.get();
                if(owner!=null)php.runtime.invoke.PhpObjectLifecycle.destruct(owner,environment,
                        php.runtime.invoke.PhpObjectLifecycle.Reason.ENVIRONMENT_FINALIZE);
            }
            environment.runPendingFinalizers();
            synchronized(this) {
                registrations.removeIf(r->r.retired);
                if(!registrations.isEmpty())
                    throw new IllegalStateException("Fiber retirement awaits its queued JVM finalizer; retry Environment close before finalization");
                active=null;retirementCaller=null;complete=true;
            }
        } finally {
            if(!complete)synchronized(this) {closing=false;retirementCaller=null;}
        }
    }
}
