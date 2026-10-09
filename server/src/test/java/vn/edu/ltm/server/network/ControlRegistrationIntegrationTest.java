package vn.edu.ltm.server.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.server.config.ServerConfig;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.SessionRegistry;

import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ControlRegistrationIntegrationTest {
    @TempDir Path temp;
    final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();

    @Test void authenticatesRejectsDuplicatesAndHeartbeatUpdatesDurableLastSeen() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var devices = new JdbcDeviceRepository(database);
        var provisioning = new DeviceProvisioningService(devices);
        var issued = provisioning.provision("lab-1", "One", "Linux");
        var received = new LinkedBlockingQueue<ControlEnvelope>();
        var now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        try (var registry = new SessionRegistry(devices, clock);
             var server = new ControlTcpServer(new ServerConfig("127.0.0.1", 0), provisioning, registry,
                     clock, (deviceId, message) -> received.add(message))) {
            server.start();
            try (var wrong = connect(server.port())) {
                writeRegister(wrong, "lab-1", "wrong");
                assertFalse(codec.read(wrong.getInputStream()).payload().path("accepted").asBoolean());
                assertEquals(-1, wrong.getInputStream().read());
                assertTrue(registry.find("lab-1").isEmpty());
            }
            try (var owner = connect(server.port()); var duplicate = connect(server.port())) {
                writeRegister(owner, "lab-1", issued.token());
                assertTrue(codec.read(owner.getInputStream()).payload().path("accepted").asBoolean());
                var before = devices.findById("lab-1").orElseThrow().lastSeen();
                writeRegister(duplicate, "lab-1", issued.token());
                assertFalse(codec.read(duplicate.getInputStream()).payload().path("accepted").asBoolean());
                now.set(before.plusSeconds(5));
                codec.write(owner.getOutputStream(), new ControlEnvelope(MessageType.HEARTBEAT, "h", "lab-1",
                        JsonNodeFactory.instance.objectNode()));
                codec.write(owner.getOutputStream(), new ControlEnvelope(MessageType.METRICS, "m", "lab-1",
                        JsonNodeFactory.instance.objectNode()));
                assertNotNull(received.poll(3, TimeUnit.SECONDS));
                assertEquals(before.plusSeconds(5), devices.findById("lab-1").orElseThrow().lastSeen());
                assertEquals(Device.Status.ONLINE, devices.findById("lab-1").orElseThrow().status());
                assertTrue(registry.send("lab-1", new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "restore", "lab-1",
                        JsonNodeFactory.instance.objectNode())));
                assertEquals(MessageType.RESTORE_AVAILABLE, codec.read(owner.getInputStream()).type());
                codec.write(owner.getOutputStream(), new ControlEnvelope(MessageType.METRICS, "bad", "someone-else",
                        JsonNodeFactory.instance.objectNode()));
                assertEquals(MessageType.ERROR, codec.read(owner.getInputStream()).type());
                assertEquals(-1, owner.getInputStream().read());
            }
        }
        assertEquals(Device.Status.OFFLINE, devices.findById("lab-1").orElseThrow().status());
    }

    @Test void rejectsMessagesBeforeAuthenticationAndStillAcceptsNextClient() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var provisioning = new DeviceProvisioningService(new JdbcDeviceRepository(database));
        var issued = provisioning.provision("lab-1", "One", "Linux");
        try (var registry = new SessionRegistry(new JdbcDeviceRepository(database));
             var server = new ControlTcpServer(new ServerConfig("127.0.0.1", 0), provisioning, registry)) {
            server.start();
            try (var peer = connect(server.port())) {
                codec.write(peer.getOutputStream(), new ControlEnvelope(MessageType.HEARTBEAT, "h", "lab-1",
                        JsonNodeFactory.instance.objectNode()));
                assertEquals(MessageType.ERROR, codec.read(peer.getInputStream()).type());
                assertEquals(-1, peer.getInputStream().read());
            }
            try (var peer = connect(server.port())) {
                writeRegister(peer, "lab-1", issued.token());
                assertTrue(codec.read(peer.getInputStream()).payload().path("accepted").asBoolean());
            }
        }
    }

    private Socket connect(int port) throws Exception {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3000);
        return socket;
    }

    private void writeRegister(Socket socket, String deviceId, String token) throws Exception {
        codec.write(socket.getOutputStream(), new ControlEnvelope(MessageType.REGISTER, "register", deviceId,
                JsonNodeFactory.instance.objectNode().put("token", token)));
    }
}
