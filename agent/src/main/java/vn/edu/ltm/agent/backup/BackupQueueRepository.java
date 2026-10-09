package vn.edu.ltm.agent.backup;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public interface BackupQueueRepository {
    void enqueueOrUpdate(BackupCandidate candidate) throws SQLException;
    /** Atomically claims due PENDING/RETRY rows as UPLOADING. */
    List<BackupQueueItem> nextReady(int limit) throws SQLException;
    /** Call only after the Server acknowledges this checksum. */
    void markUploaded(long id, String sha256) throws SQLException;
    void markRetry(long id, Instant nextAttempt, String error) throws SQLException;
}
