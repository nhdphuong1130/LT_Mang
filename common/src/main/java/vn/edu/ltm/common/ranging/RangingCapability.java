package vn.edu.ltm.common.ranging;

import java.util.Objects;

public record RangingCapability(boolean playbackSupported, boolean captureSupported,
                                int sampleRateHz, double testedStartHz, double testedEndHz,
                                String playbackMixer, String captureMixer, RangingStatus status) {
    public RangingCapability {
        playbackMixer = Objects.requireNonNull(playbackMixer).strip();
        captureMixer = Objects.requireNonNull(captureMixer).strip();
        Objects.requireNonNull(status);
        if (sampleRateHz <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        if (testedStartHz < 0 || testedEndHz <= testedStartHz || testedEndHz >= sampleRateHz / 2.0)
            throw new IllegalArgumentException("Invalid tested frequency band");
        if (status == RangingStatus.ACOUSTIC_READY && (!playbackSupported || !captureSupported))
            throw new IllegalArgumentException("Ready capability requires playback and capture");
    }
}
