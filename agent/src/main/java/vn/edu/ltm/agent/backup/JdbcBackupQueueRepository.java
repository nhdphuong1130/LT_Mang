package vn.edu.ltm.agent.backup;

import vn.edu.ltm.agent.persistence.AgentDatabase;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class JdbcBackupQueueRepository implements BackupQueueRepository {
    private final AgentDatabase database;
    private final Clock clock;

    public JdbcBackupQueueRepository(AgentDatabase database) { this(database, Clock.systemUTC()); }
    public JdbcBackupQueueRepository(AgentDatabase database, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public void enqueueOrUpdate(BackupCandidate candidate) throws SQLException {
        Objects.requireNonNull(candidate);
        transaction(connection -> {
            // Never suppress a queued reversion: a previous transfer may have reached the
            // Server while its acknowledgment was lost. Only an idle uploaded path is unchanged.
            try (var statement = connection.prepareStatement("""
                    INSERT INTO backup_queue(relative_path, size_bytes, modified_at, sha256, status)
                    SELECT ?, ?, ?, ?, 'PENDING'
                    WHERE NOT EXISTS (SELECT 1 FROM uploaded_files WHERE relative_path = ? AND sha256 = ?)
                       OR EXISTS (SELECT 1 FROM backup_queue WHERE relative_path = ?)
                    ON CONFLICT(relative_path) DO UPDATE SET
                        size_bytes = excluded.size_bytes, modified_at = excluded.modified_at, sha256 = excluded.sha256,
                        status = CASE WHEN backup_queue.status = 'UPLOADING' THEN 'UPLOADING' ELSE 'PENDING' END,
                        next_attempt = 0, attempts = 0, last_error = NULL
                    WHERE backup_queue.sha256 <> excluded.sha256
                    """)) {
                statement.setString(1, candidate.relativePath());
                statement.setLong(2, candidate.size());
                statement.setString(3, candidate.modifiedAt().toString());
                statement.setString(4, candidate.sha256());
                statement.setString(5, candidate.relativePath());
                statement.setString(6, candidate.sha256());
                statement.setString(7, candidate.relativePath());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public List<BackupQueueItem> nextReady(int limit) throws SQLException {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return transaction(connection -> {
            var items = new ArrayList<BackupQueueItem>();
            try (var claim = connection.prepareStatement("""
                    UPDATE backup_queue SET status = 'UPLOADING', uploading_sha256 = sha256
                    WHERE id IN (SELECT id FROM backup_queue WHERE status IN ('PENDING', 'RETRY')
                        AND next_attempt <= ? ORDER BY id LIMIT ?)
                    RETURNING *
                    """)) {
                claim.setLong(1, clock.millis());
                claim.setInt(2, limit);
                try (var result = claim.executeQuery()) {
                    while (result.next()) items.add(read(result));
                }
            }
            items.sort(Comparator.comparingLong(BackupQueueItem::id));
            return List.copyOf(items);
        });
    }

    @Override public void markUploaded(long id, String sha256) throws SQLException {
        Objects.requireNonNull(sha256);
        transaction(connection -> {
            String path;
            try (var find = connection.prepareStatement("""
                    SELECT relative_path FROM backup_queue
                    WHERE id = ? AND status = 'UPLOADING' AND uploading_sha256 = ?
                    """)) {
                find.setLong(1, id);
                find.setString(2, sha256);
                try (var result = find.executeQuery()) {
                    if (!result.next()) throw new SQLException("No matching upload claim for " + id);
                    path = result.getString(1);
                }
            }
            try (var record = connection.prepareStatement("""
                    INSERT INTO uploaded_files(relative_path, sha256) VALUES (?, ?)
                    ON CONFLICT(relative_path) DO UPDATE SET sha256 = excluded.sha256
                    """)) {
                record.setString(1, path);
                record.setString(2, sha256);
                record.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM backup_queue WHERE id = ? AND sha256 = ?")) {
                delete.setLong(1, id);
                delete.setString(2, sha256);
                delete.executeUpdate();
            }
            // A different current checksum is an edit received while this upload was in flight.
            try (var release = connection.prepareStatement("""
                    UPDATE backup_queue SET status = 'PENDING', uploading_sha256 = NULL,
                        next_attempt = 0, attempts = 0, last_error = NULL WHERE id = ?
                    """)) {
                release.setLong(1, id);
                release.executeUpdate();
            }
            return null;
        });
    }

    @Override public void markRetry(long id, Instant nextAttempt, String error) throws SQLException {
        Objects.requireNonNull(nextAttempt);
        transaction(connection -> {
            try (var retry = connection.prepareStatement("""
                    UPDATE backup_queue SET
                        status = CASE WHEN sha256 = uploading_sha256 THEN 'RETRY' ELSE 'PENDING' END,
                        next_attempt = CASE WHEN sha256 = uploading_sha256 THEN ? ELSE 0 END,
                        attempts = attempts + 1, last_error = ?, uploading_sha256 = NULL
                    WHERE id = ? AND status = 'UPLOADING'
                    """)) {
                retry.setLong(1, nextAttempt.toEpochMilli());
                retry.setString(2, error);
                retry.setLong(3, id);
                if (retry.executeUpdate() != 1) throw new SQLException("No upload claim for " + id);
            }
            return null;
        });
    }

    private static BackupQueueItem read(ResultSet row) throws SQLException {
        return new BackupQueueItem(row.getLong("id"), new BackupCandidate(row.getString("relative_path"),
                row.getLong("size_bytes"), Instant.parse(row.getString("modified_at")), row.getString("sha256")),
                BackupQueueItem.Status.valueOf(row.getString("status")), row.getInt("attempts"),
                Instant.ofEpochMilli(row.getLong("next_attempt")), row.getString("last_error"));
    }

    @FunctionalInterface private interface Transaction<T> { T run(Connection connection) throws SQLException; }

    private <T> T transaction(Transaction<T> action) throws SQLException {
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            // Acquire the write lock before any reads, including concurrent repository instances.
            statement.execute("BEGIN IMMEDIATE");
            try {
                T result = action.run(connection);
                statement.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { statement.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }
}
