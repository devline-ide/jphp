package php.runtime.invoke;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.lang.IObject;
import php.runtime.lang.support.IManualDestructable;
import php.runtime.reflection.ClassEntity;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/** One-shot PHP destructor dispatch shared by existing callers and explicit ownership adapters. */
public final class PhpObjectLifecycle {
    public enum Reason { MANUAL_UNSET, ENVIRONMENT_FINALIZE, OWNERSHIP_RELEASE }
    private PhpObjectLifecycle() {}

    public static void beginConstruction(IObject object) {
        if (object instanceof php.runtime.lang.BaseObject) ((php.runtime.lang.BaseObject) object).beginPhpConstruction();
    }
    public static void completeConstruction(IObject object) {
        if (object instanceof php.runtime.lang.BaseObject) ((php.runtime.lang.BaseObject) object).completePhpConstruction();
    }
    public static void failConstruction(IObject object, Throwable failure) {
        try { object.doFinalize(); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }

    /** Generated JVM entries only publish a pending receiver; PHP runs later on a runtime caller. */
    public static void enqueueJvmFinalizer(IObject object) {
        if (object.isFinalized()) return;
        if (object instanceof php.runtime.lang.BaseObject && ((php.runtime.lang.BaseObject) object).isPhpConstructionPending()) return;
        Environment env = object.getEnvironment();
        if (env != null) env.enqueueJvmFinalizer(object);
    }

    /**
     * The caller supplies the releasing environment. The finalized bit is set before any
     * callback, preserving resurrection/reentry behavior. This does not release property guards.
     * OWNERSHIP_RELEASE preserves the raw failure for the domain's explicit composition policy.
     */
    public static boolean destruct(IObject object, Environment releasingEnvironment, Reason reason) throws Throwable {
        if(reason!=Reason.MANUAL_UNSET && object instanceof php.runtime.lang.Generator
                && ((php.runtime.lang.Generator)object).__isBusyInFiber()) {
            try(var admission=releasingEnvironment.enterCallbackDomainForRetirement()) {
                return destruct(object,releasingEnvironment,reason,null);
            }
        }
        return destruct(object,releasingEnvironment,reason,null);
    }
    /** A certified lifecycle receiver is borrowed from FINALIZING, never staged as an owner. */
    public static boolean destruct(php.runtime.PhpOwnershipObjects.ExecutionEntry entry,Environment releasingEnvironment) throws Throwable {
        Objects.requireNonNull(entry);entry.validateEnvironment(releasingEnvironment);
        IObject object=entry.receiver();
        if (entry.method()!=object.getReflection().methodDestruct)
            throw new IllegalArgumentException("Lifecycle entry does not identify the actual destructor");
        return destruct(object,releasingEnvironment,Reason.OWNERSHIP_RELEASE,entry);
    }
    private static boolean destruct(IObject object,Environment releasingEnvironment,Reason reason,
            php.runtime.PhpOwnershipObjects.ExecutionEntry entry) throws Throwable {
        Objects.requireNonNull(reason);
        if (reason == Reason.MANUAL_UNSET && object instanceof php.runtime.lang.Generator
                && ((php.runtime.lang.Generator)object).__isBusyInFiber()) return false;
        if (php.runtime.PhpLazyObjects.skipDestructor(object)) return false;
        ClassEntity entity = object.getReflection();
        if (object instanceof php.runtime.lang.support.IPhpNativeRetirement retirement) {
            // Ordinary unset has no reference-count proof that other PHP aliases are gone.
            if(reason==Reason.MANUAL_UNSET)return false;
            if(entry!=null || reason==Reason.OWNERSHIP_RELEASE)
                throw new IllegalStateException("Native continuation retirement requires its parked ownership attestation");
            if(object.isFinalized() || object instanceof php.runtime.lang.BaseObject
                    && ((php.runtime.lang.BaseObject)object).isPhpConstructionPending())return false;
            if(object.getEnvironment()!=releasingEnvironment || entity.getNativeClass()!=object.getClass() || entity.methodDestruct!=null)
                throw new IllegalArgumentException("Native retirement does not match its actual declaration and Environment");
            retirement.retireNative(releasingEnvironment);object.doFinalize();return true;
        }
        if (entity.methodDestruct == null || object.isFinalized()
                || object instanceof php.runtime.lang.BaseObject && ((php.runtime.lang.BaseObject) object).isPhpConstructionPending()) return false;
        object.doFinalize();
        releasingEnvironment.pushCall(object, entity.methodDestruct.getName());
        try {
            if (reason == Reason.MANUAL_UNSET) {
                try {invoke(object, releasingEnvironment, entity, reason,entry);}
                catch (InvocationTargetException failure) {releasingEnvironment.__throwException(failure);}
                catch (RuntimeException failure) {throw failure;}
                catch (Throwable failure) {throw new CriticalException(failure);}
            } else {
                invoke(object, releasingEnvironment, entity, reason,entry);
            }
        } finally {releasingEnvironment.popCall();}
        return true;
    }
    private static void invoke(IObject object, Environment environment, ClassEntity entity, Reason reason,
            php.runtime.PhpOwnershipObjects.ExecutionEntry entry) throws Throwable {
        if (entry!=null) {entry.dispatch(environment,environment.trace());return;}
        if (reason != Reason.ENVIRONMENT_FINALIZE && object instanceof IManualDestructable)
            ((IManualDestructable)object).onManualDestruct(environment);
        if (reason == Reason.OWNERSHIP_RELEASE) {
            php.runtime.PhpOwnershipExecution.Session session=php.runtime.PhpOwnershipExecution.current(environment);
            if (session != null) {session.invokeDestructor(entity.methodDestruct,object,environment.trace());return;}
        }
        TraceInfo trace = reason == Reason.ENVIRONMENT_FINALIZE ? TraceInfo.UNKNOWN : environment.trace();
        PhpDeprecatedInvocation.beforeFunction(environment, trace, entity.methodDestruct);
        entity.methodDestruct.invokeDynamic(object, environment, trace);
    }
    /** No checked signature change in Memory.manualUnset; pre-dispatch failures retain their exact identity. */
    public static void manualUnset(IObject object, Environment environment) {
        try {destruct(object, environment, Reason.MANUAL_UNSET);}
        catch (Throwable failure) {PhpObjectLifecycle.<RuntimeException>raise(failure);}
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raise(Throwable failure) throws T {throw (T)failure;}
}
