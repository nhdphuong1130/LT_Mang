package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.model.DirectLinkIdentity;
import vn.edu.ltm.common.model.DirectLinkStatus;
import vn.edu.ltm.common.model.DistanceCalibrationModel;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeDistanceTrackerTest {
    @Test void liveSamplingIntervalsStayBelowOneSecond() {
        assertTrue(RealtimeDistanceTracker.STATION_COLLECTION_INTERVAL_MILLIS <= 500);
        assertTrue(RealtimeDistanceTracker.PROBE_INTERVAL_MILLIS <= 750);
    }

    @Test
    void doesNotInventDistanceOrProbeValuesBeforeReceivingMeasurements() {
        var tracker = new RealtimeDistanceTracker();

        DistanceTelemetry pending = tracker.getOrProbe("pc-b", null, true);

        assertTrue(pending.isOnline());
        assertFalse(pending.hasPhysicalDistance());
        assertEquals(-1.0, pending.estimatedMeters());
        assertEquals(-1.0, pending.rttMs());
    }

    @Test void failedPingDoesNotProduceSyntheticRttOrTtl() {
        assertNull(RealtimeDistanceTracker.probeHost("256.256.256.256"));
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

    @Test void appliesActiveCalibrationAndResetsFilterWhenModelChanges() {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        var registry = new DirectLinkRegistry(Clock.fixed(now, ZoneOffset.UTC));
        registry.update("pc-b", DirectLinkIdentity.connected("Direct", "CC:C5:30:08:CF:12", now,
                "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        var model = new AtomicReference<>(new DistanceCalibrationModel("cal-1", now, -50, 2.0, 4, 0.1, 0.1));
        var tracker = new RealtimeDistanceTracker(registry, List::of, Clock.fixed(now, ZoneOffset.UTC), model::get);
        tracker.recordStationSamples(List.of(new LinuxP2pStationCollector.StationSample(
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", -50, now)));
        assertEquals("cal-1", tracker.recordProbe("pc-b", 2, 128).calibrationId());

        model.set(new DistanceCalibrationModel("cal-2", now, -60, 2.5, 4, 0.2, 0.3));
        tracker.recordStationSamples(List.of(new LinuxP2pStationCollector.StationSample(
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", -80, now)));
        var afterChange = tracker.recordProbe("pc-b", 2, 128);

        assertEquals("cal-2", afterChange.calibrationId());
        assertEquals(-80, afterChange.rssiFilteredDbm(), 0.01);
        assertTrue(afterChange.calibrated());
    }

    @Test void retainsRecentSampleAcrossEmptyScansUntilExpiry() {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        var registry = new DirectLinkRegistry(Clock.fixed(now, ZoneOffset.UTC));
        registry.update("pc-b", DirectLinkIdentity.connected("Direct", "CC:C5:30:08:CF:12", now,
                "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        var tracker = new RealtimeDistanceTracker(registry, List::of, Clock.fixed(now, ZoneOffset.UTC));
        tracker.recordStationSamples(List.of(new LinuxP2pStationCollector.StationSample(
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", -55, now)));

        // Intermediate empty scan should not wipe out the fresh sample
        tracker.recordStationSamples(List.of());
        var probe = tracker.recordProbe("pc-b", 5.0, 128);

        assertTrue(probe.hasPhysicalDistance());
        assertEquals(DirectLinkStatus.CONNECTED, probe.directLinkStatus());
    }

    @Test void stopsPublishingPhysicalDistanceAfterDirectRssiExpires() {
        var time = new AtomicReference<>(Instant.parse("2026-10-24T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var registry = new DirectLinkRegistry(clock);
        registry.update("pc-b", DirectLinkIdentity.connected("Direct", "CC:C5:30:08:CF:12", time.get(),
                "1.1.0", Set.of("WIFI_DIRECT_IDENTITY_V1")));
        var tracker = new RealtimeDistanceTracker(registry, List::of, clock);
        tracker.recordStationSamples(List.of(new LinuxP2pStationCollector.StationSample(
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", -55, time.get())));
        assertTrue(tracker.recordProbe("pc-b", 5.0, 128).hasPhysicalDistance());

        time.set(time.get().plusSeconds(31));

        DistanceTelemetry expired = tracker.getOrProbe("pc-b", null, true);
        assertFalse(expired.hasPhysicalDistance());
        assertEquals(DirectLinkStatus.STALE_SIGNAL, expired.directLinkStatus());
    }
}
