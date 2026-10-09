package vn.edu.ltm.server.incident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class IncidentServiceTest {
    @TempDir Path temp;
    @Test void oneOpenIncidentIsUpdatedResolvedAndCanReopenDurably() throws Exception {
        var db = new Database(temp.resolve("server.db")); db.initialize();
        new DeviceProvisioningService(new JdbcDeviceRepository(db)).provision("lab-1", "One", "Linux");
        var repository = new JdbcIncidentRepository(db);
        var service = new IncidentService(repository);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> service.record("lab-1",
                    new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.OPEN, start, "95%"))));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        service.record("lab-1", new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.UPDATE, start.plusSeconds(5), "98%"));
        var rows = repository.findAll("lab-1");
        assertEquals(1, rows.size());
        assertEquals(start, rows.getFirst().openedAt());
        assertEquals(start.plusSeconds(5), rows.getFirst().updatedAt());
        assertEquals("98%", rows.getFirst().message());
        service.record("lab-1", new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.RESOLVED, start.plusSeconds(10), "normal"));
        service.record("lab-1", new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.RESOLVED, start.plusSeconds(11), "normal"));
        assertEquals(start.plusSeconds(10), repository.findAll("lab-1").getFirst().resolvedAt());
        service.record("lab-1", new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.OPEN, start.plusSeconds(15), "99%"));
        rows = new JdbcIncidentRepository(new Database(temp.resolve("server.db"))).findAll("lab-1");
        assertEquals(2, rows.size());
        assertEquals(1, rows.stream().filter(r -> r.status() == RiskEvent.Status.OPEN).count());
    }

    @Test void updateCanRecoverAnOpenEventMissedWhileOffline() throws Exception {
        var db = new Database(temp.resolve("server.db")); db.initialize();
        new DeviceProvisioningService(new JdbcDeviceRepository(db)).provision("lab-1", "One", "Linux");
        var repository = new JdbcIncidentRepository(db);
        new IncidentService(repository).record("lab-1", new RiskEvent(RiskType.OVERHEAT,
                RiskEvent.Status.UPDATE, Instant.parse("2026-01-01T00:00:00Z"), "85 C"));
        assertEquals(RiskEvent.Status.OPEN, repository.findAll("lab-1").getFirst().status());
    }
}
