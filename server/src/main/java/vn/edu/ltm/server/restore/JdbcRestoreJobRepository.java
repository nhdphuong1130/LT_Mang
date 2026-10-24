package vn.edu.ltm.server.restore;

import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.persistence.SqlTimestamp;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcRestoreJobRepository implements RestoreJobRepository {
    private final Database database;
    public JdbcRestoreJobRepository(Database database) { this.database = Objects.requireNonNull(database); }

    @Override public Optional<BackupMetadata> backup(long versionId) throws SQLException {
        try (var connection = database.openConnection(); var query = connection.prepareStatement("""
                SELECT v.id, f.device_id, f.relative_path, v.size_bytes, v.sha256, v.storage_path
                FROM backup_versions v JOIN backup_files f ON f.id = v.file_id WHERE v.id = ?
                """)) {
            query.setLong(1, versionId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(new BackupMetadata(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getLong(4), rows.getString(5), Path.of(rows.getString(6)))) : Optional.empty();
            }
        }
    }

    @Override public List<Long> latestVersionIdsForFolder(String sourceDeviceId, String pathPrefix) throws SQLException {
        String prefix = pathPrefix == null ? "" : pathPrefix.strip();
        var ids = new ArrayList<Long>();
        try (var connection = database.openConnection(); var query = connection.prepareStatement("""
                SELECT MAX(v.id), f.relative_path
                FROM backup_versions v JOIN backup_files f ON f.id = v.file_id
                WHERE f.device_id = ?
                GROUP BY f.id, f.relative_path
                ORDER BY f.relative_path ASC
                """)) {
            query.setString(1, sourceDeviceId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String rel = rows.getString(2);
                    if (prefix.isEmpty() || rel.equals(prefix) || rel.startsWith(prefix + "/")) {
                        ids.add(rows.getLong(1));
                    }
                }
            }
        }
        return List.copyOf(ids);
    }

    @Override public void create(RestoreJob job) throws SQLException {
        try (var connection = database.openConnection(); var insert = connection.prepareStatement("""
                INSERT INTO restore_jobs(id, backup_version_id, device_id, admin_username, relative_path,
                    size_bytes, sha256, storage_path, token_hash, expires_at, status, requested_at)
                SELECT ?, v.id, ?, ?, f.relative_path, v.size_bytes, v.sha256, v.storage_path,
                    '', ?, 'PENDING', ?
                FROM backup_versions v JOIN backup_files f ON f.id = v.file_id
                WHERE v.id = ? AND EXISTS (SELECT 1 FROM devices d WHERE d.device_id = ?)
                """)) {
            insert.setString(1, job.id().toString());
            insert.setString(2, job.deviceId());
            insert.setString(3, job.adminUsername());
            insert.setString(4, SqlTimestamp.format(job.expiresAt()));
            insert.setString(5, SqlTimestamp.format(job.requestedAt()));
            insert.setLong(6, job.backupVersionId());
            insert.setString(7, job.deviceId());
            if (insert.executeUpdate() != 1) throw new IllegalArgumentException("Backup version or target device is not available");
        }
    }

    @Override public BackupMetadata metadata(UUID jobId) throws SQLException {
        try (var connection = database.openConnection(); var query = connection.prepareStatement("""
                SELECT backup_version_id, device_id, relative_path, size_bytes, sha256, storage_path
                FROM restore_jobs WHERE id = ?
                """)) {
            query.setString(1, jobId.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Restore job does not exist");
                return new BackupMetadata(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getLong(4), rows.getString(5), Path.of(rows.getString(6)));
            }
        }
    }

    @Override public Optional<RestoreJob> find(UUID id) throws SQLException {
        try (var connection = database.openConnection(); var query = connection.prepareStatement("SELECT * FROM restore_jobs WHERE id = ?")) {
            query.setString(1, id.toString());
            try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        }
    }

    @Override public List<RestoreJob> offerable(String deviceId) throws SQLException {
        var jobs = new ArrayList<RestoreJob>();
        try (var connection = database.openConnection(); var query = connection.prepareStatement(
                "SELECT * FROM restore_jobs WHERE device_id = ? AND status IN ('PENDING', 'AVAILABLE') ORDER BY requested_at, id")) {
            query.setString(1, deviceId);
            try (var rows = query.executeQuery()) { while (rows.next()) jobs.add(read(rows)); }
        }
        return List.copyOf(jobs);
    }

    @Override public void recoverExpired(Instant now) throws SQLException {
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("""
                    UPDATE restore_jobs SET status = 'PENDING', token_hash = '', lease_owner = NULL,
                        lease_expires_at = NULL
                    WHERE status = 'IN_PROGRESS' AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                    RETURNING id, device_id
                    """)) {
                update.setString(1, SqlTimestamp.format(now));
                try (var rows = update.executeQuery(); var audit = connection.prepareStatement("""
                        INSERT INTO audit_logs(occurred_at, actor, action, device_id, details) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        String deviceId = rows.getString(2);
                        audit.setString(1, now.toString());
                        audit.setString(2, "server");
                        audit.setString(3, "RESTORE_RECOVERED");
                        audit.setString(4, deviceId);
                        audit.setString(5, "job=" + id);
                        audit.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Override public Optional<OfferSnapshot> offerSnapshot(UUID id) throws SQLException {
        try (var connection = database.openConnection(); var query = connection.prepareStatement("""
                SELECT status, token_hash, expires_at, offered_session_id FROM restore_jobs WHERE id = ?
                """)) {
            query.setString(1, id.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String session = rows.getString(4);
                return Optional.of(new OfferSnapshot(RestoreJob.Status.valueOf(rows.getString(1)), rows.getString(2),
                        Instant.parse(rows.getString(3)), session == null ? null : UUID.fromString(session)));
            }
        }
    }

    @Override public boolean offer(UUID id, RestoreJob.Status expectedStatus, String expectedHash,
                                   String tokenHash, Instant expiresAt, UUID sessionId) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = 'AVAILABLE', token_hash = ?, expires_at = ?, offered_session_id = ?
                WHERE id = ? AND status = ? AND token_hash = ? AND status IN ('PENDING', 'AVAILABLE')
                """)) {
            update.setString(1, tokenHash);
            update.setString(2, SqlTimestamp.format(expiresAt));
            update.setString(3, sessionId.toString());
            update.setString(4, id.toString());
            update.setString(5, expectedStatus.name());
            update.setString(6, expectedHash);
            return update.executeUpdate() == 1;
        }
    }

    @Override public void revertOffer(UUID id, String tokenHash) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = 'PENDING', token_hash = '', offered_session_id = NULL
                WHERE id = ? AND status = 'AVAILABLE' AND token_hash = ?
                """)) {
            update.setString(1, id.toString());
            update.setString(2, tokenHash);
            update.executeUpdate();
        }
    }

    @Override public Optional<RestoreJob> claim(String tokenHash, String deviceId, String leaseOwner,
                                                Instant now, Duration leaseDuration) throws SQLException {
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("""
                    UPDATE restore_jobs SET status = 'IN_PROGRESS', token_hash = '', lease_owner = ?, lease_expires_at = ?
                    WHERE token_hash = ? AND device_id = ? AND status = 'AVAILABLE' AND expires_at > ?
                    RETURNING *
                    """)) {
                update.setString(1, leaseOwner);
                update.setString(2, SqlTimestamp.format(now.plus(leaseDuration)));
                update.setString(3, tokenHash);
                update.setString(4, deviceId);
                update.setString(5, SqlTimestamp.format(now));
                RestoreJob result;
                try (var rows = update.executeQuery()) { result = rows.next() ? read(rows) : null; }
                connection.commit();
                return Optional.ofNullable(result);
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Override public boolean renewLease(UUID id, String leaseOwner, Instant now, Duration leaseDuration) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET lease_expires_at = ?
                WHERE id = ? AND status = 'IN_PROGRESS' AND lease_owner = ? AND lease_expires_at > ?
                """)) {
            update.setString(1, SqlTimestamp.format(now.plus(leaseDuration)));
            update.setString(2, id.toString());
            update.setString(3, leaseOwner);
            update.setString(4, SqlTimestamp.format(now));
            return update.executeUpdate() == 1;
        }
    }

    @Override public void completeAndAudit(UUID id, String leaseOwner, boolean success, Instant now, String error,
                                           String actor, String deviceId, String details) throws SQLException {
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                complete(connection, id, leaseOwner, success, now, error);
                audit(connection, now, actor, success ? "RESTORE_COMPLETE" : "RESTORE_FAILURE", deviceId, details);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    private static void complete(java.sql.Connection connection, UUID id, String leaseOwner, boolean success,
                                 Instant now, String error) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = ?, completed_at = ?, error = ?, lease_owner = NULL,
                    lease_expires_at = NULL
                WHERE id = ? AND status = 'IN_PROGRESS' AND lease_owner = ? AND lease_expires_at > ?
                """)) {
            update.setString(1, success ? "COMPLETED" : "FAILED");
            update.setString(2, SqlTimestamp.format(now));
            update.setString(3, error);
            update.setString(4, id.toString());
            update.setString(5, leaseOwner);
            update.setString(6, SqlTimestamp.format(now));
            if (update.executeUpdate() != 1) throw new IllegalStateException("Restore job is not in progress");
        }
    }

    @Override public void audit(Instant at, String actor, String action, String deviceId, String details) throws SQLException {
        try (var connection = database.openConnection()) { audit(connection, at, actor, action, deviceId, details); }
    }

    private static void audit(java.sql.Connection connection, Instant at, String actor, String action,
                              String deviceId, String details) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO audit_logs(occurred_at, actor, action, device_id, details) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, at.toString());
            insert.setString(2, actor);
            insert.setString(3, action);
            insert.setString(4, deviceId);
            insert.setString(5, details);
            insert.executeUpdate();
        }
    }

    @Override public List<Path> orphanedBlobs() throws SQLException {
        var paths = new ArrayList<Path>();
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT DISTINCT j.storage_path FROM restore_jobs j
                     WHERE j.status IN ('COMPLETED', 'FAILED')
                       AND NOT EXISTS (SELECT 1 FROM backup_versions v WHERE v.storage_path = j.storage_path)
                       AND NOT EXISTS (SELECT 1 FROM restore_jobs active
                           WHERE active.storage_path = j.storage_path
                           AND active.status IN ('PENDING', 'AVAILABLE', 'IN_PROGRESS'))
                     """)) {
            while (rows.next()) paths.add(Path.of(rows.getString(1)));
        }
        return List.copyOf(paths);
    }

    private static RestoreJob read(ResultSet rows) throws SQLException {
        String completed = rows.getString("completed_at");
        return new RestoreJob(UUID.fromString(rows.getString("id")), rows.getLong("backup_version_id"),
                rows.getString("device_id"), rows.getString("admin_username"), Instant.parse(rows.getString("expires_at")),
                RestoreJob.Status.valueOf(rows.getString("status")), Instant.parse(rows.getString("requested_at")),
                completed == null ? null : Instant.parse(completed), rows.getString("error"));
    }
}
