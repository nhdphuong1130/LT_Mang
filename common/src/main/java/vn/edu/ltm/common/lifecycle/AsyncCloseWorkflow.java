package vn.edu.ltm.common.lifecycle;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Non-blocking, retryable shutdown gate for UI close requests. */
public final class AsyncCloseWorkflow {
    @FunctionalInterface public interface Cleanup { void run() throws Exception; }

    private final Executor background;
    private final Executor ui;
    private final Cleanup cleanup;
    private final Runnable onClosing;
    private final Runnable onComplete;
    private final Consumer<Throwable> onFailure;
    private boolean running;
    private boolean complete;

    public AsyncCloseWorkflow(Executor background, Executor ui, Cleanup cleanup, Runnable onClosing,
                              Runnable onComplete, Consumer<Throwable> onFailure) {
        this.background = Objects.requireNonNull(background);
        this.ui = Objects.requireNonNull(ui);
        this.cleanup = Objects.requireNonNull(cleanup);
        this.onClosing = Objects.requireNonNull(onClosing);
        this.onComplete = Objects.requireNonNull(onComplete);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    /** Returns immediately; false means a close is in progress or already complete. */
    public synchronized boolean request() {
        if (running || complete) return false;
        running = true;
        onClosing.run();
        try { background.execute(this::runCleanup); }
        catch (RuntimeException failure) { fail(failure); }
        return true;
    }

    public synchronized boolean isComplete() { return complete; }

    private void runCleanup() {
        try {
            cleanup.run();
            synchronized (this) { complete = true; running = false; }
            ui.execute(onComplete);
        } catch (Exception failure) { fail(failure); }
    }

    private void fail(Throwable failure) {
        synchronized (this) { running = false; }
        ui.execute(() -> onFailure.accept(failure));
    }
}
