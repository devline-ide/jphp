/**
 * A "yield return" implementation for Java
 * By Jim Blackler (jimblackler@gmail.com)
 *
 * http://jimblackler.net/blog/?p=61
 * http://svn.jimblackler.net/jimblackler/trunk/IdeaProjects/YieldAdapter/
 */
package php.runtime.util.generator;

import java.util.NoSuchElementException;
import java.util.concurrent.LinkedTransferQueue;

/**
 * A class to convert methods that implement the Collector<> class into a standard Iterable<>, using
 * a new thread created for the collection process, and a transfer queue.
 */
public class ThreadedYieldAdapter<T> implements YieldAdapter<T> {
    private static final Object RESUME = new Object();

    /**
     * Message structure to pass values between threads.
     */
    class Message {
        Throwable failure() { return null; }
    }

    abstract private class StopMessage extends Message {

    }

    private class EndMessage extends StopMessage {

    }

    private class AbortedMessage extends StopMessage {

    }

    private class FailedMessage extends StopMessage {
        private final Throwable failure;

        private FailedMessage(Throwable failure) { this.failure = failure; }

        @Override
        Throwable failure() { return failure; }
    }

    private static class VarContainer<T> {
        T var;
        volatile boolean closed;
        Object packet;

        public T getVar() {
            return var;
        }

        public void setVar(T var) {
            this.var = var;
        }
    }

    /**
     * The vehicle to pass the actual values.
     */
    class ValueMessage extends Message {

        ValueMessage(T value) {
            this.value = value;
        }

        T value;
    }



    /**
     * Convert a method that implements the Collector<> class with a standard Iterable<>. This means
     * that the collecting method can use complex recursive logic, but still allows the calling code
     * to handle the results with a standard iterator. Results are returned immediately and do not
     * incur overhead of being stored in a list. Calculation overhead is only performed for the
     * results that are requested through the iterator.
     *
     * This is implemented using a new thread created for the collection process, and a
     * SynchronousQueue<> object.
     */
    public YieldAdapterIterable<T> adapt(final Collector<T> client) {

        return new YieldAdapterIterable<T>() {
            public YieldAdapterIterator<T> iterator() {

                final LinkedTransferQueue<Message> synchronousQueue = new LinkedTransferQueue<Message>();

                // Mechanism to ensure both threads don't run at the same time
                final LinkedTransferQueue<Object> returnQueue = new LinkedTransferQueue<Object>();

                final VarContainer<Thread> collectThread = new VarContainer<Thread>();

                final YieldAdapterIterator<T> iterator = new YieldAdapterIterator<T>() {
                    private volatile Message messageWaiting = null;

                    public boolean hasNext() {

                        readNextMessage();
                        return !collectThread.closed && !StopMessage.class.isAssignableFrom(messageWaiting.getClass());
                        // instanceof cannot be used because of generics restriction
                    }

                    public T next() {
                        readNextMessage();

                        synchronized (this) {
                            if (collectThread.closed || StopMessage.class.isAssignableFrom(messageWaiting.getClass())) {
                                // instanceof cannot be used because of generics restriction
                                throw new NoSuchElementException();
                            }

                            ValueMessage packet = (ValueMessage) messageWaiting;
                            final T value = currentValue = packet.value;
                            packet.value = null;
                            messageWaiting = null; // for next time
                            return value;
                        }
                    }

                    private void readNextMessage() {
                        if (collectThread.closed) {
                            messageWaiting = new EndMessage();
                            return;
                        }
                        if (messageWaiting == null) { // do not run if value waiting to be put
                            try {
                                returnQueue.offer(RESUME); // allow other thread to gather result
                                messageWaiting = synchronousQueue.take();
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                                close();
                            }
                        }
                        Throwable failure = messageWaiting.failure();
                        if (failure != null && !collectThread.closed) {
                            messageWaiting = new EndMessage();
                            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                            if (failure instanceof Error) throw (Error) failure;
                            throw new IllegalStateException(failure);
                        }
                    }

                    public void remove() {
                        throw new UnsupportedOperationException("Generators don't support remove()");
                    }

                    /**
                     * This can be manually called by the calling code to force release of
                     * resources at the earliest opportunity.
                     */
                    @Override
                    public void close() {
                        Thread worker = collectThread.getVar();
                        synchronized (this) {
                            if (!collectThread.closed) {
                                collectThread.closed = true;
                                // Keep a terminal marker available even when a reader has not taken yet.
                                synchronousQueue.offer(new EndMessage());
                                if (worker != Thread.currentThread()) worker.interrupt();
                            }
                        }
                        if (worker != Thread.currentThread()) {
                            boolean interrupted = false;
                            while (worker.isAlive()) {
                                try {
                                    worker.join();
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                }
                            }
                            if (interrupted) Thread.currentThread().interrupt();
                        }
                        messageWaiting = new EndMessage();
                        currentValue = null;
                        throwable = null;
                        if (collectThread.packet != null) ((ValueMessage) collectThread.packet).value = null;
                    }
                };

                // This thread is where the collecting logic is executed.
                collectThread.setVar(new Thread() {
                    @Override
                    public void run() {

                        // Important .. handling thread (main thread) gets to run first.
                        // This is because the collecting process should be run on demand in response to
                        // iterator access. Each result should be dealt with by the handling process before
                        // the collecting process is able to modify any resources that may be requred by
                        // results.

                        try {
                            returnQueue.take();
                        } catch (final InterruptedException e) {
                            //throw new RuntimeException("Error with yield adapter", e);
                            return;
                        }
                        try {
                            try {

                                client.collect(new ResultHandler<T>() {
                                    public T handleResult(final T value)
                                            throws CollectionAbortedException {
                                        try {
                                            if (collectThread.closed) throw new CollectionAbortedException(new InterruptedException());
                                            T oldValue = iterator.getCurrentValue();
                                            ValueMessage packet = (ValueMessage) collectThread.packet;
                                            if (packet == null) {
                                                packet = new ValueMessage(value);
                                                collectThread.packet = packet;
                                            } else packet.value = value;
                                            synchronousQueue.transfer(packet);
                                            returnQueue.take();  // wait for permission to continue
                                            return oldValue;
                                        } catch (final InterruptedException e) {
                                            // this thread has been aborted
                                            throw new CollectionAbortedException(e);
                                        }
                                    }
                                });

                                if (!collectThread.closed) synchronousQueue.transfer(new EndMessage());
                                // Signal no more results to come

                            } catch (final CollectionAbortedException collectionAborted) {
                                if (!(collectionAborted
                                        .getCause() instanceof InterruptedException)) {
                                    // Collect was aborted by client
                                    // This is not sent on thread abort as there is nothing waiting
                                    // to receive it, and the thread will block.
                                    if (!collectThread.closed) synchronousQueue.transfer(new AbortedMessage());
                                }
                            } catch (RuntimeException | Error failure) {
                                if (!collectThread.closed) synchronousQueue.transfer(new FailedMessage(failure));
                            }

                        } catch (final InterruptedException e) {
                            // Operation was aborted internally (e.g.  iterator out of scope)
                        }
                    }
                });
                collectThread.getVar().setDaemon(true);
                collectThread.getVar().start();

                return iterator;
            }
        };
    }
}

