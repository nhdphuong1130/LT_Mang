package vn.edu.ltm.agent.risk;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.model.*;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RiskDetectorTest {
    final FakeClock clock = new FakeClock();
    final RiskDetector detector = new RiskDetector(RiskThresholds.defaults());

    @Test void defaultThresholdsMatchLatestEightyPercentPolicy() {
        var defaults = RiskThresholds.defaults();
        assertEquals(80, defaults.cpuPercent());
        assertEquals(Duration.ZERO, defaults.cpuDuration());
        assertEquals(80, defaults.ramPercent());
        assertEquals(Duration.ZERO, defaults.ramDuration());
        assertEquals(80, defaults.diskPercent());
        assertEquals(80, defaults.temperatureCelsius());
        assertEquals(20, defaults.batteryPercent());
    }

    @Test void cpuOpensImmediatelyAtEightyPercentAndCanReopen() {
        assertEvent(evaluate(80, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.OPEN);
        assertEvent(evaluate(99, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.UPDATE);
        assertEvent(evaluate(79, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.RESOLVED);
        assertEvent(evaluate(80, 0, 0, null, null, false), RiskType.HIGH_CPU, RiskEvent.Status.OPEN);
    }

    @Test void ramOpensImmediatelyAtEightyPercentWithoutDuplicateOpens() {
        assertEvent(evaluate(0, 80, 0, null, null, false), RiskType.HIGH_RAM, RiskEvent.Status.OPEN);
        assertEvent(evaluate(0, 81, 0, null, null, false), RiskType.HIGH_RAM, RiskEvent.Status.UPDATE);
    }

    @Test void diskTemperatureAndUnpluggedBatteryOpenImmediatelyAtEightyPercent() {
        var events = evaluate(0, 0, 80, 80d, 20d, false);
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
