package vn.edu.ltm.common.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class AsyncCloseWorkflowTest {
    @Test void requestReturnsPromptlyAndExitRunsOnlyAfterCleanup() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var workflow = new AsyncCloseWorkflow(() -> worker, Runnable::run, () -> {
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
        var workers = new CopyOnWriteArrayList<ExecutorService>();
        try {
            var failed = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var attempts = new AtomicInteger();
            var workflow = new AsyncCloseWorkflow(() -> {
                var worker = Executors.newSingleThreadExecutor(); workers.add(worker); return worker;
            }, Runnable::run, () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("still alive");
            }, () -> { }, exited::countDown, failure -> failed.countDown());
            assertTrue(workflow.request());
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(workers.getFirst().awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(workflow.isComplete());
            assertEquals(1, exited.getCount());
            assertTrue(workflow.request());
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(2, workers.size());
            assertTrue(workers.getLast().awaitTermination(3, TimeUnit.SECONDS));
        } finally { workers.forEach(ExecutorService::shutdownNow); }
    }

    @Test void workerTerminatesBeforeUiExitCallbackAndForcedStopRetries() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var ui = new LinkedBlockingQueue<Runnable>();
        var attempts = new AtomicInteger();
        var exited = new CountDownLatch(1);
        var workflow = new AsyncCloseWorkflow(() -> worker, ui::add, () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("retry");
        }, () -> { }, exited::countDown, failure -> fail(failure));
        workflow.requestUntilComplete();
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(workflow.isComplete());
        assertEquals(1, exited.getCount(), "Exit remains on the UI executor");
        assertEquals(2, attempts.get());
        ui.take().run();
        assertEquals(0, exited.getCount());
    }

    @Test void forceArrivingBeforeFailureKeepsRetrying() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var attempts = new AtomicInteger();
            var workflow = new AsyncCloseWorkflow(() -> worker, Runnable::run, () -> {
                if (attempts.incrementAndGet() == 1) {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    throw new IllegalStateException("first failed");
                }
            }, () -> { }, exited::countDown, failure -> fail(failure));
            assertTrue(workflow.request());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            workflow.requestUntilComplete();
            release.countDown();
            assertTrue(exited.await(3, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        } finally { worker.shutdownNow(); }
    }

    @RepeatedTest(20) void forceInOldFailureDecisionGapCannotLoseRetryWakeup() throws Exception {
        var workers = new CopyOnWriteArrayList<ExecutorService>();
        try {
            var inGap = new CountDownLatch(1);
            var leaveGap = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var failuresShown = new AtomicInteger();
            var attempts = new AtomicInteger();
            var ui = new LinkedBlockingQueue<Runnable>();
            var workflow = new AsyncCloseWorkflow(() -> {
                var worker = Executors.newSingleThreadExecutor(); workers.add(worker); return worker;
            }, ui::add, () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("first failed");
            }, () -> { }, exited::countDown, failure -> failuresShown.incrementAndGet(), () -> {
                inGap.countDown();
                try { assertTrue(leaveGap.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(interrupted); }
            });
            assertTrue(workflow.request());
            assertTrue(inGap.await(3, TimeUnit.SECONDS));
            var forceAttempted = new CountDownLatch(1);
            var force = Thread.ofPlatform().start(() -> {
                forceAttempted.countDown();
                workflow.requestUntilComplete();
            });
            assertTrue(forceAttempted.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (force.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(Thread.State.BLOCKED, force.getState(), "Force request must arrive during the old read/fail gap");
            leaveGap.countDown();
            force.join(3000);
            assertFalse(force.isAlive());
            assertEquals(2, workers.size());
            for (var worker : workers) assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            while (!ui.isEmpty()) ui.poll().run();
            assertEquals(0, exited.getCount());
            assertEquals(2, attempts.get());
            assertEquals(0, failuresShown.get());
        } finally { workers.forEach(ExecutorService::shutdownNow); }
    }
}
