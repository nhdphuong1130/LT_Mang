package vn.edu.ltm.server.restore;

import vn.edu.ltm.server.persistence.Database;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

    @Override public void create(RestoreJob job) throws SQLException {
        try (var connection = database.openConnection(); var insert = connection.prepareStatement("""
                INSERT INTO restore_jobs(id, backup_version_id, device_id, admin_username, token_hash,
                    expires_at, status, requested_at) VALUES (?, ?, ?, ?, '', ?, 'PENDING', ?)
                """)) {
            insert.setString(1, job.id().toString());
            insert.setLong(2, job.backupVersionId());
            insert.setString(3, job.deviceId());
            insert.setString(4, job.adminUsername());
            insert.setString(5, job.expiresAt().toString());
            insert.setString(6, job.requestedAt().toString());
            insert.executeUpdate();
        }
    }

    @Override public Optional<RestoreJob> find(UUID id) throws SQLException {
        try (var connection = database.openConnection(); var query = connection.prepareStatement("SELECT * FROM restore_jobs WHERE id = ?")) {
            query.setString(1, id.toString());
            try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        }
    }

    @Override public List<RestoreJob> pending(String deviceId) throws SQLException {
        var jobs = new ArrayList<RestoreJob>();
        try (var connection = database.openConnection(); var query = connection.prepareStatement(
                "SELECT * FROM restore_jobs WHERE device_id = ? AND status = 'PENDING' ORDER BY requested_at, id")) {
            query.setString(1, deviceId);
            try (var rows = query.executeQuery()) { while (rows.next()) jobs.add(read(rows)); }
        }
        return List.copyOf(jobs);
    }

    @Override public void requeueExpired(String deviceId, Instant now) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = 'PENDING', token_hash = ''
                WHERE device_id = ? AND status = 'AVAILABLE' AND expires_at <= ?
                """)) {
            update.setString(1, deviceId);
            update.setString(2, now.toString());
            update.executeUpdate();
        }
    }

    @Override public boolean offer(UUID id, String tokenHash, Instant expiresAt) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = 'AVAILABLE', token_hash = ?, expires_at = ?
                WHERE id = ? AND status = 'PENDING'
                """)) {
            update.setString(1, tokenHash);
            update.setString(2, expiresAt.toString());
            update.setString(3, id.toString());
            return update.executeUpdate() == 1;
        }
    }

    @Override public void revertOffer(UUID id) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = 'PENDING', token_hash = '' WHERE id = ? AND status = 'AVAILABLE'
                """)) {
            update.setString(1, id.toString());
            update.executeUpdate();
        }
    }

    @Override public Optional<RestoreJob> claim(String tokenHash, String deviceId, Instant now) throws SQLException {
        try (var connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement("""
                    UPDATE restore_jobs SET status = 'IN_PROGRESS', token_hash = ''
                    WHERE token_hash = ? AND device_id = ? AND status = 'AVAILABLE' AND expires_at > ?
                    RETURNING *
                    """)) {
                update.setString(1, tokenHash);
                update.setString(2, deviceId);
                update.setString(3, now.toString());
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

    @Override public void complete(UUID id, boolean success, Instant now, String error) throws SQLException {
        try (var connection = database.openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET status = ?, completed_at = ?, error = ? WHERE id = ? AND status = 'IN_PROGRESS'
                """)) {
            update.setString(1, success ? "COMPLETED" : "FAILED");
            update.setString(2, now.toString());
            update.setString(3, error);
            update.setString(4, id.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Restore job is not in progress");
        }
    }

    @Override public void audit(Instant at, String actor, String action, String deviceId, String details) throws SQLException {
        try (var connection = database.openConnection(); var insert = connection.prepareStatement("""
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

    private static RestoreJob read(ResultSet rows) throws SQLException {
        String completed = rows.getString("completed_at");
        return new RestoreJob(UUID.fromString(rows.getString("id")), rows.getLong("backup_version_id"),
                rows.getString("device_id"), rows.getString("admin_username"), Instant.parse(rows.getString("expires_at")),
                RestoreJob.Status.valueOf(rows.getString("status")), Instant.parse(rows.getString("requested_at")),
                completed == null ? null : Instant.parse(completed), rows.getString("error"));
    }
}
