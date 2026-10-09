package vn.edu.ltm.agent.network;

import java.time.Duration;

public final class ReconnectPolicy {
    private static final int[] DELAYS = {2, 5, 10, 30};
    private int index;

    public synchronized Duration nextDelay() {
        Duration delay = Duration.ofSeconds(DELAYS[index]);
        if (index < DELAYS.length - 1) index++;
        return delay;
    }

    public synchronized void reset() { index = 0; }
}
