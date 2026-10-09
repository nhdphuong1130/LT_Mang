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
    }
}
