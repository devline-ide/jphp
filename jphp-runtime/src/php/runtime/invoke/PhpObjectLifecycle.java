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

    /**
     * The caller supplies the releasing environment. The finalized bit is set before any
     * callback, preserving resurrection/reentry behavior. This does not release property guards.
     * OWNERSHIP_RELEASE preserves the raw failure for the domain's explicit composition policy.
     */
    public static boolean destruct(IObject object, Environment releasingEnvironment, Reason reason) throws Throwable {
        Objects.requireNonNull(reason);
        ClassEntity entity = object.getReflection();
        if (entity.methodDestruct == null || object.isFinalized()) return false;
        object.doFinalize();
        releasingEnvironment.pushCall(object, entity.methodDestruct.getName());
        try {
            if (reason == Reason.MANUAL_UNSET) {
                try {invoke(object, releasingEnvironment, entity, reason);}
                catch (InvocationTargetException failure) {releasingEnvironment.__throwException(failure);}
                catch (RuntimeException failure) {throw failure;}
                catch (Throwable failure) {throw new CriticalException(failure);}
            } else {
                invoke(object, releasingEnvironment, entity, reason);
            }
        } finally {releasingEnvironment.popCall();}
        return true;
    }
    private static void invoke(IObject object, Environment environment, ClassEntity entity, Reason reason) throws Throwable {
        if (reason != Reason.ENVIRONMENT_FINALIZE && object instanceof IManualDestructable)
            ((IManualDestructable)object).onManualDestruct(environment);
        entity.methodDestruct.invokeDynamic(object, environment,
                reason == Reason.ENVIRONMENT_FINALIZE ? TraceInfo.UNKNOWN : environment.trace());
    }
    /** No checked signature change in Memory.manualUnset; pre-dispatch failures retain their exact identity. */
    public static void manualUnset(IObject object, Environment environment) {
        try {destruct(object, environment, Reason.MANUAL_UNSET);}
        catch (Throwable failure) {PhpObjectLifecycle.<RuntimeException>raise(failure);}
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raise(Throwable failure) throws T {throw (T)failure;}
}
