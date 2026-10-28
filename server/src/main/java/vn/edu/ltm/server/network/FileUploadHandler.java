package vn.edu.ltm.server.network;

import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.server.backup.BackupService;
import vn.edu.ltm.server.device.DeviceProvisioningService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileUploadHandler implements Runnable {
    @FunctionalInterface public interface TempOutputFactory { OutputStream open(Path path) throws IOException; }

    private final Socket socket;
    private final DeviceProvisioningService provisioning;
    private final BackupService backups;
    private final TempOutputFactory outputFactory;
    private final FileTransferHeader initialHeader;
    private final vn.edu.ltm.server.audit.AuditLogRepository auditLogs;
    private final FileTransferCodec codec = new FileTransferCodec();

    public FileUploadHandler(Socket socket, DeviceProvisioningService provisioning, BackupService backups,
                             TempOutputFactory outputFactory) {
        this(socket, provisioning, backups, outputFactory, null, null);
    }

    public FileUploadHandler(Socket socket, DeviceProvisioningService provisioning, BackupService backups,
                             TempOutputFactory outputFactory, FileTransferHeader initialHeader) {
        this(socket, provisioning, backups, outputFactory, initialHeader, null);
    }

    public FileUploadHandler(Socket socket, DeviceProvisioningService provisioning, BackupService backups,
                             TempOutputFactory outputFactory, FileTransferHeader initialHeader,
                             vn.edu.ltm.server.audit.AuditLogRepository auditLogs) {
        this.socket = socket;
        this.provisioning = provisioning;
        this.backups = backups;
        this.outputFactory = outputFactory;
        this.initialHeader = initialHeader;
        this.auditLogs = auditLogs;
    }

    @Override public void run() {
        BackupService.UploadReservation reservation = null;
        try (socket) {
            try {
                socket.setSoTimeout(30_000);
                FileTransferHeader header = initialHeader == null ? codec.readHeader(socket.getInputStream()) : initialHeader;
                if (!provisioning.authenticate(header.deviceId(), header.credential())) {
                    send(TransferResult.Status.REJECTED, "Invalid device credential");
                    return;
                }
                if (!provisioning.isBackupAllowed(header.deviceId())) {
                    send(TransferResult.Status.REJECTED, "Backup disabled by administrator");
                    return;
                }
                try {
                    reservation = backups.acceptUpload(header);
                } catch (IllegalArgumentException invalid) {
                    send(TransferResult.Status.REJECTED, invalid.getMessage());
                    return;
                }
                if (reservation == null) {
                    send(TransferResult.Status.UNCHANGED, "Already stored");
                    return;
                }
                backups.prepare(reservation);
                Path temp = backups.tempPath(reservation);
                MessageDigest digest = sha256();
                try (var output = outputFactory.open(temp)) {
                    send(TransferResult.Status.READY, "Send payload");
                    codec.copyExactly(socket.getInputStream(), output, header.fileSize(), digest);
                    output.flush();
                }
                try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
                TransferResult result = backups.complete(reservation, temp, HexFormat.of().formatHex(digest.digest()));
                if (auditLogs != null && (result.status() == TransferResult.Status.STORED || result.status() == TransferResult.Status.UNCHANGED)) {
                    auditLogs.log(java.time.Instant.now(), header.deviceId(), "BACKUP_UPLOAD", header.deviceId(),
                            "Upload sao lưu file: [" + header.relativePath() + "] (" + header.fileSize() + " B, " + result.status() + ")");
                }
                codec.writeResult(socket.getOutputStream(), result);
            } catch (Exception failure) {
                if (reservation != null) try { backups.abort(reservation); } catch (IOException ignored) { }
                try { send(TransferResult.Status.ERROR, "Upload failed"); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void send(TransferResult.Status status, String message) throws IOException {
        codec.writeResult(socket.getOutputStream(), new TransferResult(status, message));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static TempOutputFactory diskOutput() {
        return path -> Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
