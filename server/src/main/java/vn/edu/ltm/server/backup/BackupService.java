package vn.edu.ltm.server.backup;

import vn.edu.ltm.common.transfer.FileOperation;
import vn.edu.ltm.common.transfer.FileTransferHeader;
import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.common.util.Hashing;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class BackupService {
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private final BackupRepository repository;
    private final StoragePathResolver paths;

    public BackupService(BackupRepository repository, StoragePathResolver paths) {
        this.repository = Objects.requireNonNull(repository);
        this.paths = Objects.requireNonNull(paths);
    }

    public synchronized UploadReservation acceptUpload(FileTransferHeader header) throws SQLException {
        if (header.operation() != FileOperation.BACKUP_UPLOAD || header.fileSize() < 0
                || header.fileSize() > MAX_FILE_SIZE || !header.sha256().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid upload metadata");
        Path finalPath = paths.resolve(header.deviceId(), header.relativePath(), UUID.randomUUID().toString());
        // The peer's transfer ID is correlation metadata, not a filesystem reservation key.
        Path tempPath = paths.tempPath(UUID.randomUUID().toString());
        var versions = repository.versions(header.deviceId(), header.relativePath());
        if (!versions.isEmpty() && versions.getFirst().sha256().equals(header.sha256())) return null;
        return new UploadReservation(header, tempPath, finalPath);
    }

    public Path tempPath(UploadReservation reservation) { return reservation.tempPath(); }

    public void prepare(UploadReservation reservation) throws IOException {
        paths.ensureParent(reservation.tempPath());
    }

    public synchronized TransferResult complete(UploadReservation reservation, Path tempFile, String actualSha256)
            throws IOException, SQLException {
        var header = reservation.header();
        try {
            if (!reservation.tempPath().equals(tempFile) || Files.size(tempFile) != header.fileSize()
                    || !header.sha256().equals(actualSha256) || !header.sha256().equals(Hashing.sha256(tempFile)))
                return new TransferResult(TransferResult.Status.REJECTED, "Size or checksum mismatch");
            var versions = repository.versions(header.deviceId(), header.relativePath());
            if (!versions.isEmpty() && versions.getFirst().sha256().equals(header.sha256()))
                return new TransferResult(TransferResult.Status.UNCHANGED, "Already stored");
            paths.ensureParent(reservation.finalPath());
            try {
                Files.move(tempFile, reservation.finalPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tempFile, reservation.finalPath());
            }
            java.util.List<Path> removed;
            try {
                removed = repository.commitVersion(header.deviceId(), header.relativePath(), header.fileSize(),
                        header.sha256(), reservation.finalPath());
            } catch (SQLException failure) {
                Files.deleteIfExists(reservation.finalPath());
                throw failure;
            }
            publishLiveFile(header.deviceId(), header.relativePath(), reservation.finalPath());
            for (Path path : removed) {
                try { Files.deleteIfExists(path); }
                catch (IOException | SecurityException failure) {
                    System.getLogger(BackupService.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not remove expired backup blob: " + path, failure);
                }
            }
            return new TransferResult(TransferResult.Status.STORED, "Stored");
        } finally { Files.deleteIfExists(tempFile); }
    }

    public void abort(UploadReservation reservation) throws IOException { Files.deleteIfExists(reservation.tempPath()); }

    public synchronized void copyFromAnotherDevice(
            String targetDeviceId, String relativePath, Path sourceBlob, long fileSize, String sha256)
            throws IOException, SQLException {
        if (!Files.isRegularFile(sourceBlob)) return;
        var existing = repository.versions(targetDeviceId, relativePath);
        if (!existing.isEmpty() && existing.getFirst().sha256().equals(sha256)) {
            publishLiveFile(targetDeviceId, relativePath, sourceBlob);
            return;
        }
        Path destVersionPath = paths.resolve(targetDeviceId, relativePath, UUID.randomUUID().toString());
        paths.ensureParent(destVersionPath);
        Files.copy(sourceBlob, destVersionPath, StandardCopyOption.REPLACE_EXISTING);
        java.util.List<Path> removed;
        try {
            removed = repository.commitVersion(targetDeviceId, relativePath, fileSize, sha256, destVersionPath);
        } catch (SQLException failure) {
            Files.deleteIfExists(destVersionPath);
            throw failure;
        }
        publishLiveFile(targetDeviceId, relativePath, destVersionPath);
        for (Path path : removed) {
            try { Files.deleteIfExists(path); } catch (IOException | SecurityException ignored) { }
        }
    }

    public synchronized long importServerFile(String targetDeviceId, String relativePath, Path localFile)
            throws IOException, SQLException {
        if (!Files.isRegularFile(localFile)) throw new IllegalArgumentException("Source file does not exist: " + localFile);
        long size = Files.size(localFile);
        String sha256 = Hashing.sha256(localFile);
        copyFromAnotherDevice(targetDeviceId, relativePath, localFile, size, sha256);
        var versions = repository.versions(targetDeviceId, relativePath);
        if (versions.isEmpty()) throw new IllegalStateException("Could not record imported server file");
        return versions.getFirst().id();
    }

    private void publishLiveFile(String deviceId, String relativePath, Path versionFile) throws IOException {
        Path livePath = paths.resolveLiveFile(deviceId, relativePath);
        paths.ensureParent(livePath);
        Path stage = livePath.resolveSibling("." + livePath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(versionFile, stage, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(stage, livePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(stage, livePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    public record UploadReservation(FileTransferHeader header, Path tempPath, Path finalPath) { }
}
