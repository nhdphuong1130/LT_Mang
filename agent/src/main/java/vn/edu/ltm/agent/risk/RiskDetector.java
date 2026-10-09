package vn.edu.ltm.agent.risk;

import vn.edu.ltm.common.model.*;
import java.time.*;
import java.util.*;

public final class RiskDetector {
    private final RiskThresholds thresholds;
    private final Map<RiskType, Instant> pending = new EnumMap<>(RiskType.class);
    private final Set<RiskType> active = EnumSet.noneOf(RiskType.class);

    public RiskDetector(RiskThresholds thresholds) { this.thresholds = Objects.requireNonNull(thresholds); }
    public RiskDetector() { this(RiskThresholds.defaults()); }

    public synchronized List<RiskEvent> evaluate(SystemMetrics metrics, Instant now) {
        Objects.requireNonNull(metrics); Objects.requireNonNull(now);
        var events = new ArrayList<RiskEvent>();
        check(events, RiskType.HIGH_CPU, metrics.cpuPercent() >= thresholds.cpuPercent(), thresholds.cpuDuration(),
                "CPU " + metrics.cpuPercent() + "%", now);
        check(events, RiskType.HIGH_RAM, metrics.ramPercent() >= thresholds.ramPercent(), thresholds.ramDuration(),
                "RAM " + metrics.ramPercent() + "%", now);
        check(events, RiskType.DISK_FULL, metrics.diskPercent() >= thresholds.diskPercent(), Duration.ZERO,
                "Disk " + metrics.diskPercent() + "%", now);
        check(events, RiskType.OVERHEAT, metrics.temperatureCelsius() != null
                && metrics.temperatureCelsius() >= thresholds.temperatureCelsius(), Duration.ZERO,
                "Temperature " + metrics.temperatureCelsius() + " C", now);
        check(events, RiskType.LOW_BATTERY, metrics.batteryPercent() != null
                && metrics.batteryPercent() <= thresholds.batteryPercent() && !metrics.charging(), Duration.ZERO,
                "Battery " + metrics.batteryPercent() + "%", now);
        return List.copyOf(events);
    }

    private void check(List<RiskEvent> events, RiskType type, boolean exceeded, Duration delay, String reading, Instant now) {
        if (!exceeded) {
            pending.remove(type);
            if (active.remove(type)) events.add(new RiskEvent(type, RiskEvent.Status.RESOLVED, now, type + " returned to normal"));
        } else {
            Instant since = pending.computeIfAbsent(type, ignored -> now);
            if (now.isBefore(since)) { pending.put(type, now); since = now; }
            if (!now.isBefore(since.plus(delay))) {
                var status = active.add(type) ? RiskEvent.Status.OPEN : RiskEvent.Status.UPDATE;
                events.add(new RiskEvent(type, status, now, reading + ". Please save your work."));
            }
        }
    }
}
