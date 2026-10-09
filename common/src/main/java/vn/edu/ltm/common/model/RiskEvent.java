package vn.edu.ltm.common.model;

import java.time.Instant;
import java.util.Objects;

public record RiskEvent(RiskType riskType, Status status, Instant occurredAt, String message) {
    public enum Status { OPEN, UPDATE, RESOLVED }
    public RiskEvent {
        Objects.requireNonNull(riskType, "riskType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Risk message is required");
    }
}
