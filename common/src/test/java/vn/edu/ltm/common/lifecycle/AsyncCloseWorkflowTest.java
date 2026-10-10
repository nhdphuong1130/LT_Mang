package vn.edu.ltm.common.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncCloseWorkflowTest {
    @Test void requestReturnsPromptlyAndExitRunsOnlyAfterCleanup() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var workflow = new AsyncCloseWorkflow(worker, Runnable::run, () -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
            }, () -> { }, exited::countDown, failure -> fail(failure));
            long before = System.nanoTime();
            assertTrue(workflow.request());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(1, exited.getCount());
            assertFalse(workflow.request(), "Only one cleanup may run at a time");
            release.countDown();
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertTrue(workflow.isComplete());
        }
    }

    @Test void failedCleanupIsVisibleAndRetryable() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var failed = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var attempts = new AtomicInteger();
            var workflow = new AsyncCloseWorkflow(worker, Runnable::run, () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("still alive");
            }, () -> { }, exited::countDown, failure -> failed.countDown());
            assertTrue(workflow.request());
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertFalse(workflow.isComplete());
            assertEquals(1, exited.getCount());
            assertTrue(workflow.request());
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
    }
}
