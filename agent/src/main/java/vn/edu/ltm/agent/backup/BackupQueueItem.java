package vn.edu.ltm.agent.backup;

import java.time.Instant;

public record BackupQueueItem(long id, BackupCandidate candidate, Status status,
                              int attempts, Instant nextAttempt, String lastError) {
    public enum Status { PENDING, UPLOADING, RETRY }
}
