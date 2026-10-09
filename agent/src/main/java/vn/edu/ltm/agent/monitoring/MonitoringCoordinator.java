package vn.edu.ltm.agent.monitoring;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.agent.alert.AlertSink;
import vn.edu.ltm.agent.metrics.MetricsCollector;
import vn.edu.ltm.agent.network.ControlClient;
import vn.edu.ltm.agent.risk.RiskDetector;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.common.protocol.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Owns the 5-second sampling/heartbeat cadence, independently of transport reconnect. */
public final class MonitoringCoordinator implements AutoCloseable {
    @FunctionalInterface public interface Sender { void send(ControlEnvelope message) throws IOException; }
    private final String deviceId;
    private final Sender sender;
    private final MetricsCollector collector;
    private final RiskDetector detector;
    private final AlertSink alerts;
    private final Clock clock;
    private final Map<RiskType, RiskEvent> pending = new EnumMap<>(RiskType.class);
    private final Map<RiskType, RiskEvent> resolutions = new EnumMap<>(RiskType.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-monitoring").factory());
    private boolean started;
    private boolean closed;

    public MonitoringCoordinator(String deviceId, ControlClient client, MetricsCollector collector,
                                 RiskDetector detector, AlertSink alerts, Clock clock) {
        this(deviceId, client::send, collector, detector, alerts, clock);
    }
    public MonitoringCoordinator(String deviceId, Sender sender, MetricsCollector collector,
                                 RiskDetector detector, AlertSink alerts, Clock clock) {
        this.deviceId = Objects.requireNonNull(deviceId);
        this.sender = Objects.requireNonNull(sender);
        this.collector = Objects.requireNonNull(collector);
        this.detector = Objects.requireNonNull(detector);
        this.alerts = Objects.requireNonNull(alerts);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Monitoring is closed");
        if (started) return;
        started = true;
        scheduler.scheduleAtFixedRate(() -> {
            try { sample(); }
            catch (RuntimeException failure) { System.getLogger(getClass().getName())
                    .log(System.Logger.Level.WARNING, "Monitoring sample failed: {0}", failure.getClass().getName()); }
        }, 0, 5, TimeUnit.SECONDS);
    }

    public synchronized void sample() {
        if (closed) return;
        // A collection failure must not suppress the control heartbeat.
        send(MessageType.HEARTBEAT, JsonNodeFactory.instance.objectNode());
        var metrics = collector.collect();
        for (var event : detector.evaluate(metrics, clock.instant())) {
            if (event.status() == RiskEvent.Status.RESOLVED) {
                resolutions.put(event.riskType(), event);
                pending.remove(event.riskType());
            } else pending.put(event.riskType(), event);
            if (event.status() != RiskEvent.Status.UPDATE) {
                try { alerts.show(event); }
                catch (RuntimeException failure) { System.getLogger(getClass().getName())
                        .log(System.Logger.Level.WARNING, "Local alert dispatch failed"); }
            }
        }
        var payload = JsonNodeFactory.instance.objectNode().put("collectedAt", metrics.collectedAt().toString())
                .put("cpuPercent", metrics.cpuPercent()).put("ramPercent", metrics.ramPercent())
                .put("diskPercent", metrics.diskPercent()).put("charging", metrics.charging());
        if (metrics.temperatureCelsius() == null) payload.putNull("temperatureCelsius");
        else payload.put("temperatureCelsius", metrics.temperatureCelsius());
        if (metrics.batteryPercent() == null) payload.putNull("batteryPercent");
        else payload.put("batteryPercent", metrics.batteryPercent());
        send(MessageType.METRICS, payload);
        flush(resolutions, false);
        flush(pending, true);
    }

    private void flush(Map<RiskType, RiskEvent> events, boolean requireResolutionSent) {
        var iterator = events.entrySet().iterator();
        while (iterator.hasNext()) {
            var event = iterator.next().getValue();
            if (requireResolutionSent && resolutions.containsKey(event.riskType())) continue;
            var risk = JsonNodeFactory.instance.objectNode().put("riskType", event.riskType().name())
                    .put("status", event.status().name()).put("occurredAt", event.occurredAt().toString())
                    .put("message", event.message());
            if (send(event.status() == RiskEvent.Status.RESOLVED ? MessageType.RISK_RESOLVED : MessageType.RISK_ALERT, risk))
                iterator.remove();
        }
    }

    private boolean send(MessageType type, com.fasterxml.jackson.databind.JsonNode payload) {
        try { sender.send(new ControlEnvelope(type, UUID.randomUUID().toString(), deviceId, payload)); return true; }
        catch (IOException offline) { return false; }
    }
    @Override public synchronized void close() { closed = true; scheduler.shutdownNow(); }
}
