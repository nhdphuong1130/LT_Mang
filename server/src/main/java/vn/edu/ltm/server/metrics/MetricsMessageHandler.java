package vn.edu.ltm.server.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.server.incident.IncidentService;
import vn.edu.ltm.server.session.SessionRegistry;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.BiConsumer;

/** Pass to ControlTcpServer's authenticated application callback. */
public final class MetricsMessageHandler implements BiConsumer<String, ControlEnvelope> {
    private final MetricsRepository metrics;
    private final IncidentService incidents;
    private final SessionRegistry sessions;
    public MetricsMessageHandler(MetricsRepository metrics, IncidentService incidents, SessionRegistry sessions) {
        this.metrics = metrics; this.incidents = incidents; this.sessions = sessions;
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
            } else {
                var event = new RiskEvent(RiskType.valueOf(text(payload, "riskType")),
                        RiskEvent.Status.valueOf(text(payload, "status")), timestamp(payload, "occurredAt"), text(payload, "message"));
                if ((message.type() == MessageType.RISK_RESOLVED) != (event.status() == RiskEvent.Status.RESOLVED))
                    throw new IllegalArgumentException("Risk status does not match message type");
                incidents.record(deviceId, event);
            }
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            // Return a stable reason, never raw payload values or exception text.
            sessions.send(deviceId, new ControlEnvelope(MessageType.ERROR, message.requestId(), deviceId,
                    JsonNodeFactory.instance.objectNode().put("reason", "Invalid " + message.type() + " payload")));
        }
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
