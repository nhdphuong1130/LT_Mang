package vn.edu.ltm.server.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Administrator authentication for the caller-facing command path. Password inputs are consumed and cleared. */
public final class AdminAuthService {
    private final AdminUserRepository users;
    private final ThreadLocal<String> currentAdmin = new ThreadLocal<>();

    public AdminAuthService(AdminUserRepository users) { this.users = Objects.requireNonNull(users); }

    public void createInitialAdmin(String username, char[] password) throws SQLException {
        Objects.requireNonNull(password);
        try {
            validUsername(username);
            if (password.length < 12) throw new IllegalArgumentException("Password must be at least 12 characters");
            String hash = BCrypt.withDefaults().hashToString(12, password);
            if (!users.insertInitial(username, hash, Instant.now()))
                throw new IllegalStateException("Initial administrator already exists");
        } finally { Arrays.fill(password, '\0'); }
    }

    public AuthResult authenticate(String username, char[] password) throws SQLException {
        Objects.requireNonNull(password);
        currentAdmin.remove();
        try {
            if (!isValidUsername(username)) return new AuthResult(false, null);
            var hash = users.passwordHash(username);
            boolean accepted = hash.isPresent() && BCrypt.verifyer().verify(password, hash.get()).verified;
            if (accepted) currentAdmin.set(username);
            return new AuthResult(accepted, accepted ? username : null);
        } finally { Arrays.fill(password, '\0'); }
    }

    public void requireAuthenticated(String username) {
        if (username == null || !username.equals(currentAdmin.get())) throw new SecurityException("Administrator login required");
    }

    public void logout() { currentAdmin.remove(); }

    private static void validUsername(String username) {
        if (!isValidUsername(username)) throw new IllegalArgumentException("Invalid administrator username");
    }

    private static boolean isValidUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9._-]{1,64}");
    }

    public record AuthResult(boolean authenticated, String username) { }
}
