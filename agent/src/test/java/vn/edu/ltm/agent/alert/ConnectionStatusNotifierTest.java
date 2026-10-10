package vn.edu.ltm.agent.alert;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionStatusNotifierTest {
    @Test void warnsOncePerLostConnectionEpisode() {
        var warnings = new AtomicInteger();
        var notifier = new ConnectionStatusNotifier(warnings::incrementAndGet);
        notifier.poll(false);
        assertEquals(0, warnings.get());
        notifier.poll(true);
        notifier.poll(false);
        notifier.poll(false);
        assertEquals(1, warnings.get());
        notifier.poll(true);
        notifier.poll(false);
        assertEquals(2, warnings.get());
    }
}
