package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.model.DirectLinkIdentity;
import vn.edu.ltm.common.model.DirectLinkStatus;

import java.time.*;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeDistanceTrackerTest {
    @Test
    void doesNotInventDistanceOrProbeValuesBeforeReceivingMeasurements() {
        var tracker = new RealtimeDistanceTracker();

        DistanceTelemetry pending = tracker.getOrProbe("pc-b", null, true);

        assertTrue(pending.isOnline());
        assertFalse(pending.hasPhysicalDistance());
        assertEquals(-1.0, pending.estimatedMeters());
        assertEquals(-1.0, pending.rttMs());
    }

    @Test
    void usesOnlyServerObservedWifiDirectStationForPhysicalDistance() {
        var pingSample = "64 bytes from 10.35.92.160: icmp_seq=1 ttl=128 time=12.4 ms";
        var parsed = RealtimeDistanceTracker.parsePingOutput(pingSample);
        assertNotNull(parsed);
        assertEquals(12.4, parsed.rttMs(), 0.01);
        assertEquals(128, parsed.ttl());

        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        var registry = new DirectLinkRegistry(Clock.fixed(now, ZoneOffset.UTC));
        registry.update("pc-b", DirectLinkIdentity.connected("Direct", "CC:C5:30:08:CF:12", now,
                "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        var tracker = new RealtimeDistanceTracker(registry, List::of, Clock.fixed(now, ZoneOffset.UTC));
        tracker.recordStationSamples(List.of(new LinuxP2pStationCollector.StationSample(
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", -58, now)));
        DistanceTelemetry t = tracker.recordProbe("pc-b", parsed.rttMs(), parsed.ttl());
        assertTrue(t.isOnline());
        assertTrue(t.estimatedMeters() > 1.0 && t.estimatedMeters() < 10.0);
        assertEquals(1, t.hopCount());
        assertEquals(DirectLinkStatus.CONNECTED, t.directLinkStatus());
        assertEquals("WIFI_DIRECT_SERVER_STATION", t.measurementSource());
    }

    @Test void legacyAgentRssiCannotCreatePhysicalDistance() {
        var tracker = new RealtimeDistanceTracker();
        DistanceTelemetry t = tracker.recordProbe("pc-b", 4.0, 128);
        assertFalse(t.hasPhysicalDistance());
        assertEquals(DirectLinkStatus.AGENT_UPDATE_REQUIRED, t.directLinkStatus());
    }
}
