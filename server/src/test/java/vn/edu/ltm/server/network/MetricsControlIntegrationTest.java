package vn.edu.ltm.server.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.server.config.ServerConfig;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.incident.*;
import vn.edu.ltm.server.metrics.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.SessionRegistry;
import java.net.Socket;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricsControlIntegrationTest {
    @TempDir Path temp;
    final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();

    @Test void onlyAuthenticatedValidMetricsPersistAndRiskMessagesDriveLifecycle() throws Exception {
        var db = new Database(temp.resolve("server.db")); db.initialize();
        var devices = new JdbcDeviceRepository(db);
        var provisioning = new DeviceProvisioningService(devices);
        var issued = provisioning.provision("lab-1", "One", "Linux");
        var metrics = new JdbcMetricsRepository(db);
        var incidents = new JdbcIncidentRepository(db);
        var dispatched = new LinkedBlockingQueue<String>();
        try (var registry = new SessionRegistry(devices)) {
            var application = new MetricsMessageHandler(metrics, new IncidentService(incidents), registry);
            try (var server = new ControlTcpServer(new ServerConfig("127.0.0.1", 0), provisioning, registry,
                    Clock.systemUTC(), (id, message) -> { application.accept(id, message); dispatched.add(message.requestId()); })) {
                server.start();
                try (var unauthenticated = connect(server.port())) {
                    codec.write(unauthenticated.getOutputStream(), envelope(MessageType.METRICS, "before", metricsPayload()));
                    assertEquals(MessageType.ERROR, codec.read(unauthenticated.getInputStream()).type());
                    assertTrue(metrics.findLatest("lab-1").isEmpty());
                }
                try (var socket = connect(server.port())) {
                    codec.write(socket.getOutputStream(), envelope(MessageType.REGISTER, "register",
                            JsonNodeFactory.instance.objectNode().put("token", issued.token())));
                    assertTrue(codec.read(socket.getInputStream()).payload().path("accepted").booleanValue());
                    codec.write(socket.getOutputStream(), envelope(MessageType.METRICS, "valid", metricsPayload()));
                    assertEquals("valid", dispatched.poll(3, TimeUnit.SECONDS));
                    assertEquals(42, metrics.findLatest("lab-1").orElseThrow().cpuPercent());
                    assertNull(metrics.findLatest("lab-1").orElseThrow().temperatureCelsius());
                    assertNull(metrics.findLatest("lab-1").orElseThrow().batteryPercent());
                    for (var invalid : java.util.List.of(metricsPayload().put("cpuPercent", 101),
                            metricsPayload().put("ramPercent", "90"), metricsPayload().put("charging", "false"),
                            metricsPayload().put("batteryPercent", -1), metricsPayload().put("temperatureCelsius", "NaN"),
                            metricsPayload().put("collectedAt", "yesterday"), metricsPayload().remove(java.util.List.of("diskPercent")))) {
                        codec.write(socket.getOutputStream(), envelope(MessageType.METRICS, "bad", invalid));
                        var error = codec.read(socket.getInputStream());
                        assertEquals(MessageType.ERROR, error.type());
                        assertEquals("bad", dispatched.poll(3, TimeUnit.SECONDS));
                        assertEquals(42, metrics.findLatest("lab-1").orElseThrow().cpuPercent());
                    }
                    codec.write(socket.getOutputStream(), envelope(MessageType.METRICS, "new", metricsPayload()
                            .put("cpuPercent", 55).put("collectedAt", "2026-01-01T00:00:05Z")));
                    assertEquals("new", dispatched.poll(3, TimeUnit.SECONDS));
                    codec.write(socket.getOutputStream(), envelope(MessageType.METRICS, "stale", metricsPayload()));
                    assertEquals("stale", dispatched.poll(3, TimeUnit.SECONDS));
                    assertEquals(55, metrics.findLatest("lab-1").orElseThrow().cpuPercent());
                    var risk = JsonNodeFactory.instance.objectNode().put("riskType", "DISK_FULL")
                            .put("status", "OPEN").put("occurredAt", "2026-01-01T00:00:00Z").put("message", "95%");
                    codec.write(socket.getOutputStream(), envelope(MessageType.RISK_ALERT, "open", risk));
                    assertEquals("open", dispatched.poll(3, TimeUnit.SECONDS));
                    assertEquals(1, incidents.findAll("lab-1").size());
                    risk.put("status", "RESOLVED").put("occurredAt", "2026-01-01T00:00:05Z");
                    codec.write(socket.getOutputStream(), envelope(MessageType.RISK_RESOLVED, "resolve", risk));
                    assertEquals("resolve", dispatched.poll(3, TimeUnit.SECONDS));
                    assertEquals(RiskEvent.Status.RESOLVED, incidents.findAll("lab-1").getFirst().status());
                    codec.write(socket.getOutputStream(), envelope(MessageType.RISK_ALERT, "wrong-status", risk));
                    assertEquals(MessageType.ERROR, codec.read(socket.getInputStream()).type());
                    assertEquals("wrong-status", dispatched.poll(3, TimeUnit.SECONDS));
                    risk.put("status", "OPEN").put("riskType", "UNKNOWN");
                    codec.write(socket.getOutputStream(), envelope(MessageType.RISK_ALERT, "unknown-type", risk));
                    assertEquals(MessageType.ERROR, codec.read(socket.getInputStream()).type());
                    assertEquals("unknown-type", dispatched.poll(3, TimeUnit.SECONDS));
                    assertEquals(1, incidents.findAll("lab-1").size());
                }
                try (var connection = db.openConnection(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT count(*) FROM latest_metrics")) {
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
                }
            }
        }
    }
    private com.fasterxml.jackson.databind.node.ObjectNode metricsPayload() {
        return JsonNodeFactory.instance.objectNode().put("collectedAt", "2026-01-01T00:00:00Z")
                .put("cpuPercent", 42).put("ramPercent", 50).put("diskPercent", 70)
                .putNull("temperatureCelsius").putNull("batteryPercent").put("charging", false);
    }
    private ControlEnvelope envelope(MessageType type, String request, com.fasterxml.jackson.databind.JsonNode payload) {
        return new ControlEnvelope(type, request, "lab-1", payload);
    }
    private Socket connect(int port) throws Exception {
        var socket = new Socket("127.0.0.1", port); socket.setSoTimeout(3000); return socket;
    }
}
