package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AcousticRangingConfigTest {
    @Test void acceptsAConsistentConfiguration() {
        assertDoesNotThrow(() -> validBuilder().build());
    }

    @Test void rejectsChirpInAudibleBand() {
        var builder = validBuilder().chirpStartHz(19_500);
        assertTrue(assertThrows(IllegalArgumentException.class, builder::build)
                .getMessage().contains("audible ceiling"));
    }

    @Test void rejectsChirpAtOrAboveNyquist() {
        var builder = validBuilder().chirpEndHz(48_000);
        assertTrue(assertThrows(IllegalArgumentException.class, builder::build)
                .getMessage().contains("Nyquist"));
    }

    @Test void rejectsInvalidThresholdAndWeights() {
        assertThrows(IllegalArgumentException.class,
                validBuilder().confidenceThreshold(101)::build);
        assertThrows(IllegalArgumentException.class,
                validBuilder().snrWeight(0.31)::build);
    }

    @Test void rejectsCaptureWindowTooShortForMaximumDistance() {
        var builder = validBuilder().captureDurationMs(20).maxDistanceMeters(20);
        assertTrue(assertThrows(IllegalArgumentException.class, builder::build)
                .getMessage().contains("capture window"));
    }

    static AcousticRangingConfig.Builder validBuilder() {
        return AcousticRangingConfig.builder()
                .enabled(true).playbackMixer("").captureMixer("")
                .sampleRateHz(96_000).chirpStartHz(20_500).chirpEndHz(22_500)
                .chirpDurationMs(20).captureDurationMs(250).responseDelayMs(50)
                .measurementIntervalMs(500).maxRetries(2).rollingWindow(5)
                .confidenceThreshold(90).minSnrDb(12).minPeakRatio(2.5)
                .audibleCeilingHz(20_000).maxAudibleLeakageDb(-40)
                .maxDistanceMeters(10).ambientTemperatureCelsius(25)
                .calibrationOffsetMillis(0).maxJumpMeters(1.5)
                .snrWeight(0.30).peakWeight(0.25).twoWayWeight(0.20)
                .stabilityWeight(0.15).hardwareWeight(0.10);
    }
}
