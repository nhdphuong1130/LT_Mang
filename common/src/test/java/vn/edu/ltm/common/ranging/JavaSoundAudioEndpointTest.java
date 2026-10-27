package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaSoundAudioEndpointTest {

    @Test
    void pcmRoundTripPreservesSamplesAccurately() {
        double[] original = new double[]{-1.0, -0.75, -0.5, 0.0, 0.25, 0.5, 0.75, 0.999};
        byte[] pcm = JavaSoundAudioEndpoint.doublesToPcm(original);
        assertEquals(original.length * 2, pcm.length);

        double[] recovered = JavaSoundAudioEndpoint.pcmToDoubles(pcm);
        assertEquals(original.length, recovered.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], recovered[i], 0.001, "Sample mismatch at index " + i);
        }
    }

    @Test
    void pcmConversionClampsOutOfBoundsValues() {
        double[] outOfBounds = new double[]{-1.5, 1.5};
        byte[] pcm = JavaSoundAudioEndpoint.doublesToPcm(outOfBounds);
        double[] recovered = JavaSoundAudioEndpoint.pcmToDoubles(pcm);
        assertEquals(-1.0, recovered[0], 0.001);
        assertEquals(1.0, recovered[1], 0.001);
    }

    @Test
    void rejectsEmptyOrNegativeCaptureDuration() {
        var config = AcousticRangingConfigTest.validBuilder().build();
        try (var endpoint = new JavaSoundAudioEndpoint(config)) {
            assertThrows(IllegalArgumentException.class, () -> endpoint.capture(-10));
            assertThrows(IllegalArgumentException.class, () -> endpoint.capture(0));
            assertThrows(IllegalArgumentException.class, () -> endpoint.play(null));
        }
    }

    @Test
    void closeIsSafeAndIdempotent() {
        var config = AcousticRangingConfigTest.validBuilder().build();
        var endpoint = new JavaSoundAudioEndpoint(config);
        assertDoesNotThrow(endpoint::close);
        assertDoesNotThrow(endpoint::close);
    }
}
