package vn.edu.ltm.agent.risk;

import java.time.Duration;

public record RiskThresholds(double cpuPercent, Duration cpuDuration, double ramPercent, Duration ramDuration,
                             double diskPercent, double temperatureCelsius, double batteryPercent) {
    public RiskThresholds {
        if (!percentage(cpuPercent) || !percentage(ramPercent) || !percentage(diskPercent)
                || !percentage(batteryPercent) || !Double.isFinite(temperatureCelsius)
                || cpuDuration == null || ramDuration == null || cpuDuration.isNegative() || ramDuration.isNegative())
            throw new IllegalArgumentException("Invalid risk thresholds");
    }
    public static RiskThresholds defaults() {
        return new RiskThresholds(80, Duration.ZERO, 80, Duration.ZERO, 80, 80, 20);
    }
    private static boolean percentage(double value) { return Double.isFinite(value) && value >= 0 && value <= 100; }
}
