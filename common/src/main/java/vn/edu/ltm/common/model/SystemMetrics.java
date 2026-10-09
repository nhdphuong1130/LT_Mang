package vn.edu.ltm.common.model;

import java.time.Instant;
import java.util.Objects;

public record SystemMetrics(Instant collectedAt, double cpuPercent, double ramPercent, double diskPercent,
                            Double temperatureCelsius, Double batteryPercent, boolean charging) {
    public SystemMetrics {
        Objects.requireNonNull(collectedAt, "collectedAt");
        percentage(cpuPercent); percentage(ramPercent); percentage(diskPercent);
        if (batteryPercent != null) percentage(batteryPercent);
        if (temperatureCelsius != null && !Double.isFinite(temperatureCelsius))
            throw new IllegalArgumentException("Invalid temperature");
    }

    private static void percentage(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100)
            throw new IllegalArgumentException("Percentage must be finite and in [0,100]");
    }
}
