package php.runtime.exceptions;

/** Internal stack cancellation: executes PHP finally without becoming a catchable PHP Throwable. */
public abstract class PhpUnwindSignal extends RuntimeException implements JPHPException {
    protected PhpUnwindSignal() { super(null, null, false, false); }
}
