package vn.edu.ltm.server.ui;

import java.util.Objects;

/** A control registration is only accepted after the file channel is ready. */
final class ServerListeners implements AutoCloseable {
    interface Listener {
        void start() throws Exception;
        void close();
    }
    private final Listener files;
    private final Listener control;
    ServerListeners(Listener files, Listener control) {
        this.files = Objects.requireNonNull(files);
        this.control = Objects.requireNonNull(control);
    }
    void start() throws Exception {
        try {
            files.start();
            control.start();
        } catch (Exception failure) {
            try { control.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            try { files.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    @Override public void close() {
        try { control.close(); }
        finally { files.close(); }
    }
}
