package org.develnext.jphp.ext.javafx.support;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class JavaFXRuntime {
    private static final Object STARTUP_LOCK = new Object();
    private static volatile boolean started;
    private static final java.util.List<Runnable> applicationStopHandlers = new java.util.ArrayList<>();
    private static boolean applicationStopped;

    private JavaFXRuntime() {
    }

    /** Owns application resources on the JavaFX queue, before the toolkit stops. */
    public static AutoCloseable onApplicationStop(Runnable handler) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Application stop handlers belong to the JavaFX thread");
        if (applicationStopped) throw new IllegalStateException("Application has stopped");
        java.util.Objects.requireNonNull(handler);
        applicationStopHandlers.add(handler);
        return () -> {
            if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Application stop handlers belong to the JavaFX thread");
            applicationStopHandlers.remove(handler);
        };
    }

    public static void applicationStopping() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Application stop handlers belong to the JavaFX thread");
        applicationStopped = true;
        java.util.List<Runnable> handlers = new java.util.ArrayList<>(applicationStopHandlers);
        applicationStopHandlers.clear();
        Throwable failure = null;
        for (int i = handlers.size() - 1; i >= 0; i--) {
            try { handlers.get(i).run(); }
            catch (RuntimeException | Error cause) { if (failure == null) failure = cause; else failure.addSuppressed(cause); }
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
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
