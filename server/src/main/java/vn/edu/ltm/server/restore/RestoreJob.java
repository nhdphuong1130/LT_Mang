package vn.edu.ltm.server.restore;

import java.time.Instant;
import java.util.UUID;

public record RestoreJob(UUID id, long backupVersionId, String deviceId, String adminUsername,
                         Instant expiresAt, Status status, Instant requestedAt, Instant completedAt,
                         String error) {
    public enum Status { PENDING, AVAILABLE, IN_PROGRESS, COMPLETED, FAILED }
}
