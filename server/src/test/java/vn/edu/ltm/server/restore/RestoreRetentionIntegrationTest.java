package vn.edu.ltm.server.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.protocol.LengthPrefixedJsonCodec;
import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.auth.*;
import vn.edu.ltm.server.backup.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.SessionRegistry;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RestoreRetentionIntegrationTest {
    @TempDir Path root;

    @Test void completedJobRemainsAuditableAfterSixthUploadPrunesItsVersion() throws Exception {
        var fixture = fixture();
        for (int n = 0; n < 5; n++) fixture.upload(n);
        var oldest = fixture.repository().versions("device", "report.txt").getLast();
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
            assertTrue(sessions.register("device", server).accepted());
            var restores = fixture.restores(sessions);
            var job = restores.request(oldest.id(), "device", "admin");
            var offer = new LengthPrefixedJsonCodec().read(agent.getInputStream());
            restores.claim(offer.payload().path("token").asText(), "device");
            restores.complete(job.id(), new TransferResult(TransferResult.Status.STORED, "Restored"));
            fixture.upload(5);
            assertEquals(5, fixture.repository().versions("device", "report.txt").size());
            assertEquals(RestoreJob.Status.COMPLETED, restores.find(job.id()).orElseThrow().status());
            assertEquals(oldest.id(), restores.find(job.id()).orElseThrow().backupVersionId());
            assertFalse(Files.exists(oldest.storagePath()));
        }
    }

    @Test void pendingRestoreKeepsPrunedBlobUntilItFinishes() throws Exception {
        var fixture = fixture();
        for (int n = 0; n < 5; n++) fixture.upload(n);
        var oldest = fixture.repository().versions("device", "report.txt").getLast();
        try (var sessions = new SessionRegistry(fixture.devices())) {
            var restores = fixture.restores(sessions);
            var job = restores.request(oldest.id(), "device", "admin");
            assertEquals(RestoreJob.Status.PENDING, job.status());
            fixture.upload(5);
            assertEquals(5, fixture.repository().versions("device", "report.txt").size());
            assertTrue(Files.exists(oldest.storagePath()));
            try (var listener = new ServerSocket(0);
                 var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
                assertTrue(sessions.register("device", server).accepted());
                var offer = new LengthPrefixedJsonCodec().read(agent.getInputStream());
                var claim = restores.claim(offer.payload().path("token").asText(), "device");
                assertEquals("version-0", Files.readString(claim.storagePath()));
                restores.complete(job.id(), new TransferResult(TransferResult.Status.STORED, "Restored"));
            }
            assertFalse(Files.exists(oldest.storagePath()));
            assertEquals(RestoreJob.Status.COMPLETED, restores.find(job.id()).orElseThrow().status());
        }
    }

    @Test void staleVersionSnapshotCannotCreateAJobAfterRetentionDeletedItsBlob() throws Exception {
        var fixture = fixture();
        for (int n = 0; n < 5; n++) fixture.upload(n);
        var oldest = fixture.repository().versions("device", "report.txt").getLast();
        var jobs = new JdbcRestoreJobRepository(fixture.database());
        assertTrue(jobs.backup(oldest.id()).isPresent());
        fixture.upload(5);
        Instant now = Instant.now();
        var job = new RestoreJob(UUID.randomUUID(), oldest.id(), "device", "admin", now.plusSeconds(600),
                RestoreJob.Status.PENDING, now, null, null);
        assertThrows(IllegalArgumentException.class, () -> jobs.create(job));
        assertTrue(jobs.find(job.id()).isEmpty());
    }

    private Fixture fixture() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        var devices = new JdbcDeviceRepository(database);
        new DeviceProvisioningService(devices).provision("device", "Device", "Linux");
        var auth = new AdminAuthService(new JdbcAdminUserRepository(database));
        auth.createInitialAdmin("admin", "correct password".toCharArray());
        auth.authenticate("admin", "correct password".toCharArray());
        var repository = new JdbcBackupRepository(database);
        var backups = new BackupService(repository, new StoragePathResolver(root.resolve("storage")));
        return new Fixture(root, database, devices, auth, repository, backups);
    }

    private record Fixture(Path root, Database database, JdbcDeviceRepository devices, AdminAuthService auth,
                           JdbcBackupRepository repository, BackupService backups) {
        RestoreService restores(SessionRegistry sessions) {
            return new RestoreService(new JdbcRestoreJobRepository(database), auth, sessions, Clock.systemUTC());
        }

        void upload(int version) throws Exception {
            Path source = root.resolve("source-" + version);
            Files.writeString(source, "version-" + version);
            String sha = Hashing.sha256(source);
            var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                    "device", "unused", "report.txt", Files.size(source), sha, null);
            var reservation = backups.acceptUpload(header);
            backups.prepare(reservation);
            Files.copy(source, backups.tempPath(reservation));
            assertEquals(TransferResult.Status.STORED,
                    backups.complete(reservation, backups.tempPath(reservation), sha).status());
        }

    }
}
