package vn.edu.ltm.agent.restore;

import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.transfer.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Downloads into a verified temporary file, then atomically links it into Recovered without replacement. */
public final class RestoreHandler {
    private final Path protectedRoot;
    private final String host;
    private final int port;
    private final String deviceId;
    private final String deviceCredential;
    private final RestorePathResolver paths = new RestorePathResolver();
    private final FileTransferCodec codec = new FileTransferCodec();

    public RestoreHandler(Path protectedRoot, String host, int port, String deviceId, String deviceCredential) {
        this.protectedRoot = protectedRoot;
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.deviceCredential = deviceCredential;
    }

    public Path restore(ControlEnvelope offer) throws IOException {
        if (offer == null || offer.type() != MessageType.RESTORE_AVAILABLE || !deviceId.equals(offer.deviceId())
                || offer.payload() == null) throw new IllegalArgumentException("Invalid restore notification");
        var payload = offer.payload();
        String token = payload.path("token").asText("");
        String relativePath = payload.path("relativePath").asText("");
        String sha256 = payload.path("sha256").asText("");
        long size = payload.path("size").asLong(-1);
        long versionId = payload.path("versionId").asLong(-1);
        if (!token.matches("[A-Za-z0-9_-]{43}") || !sha256.matches("[0-9a-f]{64}")
                || size < 0 || versionId < 1) throw new IllegalArgumentException("Invalid restore metadata");
        Instant requestedAt = Instant.parse(payload.path("requestedAt").asText());
        Path destination = paths.resolve(protectedRoot, requestedAt, relativePath);
        Files.createDirectories(destination.getParent());
        Path partial = destination.resolveSibling(destination.getFileName() + "." + UUID.randomUUID() + ".part");
        var header = new FileTransferHeader(FileOperation.RESTORE_DOWNLOAD, token, deviceId, deviceCredential,
                relativePath, size, sha256, Long.toString(versionId));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            codec.writeHeader(socket.getOutputStream(), header);
            TransferResult initial = codec.readResult(socket.getInputStream());
            if (initial.status() != TransferResult.Status.READY) throw new IOException(initial.message());
            try {
                MessageDigest digest = digest();
                try (var output = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    codec.copyExactly(socket.getInputStream(), output, size, digest);
                    output.flush();
                }
                try (var channel = FileChannel.open(partial, StandardOpenOption.WRITE)) { channel.force(true); }
                if (Files.size(partial) != size || !sha256.equals(HexFormat.of().formatHex(digest.digest())))
                    throw new IOException("Restore checksum mismatch");
                for (int attempt = 0; attempt < 100; attempt++) {
                    destination = paths.resolve(protectedRoot, requestedAt, relativePath);
                    try {
                        Files.createLink(destination, partial);
                        codec.writeResult(socket.getOutputStream(), new TransferResult(TransferResult.Status.STORED, "Restored"));
                        return destination;
                    } catch (FileAlreadyExistsException collision) {
                        // Another restore claimed this name; resolve the next one.
                    }
                }
                throw new IOException("No available restore filename");
            } catch (IOException failure) {
                try { codec.writeResult(socket.getOutputStream(),
                        new TransferResult(TransferResult.Status.REJECTED, "Restore verification failed")); }
                catch (IOException ignored) { }
                throw failure;
            } finally {
                try { Files.deleteIfExists(partial); }
                catch (IOException cleanupFailure) {
                    System.getLogger(RestoreHandler.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not remove restore partial file", cleanupFailure);
                }
            }
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
