package vn.edu.ltm.agent.metrics;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import vn.edu.ltm.common.model.SystemMetrics;
import java.time.Clock;

/** CPU is sampled between calls; disk usage is the fullest local mounted filesystem. */
public final class OshiMetricsCollector implements MetricsCollector {
    private final SystemInfo system;
    private final Clock clock;
    private final CentralProcessor processor;
    private long[] ticks;

    public OshiMetricsCollector() { this(new SystemInfo(), Clock.systemUTC()); }
    public OshiMetricsCollector(SystemInfo system, Clock clock) {
        this.system = system;
        this.clock = clock;
        processor = system.getHardware().getProcessor();
        ticks = processor.getSystemCpuLoadTicks();
    }

    @Override public synchronized SystemMetrics collect() {
        double cpu = percent(processor.getSystemCpuLoadBetweenTicks(ticks) * 100);
        ticks = processor.getSystemCpuLoadTicks();
        var memory = system.getHardware().getMemory();
        double ram = used(memory.getTotal(), memory.getAvailable());
        double disk = system.getOperatingSystem().getFileSystem().getFileStores(true).stream()
                .filter(store -> store.getTotalSpace() > 0)
                .mapToDouble(store -> used(store.getTotalSpace(), store.getUsableSpace())).max().orElse(0);
        Double temperature = null;
        Double battery = null;
        boolean charging = false;
        try {
            double value = system.getHardware().getSensors().getCpuTemperature();
            if (Double.isFinite(value) && value != 0) temperature = value;
        } catch (RuntimeException unavailable) { /* Optional sensor; no fabricated value. */ }
        try {
            var sources = system.getHardware().getPowerSources();
            // Use the lowest reported battery to avoid hiding an exhausted pack.
            for (var source : sources) {
                double value = source.getRemainingCapacityPercent();
                if (Double.isFinite(value) && value >= 0) {
                    double candidate = percent(value * 100);
                    if (battery == null || candidate < battery) {
                        battery = candidate;
                        charging = source.isCharging() || source.isPowerOnLine();
                    }
                }
            }
        } catch (RuntimeException unavailable) { /* Optional battery support. */ }
        return new SystemMetrics(clock.instant(), cpu, ram, disk, temperature, battery, charging);
    }

    private static double used(long total, long available) {
        return total <= 0 ? 0 : percent(100d * (1 - (double) available / total));
    }
    private static double percent(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
    }
}
