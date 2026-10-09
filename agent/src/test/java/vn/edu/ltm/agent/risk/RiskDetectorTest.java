package vn.edu.ltm.agent.risk;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RiskDetectorTest {
    final FakeClock clock = new FakeClock();
    final RiskDetector detector = new RiskDetector(RiskThresholds.defaults());

    @Test void cpuRequiresThirtySustainedSecondsAndCanReopen() {
        assertTrue(evaluate(90, 0, 0, null, null, false).isEmpty());
        clock.advance(29);
        assertTrue(evaluate(99, 0, 0, null, null, false).isEmpty());
        clock.advance(1);
        assertEvent(evaluate(90, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.OPEN);
        clock.advance(5);
        assertEvent(evaluate(99, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.UPDATE);
        assertEvent(evaluate(89, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.RESOLVED);
        assertTrue(evaluate(90, 0, 0, null, null, false).isEmpty());
        clock.advance(30);
        assertEvent(evaluate(90, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.OPEN);
    }

    @Test void oscillationResetsPendingRamDurationWithoutDuplicateOpens() {
        assertTrue(evaluate(0, 90, 0, null, null, false).isEmpty());
        clock.advance(14);
        assertTrue(evaluate(0, 89, 0, null, null, false).isEmpty());
        assertTrue(evaluate(0, 91, 0, null, null, false).isEmpty());
        clock.advance(14);
        assertTrue(evaluate(0, 90, 0, null, null, false).isEmpty());
        clock.advance(1);
        assertEvent(evaluate(0, 90, 0, null, null, false), RiskType.HIGH_RAM, RiskEvent.Status.OPEN);
        assertEvent(evaluate(0, 91, 0, null, null, false), RiskType.HIGH_RAM, RiskEvent.Status.UPDATE);
    }

    @Test void diskTemperatureAndUnpluggedBatteryOpenImmediately() {
        var events = evaluate(0, 0, 95, 85d, 10d, false);
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(e -> e.status() == RiskEvent.Status.OPEN));
        var resolved = evaluate(0, 0, 0, null, null, true);
        assertEquals(3, resolved.size());
        assertTrue(resolved.stream()
                .allMatch(e -> e.status() == RiskEvent.Status.RESOLVED));
    }

    @Test void unavailableSensorsAndChargingNeverOpenRisks() {
        assertTrue(evaluate(0, 0, 0, null, null, false).isEmpty());
        assertTrue(evaluate(0, 0, 0, null, 1d, true).isEmpty());
    }

    List<RiskEvent> evaluate(double cpu, double ram, double disk, Double temperature, Double battery, boolean charging) {
        return detector.evaluate(new SystemMetrics(clock.instant(), cpu, ram, disk, temperature, battery, charging), clock.instant());
    }
    static void assertEvent(List<RiskEvent> events, RiskType type, RiskEvent.Status status) {
        assertEquals(1, events.size());
        assertEquals(type, events.getFirst().riskType());
        assertEquals(status, events.getFirst().status());
    }
    static final class FakeClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
