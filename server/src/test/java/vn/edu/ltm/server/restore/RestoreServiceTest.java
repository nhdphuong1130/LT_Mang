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
import java.util.UUID;

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
            var restarted = service(fixture, sessions, NOW.plusSeconds(30));
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
