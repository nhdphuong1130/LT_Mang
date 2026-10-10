package vn.edu.ltm.agent.alert;

import java.util.Objects;

/** Emits one warning for each established connection that is subsequently lost. */
public final class ConnectionStatusNotifier {
    private final Runnable warning;
    private boolean connected;

    public ConnectionStatusNotifier(Runnable warning) { this.warning = Objects.requireNonNull(warning); }

    public synchronized void poll(boolean registered) {
        if (connected && !registered) warning.run();
        connected = registered;
    }
}
