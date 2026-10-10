package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;

/** Executes one pipe SEND, whose left value was captured before evaluating the callable. */
public final class PhpPipe {
    private PhpPipe() {}
    public static Memory apply(Memory capturedValue, Memory callable, Environment env, TraceInfo trace) throws Throwable {
        PhpCallFrame frame = PhpCallFrame.callable(callable, env, trace, 1);
        frame.send(capturedValue, PhpCallFrame.VALUE);
        return frame.invoke();
    }
}
