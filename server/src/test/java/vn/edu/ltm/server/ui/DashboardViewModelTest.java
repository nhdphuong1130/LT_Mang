package vn.edu.ltm.server.ui;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;

class DashboardViewModelTest {
    @Test void restoreRequiresLoginAndSelection() {
        var model = new DashboardViewModel(Runnable::run);
        assertFalse(model.canRestore());
        model.selectVersion(5, "pc-1", "work/report.txt", Instant.now());
        assertFalse(model.canRestore());
        model.login("admin");
        assertTrue(model.canRestore());
        model.logout();
        assertFalse(model.canRestore());
    }
    @Test void provisioningTokenIsNotKeptInViewState() {
        var model = new DashboardViewModel(Runnable::run);
        model.login("admin");
        model.showProvisioned("pc-1", "secret-token");
        assertEquals("secret-token", model.oneTimeToken());
        model.dismissToken();
        assertNull(model.oneTimeToken());
        assertFalse(model.toString().contains("secret-token"));
    }
    @Test void backgroundUpdatesUseUiExecutor() {
        var pending = new ArrayDeque<Runnable>();
        var model = new DashboardViewModel(pending::add);
        model.updateCounts(2, 3, 4);
        assertEquals(0, model.deviceCount());
        pending.remove().run();
        assertEquals(2, model.deviceCount());
    }
}
