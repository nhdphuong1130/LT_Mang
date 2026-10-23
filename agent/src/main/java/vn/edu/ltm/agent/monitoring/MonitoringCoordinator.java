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
    private volatile DxdiagProfile cachedDxdiag;
    private volatile DirectLinkIdentity cachedDirectLink;
    private final java.util.concurrent.atomic.AtomicBoolean collectingDxdiag = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.function.Supplier<DxdiagProfile> dxdiagSupplier;
    private final java.util.function.Supplier<DirectLinkIdentity> directLinkSupplier;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-monitoring").factory());
    private boolean started;
    private volatile boolean closed;

    public MonitoringCoordinator(String deviceId, ControlClient client, MetricsCollector collector,
                                 RiskDetector detector, AlertSink alerts, Clock clock) {
        this(deviceId, client::send, collector, detector, alerts, clock,
                createDefaultDxdiagSupplier(), createDefaultDirectLinkSupplier());
    }
    private static java.util.function.Supplier<DxdiagProfile> createDefaultDxdiagSupplier() {
        var dxCollector = new vn.edu.ltm.agent.metrics.OshiDxdiagCollector();
        return dxCollector::collect;
    }
    private static java.util.function.Supplier<DirectLinkIdentity> createDefaultDirectLinkSupplier() {
        var directCollector = new vn.edu.ltm.agent.metrics.DirectLinkIdentityCollector();
        return directCollector::collect;
    }
    public MonitoringCoordinator(String deviceId, Sender sender, MetricsCollector collector,
                                 RiskDetector detector, AlertSink alerts, Clock clock) {
        this(deviceId, sender, collector, detector, alerts, clock, () -> null, createDefaultDirectLinkSupplier());
    }
    public MonitoringCoordinator(String deviceId, Sender sender, MetricsCollector collector,
                                 RiskDetector detector, AlertSink alerts, Clock clock,
                                 java.util.function.Supplier<DxdiagProfile> dxdiagSupplier) {
        this(deviceId, sender, collector, detector, alerts, clock, dxdiagSupplier, createDefaultDirectLinkSupplier());
    }
    public MonitoringCoordinator(String deviceId, Sender sender, MetricsCollector collector,
                                 RiskDetector detector, AlertSink alerts, Clock clock,
                                 java.util.function.Supplier<DxdiagProfile> dxdiagSupplier,
                                 java.util.function.Supplier<DirectLinkIdentity> directLinkSupplier) {
        this.deviceId = Objects.requireNonNull(deviceId);
        this.sender = Objects.requireNonNull(sender);
        this.collector = Objects.requireNonNull(collector);
        this.detector = Objects.requireNonNull(detector);
        this.alerts = Objects.requireNonNull(alerts);
        this.clock = Objects.requireNonNull(clock);
        this.dxdiagSupplier = Objects.requireNonNull(dxdiagSupplier);
        this.directLinkSupplier = Objects.requireNonNull(directLinkSupplier);
        outputWorker = Thread.ofVirtual().name("agent-monitoring-output").start(this::outputLoop);
    }

    public synchronized void reportRiskyFile(String relativePath, String reason) {
        if (closed) return;
        var event = new RiskEvent(RiskType.RISKY_FILE, RiskEvent.Status.OPEN, clock.instant(),
                "Risky file blocked [" + relativePath + "]: " + reason);
        pending.put(RiskType.RISKY_FILE, event);
        try { alerts.show(event); }
        catch (RuntimeException failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Local alert dispatch failed");
        }
        if (outputReady.availablePermits() == 0) outputReady.release();
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
            if (cachedDxdiag == null && !started) {
                try { cachedDxdiag = dxdiagSupplier.get(); } catch (RuntimeException ignored) { }
                try { cachedDirectLink = directLinkSupplier.get(); } catch (RuntimeException ignored) { }
            } else if (collectingDxdiag.compareAndSet(false, true)) {
                Thread.ofVirtual().name("agent-dxdiag-refresh").start(() -> {
                    try {
                        DirectLinkIdentity directLink = directLinkSupplier.get();
                        if (directLink != null) cachedDirectLink = directLink;
                        DxdiagProfile updated = dxdiagSupplier.get();
                        if (updated != null) cachedDxdiag = updated;
                    } catch (Throwable ignored) {
                    } finally {
                        collectingDxdiag.set(false);
                    }
                });
            }
            if (cachedDirectLink != null) {
                payload.put("agentVersion", cachedDirectLink.agentVersion());
                var capabilities = payload.putArray("capabilities");
                cachedDirectLink.capabilities().stream().sorted().forEach(capabilities::add);
                var direct = payload.putObject("directLink");
                direct.put("status", cachedDirectLink.status().name())
                      .put("interfaceName", cachedDirectLink.interfaceName())
                      .put("localP2pMac", cachedDirectLink.localP2pMac());
                if (cachedDirectLink.sampledAt() == null) direct.putNull("sampledAt");
                else direct.put("sampledAt", cachedDirectLink.sampledAt().toString());
            }
            if (cachedDxdiag != null) {
                var dx = payload.putObject("dxdiag");
                dx.put("computerName", cachedDxdiag.computerName())
                  .put("operatingSystem", cachedDxdiag.operatingSystem())
                  .put("systemManufacturer", cachedDxdiag.systemManufacturer())
                  .put("systemModel", cachedDxdiag.systemModel())
                  .put("biosVersion", cachedDxdiag.biosVersion())
                  .put("processor", cachedDxdiag.processor())
                  .put("memory", cachedDxdiag.memory())
                  .put("pageFileOrSwap", cachedDxdiag.pageFileOrSwap())
                  .put("directXOrKernelVersion", cachedDxdiag.directXOrKernelVersion())
                  .put("gpuName", cachedDxdiag.gpuName())
                  .put("gpuVendor", cachedDxdiag.gpuVendor())
                  .put("gpuVram", cachedDxdiag.gpuVram())
                  .put("displayInfo", cachedDxdiag.displayInfo())
                  .put("diskSummary", cachedDxdiag.diskSummary())
                  .put("networkSummary", cachedDxdiag.networkSummary());
            }
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

    /** Optional lifecycle barrier for owners that must replace this coordinator without overlap. */
    public boolean awaitStopped(java.time.Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        if (!closed) throw new IllegalStateException("Close monitoring before awaiting termination");
        long deadline = System.nanoTime() + timeout.toNanos();
        outputWorker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeout.toNanos())));
        if (outputWorker.isAlive()) return false;
        long remaining = deadline - System.nanoTime();
        return scheduler.awaitTermination(Math.max(0, remaining), TimeUnit.NANOSECONDS);
    }

    private record OutputBatch(ControlEnvelope heartbeat, ControlEnvelope metrics,
                               List<RiskEvent> resolutions, List<RiskEvent> active) { }
}
