/**
 * A "yield return" implementation for Java
 * By Jim Blackler (jimblackler@gmail.com)
 *
 * http://jimblackler.net/blog/?p=61
 * http://svn.jimblackler.net/jimblackler/trunk/IdeaProjects/YieldAdapter/
 */
package php.runtime.util.generator;

import java.io.Closeable;
import java.util.Iterator;

/**
 * A version of a standard Iterator<> used by the yield adapter. The only addition is a close()
 * function to clear resources manually when required.
 */
abstract public class YieldAdapterIterator<T> implements Iterator<T>, Closeable {

    /**
     * The collecting worker retains this iterator while suspended. Partial traversal must be
     * closed explicitly; garbage collection cannot retire a live worker. Close interrupts the
     * worker and waits for collector cleanup before clearing retained payloads. A collector must
     * cooperate with interruption. Closing from the collector itself requests cancellation without
     * waiting for its own termination. Fully consumed iterators finish their worker naturally.
     */

    protected Throwable throwable;

    protected T currentValue;

    public T getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(T currentValue) {
        this.currentValue = currentValue;
    }

    public T sendValue(T value) {
        this.currentValue = value;
        return next();
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public void sendThrow(Throwable throwable) {
        this.throwable = throwable;
        next();
    }
}