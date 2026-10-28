package vn.edu.ltm.server.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.server.incident.IncidentService;
import vn.edu.ltm.server.session.SessionRegistry;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/** Pass to ControlTcpServer's authenticated application callback. */
public final class MetricsMessageHandler implements BiConsumer<String, ControlEnvelope> {
    private final MetricsRepository metrics;
    private final IncidentService incidents;
    private final SessionRegistry sessions;
    private final vn.edu.ltm.server.device.DeviceRepository devices;
    private final RealtimeDistanceTracker distanceTracker;
    private final DirectLinkRegistry directLinks;
    public MetricsMessageHandler(MetricsRepository metrics, IncidentService incidents, SessionRegistry sessions) {
        this(metrics, incidents, sessions, null, null, null);
    }
    public MetricsMessageHandler(MetricsRepository metrics, IncidentService incidents, SessionRegistry sessions,
                                 vn.edu.ltm.server.device.DeviceRepository devices) {
        this(metrics, incidents, sessions, devices, null, null);
    }
    public MetricsMessageHandler(MetricsRepository metrics, IncidentService incidents, SessionRegistry sessions,
                                 vn.edu.ltm.server.device.DeviceRepository devices, RealtimeDistanceTracker distanceTracker) {
        this(metrics, incidents, sessions, devices, distanceTracker, null);
    }
    public MetricsMessageHandler(MetricsRepository metrics, IncidentService incidents, SessionRegistry sessions,
                                 vn.edu.ltm.server.device.DeviceRepository devices,
                                 RealtimeDistanceTracker distanceTracker, DirectLinkRegistry directLinks) {
        this.metrics = metrics; this.incidents = incidents; this.sessions = sessions;
        this.devices = devices; this.distanceTracker = distanceTracker; this.directLinks = directLinks;
    }

    @Override public void accept(String deviceId, ControlEnvelope message) {
        if (message.type() != MessageType.METRICS && message.type() != MessageType.RISK_ALERT
                && message.type() != MessageType.RISK_RESOLVED) return;
        try {
            if (!deviceId.equals(message.deviceId())) throw new IllegalArgumentException("Device mismatch");
            JsonNode payload = message.payload();
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException("Object payload required");
            if (message.type() == MessageType.METRICS) {
                var charging = payload.get("charging");
                if (charging == null || !charging.isBoolean()) throw new IllegalArgumentException("Boolean charging required");
                var sample = new SystemMetrics(timestamp(payload, "collectedAt"), number(payload, "cpuPercent"),
                        number(payload, "ramPercent"), number(payload, "diskPercent"),
                        nullableNumber(payload, "temperatureCelsius"), nullableNumber(payload, "batteryPercent"), charging.booleanValue());
                metrics.upsert(deviceId, sample);
                if (directLinks != null) {
                    directLinks.update(deviceId, parseDirectLink(payload));
                }
                if (devices != null && payload.has("dxdiag") && payload.get("dxdiag").isObject()) {
                    var dx = payload.get("dxdiag");
                    var profile = new DxdiagProfile(
                            optText(dx, "computerName", deviceId),
                            optText(dx, "operatingSystem", "N/A"),
                            optText(dx, "systemManufacturer", "N/A"),
                            optText(dx, "systemModel", "N/A"),
                            optText(dx, "biosVersion", "N/A"),
                            optText(dx, "processor", "N/A"),
                            optText(dx, "memory", "N/A"),
                            optText(dx, "pageFileOrSwap", "N/A"),
                            optText(dx, "directXOrKernelVersion", "N/A"),
                            optText(dx, "gpuName", "N/A"),
                            optText(dx, "gpuVendor", "N/A"),
                            optText(dx, "gpuVram", "N/A"),
                            optText(dx, "displayInfo", "N/A"),
                            optText(dx, "diskSummary", "N/A"),
                            optText(dx, "networkSummary", "N/A")
                    );
                    devices.updateDxdiag(deviceId, profile);
                }
            } else {
                var event = new RiskEvent(RiskType.valueOf(text(payload, "riskType")),
                        RiskEvent.Status.valueOf(text(payload, "status")), timestamp(payload, "occurredAt"), text(payload, "message"));
                if ((message.type() == MessageType.RISK_RESOLVED) != (event.status() == RiskEvent.Status.RESOLVED))
                    throw new IllegalArgumentException("Risk status does not match message type");
                incidents.record(deviceId, event);
            }
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            // Return a stable reason, never raw payload values or exception text.
            if (sessions != null) {
                sessions.send(deviceId, new ControlEnvelope(MessageType.ERROR, message.requestId(), deviceId,
                        JsonNodeFactory.instance.objectNode().put("reason", "Invalid " + message.type() + " payload")));
            }
        }
    }

    static DirectLinkIdentity parseDirectLink(JsonNode payload) {
        JsonNode direct = payload.get("directLink");
        JsonNode capabilitiesNode = payload.get("capabilities");
        String version = optText(payload, "agentVersion", "legacy");
        if (direct == null || !direct.isObject() || capabilitiesNode == null || !capabilitiesNode.isArray()) {
            return DirectLinkIdentity.unavailable(version, Set.of());
        }
        Set<String> capabilities = new HashSet<>();
        capabilitiesNode.forEach(value -> { if (value.isTextual()) capabilities.add(value.textValue()); });
        DirectLinkStatus status = DirectLinkStatus.valueOf(text(direct, "status"));
        Instant sampledAt = null;
        JsonNode sampled = direct.get("sampledAt");
        if (sampled != null && sampled.isTextual() && !sampled.textValue().isBlank()) {
            sampledAt = Instant.parse(sampled.textValue());
        }
        return new DirectLinkIdentity(status, optText(direct, "interfaceName", ""),
                optText(direct, "localP2pMac", ""), sampledAt, version, capabilities);
    }

    private static String optText(JsonNode node, String field, String fallback) {
        var v = node.get(field);
        return (v != null && v.isTextual() && !v.textValue().isBlank()) ? v.textValue() : fallback;
    }
    private static String text(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("String required");
        return value.textValue();
    }
    private static Instant timestamp(JsonNode payload, String field) { return Instant.parse(text(payload, field)); }
    private static double number(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite number required");
        return value.doubleValue();
    }
    private static Double nullableNumber(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null) throw new IllegalArgumentException("Nullable sensor field required");
        return value.isNull() ? null : number(payload, field);
    }
}
