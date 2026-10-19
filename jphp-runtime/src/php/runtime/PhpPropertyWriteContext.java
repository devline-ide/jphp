package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;

/** The executing PHP module, restored at every return/exception and across nested environments. */
public final class PhpPropertyWriteContext {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private PhpPropertyWriteContext() {}
    public static final class Frame {
        public final Environment environment;
        public final TraceInfo trace;
        public final boolean strict;
        private final Frame previous;
        public Frame previousFrame() { return previous; }
        private Frame(Environment environment, TraceInfo trace, boolean strict, Frame previous) {
            this.environment=environment; this.trace=trace; this.strict=strict; this.previous=previous;
        }
    }
    public static Frame enter(Environment env, TraceInfo trace, boolean strict) {
        var frame = new Frame(env, trace, strict, CURRENT.get()); CURRENT.set(frame); return frame;
    }
    public static void leave(Frame frame) {
        if (CURRENT.get() != frame) throw new IllegalStateException("Unbalanced PHP property write context");
        PhpPrimitiveCellRuntime.leaving(frame);
        if (frame.previous == null) CURRENT.remove(); else CURRENT.set(frame.previous);
    }
    public static Frame current(Environment env) {
        var frame=CURRENT.get(); return frame != null && (env == null || frame.environment == env) ? frame : null;
    }
}
