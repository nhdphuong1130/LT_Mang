package vn.edu.ltm.agent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.persistence.AgentDatabase;
import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.common.util.Hashing;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class BackupWorkerTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test void notificationFailureCannotTurnSuccessfulUploadIntoRetry() throws Exception {
        Path protectedRoot = Files.createDirectory(root.resolve("protected-notification"));
        Path source = protectedRoot.resolve("file.txt");
        Files.writeString(source, "stored");
        var queue = queue();
        queue.enqueueOrUpdate(candidate(source, "file.txt"));
        var worker = new BackupWorker(queue,
                item -> new TransferResult(TransferResult.Status.STORED, "Stored"),
                protectedRoot, Clock.fixed(NOW, ZoneOffset.UTC),
                ignored -> { throw new IllegalStateException("UI unavailable"); });

        worker.runOnce();

        assertTrue(queue.nextReady(1).isEmpty());
    }

    @Test void acknowledgesOnlyClaimedChecksumWhenQueuedFileChangesDuringUpload() throws Exception {
        Path protectedRoot = Files.createDirectory(root.resolve("protected"));
        Path source = protectedRoot.resolve("file.txt");
        Files.writeString(source, "first");
        var queue = queue();
        queue.enqueueOrUpdate(candidate(source, "file.txt"));
        var worker = new BackupWorker(queue, item -> {
            Files.writeString(source, "second");
            queue.enqueueOrUpdate(candidate(source, "file.txt"));
            return new TransferResult(TransferResult.Status.STORED, "Stored");
        }, protectedRoot, Clock.fixed(NOW, ZoneOffset.UTC));
        worker.runOnce();
        var pending = queue.nextReady(1);
        assertEquals(1, pending.size());
        assertEquals(Hashing.sha256(source), pending.getFirst().candidate().sha256());
    }

    @Test void modifiedSourceWithoutWatcherIsRequeuedAndFailureGetsBoundedBackoff() throws Exception {
        Path protectedRoot = Files.createDirectory(root.resolve("protected"));
        Path source = protectedRoot.resolve("file.txt");
        Files.writeString(source, "first");
        var queue = queue();
        queue.enqueueOrUpdate(candidate(source, "file.txt"));
        var worker = new BackupWorker(queue, item -> {
            Files.writeString(source, "second");
            return new TransferResult(TransferResult.Status.STORED, "Stored");
        }, protectedRoot, Clock.fixed(NOW, ZoneOffset.UTC));
        worker.runOnce();
        var refreshed = queue.nextReady(1).getFirst();
        assertEquals(Hashing.sha256(source), refreshed.candidate().sha256());
        queue.markRetry(refreshed.id(), NOW, "reset");
        // The next claimed version fails, remains durable, and is due only after backoff.
        var failed = new BackupWorker(queue, item -> { throw new IOException("offline"); },
                protectedRoot, Clock.fixed(NOW, ZoneOffset.UTC));
        failed.runOnce();
        assertTrue(queue.nextReady(1).isEmpty());
        var later = new JdbcBackupQueueRepository(new AgentDatabase(root.resolve("agent.db")),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        assertEquals(1, later.nextReady(1).size());
    }

    @Test void uploaderStreamsClaimedFileOverOneAuthenticatedFileSession() throws Exception {
        Path protectedRoot = Files.createDirectory(root.resolve("protected"));
        Path source = protectedRoot.resolve("file.txt");
        Files.writeString(source, "on-wire");
        var queue = queue();
        queue.enqueueOrUpdate(candidate(source, "file.txt"));
        var item = queue.nextReady(1).getFirst();
        try (var listener = new ServerSocket(0); var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var observed = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    var codec = new vn.edu.ltm.common.transfer.FileTransferCodec();
                    var header = codec.readHeader(socket.getInputStream());
                    codec.writeResult(socket.getOutputStream(),
                            new TransferResult(TransferResult.Status.READY, "ready"));
                    byte[] payload = socket.getInputStream().readNBytes((int) header.fileSize());
                    codec.writeResult(socket.getOutputStream(),
                            new TransferResult(TransferResult.Status.STORED, "stored"));
                    return new Object[]{header, payload};
                }
            });
            var uploader = new BackupUploader(protectedRoot, "127.0.0.1", listener.getLocalPort(), "device", "secret");
            assertEquals(TransferResult.Status.STORED, uploader.upload(item).status());
            var received = observed.get(3, java.util.concurrent.TimeUnit.SECONDS);
            var header = (vn.edu.ltm.common.transfer.FileTransferHeader) received[0];
            assertEquals("device", header.deviceId());
            assertEquals("secret", header.credential());
            assertEquals("file.txt", header.relativePath());
            assertEquals(item.candidate().sha256(), header.sha256());
            assertArrayEquals("on-wire".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) received[1]);
        }
    }

    @Test void sourceModificationAfterHeaderCausesRetryOfNewChecksum() throws Exception {
        Path protectedRoot = Files.createDirectory(root.resolve("protected"));
        Path source = protectedRoot.resolve("file.txt");
        Files.writeString(source, "first");
        var queue = queue();
        queue.enqueueOrUpdate(candidate(source, "file.txt"));
        try (var listener = new ServerSocket(0); var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    var codec = new vn.edu.ltm.common.transfer.FileTransferCodec();
                    var header = codec.readHeader(socket.getInputStream());
                    Files.writeString(source, "other");
                    codec.writeResult(socket.getOutputStream(),
                            new TransferResult(TransferResult.Status.READY, "ready"));
                    socket.getInputStream().readNBytes((int) header.fileSize());
                    codec.writeResult(socket.getOutputStream(),
                            new TransferResult(TransferResult.Status.STORED, "stored"));
                    return null;
                }
            });
            var uploader = new BackupUploader(protectedRoot, "127.0.0.1", listener.getLocalPort(), "device", "secret");
            new BackupWorker(queue, uploader, protectedRoot).runOnce();
            peer.get(3, java.util.concurrent.TimeUnit.SECONDS);
            var pending = queue.nextReady(1);
            assertEquals(1, pending.size());
            assertEquals(Hashing.sha256(source), pending.getFirst().candidate().sha256());
        }
    }

    private JdbcBackupQueueRepository queue() throws Exception {
        var database = new AgentDatabase(root.resolve("agent.db"));
        database.initialize();
        return new JdbcBackupQueueRepository(database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BackupCandidate candidate(Path source, String relative) throws IOException {
        return new BackupCandidate(relative, Files.size(source), Files.getLastModifiedTime(source).toInstant(),
                Hashing.sha256(source));
    }
}
