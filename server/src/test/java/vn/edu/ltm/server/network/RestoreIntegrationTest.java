package vn.edu.ltm.server.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.restore.RestoreHandler;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.auth.*;
import vn.edu.ltm.server.backup.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.restore.*;
import vn.edu.ltm.server.session.SessionRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.common.transfer.*;

import static org.junit.jupiter.api.Assertions.*;

class RestoreIntegrationTest {
    @TempDir Path root;

    @Test void authenticatedRestoreStreamsIntoRecoveredWithoutOverwritingOriginal() throws Exception {
        Fixture fixture = fixture();
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectories(protectedRoot);
        Path original = protectedRoot.resolve("report.txt");
        Files.writeString(original, "local original");
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agentControl = new Socket("127.0.0.1", listener.getLocalPort()); var serverControl = listener.accept()) {
            assertTrue(sessions.register("device", serverControl).accepted());
            var service = fixture.service(sessions);
            try (var fileServer = new FileTransferServer("127.0.0.1", 0, fixture.provisioning(), fixture.backups(), service)) {
                fileServer.start();
                var job = service.request(fixture.versionId(), "device", "admin");
                ControlEnvelope offer = new LengthPrefixedJsonCodec().read(agentControl.getInputStream());
                Path restored = new RestoreHandler(protectedRoot, "127.0.0.1", fileServer.port(), "device", fixture.token())
                        .restore(offer);
                assertEquals("version contents", Files.readString(restored));
                assertEquals("local original", Files.readString(original));
                assertTrue(restored.startsWith(protectedRoot.resolve("Recovered")));
                for (int attempt = 0; attempt < 100 && service.find(job.id()).orElseThrow().status() != RestoreJob.Status.COMPLETED; attempt++)
                    Thread.sleep(10);
                assertEquals(RestoreJob.Status.COMPLETED, service.find(job.id()).orElseThrow().status());
            }
        }
    }

    @Test void changedBlobFailsChecksumAndRemovesPartialFile() throws Exception {
        Fixture fixture = fixture();
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectories(protectedRoot);
        try (var sessions = new SessionRegistry(fixture.devices()); var listener = new ServerSocket(0);
             var agentControl = new Socket("127.0.0.1", listener.getLocalPort()); var serverControl = listener.accept()) {
            assertTrue(sessions.register("device", serverControl).accepted());
            var service = fixture.service(sessions);
            try (var fileServer = new FileTransferServer("127.0.0.1", 0, fixture.provisioning(), fixture.backups(), service)) {
                fileServer.start();
                var job = service.request(fixture.versionId(), "device", "admin");
                var offer = new LengthPrefixedJsonCodec().read(agentControl.getInputStream());
                Files.writeString(fixture.blob(), "tampered content");
                assertThrows(IOException.class, () -> new RestoreHandler(protectedRoot, "127.0.0.1",
                        fileServer.port(), "device", fixture.token()).restore(offer));
                try (var files = Files.walk(protectedRoot)) {
                    assertTrue(files.noneMatch(path -> path.toString().endsWith(".part")));
                }
                for (int attempt = 0; attempt < 100 && service.find(job.id()).orElseThrow().status() != RestoreJob.Status.FAILED; attempt++)
                    Thread.sleep(10);
                assertEquals(RestoreJob.Status.FAILED, service.find(job.id()).orElseThrow().status());
                try (var connection = fixture.db().openConnection(); var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT COUNT(*) FROM audit_logs WHERE action = 'RESTORE_FAILURE'")) {
                    assertEquals(1, rows.getInt(1));
                }
            }
        }
    }

    @Test void agentDeletesPartAfterReceivingCorruptPayload() throws Exception {
        Fixture fixture = fixture();
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectories(protectedRoot);
        var result = new AtomicReference<TransferResult>();
        try (var listener = new ServerSocket(0)) {
            Thread sender = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept()) {
                    var codec = new FileTransferCodec();
                    codec.readHeader(socket.getInputStream());
                    codec.writeResult(socket.getOutputStream(), new TransferResult(TransferResult.Status.READY, "payload"));
                    socket.getOutputStream().write("tampered content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    result.set(codec.readResult(socket.getInputStream()));
                } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            var payload = JsonNodeFactory.instance.objectNode()
                    .put("token", "A".repeat(43)).put("relativePath", "report.txt")
                    .put("sha256", Hashing.sha256(fixture.blob())).put("size", Files.size(fixture.blob()))
                    .put("versionId", fixture.versionId()).put("requestedAt", Instant.now().toString());
            var offer = new ControlEnvelope(MessageType.RESTORE_AVAILABLE, UUID.randomUUID().toString(), "device", payload);
            assertThrows(IOException.class, () -> new RestoreHandler(protectedRoot, "127.0.0.1",
                    listener.getLocalPort(), "device", fixture.token()).restore(offer));
            sender.join(3000);
            assertEquals(TransferResult.Status.REJECTED, result.get().status());
            try (var files = Files.walk(protectedRoot)) {
                assertTrue(files.noneMatch(path -> Files.isRegularFile(path)));
            }
        }
    }

    @Test void swappedRecoveredDirectoryCannotRedirectPayloadOutsideProtected() throws Exception {
        Fixture fixture = fixture();
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectories(protectedRoot);
        Instant requestedAt = Instant.parse("2026-10-10T00:00:00Z");
        Path timestamp = protectedRoot.resolve("Recovered/20261010T000000Z");
        Path outside = root.resolve("outside");
        var headerRead = new CountDownLatch(1);
        var sendPayload = new CountDownLatch(1);
        var firstByteSent = new CountDownLatch(1);
        var finishPayload = new CountDownLatch(1);
        try (var listener = new ServerSocket(0)) {
            Thread sender = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept()) {
                    var codec = new FileTransferCodec();
                    codec.readHeader(socket.getInputStream());
                    headerRead.countDown();
                    if (!sendPayload.await(3, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    codec.writeResult(socket.getOutputStream(), new TransferResult(TransferResult.Status.READY, "payload"));
                    byte[] bytes = Files.readAllBytes(fixture.blob());
                    socket.getOutputStream().write(bytes[0]);
                    socket.getOutputStream().flush();
                    firstByteSent.countDown();
                    if (!finishPayload.await(3, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    try { socket.getOutputStream().write(bytes, 1, bytes.length - 1); }
                    catch (IOException disconnected) { /* Rejection can close the socket before remaining bytes. */ }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            var payload = JsonNodeFactory.instance.objectNode()
                    .put("token", "B".repeat(43)).put("relativePath", "report.txt")
                    .put("sha256", Hashing.sha256(fixture.blob())).put("size", Files.size(fixture.blob()))
                    .put("versionId", fixture.versionId()).put("requestedAt", requestedAt.toString());
            var offer = new ControlEnvelope(MessageType.RESTORE_AVAILABLE, UUID.randomUUID().toString(), "device", payload);
            var result = CompletableFuture.supplyAsync(() -> {
                try { return new RestoreHandler(protectedRoot, "127.0.0.1", listener.getLocalPort(),
                        "device", fixture.token()).restore(offer); }
                catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            assertTrue(headerRead.await(3, TimeUnit.SECONDS));
            Files.move(timestamp, outside);
            Files.createSymbolicLink(timestamp, outside);
            sendPayload.countDown();
            assertTrue(firstByteSent.await(3, TimeUnit.SECONDS));
            boolean wroteOutside = false;
            try {
                for (int attempt = 0; attempt < 50; attempt++) {
                    try (var files = Files.list(outside)) {
                        if (files.anyMatch(Files::isRegularFile)) { wroteOutside = true; break; }
                    }
                    Thread.sleep(10);
                }
            } finally { finishPayload.countDown(); }
            assertFalse(wroteOutside, "Even a temporary partial file must not be written outside Protected");
            assertThrows(Exception.class, () -> result.get(3, TimeUnit.SECONDS));
            sender.join(3000);
            try (var files = Files.walk(outside)) {
                assertTrue(files.noneMatch(Files::isRegularFile), "No partial or published file may escape Protected");
            }
        }
    }

    private Fixture fixture() throws Exception {
        var db = new Database(root.resolve("server.db"));
        db.initialize();
        var devices = new JdbcDeviceRepository(db);
        var provisioning = new DeviceProvisioningService(devices);
        String token = provisioning.provision("device", "Device", "Linux").token();
        var auth = new AdminAuthService(new JdbcAdminUserRepository(db));
        auth.createInitialAdmin("admin", "correct password".toCharArray());
        auth.authenticate("admin", "correct password".toCharArray());
        Path blob = root.resolve("storage/blob");
        Files.createDirectories(blob.getParent());
        Files.writeString(blob, "version contents");
        var repository = new JdbcBackupRepository(db);
        repository.commitVersion("device", "report.txt", Files.size(blob), Hashing.sha256(blob), blob);
        return new Fixture(db, devices, provisioning, token, auth,
                new BackupService(repository, new StoragePathResolver(root.resolve("storage"))),
                repository.versions("device", "report.txt").getFirst().id(), blob);
    }

    private record Fixture(Database db, JdbcDeviceRepository devices, DeviceProvisioningService provisioning,
                           String token, AdminAuthService auth, BackupService backups, long versionId, Path blob) {
        RestoreService service(SessionRegistry sessions) {
            return new RestoreService(new JdbcRestoreJobRepository(db), auth, sessions, Clock.systemUTC());
        }
    }
}
