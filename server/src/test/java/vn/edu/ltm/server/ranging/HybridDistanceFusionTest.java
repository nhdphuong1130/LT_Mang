package vn.edu.ltm.server.ranging;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.DirectLinkStatus;
import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.ranging.AcousticRangingConfig;
import vn.edu.ltm.common.ranging.RangingStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class HybridDistanceFusionTest {

    @Test
    void highConfidenceAcousticBecomesPrimaryMeasurement() {
        var config = defaultConfig();
        var fusion = new HybridDistanceFusion(config);
        long now = System.currentTimeMillis();

        DistanceTelemetry wifi = new DistanceTelemetry(-55.0, -54.0, 80, 2.8, 12.0, 1.5, 128, 1,
                "TRONG TẦM (1-3 m)", "WIFI_DIRECT_KALMAN", "WIFI_DIRECT_SERVER_STATION",
                DirectLinkStatus.CONNECTED, "p2p0", "AA:BB:CC:DD:EE:FF", Instant.now(), "calib-1", true);

        RangingSample acoustic = new RangingSample("dev-1", "s-1", 1.25, 125, 96.0,
                RangingStatus.ACOUSTIC_READY, 24.0, 4.5, 0.0, -60.0, now);

        DistanceTelemetry fused = fusion.fuse("dev-1", acoustic, wifi, now);

        assertEquals(1.25, fused.estimatedMeters(), 0.001);
        assertEquals("HYBRID_ACOUSTIC", fused.method());
        assertEquals("ACOUSTIC_ULTRASONIC", fused.measurementSource());
        assertTrue(fused.hasPhysicalDistance(), "Acoustic ultrasonic telemetry must be recognized as physical distance");
        assertTrue(fused.badgeText().contains("Âm học"));
        assertTrue(fused.zoneLabel().contains("125 cm") || fused.zoneLabel().contains("1.25 m"));
        assertEquals(wifi.rttMs(), fused.rttMs());
        assertEquals(wifi.rssiFilteredDbm(), fused.rssiFilteredDbm());
    }

    @Test
    void lowConfidenceFallsBackToWifiDirectWithoutAveraging() {
        var config = defaultConfig();
        var fusion = new HybridDistanceFusion(config);
        long now = System.currentTimeMillis();

        DistanceTelemetry wifi = new DistanceTelemetry(-65.0, -64.0, 60, 3.5, 15.0, 2.0, 128, 1,
                "XA (>3 m)", "WIFI_DIRECT_KALMAN", "WIFI_DIRECT_SERVER_STATION",
                DirectLinkStatus.CONNECTED, "p2p0", "AA:BB:CC:DD:EE:FF", Instant.now(), "calib-1", true);

        // Acoustic confidence 75% (< 90 threshold)
        RangingSample lowConfAcoustic = new RangingSample("dev-1", "s-2", 1.80, 180, 75.0,
                RangingStatus.VERIFYING, 14.0, 2.6, 12.0, -45.0, now);

        DistanceTelemetry fused = fusion.fuse("dev-1", lowConfAcoustic, wifi, now);

        assertEquals(3.5, fused.estimatedMeters(), 0.001, "Must not average acoustic (1.80) and wifi (3.5)");
        assertTrue(fused.method().contains("WIFI_FALLBACK"));
        assertEquals("WIFI_DIRECT_SERVER_STATION", fused.measurementSource());
    }

    @Test
    void staleAcousticSampleFallsBackToWifi() {
        var config = defaultConfig();
        var fusion = new HybridDistanceFusion(config);
        long now = System.currentTimeMillis();

        DistanceTelemetry wifi = new DistanceTelemetry(-50.0, -50.0, 90, 1.0, 10.0, 1.0, 128, 1,
                "GẦN (<1 m)", "WIFI_DIRECT_KALMAN", "WIFI_DIRECT_SERVER_STATION",
                DirectLinkStatus.CONNECTED, "p2p0", "AA:BB:CC:DD:EE:FF", Instant.now(), "calib-1", true);

        // Acoustic sample is 10 seconds old (measurement interval is 300 ms)
        long tenSecondsAgo = now - 10_000L;
        RangingSample staleSample = new RangingSample("dev-1", "s-old", 1.10, 110, 98.0,
                RangingStatus.ACOUSTIC_READY, 28.0, 5.0, 0.0, -65.0, tenSecondsAgo);

        DistanceTelemetry fused = fusion.fuse("dev-1", staleSample, wifi, now);

        assertEquals(1.0, fused.estimatedMeters(), 0.001);
        assertTrue(fused.method().contains("WIFI_FALLBACK"));
    }

    @Test
    void unsupportedHardwareYieldsWifiFallbackWithExplicitStatus() {
        var config = defaultConfig();
        var fusion = new HybridDistanceFusion(config);
        long now = System.currentTimeMillis();

        DistanceTelemetry wifi = new DistanceTelemetry(-50.0, -50.0, 90, 1.0, 10.0, 1.0, 128, 1,
                "GẦN (<1 m)", "WIFI_DIRECT_KALMAN", "WIFI_DIRECT_SERVER_STATION",
                DirectLinkStatus.CONNECTED, "p2p0", "AA:BB:CC:DD:EE:FF", Instant.now(), "calib-1", true);

        RangingSample unsupported = RangingSample.failed("dev-1", "", RangingStatus.AUDIO_UNSUPPORTED);
        DistanceTelemetry fused = fusion.fuse("dev-1", unsupported, wifi, now);

        assertEquals(1.0, fused.estimatedMeters(), 0.001);
        assertTrue(fused.method().contains("AUDIO_UNSUPPORTED"));
    }

    private static AcousticRangingConfig defaultConfig() {
        return AcousticRangingConfig.builder()
                .enabled(true).playbackMixer("").captureMixer("")
                .sampleRateHz(96_000).chirpStartHz(20_500).chirpEndHz(22_500)
                .playbackAmplitude(0.5)
                .chirpDurationMs(20).captureDurationMs(150).responseDelayMs(30)
                .measurementIntervalMs(300).maxRetries(1).rollingWindow(5)
                .confidenceThreshold(90).minSnrDb(12).minPeakRatio(2.5)
                .targetSnrDb(20).targetPeakRatio(4.0).maxTwoWayErrorSamples(50)
                .audibleCeilingHz(20_000).maxAudibleLeakageDb(-40)
                .maxDistanceMeters(10).ambientTemperatureCelsius(25)
                .calibrationOffsetMillis(0).maxJumpMeters(1.5)
                .snrWeight(0.30).peakWeight(0.25).twoWayWeight(0.20)
                .stabilityWeight(0.15).hardwareWeight(0.10)
                .build();
    }
}
