package vn.edu.ltm.common.ranging;

import java.util.Objects;

public record RangingRequest(String sessionId, String waveformId, int sampleRateHz,
                             double chirpStartHz, double chirpEndHz, int chirpDurationMs,
                             int captureDurationMs, int responseDelayMs, String nonce) {
    public RangingRequest {
        sessionId = required(sessionId, "session ID");
        waveformId = required(waveformId, "waveform ID");
        nonce = required(nonce, "nonce");
        if (sampleRateHz <= 0 || chirpStartHz < 0 || chirpEndHz <= chirpStartHz
                || chirpEndHz >= sampleRateHz / 2.0)
            throw new IllegalArgumentException("Invalid request frequency configuration");
        if (chirpDurationMs <= 0 || captureDurationMs <= 0 || responseDelayMs < 0)
            throw new IllegalArgumentException("Invalid request timing");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }
}
