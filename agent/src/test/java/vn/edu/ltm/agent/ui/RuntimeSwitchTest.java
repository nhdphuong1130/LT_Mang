package vn.edu.ltm.agent.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSwitchTest {
    @Test void replacementWaitsForOldRuntimeToCloseBeforeStartingNewOne() throws Exception {
        var events = new ArrayList<String>();
        var closing = new CountDownLatch(1);
        var allowClose = new CountDownLatch(1);
        var switcher = new RuntimeSwitch<AutoCloseable>();
        switcher.replace(() -> () -> {
            closing.countDown();
            assertTrue(allowClose.await(3, TimeUnit.SECONDS));
            events.add("old closed");
        });
        var failure = new AtomicReference<Throwable>();
        var replacement = Thread.ofVirtual().start(() -> {
            try { switcher.replace(() -> { events.add("new started"); return () -> { }; }); }
            catch (Throwable error) { failure.set(error); }
        });
        assertTrue(closing.await(3, TimeUnit.SECONDS));
        assertTrue(events.isEmpty(), "New runtime must not start while old work is closing");
        allowClose.countDown();
        replacement.join(3000);
        assertFalse(replacement.isAlive());
        assertNull(failure.get());
        assertEquals(java.util.List.of("old closed", "new started"), events);
        switcher.close();
    }

    @Test void failedReplacementLeavesNoActiveRuntimeAndCanBeRetried() throws Exception {
        var switcher = new RuntimeSwitch<AutoCloseable>();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        switcher.replace(() -> () -> closed.set(true));
        assertThrows(IllegalStateException.class, () -> switcher.replace(() -> { throw new IllegalStateException("cannot start"); }));
        assertTrue(closed.get());
        var retriedClosed = new java.util.concurrent.atomic.AtomicBoolean();
        switcher.replace(() -> () -> retriedClosed.set(true));
        switcher.close();
        assertTrue(retriedClosed.get());
    }

    @Test void failedCloseRetainsExactRuntimeAndNeverStartsReplacementUntilRetrySucceeds() throws Exception {
        var switcher = new RuntimeSwitch<AutoCloseable>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        switcher.replace(() -> () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("still stopping");
        });
        assertThrows(IllegalStateException.class, () -> switcher.replace(() -> {
            started.set(true); return () -> { };
        }));
        assertFalse(started.get());
        switcher.close();
        assertEquals(2, attempts.get(), "stop retries the original runtime");
        assertThrows(IllegalStateException.class, () -> switcher.replace(() -> {
            started.set(true); return () -> { };
        }));
        assertFalse(started.get());
    }

    @Test void failedStopCanBeRetriedWithoutDroppingActiveRuntime() throws Exception {
        var switcher = new RuntimeSwitch<AutoCloseable>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        switcher.replace(() -> () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("still stopping");
        });
        assertThrows(IllegalStateException.class, switcher::close);
        switcher.close();
        assertEquals(2, attempts.get());
    }
}
