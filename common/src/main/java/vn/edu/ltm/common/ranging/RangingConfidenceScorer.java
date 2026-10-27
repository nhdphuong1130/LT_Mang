package vn.edu.ltm.common.ranging;

import java.util.Objects;

/** Applies hard safety gates before calculating the configured quality score. */
public final class RangingConfidenceScorer {
    private final AcousticRangingConfig config;

    public RangingConfidenceScorer(AcousticRangingConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public Score score(RangingResult result) {
        Objects.requireNonNull(result);
        if (result.status() != RangingStatus.ACOUSTIC_READY)
            return new Score(0, false, result.status());
        if (!config.enabled()) return new Score(0, false, RangingStatus.CONFIG_DISABLED);
        if (!result.hardwarePassed()) return new Score(0, false, RangingStatus.AUDIO_UNSUPPORTED);
        if (result.audibleLeakageDb() > config.maxAudibleLeakageDb())
            return new Score(0, false, RangingStatus.AUDIBLE_LEAKAGE);

        double snr = normalized(result.snrDb(), config.minSnrDb(), config.targetSnrDb());
        double peak = normalized(result.peakRatio(), config.minPeakRatio(), config.targetPeakRatio());
        double agreement = clamp01(1.0 - Math.abs(result.twoWayErrorSamples()) / config.maxTwoWayErrorSamples());
        double confidence = 100.0 * (snr * config.snrWeight() + peak * config.peakWeight()
                + agreement * config.twoWayWeight() + result.rollingStability() * config.stabilityWeight()
                + config.hardwareWeight());

        RangingStatus status;
        if (result.snrDb() < config.minSnrDb()) status = RangingStatus.LOW_SNR;
        else if (result.peakRatio() < config.minPeakRatio()) status = RangingStatus.MULTIPATH_AMBIGUOUS;
        else if (Math.abs(result.twoWayErrorSamples()) > config.maxTwoWayErrorSamples()) status = RangingStatus.VERIFYING;
        else status = RangingStatus.ACOUSTIC_READY;
        boolean accepted = status == RangingStatus.ACOUSTIC_READY
                && confidence >= config.confidenceThreshold();
        if (!accepted && status == RangingStatus.ACOUSTIC_READY) status = RangingStatus.VERIFYING;
        return new Score(confidence, accepted, status);
    }

    private static double normalized(double value, double minimum, double target) {
        return clamp01((value - minimum) / (target - minimum));
    }

    private static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }

    public record Score(double confidence, boolean accepted, RangingStatus status) {
        public Score { Objects.requireNonNull(status); }
    }
}
