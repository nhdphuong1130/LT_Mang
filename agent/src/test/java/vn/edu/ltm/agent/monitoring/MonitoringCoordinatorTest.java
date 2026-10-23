package vn.edu.ltm.agent.monitoring;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.agent.risk.*;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.common.protocol.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringCoordinatorTest {
    @Test void reportsDirectAdapterIdentityInsteadOfInfrastructureRssi() throws Exception {
        var sent = new LinkedBlockingQueue<ControlEnvelope>();
        Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
        var direct = DirectLinkIdentity.connected("Local Area Connection* 1", "CC-C5-30-08-CF-12",
                clock.instant(), "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1"));
        try (var coordinator = new MonitoringCoordinator("pc-n", sent::add,
                () -> new SystemMetrics(clock.instant(), 1, 2, 3, null, null, false),
                new RiskDetector(), event -> { }, clock, () -> null, () -> direct)) {
            coordinator.sample();

            var metrics = take(sent, 2).stream()
                    .filter(message -> message.type() == MessageType.METRICS)
                    .findFirst().orElseThrow();
            assertFalse(metrics.payload().has("rssiDbm"));
            assertEquals("CONNECTED", metrics.payload().path("directLink").path("status").asText());
            assertEquals("CC:C5:30:08:CF:12", metrics.payload().path("directLink").path("localP2pMac").asText());
            assertEquals("1.1.0", metrics.payload().path("agentVersion").asText());
            assertEquals("WIFI_DIRECT_IDENTITY_V1",
                    metrics.payload().path("capabilities").get(0).asText());
        }
    }

    @Test void sendsTheProfileCollectedOnThisClientInsteadOfADeviceTemplate() throws Exception {
        var sent = new LinkedBlockingQueue<ControlEnvelope>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var localProfile = new DxdiagProfile(
                "REAL-PC-B", "Windows 11 build 99999", "Actual vendor", "Actual model",
                "Actual BIOS", "Actual CPU", "24576 MB RAM", "Actual page file", "DirectX actual",
                "Actual GPU", "Actual GPU vendor", "Actual VRAM", "Actual display",
                "Actual disk", "Actual network");
        try (var coordinator = new MonitoringCoordinator("pc-b", sent::add,
                () -> new SystemMetrics(clock.instant(), 1, 2, 3, null, null, false),
                new RiskDetector(), event -> { }, clock, () -> localProfile)) {
            coordinator.sample();

            var metrics = take(sent, 2).stream()
                    .filter(message -> message.type() == MessageType.METRICS)
                    .findFirst().orElseThrow();
            assertEquals("REAL-PC-B", metrics.payload().path("dxdiag").path("computerName").asText());
            assertEquals("Actual model", metrics.payload().path("dxdiag").path("systemModel").asText());
            assertEquals("24576 MB RAM", metrics.payload().path("dxdiag").path("memory").asText());
        }
    }

    @Test void callerCanWaitForOutputWorkerAfterClosingItsTransport() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var coordinator = new MonitoringCoordinator("lab-1", message -> {
            entered.countDown();
            boolean done = false;
            while (!done) try { release.await(); done = true; }
            catch (InterruptedException ignored) { }
        }, () -> new SystemMetrics(clock.instant(), 0, 0, 0, null, null, false),
                new RiskDetector(), event -> { }, clock);
        try {
            coordinator.sample();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            coordinator.close();
            assertFalse(coordinator.awaitStopped(Duration.ofMillis(30)));
            release.countDown();
            assertTrue(coordinator.awaitStopped(Duration.ofSeconds(2)));
        } finally { release.countDown(); coordinator.close(); }
    }
    @Test void completingAnOldWriteCannotRemoveAnEqualReopenedRisk() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reopened = new CountDownLatch(1);
        var opens = new AtomicInteger();
        var disk = new AtomicInteger(95);
        var risks = new CopyOnWriteArrayList<String>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var coordinator = new MonitoringCoordinator("lab-1", message -> {
            if (message.type() != MessageType.RISK_ALERT && message.type() != MessageType.RISK_RESOLVED) return;
            var status = message.payload().path("status").asText();
            if (status.equals("OPEN") && opens.incrementAndGet() == 1) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
            risks.add(status);
            if (opens.get() == 2) reopened.countDown();
        }, () -> new SystemMetrics(clock.instant(), 0, 0, disk.get(), null, null, false),
                new RiskDetector(), event -> { }, clock);
        try {
            coordinator.sample(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            disk.set(0); coordinator.sample();
            disk.set(95); coordinator.sample();
            release.countDown();
            assertTrue(reopened.await(2, TimeUnit.SECONDS), "Completion must retire only its own risk generation");
            assertEquals(List.of("OPEN", "RESOLVED", "OPEN"), risks);
        } finally { release.countDown(); coordinator.close(); }
    }

    @Test void blockedSenderCannotStopLocalSamplingOrHoldUpShutdown() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var senderExited = new CountDownLatch(1);
        var samples = new AtomicInteger();
        var disk = new AtomicInteger(95);
        var sends = new AtomicInteger();
        var alerts = new CopyOnWriteArrayList<RiskEvent>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var coordinator = new MonitoringCoordinator("lab-1", message -> {
            sends.incrementAndGet(); entered.countDown();
            // Deliberately ignore cancellation, like a transport that cannot be interrupted.
            boolean released = false;
            while (!released) {
                try { release.await(); released = true; }
                catch (InterruptedException ignored) { }
            }
            senderExited.countDown();
        }, () -> {
            samples.incrementAndGet();
            return new SystemMetrics(clock.instant(), 0, 0, disk.get(), null, null, false);
        }, new RiskDetector(), alerts::add, clock);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var firstSample = workers.submit(coordinator::sample);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertDoesNotThrow(() -> firstSample.get(2, TimeUnit.SECONDS),
                        "Sampling must finish while the transport is blocked");
                workers.submit(() -> {
                    for (int i = 0; i < 1000; i++) coordinator.sample();
                    disk.set(20); coordinator.sample();
                    disk.set(95); coordinator.sample();
                }).get(2, TimeUnit.SECONDS);
                assertEquals(1003, samples.get());
                assertEquals(List.of(RiskEvent.Status.OPEN, RiskEvent.Status.RESOLVED, RiskEvent.Status.OPEN),
                        alerts.stream().map(RiskEvent::status).toList());
                workers.submit(coordinator::close).get(2, TimeUnit.SECONDS);
                assertEquals(1, sends.get(), "A blocked writer must have only one in-flight send");
            } finally {
                release.countDown();
                coordinator.close();
                assertTrue(senderExited.await(2, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, sends.get(), "Closing discards pending output even when a sender ignores interruption");
    }

    @Test void blockedSenderCoalescesOutputAndPreservesResolutionBeforeReopen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var sent = new CopyOnWriteArrayList<ControlEnvelope>();
        var disk = new AtomicInteger(95);
        var ram = new AtomicInteger();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var coordinator = new MonitoringCoordinator("lab-1", message -> {
            if (entered.getCount() > 0) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
            sent.add(message);
            if (message.type() == MessageType.RISK_ALERT && message.payload().path("status").asText().equals("UPDATE"))
                completed.countDown();
        }, () -> new SystemMetrics(clock.instant(), 0, ram.get(), disk.get(), null, null, false),
                new RiskDetector(), event -> { }, clock);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = workers.submit(coordinator::sample);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertDoesNotThrow(() -> first.get(2, TimeUnit.SECONDS));
                disk.set(0); coordinator.sample();
                disk.set(95);
                for (int i = 0; i < 1000; i++) { ram.set(i % 80); coordinator.sample(); }
                release.countDown();
                assertTrue(completed.await(2, TimeUnit.SECONDS));
                var metrics = sent.stream().filter(e -> e.type() == MessageType.METRICS).toList();
                assertTrue(metrics.size() <= 2, "Only an in-flight sample and the latest sample may be written");
                assertEquals(39, metrics.getLast().payload().path("ramPercent").asInt());
                var risks = sent.stream().filter(e -> e.type() == MessageType.RISK_ALERT || e.type() == MessageType.RISK_RESOLVED)
                        .map(e -> e.payload().path("status").asText()).toList();
                assertEquals(List.of("RESOLVED", "UPDATE"), risks);
                assertTrue(sent.size() <= 6, "A thousand waiting samples must coalesce into bounded output");
            } finally { release.countDown(); coordinator.close(); }
        }
    }

    @Test void reconnectClosesPreviousIncidentBeforeReportingReopenedRisk() throws Exception {
        var online = new AtomicBoolean(true);
        var disk = new AtomicInteger(95);
        var sent = new LinkedBlockingQueue<ControlEnvelope>();
        var failed = new Semaphore(0);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (var coordinator = new MonitoringCoordinator("lab-1", message -> {
            if (!online.get()) { failed.release(); throw new IOException("offline"); } sent.add(message);
        }, () -> new SystemMetrics(clock.instant(), 0, 0, disk.get(), null, null, false),
                new RiskDetector(), event -> { }, clock)) {
            coordinator.sample(); take(sent, 3);
            online.set(false); disk.set(0); coordinator.sample(); assertTrue(failed.tryAcquire(2, TimeUnit.SECONDS));
            disk.set(95); coordinator.sample(); assertTrue(failed.tryAcquire(2, TimeUnit.SECONDS));
            online.set(true); coordinator.sample();
            assertEquals(List.of(MessageType.HEARTBEAT, MessageType.METRICS,
                            MessageType.RISK_RESOLVED, MessageType.RISK_ALERT),
                    take(sent, 4).stream().map(ControlEnvelope::type).toList());
        }
    }

    @Test void keepsLocalAlertsWorkingOfflineAndRetriesResolvedRisks() throws Exception {
        var online = new AtomicBoolean(false);
        var disk = new AtomicInteger(95);
        var sent = new LinkedBlockingQueue<ControlEnvelope>();
        var failed = new Semaphore(0);
        var alerts = new ArrayList<RiskEvent>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (var coordinator = new MonitoringCoordinator("lab-1", message -> {
            if (!online.get()) { failed.release(); throw new IOException("offline"); } sent.add(message);
        }, () -> new SystemMetrics(clock.instant(), 0, 0, disk.get(), null, null, false),
                new RiskDetector(RiskThresholds.defaults()), alerts::add, clock)) {
            coordinator.sample();
            assertTrue(failed.tryAcquire(2, TimeUnit.SECONDS));
            coordinator.sample();
            assertTrue(failed.tryAcquire(2, TimeUnit.SECONDS));
            assertEquals(1, alerts.size());
            online.set(true); coordinator.sample();
            var batch = take(sent, 3);
            assertEquals(List.of(MessageType.HEARTBEAT, MessageType.METRICS, MessageType.RISK_ALERT),
                    batch.stream().map(ControlEnvelope::type).toList());
            assertEquals("UPDATE", batch.getLast().payload().path("status").asText());
            assertTrue(batch.stream().allMatch(e -> e.deviceId().equals("lab-1")));
            online.set(false); disk.set(20); coordinator.sample();
            assertTrue(failed.tryAcquire(2, TimeUnit.SECONDS));
            online.set(true); coordinator.sample();
            assertEquals(MessageType.RISK_RESOLVED, take(sent, 3).getLast().type());
            coordinator.sample();
            assertEquals(List.of(MessageType.HEARTBEAT, MessageType.METRICS), take(sent, 2).stream().map(ControlEnvelope::type).toList());
            disk.set(95); coordinator.sample();
            take(sent, 3);
            assertEquals(3, alerts.size()); // OPEN, RESOLVED, OPEN; UPDATE never pops up.
        }
    }

    static List<ControlEnvelope> take(LinkedBlockingQueue<ControlEnvelope> messages, int count) throws InterruptedException {
        var result = new ArrayList<ControlEnvelope>();
        for (int i = 0; i < count; i++) {
            var message = messages.poll(2, TimeUnit.SECONDS);
            assertNotNull(message, "Expected asynchronous monitoring output");
            result.add(message);
        }
        return result;
    }
}
