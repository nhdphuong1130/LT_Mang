package vn.edu.ltm.agent.ui;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Serializes runtime replacement and waits for the old runtime's close contract. */
final class RuntimeSwitch<T extends AutoCloseable> implements AutoCloseable {
    private T active;
    private boolean closed;
    private boolean closeRequested;

    synchronized T replace(Callable<T> startNew) throws Exception {
        Objects.requireNonNull(startNew);
        if (closeRequested) throw new IllegalStateException("Runtime switch is closing");
        if (active != null) {
            active.close();
            active = null;
        }
        T started = Objects.requireNonNull(startNew.call());
        active = started;
        return started;
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closeRequested = true;
        if (active != null) {
            active.close();
            active = null;
        }
        closed = true;
    }
}
