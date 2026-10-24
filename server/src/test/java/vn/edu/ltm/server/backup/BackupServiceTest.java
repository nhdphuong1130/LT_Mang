package vn.edu.ltm.server.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.device.JdbcDeviceRepository;
import vn.edu.ltm.server.persistence.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {
    @TempDir Path root;

    @Test void duplicateLatestChecksumIsUnchangedAndSixthVersionRemovesOldestOnly() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        var issued = new DeviceProvisioningService(new JdbcDeviceRepository(database)).provision("device", "Device", "Linux");
        var repo = new JdbcBackupRepository(database);
        var service = new BackupService(repo, new StoragePathResolver(root.resolve("storage")));
        Path first = null;
        for (int i = 0; i < 6; i++) {
            Path payload = root.resolve("source-" + i);
            Files.writeString(payload, "version-" + i);
            String sha = Hashing.sha256(payload);
            var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                    issued.deviceId(), issued.token(), "nested/file.txt", Files.size(payload), sha, null);
            var reservation = service.acceptUpload(header);
            assertNotNull(reservation);
            Path temp = service.tempPath(reservation);
            Files.createDirectories(temp.getParent());
            Files.copy(payload, temp);
            assertEquals(TransferResult.Status.STORED, service.complete(reservation, temp, sha).status());
            if (i == 0) first = repo.versions("device", "nested/file.txt").getFirst().storagePath();
            if (i == 0) assertNull(service.acceptUpload(header), "Latest checksum must be skipped");
        }
        assertEquals(5, repo.versions("device", "nested/file.txt").size());
        assertFalse(Files.exists(first));
        assertTrue(repo.versions("device", "nested/file.txt").stream().allMatch(v -> Files.exists(v.storagePath())));
        Path liveMirror = root.resolve("storage/device/nested/file.txt");
        assertTrue(Files.exists(liveMirror), "Device folder must contain real relative path file");
        assertEquals("version-5", Files.readString(liveMirror));
    }

    @Test void closedTemporaryFileMustMatchHeaderEvenWhenReportedDigestMatches() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        new DeviceProvisioningService(new JdbcDeviceRepository(database)).provision("device", "Device", "Linux");
        var repo = new JdbcBackupRepository(database);
        var service = new BackupService(repo, new StoragePathResolver(root.resolve("storage")));
        Path expected = root.resolve("expected");
        Files.writeString(expected, "good");
        String expectedSha = Hashing.sha256(expected);
        var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                "device", "unused", "file.txt", 4, expectedSha, null);
        var reservation = service.acceptUpload(header);
        Path temp = service.tempPath(reservation);
        service.prepare(reservation);
        Files.writeString(temp, "evil");
        assertEquals(TransferResult.Status.REJECTED, service.complete(reservation, temp, expectedSha).status());
        assertTrue(repo.versions("device", "file.txt").isEmpty());
        assertFalse(Files.exists(temp));
    }

    @Test void failedOldBlobCleanupDoesNotTurnCommittedUploadIntoFailure() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        new DeviceProvisioningService(new JdbcDeviceRepository(database)).provision("device", "Device", "Linux");
        var repo = new JdbcBackupRepository(database);
        var service = new BackupService(repo, new StoragePathResolver(root.resolve("storage")));
        Path oldest = null;
        for (int i = 0; i < 6; i++) {
            Path source = root.resolve("source-" + i);
            Files.writeString(source, "version-" + i);
            String sha = Hashing.sha256(source);
            var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                    "device", "unused", "file.txt", Files.size(source), sha, null);
            var reservation = service.acceptUpload(header);
            service.prepare(reservation);
            Files.copy(source, service.tempPath(reservation));
            if (i == 5) {
                // A nonempty directory at the old blob path forces a portable delete failure.
                Files.delete(oldest);
                Files.createDirectory(oldest);
                Files.writeString(oldest.resolve("sentinel"), "keep");
            }
            assertEquals(TransferResult.Status.STORED,
                    service.complete(reservation, service.tempPath(reservation), sha).status());
            if (i == 0) oldest = repo.versions("device", "file.txt").getFirst().storagePath();
        }
        var versions = repo.versions("device", "file.txt");
        assertEquals(5, versions.size());
        assertEquals("version-5", Files.readString(versions.getFirst().storagePath()));
        assertTrue(Files.exists(oldest.resolve("sentinel")), "Cleanup debt must not erase committed metadata");
    }
}
