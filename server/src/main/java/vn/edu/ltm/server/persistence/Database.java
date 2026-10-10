package vn.edu.ltm.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/** Connections are short-lived and owned by callers. */
public final class Database {
    private final Path path;

    public Database(Path path) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    public void initialize() throws SQLException {
        String schema;
        try (var resource = Database.class.getResourceAsStream("/db/schema.sql")) {
            if (resource == null) throw new IOException("Missing database schema");
            Files.createDirectories(path.getParent());
            schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new SQLException("Cannot initialize database schema", failure);
        }
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
        migrateLegacyRestoreJobs(schema);
        ensureRestoreColumns();
    }

    private void ensureRestoreColumns() throws SQLException {
        var found = new java.util.HashSet<String>();
        try (var connection = openConnection(); var statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(restore_jobs)")) {
            while (columns.next()) found.add(columns.getString("name"));
        }
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            if (!found.contains("lease_expires_at"))
                statement.execute("ALTER TABLE restore_jobs ADD COLUMN lease_expires_at TEXT");
            if (!found.contains("offered_session_id"))
                statement.execute("ALTER TABLE restore_jobs ADD COLUMN offered_session_id TEXT");
        }
    }

    private void migrateLegacyRestoreJobs(String schema) throws SQLException {
        try (var connection = openConnection(); var statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(restore_jobs)")) {
            while (columns.next()) if ("relative_path".equals(columns.getString("name"))) return;
        }
        String replacement = null;
        for (String sql : schema.split(";")) {
            if (sql.contains("CREATE TABLE IF NOT EXISTS restore_jobs")) {
                replacement = sql.replace("restore_jobs", "restore_jobs_migrated");
                break;
            }
        }
        if (replacement == null) throw new SQLException("Restore job schema is missing");
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            connection.setAutoCommit(false);
            try {
                statement.execute(replacement);
                statement.execute("""
                        INSERT INTO restore_jobs_migrated(id, backup_version_id, device_id, admin_username,
                            relative_path, size_bytes, sha256, storage_path, token_hash, expires_at,
                            status, requested_at, completed_at, error, lease_owner)
                        SELECT j.id, j.backup_version_id, j.device_id, j.admin_username,
                            f.relative_path, v.size_bytes, v.sha256, v.storage_path, j.token_hash, j.expires_at,
                            j.status, j.requested_at, j.completed_at, j.error, NULL
                        FROM restore_jobs j JOIN backup_versions v ON v.id = j.backup_version_id
                        JOIN backup_files f ON f.id = v.file_id
                        """);
                try (var counts = statement.executeQuery("""
                        SELECT (SELECT COUNT(*) FROM restore_jobs) =
                               (SELECT COUNT(*) FROM restore_jobs_migrated)
                        """)) {
                    if (!counts.next() || counts.getInt(1) != 1)
                        throw new SQLException("Legacy restore jobs could not all be migrated");
                }
                statement.execute("DROP TABLE restore_jobs");
                statement.execute("ALTER TABLE restore_jobs_migrated RENAME TO restore_jobs");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }
}
