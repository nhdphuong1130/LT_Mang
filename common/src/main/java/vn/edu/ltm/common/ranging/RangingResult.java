package vn.edu.ltm.common.ranging;

import java.util.List;
import java.util.Objects;

/** Derived features only; intentionally has no raw-audio or PCM field. */
public record RangingResult(String sessionId, long selfPeakSample, long peerPeakSample,
                            int sampleRateHz, double snrDb, double peakRatio,
                            double audibleLeakageDb, double twoWayErrorSamples,
                            double rollingStability, boolean hardwarePassed,
                            RangingStatus status, List<String> qualityFlags,
                            long measuredAtEpochMillis) {
    public RangingResult {
        Objects.requireNonNull(sessionId);
        if (sessionId.isBlank()) throw new IllegalArgumentException("Session ID is required");
        sessionId = sessionId.strip();
        Objects.requireNonNull(status);
        qualityFlags = List.copyOf(Objects.requireNonNull(qualityFlags));
        if (selfPeakSample < 0 || peerPeakSample < 0) throw new IllegalArgumentException("Peak indices cannot be negative");
        if (sampleRateHz <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        if (!Double.isFinite(snrDb) || !Double.isFinite(peakRatio) || peakRatio < 0
                || !Double.isFinite(audibleLeakageDb) || !Double.isFinite(twoWayErrorSamples)
                || !Double.isFinite(rollingStability) || rollingStability < 0 || rollingStability > 1)
            throw new IllegalArgumentException("Invalid ranging quality metrics");
        if (measuredAtEpochMillis < 0) throw new IllegalArgumentException("Measurement time cannot be negative");
    }
}
