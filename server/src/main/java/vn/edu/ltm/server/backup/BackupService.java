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
        Path tempPath = paths.tempPath(header.transferId());
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
            try {
                var removed = repository.commitVersion(header.deviceId(), header.relativePath(), header.fileSize(),
                        header.sha256(), reservation.finalPath());
                for (Path path : removed) Files.deleteIfExists(path);
            } catch (SQLException failure) {
                Files.deleteIfExists(reservation.finalPath());
                throw failure;
            }
            return new TransferResult(TransferResult.Status.STORED, "Stored");
        } finally { Files.deleteIfExists(tempFile); }
    }

    public void abort(UploadReservation reservation) throws IOException { Files.deleteIfExists(reservation.tempPath()); }

    public record UploadReservation(FileTransferHeader header, Path tempPath, Path finalPath) { }
}
