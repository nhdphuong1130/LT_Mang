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
    private final Semaphore outputReady = new Semaphore(0);
    private final Thread outputWorker;
    private ControlEnvelope heartbeat;
    private ControlEnvelope latestMetrics;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-monitoring").factory());
    private boolean started;
    private volatile boolean closed;

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
        outputWorker = Thread.ofVirtual().name("agent-monitoring-output").start(this::outputLoop);
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
        // Replace pending output rather than queueing one transport task per sample.
        heartbeat = envelope(MessageType.HEARTBEAT, JsonNodeFactory.instance.objectNode());
        try {
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
            latestMetrics = envelope(MessageType.METRICS, payload);
        } finally {
            // A collection failure must not suppress a heartbeat. One wakeup is enough.
            if (outputReady.availablePermits() == 0) outputReady.release();
        }
    }

    private void outputLoop() {
        try {
            while (!closed) {
                outputReady.acquire();
                OutputBatch batch;
                synchronized (this) {
                    if (closed) return;
                    batch = new OutputBatch(heartbeat, latestMetrics, List.copyOf(resolutions.values()), List.copyOf(pending.values()));
                }
                // All socket writes occur outside the sampling/lifecycle monitor.
                try { writeBatch(batch); }
                catch (RuntimeException failure) { System.getLogger(getClass().getName())
                        .log(System.Logger.Level.WARNING, "Monitoring output failed: {0}", failure.getClass().getName()); }
            }
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }

    private void writeBatch(OutputBatch batch) {
        if (batch.heartbeat() != null) {
            if (!send(batch.heartbeat())) return;
            synchronized (this) { if (heartbeat == batch.heartbeat()) heartbeat = null; }
        }
        if (batch.metrics() != null) {
            if (!send(batch.metrics())) return;
            synchronized (this) { if (latestMetrics == batch.metrics()) latestMetrics = null; }
        }
        if (!writeRisks(batch.resolutions(), true)) return;
        writeRisks(batch.active(), false);
    }

    private boolean writeRisks(List<RiskEvent> events, boolean resolving) {
        for (var event : events) {
            Map<RiskType, RiskEvent> source = resolving ? resolutions : pending;
            synchronized (this) {
                // A sample may replace this snapshot while an earlier write is blocked.
                if (source.get(event.riskType()) != event || (!resolving && resolutions.containsKey(event.riskType()))) continue;
            }
            var risk = JsonNodeFactory.instance.objectNode().put("riskType", event.riskType().name())
                    .put("status", event.status().name()).put("occurredAt", event.occurredAt().toString())
                    .put("message", event.message());
            if (!send(envelope(resolving ? MessageType.RISK_RESOLVED : MessageType.RISK_ALERT, risk))) return false;
            synchronized (this) { if (source.get(event.riskType()) == event) source.remove(event.riskType()); }
        }
        return true;
    }

    private ControlEnvelope envelope(MessageType type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new ControlEnvelope(type, UUID.randomUUID().toString(), deviceId, payload);
    }
    private boolean send(ControlEnvelope message) {
        if (closed) return false;
        try { sender.send(message); return !closed; }
        catch (IOException offline) { return false; }
    }
    @Override public synchronized void close() {
        closed = true;
        heartbeat = null; latestMetrics = null;
        pending.clear(); resolutions.clear();
        scheduler.shutdownNow(); outputWorker.interrupt();
    }

    private record OutputBatch(ControlEnvelope heartbeat, ControlEnvelope metrics,
                               List<RiskEvent> resolutions, List<RiskEvent> active) { }
}
