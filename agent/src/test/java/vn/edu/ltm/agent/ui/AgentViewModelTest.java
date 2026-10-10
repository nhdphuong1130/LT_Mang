package vn.edu.ltm.agent.ui;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.SystemMetrics;
import java.time.Instant;
import java.util.ArrayDeque;
import static org.junit.jupiter.api.Assertions.*;

class AgentViewModelTest {
    @Test void missingTemperatureAndQueuedUiUpdate() {
        var tasks = new ArrayDeque<Runnable>();
        var model = new AgentViewModel(tasks::add);
        model.updateMetrics(new SystemMetrics(Instant.now(), 40, 50, 60, null, null, false));
        assertEquals("N/A", model.temperature());
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals("N/A", model.temperature());
        assertEquals("40%", model.cpu());
    }
    @Test void statusTracksConnectionAndWarning() {
        var model = new AgentViewModel(Runnable::run);
        model.setConnected(true);
        assertEquals("Online", model.status());
        model.warn("Server mất liên lạc");
        assertEquals("Warning", model.status());
        model.setConnected(false);
        assertEquals("Offline", model.status());
    }
}
