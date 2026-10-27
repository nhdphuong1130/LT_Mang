package vn.edu.ltm.common.ranging;

/** All acoustic-ranging tunables, validated once before any audio work starts. */
public record AcousticRangingConfig(
        boolean enabled, String playbackMixer, String captureMixer,
        int sampleRateHz, double chirpStartHz, double chirpEndHz,
        int chirpDurationMs, int captureDurationMs, int responseDelayMs,
        int measurementIntervalMs, int maxRetries, int rollingWindow,
        double confidenceThreshold, double minSnrDb, double minPeakRatio,
        double audibleCeilingHz, double maxAudibleLeakageDb,
        double maxDistanceMeters, double ambientTemperatureCelsius,
        double calibrationOffsetMillis, double maxJumpMeters,
        double snrWeight, double peakWeight, double twoWayWeight,
        double stabilityWeight, double hardwareWeight) {

    private static final double WEIGHT_EPSILON = 1.0e-9;

    public AcousticRangingConfig {
        playbackMixer = playbackMixer == null ? "" : playbackMixer.strip();
        captureMixer = captureMixer == null ? "" : captureMixer.strip();
        require(sampleRateHz > 0, "Sample rate must be positive");
        require(audibleCeilingHz >= 0, "Audible ceiling cannot be negative");
        require(chirpStartHz > audibleCeilingHz, "Chirp start must exceed the audible ceiling");
        require(chirpEndHz > chirpStartHz, "Chirp end must exceed chirp start");
        require(chirpEndHz < sampleRateHz / 2.0, "Chirp end must be below the Nyquist frequency");
        require(chirpDurationMs > 0 && captureDurationMs > 0 && responseDelayMs >= 0,
                "Audio durations must be positive");
        require(measurementIntervalMs > 0, "Measurement interval must be positive");
        require(maxRetries >= 0, "Retry count cannot be negative");
        require(rollingWindow > 0, "Rolling window must be positive");
        require(confidenceThreshold >= 0 && confidenceThreshold <= 100,
                "Confidence threshold must be in [0, 100]");
        require(minPeakRatio > 1, "Minimum peak ratio must exceed one");
        require(maxDistanceMeters > 0 && maxJumpMeters > 0, "Distances must be positive");
        require(ambientTemperatureCelsius >= -50 && ambientTemperatureCelsius <= 60,
                "Ambient temperature is outside the supported range");
        require(snrWeight >= 0 && peakWeight >= 0 && twoWayWeight >= 0
                        && stabilityWeight >= 0 && hardwareWeight >= 0,
                "Confidence weights cannot be negative");
        double weightSum = snrWeight + peakWeight + twoWayWeight + stabilityWeight + hardwareWeight;
        require(Math.abs(weightSum - 1.0) <= WEIGHT_EPSILON, "Confidence weights must sum to one");
        double minimumCaptureMs = chirpDurationMs + responseDelayMs
                + 2_000.0 * maxDistanceMeters / speedOfSoundMetersPerSecond();
        require(captureDurationMs >= minimumCaptureMs,
                "Configured capture window is too short for the maximum distance");
    }

    public double speedOfSoundMetersPerSecond() {
        return 331.3 + 0.606 * ambientTemperatureCelsius;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean enabled;
        private String playbackMixer;
        private String captureMixer;
        private int sampleRateHz;
        private double chirpStartHz;
        private double chirpEndHz;
        private int chirpDurationMs;
        private int captureDurationMs;
        private int responseDelayMs;
        private int measurementIntervalMs;
        private int maxRetries;
        private int rollingWindow;
        private double confidenceThreshold;
        private double minSnrDb;
        private double minPeakRatio;
        private double audibleCeilingHz;
        private double maxAudibleLeakageDb;
        private double maxDistanceMeters;
        private double ambientTemperatureCelsius;
        private double calibrationOffsetMillis;
        private double maxJumpMeters;
        private double snrWeight;
        private double peakWeight;
        private double twoWayWeight;
        private double stabilityWeight;
        private double hardwareWeight;

        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder playbackMixer(String value) { playbackMixer = value; return this; }
        public Builder captureMixer(String value) { captureMixer = value; return this; }
        public Builder sampleRateHz(int value) { sampleRateHz = value; return this; }
        public Builder chirpStartHz(double value) { chirpStartHz = value; return this; }
        public Builder chirpEndHz(double value) { chirpEndHz = value; return this; }
        public Builder chirpDurationMs(int value) { chirpDurationMs = value; return this; }
        public Builder captureDurationMs(int value) { captureDurationMs = value; return this; }
        public Builder responseDelayMs(int value) { responseDelayMs = value; return this; }
        public Builder measurementIntervalMs(int value) { measurementIntervalMs = value; return this; }
        public Builder maxRetries(int value) { maxRetries = value; return this; }
        public Builder rollingWindow(int value) { rollingWindow = value; return this; }
        public Builder confidenceThreshold(double value) { confidenceThreshold = value; return this; }
        public Builder minSnrDb(double value) { minSnrDb = value; return this; }
        public Builder minPeakRatio(double value) { minPeakRatio = value; return this; }
        public Builder audibleCeilingHz(double value) { audibleCeilingHz = value; return this; }
        public Builder maxAudibleLeakageDb(double value) { maxAudibleLeakageDb = value; return this; }
        public Builder maxDistanceMeters(double value) { maxDistanceMeters = value; return this; }
        public Builder ambientTemperatureCelsius(double value) { ambientTemperatureCelsius = value; return this; }
        public Builder calibrationOffsetMillis(double value) { calibrationOffsetMillis = value; return this; }
        public Builder maxJumpMeters(double value) { maxJumpMeters = value; return this; }
        public Builder snrWeight(double value) { snrWeight = value; return this; }
        public Builder peakWeight(double value) { peakWeight = value; return this; }
        public Builder twoWayWeight(double value) { twoWayWeight = value; return this; }
        public Builder stabilityWeight(double value) { stabilityWeight = value; return this; }
        public Builder hardwareWeight(double value) { hardwareWeight = value; return this; }

        public AcousticRangingConfig build() {
            return new AcousticRangingConfig(enabled, playbackMixer, captureMixer, sampleRateHz,
                    chirpStartHz, chirpEndHz, chirpDurationMs, captureDurationMs, responseDelayMs,
                    measurementIntervalMs, maxRetries, rollingWindow, confidenceThreshold, minSnrDb,
                    minPeakRatio, audibleCeilingHz, maxAudibleLeakageDb, maxDistanceMeters,
                    ambientTemperatureCelsius, calibrationOffsetMillis, maxJumpMeters, snrWeight,
                    peakWeight, twoWayWeight, stabilityWeight, hardwareWeight);
        }
    }
}
