package vn.edu.ltm.agent.alert;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaFxAlertSinkTest {
    @Test void queuesOnePopupPerActiveRiskWithoutStartingJavaFxInDomainTests() {
        var work = new ArrayList<Runnable>();
        var shown = new ArrayList<RiskEvent>();
        var sink = new JavaFxAlertSink(work::add, shown::add);
        var open = new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.OPEN, Instant.EPOCH, "95%");
        sink.show(open); sink.show(open);
        sink.show(new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.UPDATE, Instant.EPOCH, "99%"));
        assertEquals(1, work.size()); assertTrue(shown.isEmpty());
        work.removeFirst().run(); assertEquals(List.of(open), shown);
        sink.show(new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.RESOLVED, Instant.EPOCH, "normal"));
        sink.show(open); assertEquals(1, work.size());
    }
}
