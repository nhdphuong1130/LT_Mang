package vn.edu.ltm.common.ranging;

import java.util.Objects;

/** Converts an acoustic round trip into distance after configured turnaround/latency removal. */
public final class TwoWayDistanceCalculator {
    private final AcousticRangingConfig config;

    public TwoWayDistanceCalculator(AcousticRangingConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public double distanceMeters(long roundTripSamples) {
        if (roundTripSamples <= 0) throw new IllegalArgumentException("Round-trip samples must be positive");
        double elapsedMillis = roundTripSamples * 1_000.0 / config.sampleRateHz();
        double acousticMillis = elapsedMillis - config.responseDelayMs() - config.calibrationOffsetMillis();
        if (acousticMillis <= 0) throw new IllegalArgumentException("Round trip is shorter than configured latency");
        double distance = acousticMillis * config.speedOfSoundMetersPerSecond() / 2_000.0;
        if (distance > config.maxDistanceMeters())
            throw new IllegalArgumentException("Distance exceeds configured maximum");
        return distance;
    }

    public long distanceCentimeters(long roundTripSamples) {
        return Math.round(distanceMeters(roundTripSamples) * 100.0);
    }
}
