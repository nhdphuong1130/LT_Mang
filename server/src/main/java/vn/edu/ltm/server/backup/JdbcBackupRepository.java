package vn.edu.ltm.server.backup;

import vn.edu.ltm.server.persistence.Database;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcBackupRepository implements BackupRepository {
    private final Database database;

    public JdbcBackupRepository(Database database) { this.database = Objects.requireNonNull(database); }

    @Override public List<BackupVersion> versions(String deviceId, String relativePath) throws SQLException {
        try (var connection = database.openConnection()) { return versions(connection, deviceId, relativePath); }
    }

    private static List<BackupVersion> versions(Connection connection, String deviceId, String relativePath)
            throws SQLException {
        var versions = new ArrayList<BackupVersion>();
        try (var query = connection.prepareStatement("""
                SELECT v.* FROM backup_versions v JOIN backup_files f ON f.id = v.file_id
                WHERE f.device_id = ? AND f.relative_path = ? ORDER BY v.id DESC
                """)) {
            query.setString(1, deviceId);
            query.setString(2, relativePath);
            try (var rows = query.executeQuery()) {
                while (rows.next()) versions.add(new BackupVersion(rows.getLong("id"), rows.getLong("file_id"),
                        rows.getLong("size_bytes"), rows.getString("sha256"), Path.of(rows.getString("storage_path")),
                        Instant.parse(rows.getString("created_at"))));
            }
        }
        return List.copyOf(versions);
    }

    @Override public List<Path> commitVersion(String deviceId, String relativePath, long size, String sha256,
                                               Path path) throws SQLException {
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO backup_files(device_id, relative_path) VALUES (?, ?)
                        ON CONFLICT(device_id, relative_path) DO NOTHING
                        """)) {
                    insert.setString(1, deviceId);
                    insert.setString(2, relativePath);
                    insert.executeUpdate();
                }
                long fileId;
                try (var query = connection.prepareStatement(
                        "SELECT id FROM backup_files WHERE device_id = ? AND relative_path = ?")) {
                    query.setString(1, deviceId);
                    query.setString(2, relativePath);
                    try (var rows = query.executeQuery()) { fileId = rows.getLong(1); }
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO backup_versions(file_id, size_bytes, sha256, storage_path, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    insert.setLong(1, fileId);
                    insert.setLong(2, size);
                    insert.setString(3, sha256);
                    insert.setString(4, path.toString());
                    insert.setString(5, Instant.now().toString());
                    insert.executeUpdate();
                }
                var old = versions(connection, deviceId, relativePath);
                var removed = new ArrayList<Path>();
                for (int index = 5; index < old.size(); index++) {
                    var version = old.get(index);
                    try (var delete = connection.prepareStatement("DELETE FROM backup_versions WHERE id = ?")) {
                        delete.setLong(1, version.id());
                        delete.executeUpdate();
                    }
                    try (var active = connection.prepareStatement("""
                            SELECT 1 FROM restore_jobs WHERE storage_path = ?
                            AND status IN ('PENDING', 'AVAILABLE', 'IN_PROGRESS') LIMIT 1
                            """)) {
                        active.setString(1, version.storagePath().toString());
                        try (var rows = active.executeQuery()) {
                            if (!rows.next()) removed.add(version.storagePath());
                        }
                    }
                }
                statement.execute("COMMIT");
                return List.copyOf(removed);
            } catch (SQLException | RuntimeException failure) {
                try { statement.execute("ROLLBACK"); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }
}
