package vn.edu.ltm.server.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.auth.*;
import vn.edu.ltm.server.backup.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.SessionRegistry;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RestoreServiceTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    @Test void requiresAuthenticatedAdminAndPersistsOfflinePendingAcrossRestart() throws Exception {
        Fixture fixture = fixture();
        try (var sessions = new SessionRegistry(fixture.devices())) {
            var service = service(fixture, sessions, NOW);
            assertThrows(SecurityException.class, () -> service.request(fixture.versionId(), "device", "admin"));
            assertTrue(fixture.auth().authenticate("admin", "correct password".toCharArray()).authenticated());
            RestoreJob pending = service.request(fixture.versionId(), "device", "admin");
            assertEquals(RestoreJob.Status.PENDING, pending.status());
            assertEquals(RestoreJob.Status.PENDING, service.find(pending.id()).orElseThrow().status());
            var restarted = new RestoreService(new JdbcRestoreJobRepository(fixture.database()), fixture.auth(), sessions,
                    Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
            assertEquals(RestoreJob.Status.PENDING, restarted.find(pending.id()).orElseThrow().status());
            assertEquals(0, restarted.notifyPending("device"));
            assertEquals(RestoreJob.Status.PENDING, restarted.find(pending.id()).orElseThrow().status());
        }
    }

    @Test void notificationCreatesOneTimeDeviceBoundExpiringClaim() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var serverSide = listener.accept()) {
            assertTrue(sessions.register("device", serverSide).accepted());
            var service = service(fixture, sessions, NOW);
            RestoreJob job = service.request(fixture.versionId(), "device", "admin");
            assertEquals(RestoreJob.Status.AVAILABLE, job.status());
            ControlEnvelope offer = new LengthPrefixedJsonCodec().read(agent.getInputStream());
            assertEquals(MessageType.RESTORE_AVAILABLE, offer.type());
            String token = offer.payload().path("token").asText();
            assertTrue(token.length() >= 43);
            try (var connection = fixture.database().openConnection(); var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT token_hash FROM restore_jobs")) {
                assertTrue(rows.next());
                assertEquals(64, rows.getString(1).length());
                assertNotEquals(token, rows.getString(1));
            }
            assertThrows(SecurityException.class, () -> service.claim(token, "other"));
            var claim = service.claim(token, "device");
            assertEquals(fixture.versionId(), claim.versionId());
            assertThrows(SecurityException.class, () -> service.claim(token, "device"));
            service.complete(job.id(), new TransferResult(TransferResult.Status.STORED, "Restored"));
            assertEquals(RestoreJob.Status.COMPLETED, service.find(job.id()).orElseThrow().status());
        }
    }

    @Test void terminalStatusAndAuditAreCommittedTogetherForSuccessAndFailure() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
            assertTrue(sessions.register("device", server).accepted());
            var service = service(fixture, sessions, NOW);
            for (var result : java.util.List.of(
                    new TransferResult(TransferResult.Status.STORED, "Restored"),
                    new TransferResult(TransferResult.Status.REJECTED, "Bad checksum"))) {
                var job = service.request(fixture.versionId(), "device", "admin");
                String token = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
                service.claim(token, "device");
                try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE TRIGGER block_terminal_audit BEFORE INSERT ON audit_logs
                            WHEN NEW.action IN ('RESTORE_COMPLETE', 'RESTORE_FAILURE')
                            BEGIN SELECT RAISE(FAIL, 'blocked terminal audit'); END
                            """);
                }
                try {
                    assertThrows(java.sql.SQLException.class, () -> service.complete(job.id(), result));
                    assertEquals(RestoreJob.Status.IN_PROGRESS, service.find(job.id()).orElseThrow().status(),
                            "Readers must not see terminal status without its audit");
                } finally {
                    try (var connection = fixture.database().openConnection(); var statement = connection.createStatement()) {
                        statement.execute("DROP TRIGGER block_terminal_audit");
                    }
                }
                service.complete(job.id(), result);
                boolean success = result.status() == TransferResult.Status.STORED;
                assertEquals(success ? RestoreJob.Status.COMPLETED : RestoreJob.Status.FAILED,
                        service.find(job.id()).orElseThrow().status());
                try (var connection = fixture.database().openConnection(); var query = connection.prepareStatement(
                        "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND details LIKE ?")) {
                    query.setString(1, success ? "RESTORE_COMPLETE" : "RESTORE_FAILURE");
                    query.setString(2, "job=" + job.id() + "%");
                    try (var rows = query.executeQuery()) { assertEquals(1, rows.getInt(1)); }
                }
            }
        }
    }

    @Test void pendingJobIsOfferedWhenDeviceReconnects() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices())) {
            var service = service(fixture, sessions, NOW);
            var pending = service.request(fixture.versionId(), "device", "admin");
            try (var listener = new ServerSocket(0);
                 var agent = new Socket("127.0.0.1", listener.getLocalPort()); var serverSide = listener.accept()) {
                agent.setSoTimeout(3000);
                assertTrue(sessions.register("device", serverSide).accepted());
                var offer = new LengthPrefixedJsonCodec().read(agent.getInputStream());
                assertEquals(pending.id().toString(), offer.payload().path("jobId").asText());
                assertEquals(RestoreJob.Status.AVAILABLE, service.find(pending.id()).orElseThrow().status());
            }
        }
    }

    @Test void staleTokenCannotBeClaimedAndRequestIsAudited() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var serverSide = listener.accept()) {
            assertTrue(sessions.register("device", serverSide).accepted());
            RestoreJob job = service(fixture, sessions, NOW).request(fixture.versionId(), "device", "admin");
            String token = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
            var expired = service(fixture, sessions, NOW.plusSeconds(601));
            assertThrows(SecurityException.class, () -> expired.claim(token, "device"));
            assertEquals(RestoreJob.Status.AVAILABLE, expired.find(job.id()).orElseThrow().status());
        }
        try (var connection = fixture.database().openConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT COUNT(*) FROM audit_logs WHERE action = 'RESTORE_REQUEST'")) {
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test void expiredOfferGetsFreshTokenAfterReconnect() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        String stale;
        RestoreJob job;
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var serverSide = listener.accept()) {
            assertTrue(sessions.register("device", serverSide).accepted());
            job = service(fixture, sessions, NOW).request(fixture.versionId(), "device", "admin");
            stale = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
        }
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var serverSide = listener.accept()) {
            var restarted = service(fixture, sessions, NOW.plusSeconds(601));
            agent.setSoTimeout(3000);
            assertTrue(sessions.register("device", serverSide).accepted());
            String fresh = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
            assertNotEquals(stale, fresh);
            assertThrows(SecurityException.class, () -> restarted.claim(stale, "device"));
            assertEquals(job.id(), restarted.claim(fresh, "device").jobId());
        }
    }

    @Test void validAvailableOfferRotatesTokenOnReconnect() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices()); var firstListener = new ServerSocket(0);
             var firstAgent = new Socket("127.0.0.1", firstListener.getLocalPort());
             var firstServer = firstListener.accept()) {
            assertTrue(sessions.register("device", firstServer).accepted());
            var service = service(fixture, sessions, NOW);
            var job = service.request(fixture.versionId(), "device", "admin");
            String oldToken = new LengthPrefixedJsonCodec().read(firstAgent.getInputStream()).payload().path("token").asText();
            sessions.removeIfSame("device", sessions.find("device").orElseThrow().sessionId());
            try (var secondListener = new ServerSocket(0);
                 var secondAgent = new Socket("127.0.0.1", secondListener.getLocalPort());
                 var secondServer = secondListener.accept()) {
                secondAgent.setSoTimeout(3000);
                assertTrue(sessions.register("device", secondServer).accepted());
                String fresh = new LengthPrefixedJsonCodec().read(secondAgent.getInputStream()).payload().path("token").asText();
                assertNotEquals(oldToken, fresh);
                assertThrows(SecurityException.class, () -> service.claim(oldToken, "device"));
                assertEquals(job.id(), service.claim(fresh, "device").jobId());
            }
        }
    }

    @Test void crashRecoversClaimButOrdinaryReconnectLeavesActiveClaimAlone() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        RestoreJob job;
        String consumed;
        try (var sessions = new SessionRegistry(fixture.devices()); var firstListener = new ServerSocket(0);
             var firstAgent = new Socket("127.0.0.1", firstListener.getLocalPort());
             var firstServer = firstListener.accept()) {
            assertTrue(sessions.register("device", firstServer).accepted());
            var service = service(fixture, sessions, NOW);
            job = service.request(fixture.versionId(), "device", "admin");
            consumed = new LengthPrefixedJsonCodec().read(firstAgent.getInputStream()).payload().path("token").asText();
            service.claim(consumed, "device");
            sessions.removeIfSame("device", sessions.find("device").orElseThrow().sessionId());
            try (var secondListener = new ServerSocket(0);
                 var secondAgent = new Socket("127.0.0.1", secondListener.getLocalPort());
                 var secondServer = secondListener.accept()) {
                secondAgent.setSoTimeout(250);
                assertTrue(sessions.register("device", secondServer).accepted());
                assertThrows(SocketTimeoutException.class,
                        () -> new LengthPrefixedJsonCodec().read(secondAgent.getInputStream()));
                assertEquals(RestoreJob.Status.IN_PROGRESS, service.find(job.id()).orElseThrow().status());
            }
        }
        try (var sessions = new SessionRegistry(fixture.devices())) {
            var concurrent = service(fixture, sessions, NOW.plusSeconds(30));
            assertEquals(RestoreJob.Status.IN_PROGRESS, concurrent.find(job.id()).orElseThrow().status());
            var restarted = service(fixture, sessions, NOW.plusSeconds(121));
            assertEquals(RestoreJob.Status.PENDING, restarted.find(job.id()).orElseThrow().status());
            try (var listener = new ServerSocket(0);
                 var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
                agent.setSoTimeout(3000);
                assertTrue(sessions.register("device", server).accepted());
                String fresh = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
                assertNotEquals(consumed, fresh);
                assertThrows(SecurityException.class, () -> restarted.claim(consumed, "device"));
                assertEquals(job.id(), restarted.claim(fresh, "device").jobId());
            }
        }
        try (var connection = fixture.database().openConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT COUNT(*) FROM audit_logs WHERE action = 'RESTORE_RECOVERED'")) {
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test void heartbeatExtendsClaimLeaseSoConcurrentServiceCannotRecoverIt() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        var time = new AtomicReference<>(NOW);
        Clock mutableClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
            assertTrue(sessions.register("device", server).accepted());
            var first = new RestoreService(new JdbcRestoreJobRepository(fixture.database()), fixture.auth(), sessions,
                    mutableClock);
            var job = first.request(fixture.versionId(), "device", "admin");
            String token = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
            first.claim(token, "device");
            time.set(NOW.plusSeconds(90));
            assertTrue(first.renewLease(job.id()));
            var atOneFifty = service(fixture, sessions, NOW.plusSeconds(150));
            assertEquals(RestoreJob.Status.IN_PROGRESS, atOneFifty.find(job.id()).orElseThrow().status());
            var atTwoEleven = service(fixture, sessions, NOW.plusSeconds(211));
            assertEquals(RestoreJob.Status.PENDING, atTwoEleven.find(job.id()).orElseThrow().status());
        }
    }

    @Test void customClaimLeaseExpiresDeterministically() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
            assertTrue(sessions.register("device", server).accepted());
            var first = new RestoreService(new JdbcRestoreJobRepository(fixture.database()), fixture.auth(), sessions,
                    Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(10));
            var job = first.request(fixture.versionId(), "device", "admin");
            var token = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
            first.claim(token, "device");
            var beforeExpiry = service(fixture, sessions, NOW.plusSeconds(9));
            assertEquals(RestoreJob.Status.IN_PROGRESS, beforeExpiry.find(job.id()).orElseThrow().status());
            var afterExpiry = service(fixture, sessions, NOW.plusSeconds(11));
            assertEquals(RestoreJob.Status.PENDING, afterExpiry.find(job.id()).orElseThrow().status());
        }
    }

    @Test void concurrentNotifiersDoNotInvalidateAlreadyDeliveredOfferOnSameSession() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agent = new Socket("127.0.0.1", listener.getLocalPort()); var server = listener.accept()) {
            agent.setSoTimeout(350);
            assertTrue(sessions.register("device", server).accepted());
            var first = service(fixture, sessions, NOW);
            var second = service(fixture, sessions, NOW);
            var job = first.request(fixture.versionId(), "device", "admin");
            String delivered = new LengthPrefixedJsonCodec().read(agent.getInputStream()).payload().path("token").asText();
            var start = new CountDownLatch(1);
            Thread a = Thread.ofVirtual().start(() -> notifyWhenReleased(first, start));
            Thread b = Thread.ofVirtual().start(() -> notifyWhenReleased(second, start));
            start.countDown();
            a.join(3000);
            b.join(3000);
            assertFalse(a.isAlive());
            assertFalse(b.isAlive());
            assertThrows(SocketTimeoutException.class,
                    () -> new LengthPrefixedJsonCodec().read(agent.getInputStream()));
            assertEquals(job.id(), first.claim(delivered, "device").jobId());
        }
    }

    private static void notifyWhenReleased(RestoreService service, CountDownLatch start) {
        try {
            if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            service.notifyPending("device");
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test void failedOlderNotifierCannotRevertNewerToken() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        try (var sessions = new SessionRegistry(fixture.devices())) {
            var service = service(fixture, sessions, NOW);
            var job = service.request(fixture.versionId(), "device", "admin");
            var repository = new JdbcRestoreJobRepository(fixture.database());
            assertTrue(repository.offer(job.id(), RestoreJob.Status.PENDING, "", "old-hash",
                    NOW.plusSeconds(600), UUID.randomUUID()));
            var newerCommitted = new CountDownLatch(1);
            var threadFailure = new AtomicReference<Throwable>();
            Thread failedSender = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(newerCommitted.await(3, TimeUnit.SECONDS));
                    repository.revertOffer(job.id(), "old-hash");
                } catch (Throwable failure) { threadFailure.set(failure); }
            });
            Thread newerSender = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(repository.offer(job.id(), RestoreJob.Status.AVAILABLE, "old-hash", "new-hash",
                            NOW.plusSeconds(600), UUID.randomUUID()));
                } catch (Throwable failure) { threadFailure.set(failure); }
                finally { newerCommitted.countDown(); }
            });
            failedSender.join(3000);
            newerSender.join(3000);
            assertFalse(failedSender.isAlive());
            assertFalse(newerSender.isAlive());
            assertNull(threadFailure.get());
            assertEquals("new-hash", repository.offerSnapshot(job.id()).orElseThrow().tokenHash());
            assertEquals(RestoreJob.Status.AVAILABLE, service.find(job.id()).orElseThrow().status());
        }
    }

    @Test void allowsCrossDeviceTransferAndFolderBatchTransferFromSourceToTargetDevice() throws Exception {
        Fixture fixture = fixture();
        fixture.auth().authenticate("admin", "correct password".toCharArray());
        new DeviceProvisioningService(fixture.devices()).provision("pc-T", "May T", "Linux");
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agentT = new Socket("127.0.0.1", listener.getLocalPort()); var serverSideT = listener.accept()) {
            assertTrue(sessions.register("pc-T", serverSideT).accepted());
            var service = new RestoreService(new JdbcRestoreJobRepository(fixture.database()), fixture.auth(), sessions,
                    Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2),
                    new BackupService(new JdbcBackupRepository(fixture.database()), new StoragePathResolver(root.resolve("storage"))));
            var jobs = service.requestFolderTransfer("device", "", "pc-T", "admin");
            assertEquals(1, jobs.size());
            assertEquals("pc-T", jobs.getFirst().deviceId());
            assertTrue(Files.exists(root.resolve("storage/pc-T/report.txt")),
                    "Cross-device transfer must also copy file into target device's server folder");
            assertEquals(1, new JdbcBackupRepository(fixture.database()).versions("pc-T", "report.txt").size());
            ControlEnvelope offer = new LengthPrefixedJsonCodec().read(agentT.getInputStream());
            assertEquals(MessageType.RESTORE_AVAILABLE, offer.type());
            assertEquals("pc-T", offer.deviceId());
            String token = offer.payload().path("token").asText();
            var claimed = service.claim(token, "pc-T");
            assertEquals(fixture.versionId(), claimed.versionId());
            assertEquals("report.txt", claimed.relativePath());
        }
    }

    private RestoreService service(Fixture fixture, SessionRegistry sessions, Instant now) {
        return new RestoreService(new JdbcRestoreJobRepository(fixture.database()), fixture.auth(), sessions,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private Fixture fixture() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        var devices = new JdbcDeviceRepository(database);
        new DeviceProvisioningService(devices).provision("device", "Device", "Linux");
        var auth = new AdminAuthService(new JdbcAdminUserRepository(database));
        auth.createInitialAdmin("admin", "correct password".toCharArray());
        Path source = root.resolve("storage/blob");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "version contents");
        var backups = new JdbcBackupRepository(database);
        backups.commitVersion("device", "report.txt", Files.size(source), Hashing.sha256(source), source);
        long versionId = backups.versions("device", "report.txt").getFirst().id();
        return new Fixture(database, devices, auth, versionId);
    }

    private record Fixture(Database database, JdbcDeviceRepository devices, AdminAuthService auth, long versionId) { }
}
