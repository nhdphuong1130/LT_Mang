package vn.edu.ltm.server.auth;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public interface AdminUserRepository {
    boolean insertInitial(String username, String passwordHash, Instant createdAt) throws SQLException;
    Optional<String> passwordHash(String username) throws SQLException;
}
