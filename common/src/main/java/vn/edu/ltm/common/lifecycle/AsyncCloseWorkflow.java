package vn.edu.ltm.common.lifecycle;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Non-blocking, retryable shutdown gate for UI close requests. */
public final class AsyncCloseWorkflow {
    @FunctionalInterface public interface Cleanup { void run() throws Exception; }

    private final ExecutorService background;
    private final Executor ui;
    private final Cleanup cleanup;
    private final Runnable onClosing;
    private final Runnable onComplete;
    private final Consumer<Throwable> onFailure;
    private boolean running;
    private boolean complete;
    private boolean forceUntilComplete;

    public AsyncCloseWorkflow(ExecutorService background, Executor ui, Cleanup cleanup, Runnable onClosing,
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

    /** Fallback for an external toolkit exit: keep the JVM alive until cleanup succeeds. */
    public synchronized void requestUntilComplete() {
        forceUntilComplete = true;
        if (running || complete) return;
        running = true;
        background.execute(this::runCleanup);
    }

    private void runCleanup() {
        while (true) {
            try { cleanup.run(); }
            catch (Exception failure) {
                boolean forced;
                synchronized (this) { forced = forceUntilComplete; }
                if (!forced) { fail(failure); return; }
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "Shutdown failed; retrying while application exits", failure);
                try { Thread.sleep(250); }
                catch (InterruptedException ignored) { Thread.interrupted(); }
                continue;
            }
            synchronized (this) { complete = true; running = false; }
            background.shutdown();
            try { ui.execute(onComplete); }
            catch (RuntimeException failure) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "UI completion callback unavailable after cleanup", failure);
            }
            return;
        }
    }

    private void fail(Throwable failure) {
        synchronized (this) { running = false; }
        ui.execute(() -> onFailure.accept(failure));
    }
}
