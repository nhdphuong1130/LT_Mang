package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioHardwareGateTest {

    @Test
    void disabledConfigYieldsConfigDisabledStatus() {
        var config = AcousticRangingConfigTest.validBuilder().enabled(false).build();
        var mockEndpoint = new StubAudioEndpoint(true, true);
        var gate = new AudioHardwareGate(config, mockEndpoint);

        RangingCapability cap = gate.evaluate();
        assertEquals(RangingStatus.CONFIG_DISABLED, cap.status());
    }

    @Test
    void unsupportedLinesYieldAudioUnsupportedStatus() {
        var config = AcousticRangingConfigTest.validBuilder().enabled(true).build();
        var mockEndpoint = new StubAudioEndpoint(false, true);
        var gate = new AudioHardwareGate(config, mockEndpoint);

        RangingCapability cap = gate.evaluate();
        assertEquals(RangingStatus.AUDIO_UNSUPPORTED, cap.status());
        assertFalse(cap.playbackSupported());
    }

    @Test
    void audibleLeakageMeasurementDetectsAudibleTone() {
        int sampleRate = 96_000;
        double audibleCeiling = 20_000.0;
        int count = 4096;
        // Injected audible tone at 1000 Hz
        double[] audibleSamples = new double[count];
        for (int i = 0; i < count; i++) {
            audibleSamples[i] = Math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate);
        }

        double leakageDb = AudioHardwareGate.measureAudibleLeakageDb(audibleSamples, sampleRate, audibleCeiling);
        assertTrue(leakageDb > -6.0, "Audible signal should have high audible leakage, got: " + leakageDb);
    }

    @Test
    void ultrasonicChirpHasLowAudibleLeakage() {
        var config = AcousticRangingConfigTest.validBuilder().build();
        double[] chirp = new LinearChirpGenerator(config).generate();
        double leakageDb = AudioHardwareGate.measureAudibleLeakageDb(chirp, config.sampleRateHz(), config.audibleCeilingHz());
        assertTrue(leakageDb <= config.maxAudibleLeakageDb(),
                "Pure ultrasonic chirp should be below max audible leakage: " + leakageDb);
    }

    @Test
    void evaluateDetectsAudibleLeakageAndReportsStatus() {
        // Build config with an audible chirp (starting at 15 kHz, below ceiling 20 kHz)
        // AcousticRangingConfig rejects start <= ceiling, but we test gate rejection when chirp leaks
        var config = AcousticRangingConfigTest.validBuilder().maxAudibleLeakageDb(-50.0).build();
        // A mock endpoint whose loopback introduces audible noise
        var noisyEndpoint = new StubAudioEndpoint(true, true) {
            @Override
            public double[] capture(int durationMs) {
                int n = durationMs * config.sampleRateHz() / 1000;
                double[] out = new double[n];
                // 1 kHz hum
                for (int i = 0; i < n; i++) {
                    out[i] = 0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / config.sampleRateHz());
                }
                return out;
            }
        };

        var gate = new AudioHardwareGate(config, noisyEndpoint);
        RangingCapability cap = gate.evaluate(true);
        assertEquals(RangingStatus.AUDIBLE_LEAKAGE, cap.status());
    }

    @Test
    void cleanUltrasonicSetupPassesAsAcousticReady() {
        var config = AcousticRangingConfigTest.validBuilder().build();
        var mockEndpoint = new StubAudioEndpoint(true, true);
        var gate = new AudioHardwareGate(config, mockEndpoint);

        RangingCapability cap = gate.evaluate();
        assertEquals(RangingStatus.ACOUSTIC_READY, cap.status());
        assertTrue(cap.playbackSupported());
        assertTrue(cap.captureSupported());
        assertEquals(config.sampleRateHz(), cap.sampleRateHz());
    }

    private static class StubAudioEndpoint implements AudioEndpoint {
        private final boolean playback;
        private final boolean capture;

        StubAudioEndpoint(boolean playback, boolean capture) {
            this.playback = playback;
            this.capture = capture;
        }

        @Override public void play(double[] samples) {}
        @Override public double[] capture(int durationMs) {
            return new double[durationMs * 96000 / 1000];
        }
        @Override public double[] playAndCapture(double[] samplesToPlay, int captureDurationMs) {
            return capture(captureDurationMs);
        }
        @Override public boolean isPlaybackSupported() { return playback; }
        @Override public boolean isCaptureSupported() { return capture; }
        @Override public void close() {}
    }
}
