package vn.edu.ltm.common.ranging;

import java.util.Objects;

/** Generates a Hann-tapered linear frequency sweep from validated configuration. */
public final class LinearChirpGenerator {
    private final AcousticRangingConfig config;

    public LinearChirpGenerator(AcousticRangingConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public double[] generate() {
        int count = Math.multiplyExact(config.sampleRateHz(), config.chirpDurationMs()) / 1_000;
        if (count < 2) throw new IllegalArgumentException("Configured chirp contains fewer than two samples");
        double[] result = new double[count];
        double durationSeconds = config.chirpDurationMs() / 1_000.0;
        double sweepRate = (config.chirpEndHz() - config.chirpStartHz()) / durationSeconds;
        for (int sample = 0; sample < count; sample++) {
            double seconds = sample / (double) config.sampleRateHz();
            double phase = 2.0 * Math.PI
                    * (config.chirpStartHz() * seconds + sweepRate * seconds * seconds / 2.0);
            double window = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * sample / (count - 1.0)));
            result[sample] = config.playbackAmplitude() * window * Math.sin(phase);
        }
        return result;
    }
}
