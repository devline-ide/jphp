package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.common.PhpOptimizationProfile;

/** The executing PHP module, restored at every return/exception and across nested environments. */
public final class PhpPropertyWriteContext {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private PhpPropertyWriteContext() {}
    public static final class Frame {
        public final Environment environment;
        public final TraceInfo trace;
        public final boolean strict;
        public final PhpOptimizationProfile optimizationProfile;
        private final Frame previous;
        public Frame previousFrame() { return previous; }
        private Frame(Environment environment, TraceInfo trace, boolean strict, PhpOptimizationProfile optimizationProfile, Frame previous) {
            this.environment=environment; this.trace=trace; this.strict=strict; this.optimizationProfile=optimizationProfile; this.previous=previous;
        }
    }
    public static Frame enter(Environment env, TraceInfo trace, boolean strict) {
        return enter(env, trace, strict, PhpOptimizationProfile.COMPATIBLE.getId());
    }
    public static Frame enter(Environment env, TraceInfo trace, boolean strict, int optimizationProfileId) {
        var frame = new Frame(env, trace, strict, PhpOptimizationProfile.fromId(optimizationProfileId), CURRENT.get()); CURRENT.set(frame); return frame;
    }
    public static void leave(Frame frame) {
        if (CURRENT.get() != frame) throw new IllegalStateException("Unbalanced PHP property write context");
        PhpPrimitiveCellRuntime.leaving(frame);
        // A null slot retains no environment/frame, and avoids rebuilding the
        // ThreadLocal entry at every top-level PHP invocation.
        CURRENT.set(frame.previous);
    }
    public static Frame current(Environment env) {
        var frame=CURRENT.get(); return frame != null && (env == null || frame.environment == env) ? frame : null;
    }
}
