package vn.edu.ltm.server.heartbeat;

import vn.edu.ltm.server.session.SessionRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HeartbeatMonitor implements AutoCloseable {
    private final SessionRegistry sessions;
    private final Duration timeout;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    public HeartbeatMonitor(SessionRegistry sessions, Duration timeout) { this(sessions, timeout, Clock.systemUTC()); }

    public HeartbeatMonitor(SessionRegistry sessions, Duration timeout, Clock clock) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        this.sessions = sessions;
        this.timeout = timeout;
        this.clock = clock;
    }

    public void check(Instant now) { sessions.expireThrough(now.minus(timeout)); }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("heartbeat-monitor").factory());
        scheduler.scheduleAtFixedRate(() -> {
            try { check(clock.instant()); }
            catch (RuntimeException failure) {
                System.getLogger(HeartbeatMonitor.class.getName()).log(System.Logger.Level.WARNING, "Heartbeat check failed");
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override public synchronized void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public boolean awaitStopped(Duration wait) throws InterruptedException {
        ScheduledExecutorService current;
        synchronized (this) { current = scheduler; }
        return current == null || current.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS);
    }
}
