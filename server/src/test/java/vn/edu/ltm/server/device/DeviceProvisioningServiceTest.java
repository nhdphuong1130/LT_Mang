package vn.edu.ltm.server.device;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.persistence.Database;

import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class DeviceProvisioningServiceTest {
    @TempDir Path temp;

    @Test void initializesAllTablesIdempotentlyAndConfiguresEveryConnection() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        database.initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            var tables = new HashSet<String>();
            try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            assertTrue(tables.containsAll(java.util.Set.of("admin_users", "devices", "latest_metrics",
                    "incidents", "backup_files", "backup_versions", "restore_jobs", "audit_logs")));
            try (var rows = statement.executeQuery("PRAGMA foreign_keys")) { assertEquals(1, rows.getInt(1)); }
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) { assertEquals("wal", rows.getString(1)); }
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) { assertTrue(rows.getInt(1) > 0); }
        }
    }

    @Test void returnsTokenOnceAndPersistsOnlyCostTwelveHashAcrossReopen() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var repository = new JdbcDeviceRepository(database);
        var provisioning = new DeviceProvisioningService(repository);
        var issued = provisioning.provision("  lab-1  ", "Lab One", "Linux");
        database.initialize();
        assertEquals("lab-1", issued.deviceId());
        assertTrue(issued.token().length() >= 43);
        var stored = new JdbcDeviceRepository(new Database(temp.resolve("server.db"))).findById("lab-1").orElseThrow();
        assertNotEquals(issued.token(), stored.tokenHash());
        assertTrue(stored.tokenHash().startsWith("$2a$12$") || stored.tokenHash().startsWith("$2b$12$"));
        assertTrue(BCrypt.verifyer().verify(issued.token().toCharArray(), stored.tokenHash()).verified);
        assertTrue(provisioning.authenticate("lab-1", issued.token()));
        assertFalse(provisioning.authenticate("lab-1", "wrong"));
        assertFalse(provisioning.authenticate("lab-1", "x".repeat(43)));
        assertFalse(provisioning.authenticate("unknown", issued.token()));
        assertEquals(Device.Status.OFFLINE, stored.status());
        assertNull(stored.lastSeen());
        assertFalse(issued.toString().contains(issued.token()));
    }

    @Test void rejectsUnsafeIdsAndDuplicateProvisioningWithoutChangingToken() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var provisioning = new DeviceProvisioningService(new JdbcDeviceRepository(database));
        for (String invalid : new String[]{"", "  ", "../machine", "a/b", "a\\b", "a b", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> provisioning.provision(invalid, "name", "Linux"));
        }
        assertThrows(IllegalArgumentException.class, () -> provisioning.provision(null, "name", "Linux"));
        var issued = provisioning.provision("safe._-1", "Name", "Linux");
        assertThrows(IllegalStateException.class, () -> provisioning.provision("safe._-1", "Other", "Windows"));
        assertTrue(provisioning.authenticate(issued.deviceId(), issued.token()));
    }
}
