package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinearChirpGeneratorTest {
    @Test void createsConfiguredLengthBoundedAndTaperedWaveform() {
        var config = AcousticRangingConfigTest.validBuilder().build();
        double[] chirp = new LinearChirpGenerator(config).generate();

        assertEquals(config.sampleRateHz() * config.chirpDurationMs() / 1_000, chirp.length);
        assertEquals(0.0, chirp[0], 1.0e-12);
        assertEquals(0.0, chirp[chirp.length - 1], 1.0e-12);
        for (double sample : chirp) assertTrue(Math.abs(sample) <= config.playbackAmplitude() + 1.0e-12);
        assertTrue(java.util.Arrays.stream(chirp).anyMatch(sample -> Math.abs(sample) > 0.1));
    }
}
