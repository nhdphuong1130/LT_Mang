package vn.edu.ltm.server.network;

import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.restore.RestoreService;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.sql.SQLException;

/** Sends one claimed backup version over the authenticated file socket. */
public final class FileRestoreHandler implements Runnable {
    private final Socket socket;
    private final FileTransferHeader header;
    private final DeviceProvisioningService provisioning;
    private final RestoreService restores;
    private final FileTransferCodec codec = new FileTransferCodec();

    public FileRestoreHandler(Socket socket, FileTransferHeader header, DeviceProvisioningService provisioning,
                              RestoreService restores) {
        this.socket = socket;
        this.header = header;
        this.provisioning = provisioning;
        this.restores = restores;
    }

    @Override public void run() {
        RestoreService.ClaimedRestore claim = null;
        try (socket) {
            if (!provisioning.authenticate(header.deviceId(), header.credential())) {
                send(TransferResult.Status.REJECTED, "Invalid device credential");
                return;
            }
            try {
                claim = restores.claim(header.transferId(), header.deviceId());
            } catch (SecurityException rejected) {
                send(TransferResult.Status.REJECTED, "Restore token unavailable");
                return;
            }
            if (!Long.toString(claim.versionId()).equals(header.versionId())
                    || !claim.relativePath().equals(header.relativePath())
                    || claim.size() != header.fileSize() || !claim.sha256().equals(header.sha256())) {
                send(TransferResult.Status.REJECTED, "Restore metadata mismatch");
                restores.complete(claim.jobId(), new TransferResult(TransferResult.Status.REJECTED, "Restore metadata mismatch"));
                return;
            }
            if (!Files.isRegularFile(claim.storagePath()) || Files.size(claim.storagePath()) != claim.size()
                    || !Hashing.sha256(claim.storagePath()).equals(claim.sha256())) {
                send(TransferResult.Status.ERROR, "Stored backup is unavailable or corrupt");
                restores.complete(claim.jobId(), new TransferResult(TransferResult.Status.ERROR, "Stored backup unavailable"));
                return;
            }
            send(TransferResult.Status.READY, "Download payload");
            try (var input = Files.newInputStream(claim.storagePath())) {
                byte[] buffer = new byte[65_536];
                long remaining = claim.size();
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("Stored backup ended early");
                    socket.getOutputStream().write(buffer, 0, count);
                    remaining -= count;
                }
                socket.getOutputStream().flush();
            }
            TransferResult result = codec.readResult(socket.getInputStream());
            restores.complete(claim.jobId(), result);
        } catch (Exception failure) {
            if (claim != null) try {
                restores.complete(claim.jobId(), new TransferResult(TransferResult.Status.ERROR, "Restore transfer failed"));
            } catch (SQLException | IllegalStateException ignored) { }
            try { send(TransferResult.Status.ERROR, "Restore transfer failed"); } catch (IOException ignored) { }
        }
    }

    private void send(TransferResult.Status status, String message) throws IOException {
        codec.writeResult(socket.getOutputStream(), new TransferResult(status, message));
    }
}
