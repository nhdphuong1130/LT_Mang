package vn.edu.ltm.agent.restore;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.transfer.FileTransferCodec;
import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.common.util.Hashing;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RestoreHandlerDirectoryRaceTest {
    @TempDir Path root;

    @Test void directoryMovedOutsideDuringCreationNeverReceivesRestoredContent() throws Exception {
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectory(protectedRoot);
        Path source = root.resolve("source");
        Files.writeString(source, "version contents");
        Path recovered = protectedRoot.resolve("Recovered");
        Path outside = root.resolve("outside");
        try (var listener = new ServerSocket(0)) {
            var sender = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept()) {
                    var codec = new FileTransferCodec();
                    codec.readHeader(socket.getInputStream());
                    codec.writeResult(socket.getOutputStream(), new TransferResult(TransferResult.Status.READY, "Ready"));
                    socket.getOutputStream().write(Files.readAllBytes(source));
                    codec.readResult(socket.getInputStream());
                } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            var payload = JsonNodeFactory.instance.objectNode()
                    .put("token", "A".repeat(43)).put("relativePath", "report.txt")
                    .put("sha256", Hashing.sha256(source)).put("size", Files.size(source))
                    .put("versionId", 1).put("requestedAt", Instant.parse("2026-10-10T00:00:00Z").toString());
            var offer = new ControlEnvelope(MessageType.RESTORE_AVAILABLE, UUID.randomUUID().toString(), "device", payload);
            var handler = new RestoreHandler(protectedRoot, "127.0.0.1", listener.getLocalPort(), "device", "credential",
                    created -> {
                        if (created.equals(recovered)) {
                            Files.move(recovered, outside);
                            Files.createSymbolicLink(recovered, outside);
                        }
                    });
            assertThrows(IOException.class, () -> handler.restore(offer));
            sender.join(3000);
            try (var files = Files.walk(outside)) {
                assertTrue(files.noneMatch(Files::isRegularFile));
            }
            try (var files = Files.list(protectedRoot)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
            }
        }
    }

    @Test void directoryMovedOutsideAfterPublicationIsRolledBack() throws Exception {
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectory(protectedRoot);
        Path source = root.resolve("source");
        Files.writeString(source, "version contents");
        Path timestamp = protectedRoot.resolve("Recovered/20261010T000000Z");
        Path outside = root.resolve("outside");
        try (var listener = new ServerSocket(0)) {
            var sender = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept()) {
                    var codec = new FileTransferCodec();
                    codec.readHeader(socket.getInputStream());
                    codec.writeResult(socket.getOutputStream(), new TransferResult(TransferResult.Status.READY, "Ready"));
                    socket.getOutputStream().write(Files.readAllBytes(source));
                    codec.readResult(socket.getInputStream());
                } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            var payload = JsonNodeFactory.instance.objectNode()
                    .put("token", "A".repeat(43)).put("relativePath", "report.txt")
                    .put("sha256", Hashing.sha256(source)).put("size", Files.size(source))
                    .put("versionId", 1).put("requestedAt", Instant.parse("2026-10-10T00:00:00Z").toString());
            var offer = new ControlEnvelope(MessageType.RESTORE_AVAILABLE, UUID.randomUUID().toString(), "device", payload);
            var handler = new RestoreHandler(protectedRoot, "127.0.0.1", listener.getLocalPort(), "device", "credential",
                    created -> { }, published -> {
                        Files.move(timestamp, outside);
                        Files.createSymbolicLink(timestamp, outside);
                    });
            assertThrows(IOException.class, () -> handler.restore(offer));
            sender.join(3000);
            try (var files = Files.walk(outside)) {
                assertTrue(files.noneMatch(Files::isRegularFile));
            }
        }
    }
}
