package vn.edu.ltm.server.ui;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.auth.JdbcAdminUserRepository;
import vn.edu.ltm.server.persistence.Database;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LoginControllerTest {
    @TempDir Path temp;
    @Test void loginCreatesSessionAndClearsPassword() throws Exception {
        var db = new Database(temp.resolve("admin.db")); db.initialize();
        var auth = new AdminAuthService(new JdbcAdminUserRepository(db));
        auth.createInitialAdmin("admin", "long-password-123".toCharArray());
        var model = new DashboardViewModel(Runnable::run);
        var controller = new LoginController(auth, model);
        char[] password = "long-password-123".toCharArray();
        assertTrue(controller.login("admin", password));
        assertArrayEquals(new char[password.length], password);
        assertEquals("admin", model.adminUsername());
        controller.logout();
        assertFalse(model.canRestore());
    }
}
