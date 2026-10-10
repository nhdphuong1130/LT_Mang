package vn.edu.ltm.agent.backup;

import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.common.util.Hashing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** One authenticated socket per file; never shares the heartbeat channel. */
public final class BackupUploader {
    private final Path protectedRoot;
    private final String host;
    private final int port;
    private final String deviceId;
    private final String token;
    private final FileTransferCodec codec = new FileTransferCodec();

    public BackupUploader(Path protectedRoot, String host, int port, String deviceId, String token) {
        this.protectedRoot = protectedRoot;
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.token = token;
    }

    public TransferResult upload(BackupQueueItem item) throws IOException {
        var candidate = item.candidate();
        var eligibility = new BackupEligibility().evaluate(protectedRoot, protectedRoot.resolve(candidate.relativePath()));
        if (!eligibility.eligible() || eligibility.path() == null)
            throw new IOException("Source is unavailable or unsafe: " + eligibility.reason());
        Path source = eligibility.path();
        if (Files.size(source) != candidate.size() || !Hashing.sha256(source).equals(candidate.sha256()))
            throw new IOException("Source changed before upload");
        var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(), deviceId,
                token, candidate.relativePath(), candidate.size(), candidate.sha256(), null);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            codec.writeHeader(socket.getOutputStream(), header);
            TransferResult initial = codec.readResult(socket.getInputStream());
            if (initial.status() != TransferResult.Status.READY) return initial;
            MessageDigest digest = digest();
            try (var input = Files.newInputStream(source)) {
                codec.copyExactly(input, socket.getOutputStream(), candidate.size(), digest);
            }
            socket.getOutputStream().flush();
            TransferResult result = codec.readResult(socket.getInputStream());
            if (!HexFormat.of().formatHex(digest.digest()).equals(candidate.sha256())
                    || Files.size(source) != candidate.size() || !Hashing.sha256(source).equals(candidate.sha256()))
                throw new IOException("Source changed during upload");
            return result;
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
