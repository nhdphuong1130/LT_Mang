package vn.edu.ltm.agent.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;

/** Call initialize once at Agent startup, before starting any backup workers. */
public final class AgentDatabase {
    private final Path path;

    public AgentDatabase(Path path) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    /** Connections are short-lived and owned by callers. */
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
        try (var resource = AgentDatabase.class.getResourceAsStream("/db/agent-schema.sql")) {
            if (resource == null) throw new IOException("Missing Agent database schema");
            Files.createDirectories(path.getParent());
            schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new SQLException("Cannot initialize Agent database", failure);
        }
        try (var connection = openConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
                statement.executeUpdate("""
                        UPDATE backup_queue SET status = 'RETRY', uploading_sha256 = NULL,
                            next_attempt = 0, last_error = 'Upload interrupted by Agent restart'
                        WHERE status = 'UPLOADING'
                        """);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }
}
