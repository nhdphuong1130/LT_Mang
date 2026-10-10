package vn.edu.ltm.agent.ui;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Serializes runtime replacement and waits for the old runtime's close contract. */
final class RuntimeSwitch<T extends AutoCloseable> implements AutoCloseable {
    private T active;
    private boolean closed;

    synchronized T replace(Callable<T> startNew) throws Exception {
        Objects.requireNonNull(startNew);
        if (closed) throw new IllegalStateException("Runtime switch is closed");
        T previous = active;
        active = null;
        if (previous != null) previous.close();
        T started = Objects.requireNonNull(startNew.call());
        active = started;
        return started;
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        T previous = active;
        active = null;
        if (previous != null) previous.close();
    }
}
