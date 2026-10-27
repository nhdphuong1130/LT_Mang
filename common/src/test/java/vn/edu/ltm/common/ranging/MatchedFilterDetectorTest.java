package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MatchedFilterDetectorTest {
    @Test void findsKnownDelayThroughNoise() {
        var config = AcousticRangingConfigTest.validBuilder().minSnrDb(8).minPeakRatio(1.8).build();
        double[] chirp = new LinearChirpGenerator(config).generate();
        double[] recording = noise(chirp.length * 4, 0.01, 42);
        add(recording, chirp, 1_337, 1.0);

        ChirpDetection detection = new MatchedFilterDetector(config).detect(recording, chirp);

        assertTrue(detection.accepted());
        assertEquals(1_337, detection.sampleIndex());
        assertTrue(detection.snrDb() >= config.minSnrDb());
        assertTrue(detection.peakRatio() >= config.minPeakRatio());
    }

    @Test void rejectsTwoComparableSeparatedPathsAsAmbiguous() {
        var config = AcousticRangingConfigTest.validBuilder().minSnrDb(8).minPeakRatio(2.0).build();
        double[] chirp = new LinearChirpGenerator(config).generate();
        double[] recording = noise(chirp.length * 5, 0.002, 7);
        add(recording, chirp, 300, 1.0);
        add(recording, chirp, 300 + chirp.length + 50, 0.92);

        ChirpDetection detection = new MatchedFilterDetector(config).detect(recording, chirp);

        assertFalse(detection.accepted());
        assertEquals(300, detection.sampleIndex());
        assertTrue(detection.peakRatio() < config.minPeakRatio());
    }

    @Test void rejectsRecordingShorterThanReference() {
        var config = AcousticRangingConfigTest.validBuilder().build();
        double[] chirp = new LinearChirpGenerator(config).generate();
        assertThrows(IllegalArgumentException.class,
                () -> new MatchedFilterDetector(config).detect(new double[chirp.length - 1], chirp));
    }

    private static double[] noise(int length, double amplitude, long seed) {
        var random = new Random(seed);
        double[] result = new double[length];
        for (int i = 0; i < length; i++) result[i] = random.nextGaussian() * amplitude;
        return result;
    }

    private static void add(double[] target, double[] signal, int offset, double scale) {
        for (int i = 0; i < signal.length; i++) target[offset + i] += signal[i] * scale;
    }
}
