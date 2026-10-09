package vn.edu.ltm.agent.monitoring;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.agent.risk.*;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.common.protocol.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringCoordinatorTest {
    @Test void reconnectClosesPreviousIncidentBeforeReportingReopenedRisk() {
        var online = new AtomicBoolean(true);
        var disk = new AtomicInteger(95);
        var sent = new ArrayList<ControlEnvelope>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (var coordinator = new MonitoringCoordinator("lab-1", message -> {
            if (!online.get()) throw new IOException("offline"); sent.add(message);
        }, () -> new SystemMetrics(clock.instant(), 0, 0, disk.get(), null, null, false),
                new RiskDetector(), event -> { }, clock)) {
            coordinator.sample(); sent.clear();
            online.set(false); disk.set(0); coordinator.sample();
            disk.set(95); coordinator.sample();
            online.set(true); coordinator.sample();
            assertEquals(List.of(MessageType.HEARTBEAT, MessageType.METRICS,
                            MessageType.RISK_RESOLVED, MessageType.RISK_ALERT),
                    sent.stream().map(ControlEnvelope::type).toList());
        }
    }

    @Test void keepsLocalAlertsWorkingOfflineAndRetriesResolvedRisks() {
        var online = new AtomicBoolean(false);
        var disk = new AtomicInteger(95);
        var sent = new ArrayList<ControlEnvelope>();
        var alerts = new ArrayList<RiskEvent>();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (var coordinator = new MonitoringCoordinator("lab-1", message -> {
            if (!online.get()) throw new IOException("offline"); sent.add(message);
        }, () -> new SystemMetrics(clock.instant(), 0, 0, disk.get(), null, null, false),
                new RiskDetector(RiskThresholds.defaults()), alerts::add, clock)) {
            coordinator.sample();
            coordinator.sample();
            assertEquals(1, alerts.size());
            online.set(true); coordinator.sample();
            assertEquals(List.of(MessageType.HEARTBEAT, MessageType.METRICS, MessageType.RISK_ALERT),
                    sent.stream().map(ControlEnvelope::type).toList());
            assertEquals("UPDATE", sent.getLast().payload().path("status").asText());
            assertTrue(sent.stream().allMatch(e -> e.deviceId().equals("lab-1")));
            sent.clear(); online.set(false); disk.set(20); coordinator.sample();
            online.set(true); coordinator.sample();
            assertEquals(MessageType.RISK_RESOLVED, sent.getLast().type());
            sent.clear(); coordinator.sample();
            assertEquals(List.of(MessageType.HEARTBEAT, MessageType.METRICS), sent.stream().map(ControlEnvelope::type).toList());
            disk.set(95); coordinator.sample();
            assertEquals(3, alerts.size()); // OPEN, RESOLVED, OPEN; UPDATE never pops up.
        }
    }
}
