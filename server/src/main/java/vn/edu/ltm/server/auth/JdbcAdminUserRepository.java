package vn.edu.ltm.server.auth;

import vn.edu.ltm.server.persistence.Database;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class JdbcAdminUserRepository implements AdminUserRepository {
    private final Database database;

    public JdbcAdminUserRepository(Database database) { this.database = Objects.requireNonNull(database); }

    @Override public boolean insertInitial(String username, String passwordHash, Instant createdAt) throws SQLException {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement("""
                INSERT INTO admin_users(username, password_hash, created_at)
                SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM admin_users)
                """)) {
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, createdAt.toString());
            return statement.executeUpdate() == 1;
        }
    }

    @Override public Optional<String> passwordHash(String username) throws SQLException {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "SELECT password_hash FROM admin_users WHERE username = ?")) {
            statement.setString(1, username);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }
}
