package vn.edu.ltm.agent.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OshiMetricsCollectorSmokeTest {
    @Test void unavailableTemperatureIsNotFabricatedFromCpuUsage() {
        assertNull(OshiMetricsCollector.resolveTemperature(0.0, 73.0));
        assertNull(OshiMetricsCollector.resolveTemperature(Double.NaN, 73.0));
    }

    @Test void realSensorTemperatureIsPreserved() {
        assertEquals(61.5, OshiMetricsCollector.resolveTemperature(61.5, 73.0));
    }

    @Test void collectsRealMetricsWithoutAssumingAvailableSensors() {
        var collector = new OshiMetricsCollector();
        for (int i = 0; i < 2; i++) {
            var metrics = collector.collect();
            assertNotNull(metrics.collectedAt());
            assertTrue(metrics.cpuPercent() >= 0 && metrics.cpuPercent() <= 100);
            assertTrue(metrics.ramPercent() >= 0 && metrics.ramPercent() <= 100);
            assertTrue(metrics.diskPercent() >= 0 && metrics.diskPercent() <= 100);
            if (metrics.batteryPercent() != null)
                assertTrue(metrics.batteryPercent() >= 0 && metrics.batteryPercent() <= 100);
            if (metrics.temperatureCelsius() != null) assertTrue(Double.isFinite(metrics.temperatureCelsius()));
        }
    }
}
