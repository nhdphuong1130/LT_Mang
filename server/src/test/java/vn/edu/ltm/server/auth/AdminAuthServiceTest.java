package vn.edu.ltm.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.persistence.Database;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthServiceTest {
    @TempDir Path root;

    @Test void initialAdminIsExplicitAndPasswordIsHashed() throws Exception {
        var db = database();
        var auth = new AdminAuthService(new JdbcAdminUserRepository(db));
        assertFalse(auth.authenticate("admin", "admin".toCharArray()).authenticated());
        char[] password = "a long private password".toCharArray();
        auth.createInitialAdmin("admin", password);
        assertTrue(auth.authenticate("admin", "a long private password".toCharArray()).authenticated());
        assertFalse(auth.authenticate("admin", "incorrect".toCharArray()).authenticated());
        assertThrows(IllegalStateException.class, () -> auth.createInitialAdmin("other", "other password".toCharArray()));
        try (var connection = db.openConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT password_hash FROM admin_users")) {
            assertTrue(rows.next());
            String hash = rows.getString(1);
            assertTrue(hash.startsWith("$2a$12$") || hash.startsWith("$2b$12$"));
            assertFalse(hash.contains("a long private password"));
        }
    }

    @Test void callerFacingAuthenticationClearsPasswordArray() throws Exception {
        var auth = new AdminAuthService(new JdbcAdminUserRepository(database()));
        char[] password = "sensitive".toCharArray();
        auth.authenticate("admin", password);
        assertArrayEquals(new char[password.length], password);
    }

    private Database database() throws SQLException {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        return database;
    }
}
