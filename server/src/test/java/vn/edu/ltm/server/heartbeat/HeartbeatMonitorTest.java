package vn.edu.ltm.server.heartbeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.SessionRegistry;

import java.net.Socket;
import java.nio.file.Path;
import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatMonitorTest {
    @TempDir Path temp;

    @Test void fifteenSecondDeadlineExpiresOnlySilentDeviceAndPersistsHeartbeat() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var devices = new JdbcDeviceRepository(database);
        var provisioning = new DeviceProvisioningService(devices);
        provisioning.provision("silent", "Silent", "Linux");
        provisioning.provision("active", "Active", "Linux");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        try (var registry = new SessionRegistry(devices, Clock.fixed(start, ZoneOffset.UTC));
             var silent = new Socket(); var active = new Socket()) {
            registry.register("silent", silent);
            registry.register("active", active);
            registry.touch("active", start.plusSeconds(10));
            assertEquals(start.plusSeconds(10), devices.findById("active").orElseThrow().lastSeen());
            assertEquals(start.plusSeconds(10), registry.find("active").orElseThrow().lastSeen());
            try (var monitor = new HeartbeatMonitor(registry, Duration.ofSeconds(15))) {
                monitor.check(start.plusSeconds(14));
                assertTrue(registry.find("silent").isPresent());
                monitor.check(start.plusSeconds(15));
                assertTrue(registry.find("silent").isEmpty());
                assertTrue(silent.isClosed());
                assertFalse(active.isClosed());
                assertEquals(Device.Status.OFFLINE, devices.findById("silent").orElseThrow().status());
                assertEquals(Device.Status.ONLINE, devices.findById("active").orElseThrow().status());
                registry.touch("silent", start.plusSeconds(16));
                assertEquals(Device.Status.OFFLINE, devices.findById("silent").orElseThrow().status());
            }
        }
        assertEquals(Device.Status.OFFLINE, devices.findById("active").orElseThrow().status());
    }
}
