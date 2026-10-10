package vn.edu.ltm.server.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.backup.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileUploadIntegrationTest {
    @TempDir Path root;
    private final FileTransferCodec codec = new FileTransferCodec();

    @Test void validUploadAuthenticatesAndStoresExactPayload() throws Exception {
        var fixture = fixture();
        Path source = root.resolve("source");
        Files.writeString(source, "hello upload");
        try (var server = fixture.server()) {
            server.start();
            assertEquals(TransferResult.Status.STORED,
                    send(server.port(), header(fixture.token(), source), Files.readAllBytes(source)).status());
        }
        var versions = fixture.repo().versions("device", "file.txt");
        assertEquals(1, versions.size());
        assertEquals("hello upload", Files.readString(versions.getFirst().storagePath()));
    }

    @Test void rejectsWrongTokenAndOversizedHeaderBeforePayload() throws Exception {
        var fixture = fixture();
        try (var server = fixture.server()) {
            server.start();
            var normal = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                    "device", "x".repeat(43), "file.txt", 1, "0".repeat(64), null);
            assertEquals(TransferResult.Status.REJECTED, send(server.port(), normal, null).status());
            Path sparse = root.resolve("over-limit.bin");
            try (var random = new java.io.RandomAccessFile(sparse.toFile(), "rw")) {
                random.setLength(100L * 1024 * 1024 + 1);
            }
            var huge = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                    "device", fixture.token(), "file.txt", Files.size(sparse), "0".repeat(64), null);
            assertEquals(TransferResult.Status.REJECTED, send(server.port(), huge, null).status());
        }
        assertTrue(fixture.repo().versions("device", "file.txt").isEmpty());
    }

    @Test void interruptedAndWrongChecksumUploadsLeaveNoVersionOrPart() throws Exception {
        var fixture = fixture();
        Path source = root.resolve("source");
        Files.writeString(source, "abcdef");
        var header = header(fixture.token(), source);
        try (var server = fixture.server()) {
            server.start();
            try (var socket = new Socket("127.0.0.1", server.port())) {
                codec.writeHeader(socket.getOutputStream(), header);
                assertEquals(TransferResult.Status.READY, codec.readResult(socket.getInputStream()).status());
                socket.getOutputStream().write(new byte[]{1, 2});
            }
            // Wait for the connection handler to observe EOF and remove its temporary file.
            for (int i = 0; i < 100 && hasParts(); i++) Thread.sleep(10);
            assertFalse(hasParts());
            var wrong = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                    "device", fixture.token(), "file.txt", 6, "0".repeat(64), null);
            assertEquals(TransferResult.Status.REJECTED, send(server.port(), wrong, Files.readAllBytes(source)).status());
        }
        assertTrue(fixture.repo().versions("device", "file.txt").isEmpty());
        assertFalse(hasParts());
    }

    @Test void injectedDiskWriteFailureLeavesNoPublishedVersion() throws Exception {
        var fixture = fixture();
        Path source = root.resolve("source");
        Files.writeString(source, "write failure");
        try (var server = fixture.server(path -> new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("disk full"); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("disk full");
            }
        })) {
            server.start();
            assertEquals(TransferResult.Status.ERROR,
                    send(server.port(), header(fixture.token(), source), Files.readAllBytes(source)).status());
        }
        assertTrue(fixture.repo().versions("device", "file.txt").isEmpty());
        assertFalse(hasParts());
    }

    private boolean hasParts() throws IOException {
        Path temp = root.resolve("storage/.tmp");
        if (!Files.exists(temp)) return false;
        try (var files = Files.list(temp)) { return files.findAny().isPresent(); }
    }

    private TransferResult send(int port, FileTransferHeader header, byte[] payload) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            codec.writeHeader(socket.getOutputStream(), header);
            var initial = codec.readResult(socket.getInputStream());
            if (initial.status() != TransferResult.Status.READY) return initial;
            if (payload != null) socket.getOutputStream().write(payload);
            return codec.readResult(socket.getInputStream());
        }
    }

    private FileTransferHeader header(String token, Path source) throws IOException {
        return new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(), "device", token,
                "file.txt", Files.size(source), Hashing.sha256(source), null);
    }

    private Fixture fixture() throws Exception {
        var database = new Database(root.resolve("server.db"));
        database.initialize();
        var provisioning = new DeviceProvisioningService(new JdbcDeviceRepository(database));
        String token = provisioning.provision("device", "Device", "Linux").token();
        return new Fixture(token, provisioning, new JdbcBackupRepository(database),
                new BackupService(new JdbcBackupRepository(database), new StoragePathResolver(root.resolve("storage"))));
    }

    private record Fixture(String token, DeviceProvisioningService provisioning, JdbcBackupRepository repo,
                           BackupService service) {
        FileTransferServer server() { return new FileTransferServer("127.0.0.1", 0, provisioning, service); }
        FileTransferServer server(FileUploadHandler.TempOutputFactory factory) {
            return new FileTransferServer("127.0.0.1", 0, provisioning, service, factory);
        }
    }
}
