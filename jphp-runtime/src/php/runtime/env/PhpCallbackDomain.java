package php.runtime.env;

/** one instance per RuntimeClassLoader, never a static domain registry. */
public final class PhpCallbackDomain {
    private final PhpOperationGate admission = new PhpOperationGate();
    // Weak identity metadata only. Counts change atomically with actual admission, never while waiting.
    private static final class Participant {
        final java.lang.ref.WeakReference<Environment> environment;
        final boolean untagged;
        PhpExecutionLineage.Token lineage;
        boolean finalization;
        int depth;
        Participant(Environment environment) {this.environment=new java.lang.ref.WeakReference<>(environment);untagged=environment==null;}
    }
    private final java.util.List<Participant> participants=new java.util.ArrayList<>();
    private Participant participant(Environment environment,PhpExecutionLineage.Token token,boolean finalization) {
        for(Participant value:participants)if(value.untagged==(environment==null)
                &&(value.untagged||value.environment.get()==environment)
                &&(value.depth==0||value.lineage==token&&value.finalization==finalization)) {
            if(value.depth==0){value.lineage=token;value.finalization=finalization;}return value;
        }
        Participant value=new Participant(environment);value.lineage=token;value.finalization=finalization;participants.add(value);return value;
    }
    private void retireParticipant(Participant value) {
        if(--value.depth!=0)return;
        value.lineage=null;
        int idle=0;
        for(int index=participants.size()-1;index>=0;index--) {
            Participant cached=participants.get(index);
            if(cached.depth==0&&(cached.environment.get()==null&&!cached.untagged||idle++>=8))participants.remove(index);
        }
        admission.signalChange();
    }
    private boolean finalizing(Environment environment,PhpExecutionLineage.Token token) {
        for(Participant value:participants)if(value.depth!=0&&value.lineage==token&&value.finalization
                &&value.environment.get()==environment)return true;
        return false;
    }
    private void rejectRetirementConflict(Environment environment,PhpExecutionLineage.Token token) {
        for(Participant value:participants)if(value.depth!=0&&value.lineage!=token) {
            Environment actual=value.environment.get();
            if(value.untagged||actual==null||actual==environment&&!finalizing(environment,value.lineage))
                throw new IllegalStateException("Foreign-family callback admission prevents Environment retirement");
        }
    }

    public PhpExecutionLineage.Token handoffLineage(PhpExecutionLineage.Token previous) {
        PhpExecutionLineage.Token caller = PhpExecutionLineage.current();
        caller.checkDomain(this);
        // A retained parked callback keeps its real reservation. Otherwise borrow the caller's admission.
        return previous != null && admission.isOwnedBy(previous) ? previous : caller;
    }
    public void validateEntry() { PhpExecutionLineage.current().checkDomain(this); }
    public PhpOperationGate.HostLifecyclePark parkForHostLifecycle() {
        PhpExecutionLineage.Token token = PhpExecutionLineage.current();
        synchronized (token) {
            token.checkDomain(this);
            if (!PhpExecutionLineage.fiberAllowed())
                throw new IllegalStateException("Host lifecycle cannot park a non-suspending PHP callback");
            return admission.parkForHostLifecycle(token);
        }
    }
    public Lease enter() { return enter(null,false,false); }
    public Lease enterForRetirement() { return enter(null,true,false); }
    Lease enter(Environment environment) { return enter(environment,false,false); }
    Lease enterForRetirement(Environment environment) { return enter(environment,true,false); }
    Lease enterForEnvironmentRetirement(Environment environment) { if(environment==null)throw new NullPointerException("environment");return enter(environment,true,true); }
    private Lease enter(Environment environment,boolean retirement,boolean waitForOtherEnvironment) {
        if(environment!=null&&environment.scope.getClassLoader().getCallbackDomain()!=this)
            throw new IllegalArgumentException("Callback participant belongs to another runtime domain");
        PhpExecutionLineage.Token token = PhpExecutionLineage.current();
        token.claim(this);boolean interrupted=false;
        try {
            synchronized(admission) {
                if(waitForOtherEnvironment)rejectRetirementConflict(environment,token);
                PhpOperationGate.Lease lease = retirement
                        ? admission.tryEnter(token, PhpOperationGate.Reentry.SAME_LINEAGE)
                        : admission.enter(token, PhpOperationGate.Reentry.SAME_LINEAGE);
                while(lease==null) {
                    if(!waitForOtherEnvironment)
                        throw new IllegalStateException("Foreign-family callback admission prevents Environment retirement");
                    try{admission.awaitChange();}catch(InterruptedException pending){interrupted=true;}
                    rejectRetirementConflict(environment,token);
                    lease=admission.tryEnter(token,PhpOperationGate.Reentry.SAME_LINEAGE);
                }
                Participant participant=null;boolean registered=false;
                try {
                    participant=participant(environment,token,waitForOtherEnvironment);participant.depth++;registered=true;
                    Lease result=new Lease(token,lease,participant);admission.signalChange();return result;
                } catch(RuntimeException|Error failure) {
                    try{lease.close();if(registered)retireParticipant(participant);}
                    catch(RuntimeException|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
                }
            }
        } catch (RuntimeException | Error failure) { token.release(this); throw failure; }
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }

    /** Reusable only by its lexical holder, with a fresh primitive generation per entry. */
    public static final class OutputFrame {
        private final PhpCallbackDomain domain;
        private final PhpOperationGate.OutputFrame admissionFrame;
        private long generation, admissionGeneration;
        private PhpExecutionLineage.Token token;
        private Participant participant;
        private boolean active;
        private OutputFrame(PhpCallbackDomain domain) {
            this.domain = domain;admissionFrame = domain.admission.newOutputFrame();
        }
    }
    public OutputFrame newOutputFrame() { return new OutputFrame(this); }
    public long enterOutputFrame(OutputFrame frame, Environment environment) {
        if (frame == null || environment == null) throw new NullPointerException();
        if (frame.domain != this) throw new IllegalArgumentException("Output frame belongs to another runtime domain");
        if (environment.scope.getClassLoader().getCallbackDomain() != this)
            throw new IllegalArgumentException("Callback participant belongs to another runtime domain");
        PhpExecutionLineage.Token token = PhpExecutionLineage.current();token.claim(this);
        try {
            synchronized (admission) {
                if (frame.active || frame.generation == Long.MAX_VALUE)
                    throw new IllegalStateException("Output frame is active or exhausted");
                long admitted = admission.enterOutputFrame(frame.admissionFrame, token, PhpOperationGate.Reentry.SAME_LINEAGE);
                Participant participant = null;boolean registered = false;
                try {
                    participant = participant(environment, token, false);participant.depth++;registered = true;
                    frame.token = token;frame.participant = participant;frame.admissionGeneration = admitted;frame.active = true;
                    admission.signalChange();return ++frame.generation;
                } catch (RuntimeException | Error failure) {
                    try { admission.closeOutputFrame(frame.admissionFrame, admitted);if (registered) retireParticipant(participant); }
                    catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        } catch (RuntimeException | Error failure) { token.release(this);throw failure; }
    }
    public void closeOutputFrame(OutputFrame frame, long generation) {
        if (frame == null || frame.domain != this) throw new IllegalArgumentException("Foreign output frame");
        PhpExecutionLineage.Token token;
        synchronized (admission) {
            if (!frame.active || frame.generation != generation)
                throw new IllegalStateException("Stale output frame generation");
            Participant participant = frame.participant;token = frame.token;
            if (participant.depth == 0 || participant.lineage != token)
                throw new IllegalStateException("Stale callback participant release");
            admission.closeOutputFrame(frame.admissionFrame, frame.admissionGeneration);
            retireParticipant(participant);frame.active = false;frame.token = null;frame.participant = null;frame.admissionGeneration = 0;
        }
        token.release(this);
    }

    public final class Lease implements AutoCloseable {
        private final PhpExecutionLineage.Token token;
        private final PhpOperationGate.Lease lease;
        private final Participant participant;
        private boolean closed;
        private Lease(PhpExecutionLineage.Token token, PhpOperationGate.Lease lease,Participant participant) { this.token = token; this.lease = lease;this.participant=participant; }
        public void includeBody() { lease.includeBody(); }
        @Override public void close() {
            synchronized(admission) {
                if(closed)return;
                if(participant.depth==0||participant.lineage!=token)throw new IllegalStateException("Stale callback participant release");
                lease.close();retireParticipant(participant);closed=true;
            }
            token.release(PhpCallbackDomain.this);
        }
    }
}
