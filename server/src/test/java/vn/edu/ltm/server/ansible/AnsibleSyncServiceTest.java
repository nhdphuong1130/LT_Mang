package vn.edu.ltm.server.ansible;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.auth.JdbcAdminUserRepository;
import vn.edu.ltm.server.backup.BackupService;
import vn.edu.ltm.server.backup.JdbcBackupRepository;
import vn.edu.ltm.server.backup.StoragePathResolver;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.device.JdbcDeviceRepository;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.restore.JdbcRestoreJobRepository;
import vn.edu.ltm.server.restore.RestoreService;
import vn.edu.ltm.server.session.SessionRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnsibleSyncServiceTest {
    @TempDir Path temp;

    @Test void fallbackGeneratesCanonicalFileAndBroadcastsOnlyToPermittedClients() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var devices = new JdbcDeviceRepository(database);
        var provisioning = new DeviceProvisioningService(devices);
        provisioning.provision("pc-allowed", "Allowed", "Windows");
        provisioning.provision("pc-blocked", "Blocked", "Linux");
        provisioning.setPermissions("pc-blocked", true, false);

        var auth = new AdminAuthService(new JdbcAdminUserRepository(database));
        auth.createInitialAdmin("admin", "AdminPassword123!".toCharArray());
        assertTrue(auth.authenticate("admin", "AdminPassword123!".toCharArray()).authenticated());

        try (var sessions = new SessionRegistry(devices)) {
            var backups = new BackupService(
                    new JdbcBackupRepository(database),
                    new StoragePathResolver(temp.resolve("storage")));
            var restores = new RestoreService(
                    new JdbcRestoreJobRepository(database),
                    auth,
                    sessions,
                    Clock.systemUTC(),
                    Duration.ofMinutes(2),
                    backups);
            restores.setProvisioning(provisioning);
            var service = new AnsibleSyncService(
                    temp.resolve("data"),
                    temp.resolve("missing-playbook-directory"),
                    provisioning,
                    restores);

            var outcome = service.initializeAndSyncAllClients("Hello LAN Clients", "admin");

            assertFalse(outcome.ranAnsibleCli());
            assertTrue(Files.isRegularFile(outcome.generatedFile()));
            assertTrue(Files.readString(outcome.generatedFile()).contains("Hello LAN Clients"));
            assertEquals(1, outcome.broadcastJobs().size());
            assertEquals("pc-allowed", outcome.broadcastJobs().getFirst().deviceId());
        }
    }
}
