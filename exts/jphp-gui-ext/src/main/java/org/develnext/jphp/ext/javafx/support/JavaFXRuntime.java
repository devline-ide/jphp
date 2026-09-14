package org.develnext.jphp.ext.javafx.support;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class JavaFXRuntime {
    private static final Object STARTUP_LOCK = new Object();
    private static volatile boolean started;

    private JavaFXRuntime() {
    }

    public static void ensureStarted() {
        if (started) {
            return;
        }

        synchronized (STARTUP_LOCK) {
            if (started) {
                return;
            }

            if (Platform.isFxApplicationThread()) {
                started = true;
                return;
            }

            try {
                Platform.startup(() -> {
                });
            } catch (IllegalStateException alreadyStarted) {
                Platform.runLater(() -> {
                });
            }

            started = true;
        }
    }

    public static <T> T runAndWait(Callable<T> action) {
        ensureStarted();

        if (Platform.isFxApplicationThread()) {
            return call(action);
        }

        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);

        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the JavaFX thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }

            if (cause instanceof Error) {
                throw (Error) cause;
            }

            throw new IllegalStateException(cause);
        }
    }

    private static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
