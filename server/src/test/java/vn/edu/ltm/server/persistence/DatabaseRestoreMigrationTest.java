package vn.edu.ltm.server.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.auth.*;
import vn.edu.ltm.server.backup.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.restore.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseRestoreMigrationTest {
    @TempDir Path root;

    @Test void oldRestoreRowsMigrateWithVersionSnapshotAndWithoutPruningForeignKey() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        new DeviceProvisioningService(new JdbcDeviceRepository(database)).provision("device", "Device", "Linux");
        new AdminAuthService(new JdbcAdminUserRepository(database))
                .createInitialAdmin("admin", "correct password".toCharArray());
        Path blob = root.resolve("blob");
        Files.writeString(blob, "old version");
        var backups = new JdbcBackupRepository(database);
        backups.commitVersion("device", "report.txt", Files.size(blob), Hashing.sha256(blob), blob);
        long versionId = backups.versions("device", "report.txt").getFirst().id();
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        var job = new RestoreJob(UUID.randomUUID(), versionId, "device", "admin", now.plusSeconds(600),
                RestoreJob.Status.PENDING, now, null, null);
        new JdbcRestoreJobRepository(database).create(job);

        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("""
                    CREATE TABLE restore_jobs_legacy (
                        id TEXT PRIMARY KEY, backup_version_id INTEGER NOT NULL REFERENCES backup_versions(id),
                        device_id TEXT NOT NULL REFERENCES devices(device_id),
                        admin_username TEXT NOT NULL REFERENCES admin_users(username),
                        token_hash TEXT NOT NULL, expires_at TEXT NOT NULL, status TEXT NOT NULL,
                        requested_at TEXT NOT NULL, completed_at TEXT, error TEXT)
                    """);
            statement.execute("""
                    INSERT INTO restore_jobs_legacy
                    SELECT id, backup_version_id, device_id, admin_username, token_hash,
                        expires_at, status, requested_at, completed_at, error FROM restore_jobs
                    """);
            statement.execute("DROP TABLE restore_jobs");
            statement.execute("ALTER TABLE restore_jobs_legacy RENAME TO restore_jobs");
        }

        database.initialize();
        var jobs = new JdbcRestoreJobRepository(database);
        assertEquals(RestoreJob.Status.PENDING, jobs.find(job.id()).orElseThrow().status());
        assertEquals("report.txt", jobs.metadata(job.id()).relativePath());
        assertEquals(blob, jobs.metadata(job.id()).storagePath());
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var foreignKeys = statement.executeQuery("PRAGMA foreign_key_list(restore_jobs)")) {
            while (foreignKeys.next()) assertNotEquals("backup_versions", foreignKeys.getString("table"));
        }
    }
}
