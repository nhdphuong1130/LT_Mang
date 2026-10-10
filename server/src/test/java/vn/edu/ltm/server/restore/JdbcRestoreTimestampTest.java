package vn.edu.ltm.server.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.auth.JdbcAdminUserRepository;
import vn.edu.ltm.server.backup.JdbcBackupRepository;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.device.JdbcDeviceRepository;
import vn.edu.ltm.server.persistence.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcRestoreTimestampTest {
    @TempDir Path root;
    private static final Instant ZERO = Instant.parse("2026-10-10T00:00:00Z");

    @Test void fractionalOfferDeadlineIsClaimableUntilItsExactInstant() throws Exception {
        var fixture = fixture();
        var job = fixture.job(ZERO);
        var repo = fixture.repository();
        Instant deadline = ZERO.plusSeconds(10).plusMillis(500);
        assertTrue(repo.offer(job.id(), RestoreJob.Status.PENDING, "", "fractional", deadline, UUID.randomUUID()));
        assertTrue(repo.claim("fractional", "device", "owner", ZERO.plusSeconds(10), Duration.ofSeconds(10)).isPresent());
    }

    @Test void wholeSecondOfferDeadlineRejectsLaterFractionalClaim() throws Exception {
        var fixture = fixture();
        var job = fixture.job(ZERO);
        var repo = fixture.repository();
        assertTrue(repo.offer(job.id(), RestoreJob.Status.PENDING, "", "whole", ZERO.plusSeconds(10), UUID.randomUUID()));
        assertTrue(repo.claim("whole", "device", "owner", ZERO.plusSeconds(10).plusMillis(500),
                Duration.ofSeconds(10)).isEmpty());
    }

    @Test void fractionalClaimLeaseDoesNotRecoverEarlyAndDoesRecoverAtBoundary() throws Exception {
        var fixture = fixture();
        var job = fixture.job(ZERO);
        var repo = fixture.repository();
        claim(repo, job, ZERO.plusMillis(500));
        repo.recoverExpired(ZERO.plusSeconds(10));
        assertEquals(RestoreJob.Status.IN_PROGRESS, repo.find(job.id()).orElseThrow().status());
        repo.recoverExpired(ZERO.plusSeconds(10).plusMillis(500));
        assertEquals(RestoreJob.Status.PENDING, repo.find(job.id()).orElseThrow().status());
    }

    @Test void wholeSecondClaimLeaseRecoversAfterFractionalDelay() throws Exception {
        var fixture = fixture();
        var job = fixture.job(ZERO);
        var repo = fixture.repository();
        claim(repo, job, ZERO);
        repo.recoverExpired(ZERO.plusSeconds(10).plusMillis(500));
        assertEquals(RestoreJob.Status.PENDING, repo.find(job.id()).orElseThrow().status());
    }

    @Test void renewalAndCompletionUseExactSubSecondLeaseBoundary() throws Exception {
        var fixture = fixture();
        var earlyRenew = fixture.job(ZERO);
        var repo = fixture.repository();
        claim(repo, earlyRenew, ZERO.plusMillis(500));
        assertTrue(repo.renewLease(earlyRenew.id(), "owner", ZERO.plusSeconds(10), Duration.ofSeconds(10)));
        assertTrue(repo.renewLease(earlyRenew.id(), "owner", ZERO.plusSeconds(19), Duration.ofSeconds(10)));
        repo.complete(earlyRenew.id(), "owner", true, ZERO.plusSeconds(19).plusMillis(500), null);
        assertEquals(RestoreJob.Status.COMPLETED, repo.find(earlyRenew.id()).orElseThrow().status());

        var lateRenew = fixture.job(ZERO.plusSeconds(30));
        claim(repo, lateRenew, ZERO.plusSeconds(30));
        assertFalse(repo.renewLease(lateRenew.id(), "owner", ZERO.plusSeconds(40).plusMillis(500),
                Duration.ofSeconds(10)));
        assertThrows(IllegalStateException.class,
                () -> repo.complete(lateRenew.id(), "owner", true, ZERO.plusSeconds(40).plusMillis(500), null));
    }

    @Test void existingVariablePrecisionRowsAreNormalizedOnDatabaseInitialization() throws Exception {
        var fixture = fixture();
        var job = fixture.job(ZERO);
        try (var connection = fixture.database().openConnection(); var update = connection.prepareStatement("""
                UPDATE restore_jobs SET expires_at = ?, requested_at = ?, lease_expires_at = ? WHERE id = ?
                """)) {
            update.setString(1, "2026-10-10T00:00:10.500Z");
            update.setString(2, "2026-10-10T00:00:00Z");
            update.setString(3, "2026-10-10T00:00:20Z");
            update.setString(4, job.id().toString());
            update.executeUpdate();
        }
        fixture.database().initialize();
        try (var connection = fixture.database().openConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT expires_at, requested_at, lease_expires_at FROM restore_jobs")) {
            assertEquals("2026-10-10T00:00:10.500000000Z", rows.getString(1));
            assertEquals("2026-10-10T00:00:00.000000000Z", rows.getString(2));
            assertEquals("2026-10-10T00:00:20.000000000Z", rows.getString(3));
        }
    }

    private static void claim(JdbcRestoreJobRepository repo, RestoreJob job, Instant at) throws Exception {
        assertTrue(repo.offer(job.id(), RestoreJob.Status.PENDING, "", job.id().toString(),
                at.plusSeconds(100), UUID.randomUUID()));
        assertTrue(repo.claim(job.id().toString(), "device", "owner", at, Duration.ofSeconds(10)).isPresent());
    }

    private Fixture fixture() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        new DeviceProvisioningService(new JdbcDeviceRepository(database)).provision("device", "Device", "Linux");
        new AdminAuthService(new JdbcAdminUserRepository(database))
                .createInitialAdmin("admin", "correct password".toCharArray());
        Path blob = root.resolve("blob");
        Files.writeString(blob, "version contents");
        var backups = new JdbcBackupRepository(database);
        backups.commitVersion("device", "report.txt", Files.size(blob), Hashing.sha256(blob), blob);
        long versionId = backups.versions("device", "report.txt").getFirst().id();
        return new Fixture(database, versionId);
    }

    private record Fixture(Database database, long versionId) {
        JdbcRestoreJobRepository repository() { return new JdbcRestoreJobRepository(database); }
        RestoreJob job(Instant requestedAt) throws Exception {
            var job = new RestoreJob(UUID.randomUUID(), versionId, "device", "admin", requestedAt.plusSeconds(600),
                    RestoreJob.Status.PENDING, requestedAt, null, null);
            repository().create(job);
            return job;
        }
    }
}
