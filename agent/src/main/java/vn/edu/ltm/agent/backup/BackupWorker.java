package vn.edu.ltm.agent.backup;

import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.common.util.Hashing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Processes a small durable queue batch; caller schedules it separately from heartbeats. */
public final class BackupWorker {
    @FunctionalInterface public interface Upload { TransferResult upload(BackupQueueItem item) throws Exception; }

    private final BackupQueueRepository queue;
    private final Upload uploader;
    private final Path protectedRoot;
    private final Clock clock;
    private final java.util.function.Consumer<BackupCandidate> onUploadSuccess;

    public BackupWorker(BackupQueueRepository queue, BackupUploader uploader, Path protectedRoot) {
        this(queue, uploader::upload, protectedRoot, Clock.systemUTC(), null);
    }

    public BackupWorker(BackupQueueRepository queue, BackupUploader uploader, Path protectedRoot,
                        java.util.function.Consumer<BackupCandidate> onUploadSuccess) {
        this(queue, uploader::upload, protectedRoot, Clock.systemUTC(), onUploadSuccess);
    }

    public BackupWorker(BackupQueueRepository queue, Upload uploader, Path protectedRoot, Clock clock) {
        this(queue, uploader, protectedRoot, clock, null);
    }

    public BackupWorker(BackupQueueRepository queue, Upload uploader, Path protectedRoot, Clock clock,
                        java.util.function.Consumer<BackupCandidate> onUploadSuccess) {
        this.queue = Objects.requireNonNull(queue);
        this.uploader = Objects.requireNonNull(uploader);
        this.protectedRoot = Objects.requireNonNull(protectedRoot);
        this.clock = Objects.requireNonNull(clock);
        this.onUploadSuccess = onUploadSuccess;
    }

    public void runOnce() throws SQLException {
        for (var item : queue.nextReady(8)) {
            try {
                TransferResult result = uploader.upload(item);
                if (sourceChanged(item)) throw new java.io.IOException("Source changed during upload");
                if (result.status() == TransferResult.Status.STORED
                        || result.status() == TransferResult.Status.UNCHANGED) {
                    queue.markUploaded(item.id(), item.candidate().sha256());
                    if (onUploadSuccess != null) {
                        try { onUploadSuccess.accept(item.candidate()); }
                        catch (RuntimeException notificationFailure) {
                            System.getLogger(BackupWorker.class.getName()).log(System.Logger.Level.WARNING,
                                    "Upload succeeded but its UI notification failed", notificationFailure);
                        }
                    }
                } else {
                    queue.markRetry(item.id(), nextAttempt(item.attempts()), result.status().name());
                }
            } catch (SQLException databaseFailure) {
                throw databaseFailure;
            } catch (Exception failure) {
                refreshCandidate(item);
                queue.markRetry(item.id(), nextAttempt(item.attempts()), failure.getClass().getSimpleName());
            }
        }
    }

    private boolean sourceChanged(BackupQueueItem item) throws java.io.IOException {
        var result = new BackupEligibility().evaluate(protectedRoot,
                protectedRoot.resolve(item.candidate().relativePath()));
        return !result.eligible() || Files.size(result.path()) != item.candidate().size()
                || !Hashing.sha256(result.path()).equals(item.candidate().sha256());
    }

    private void refreshCandidate(BackupQueueItem item) throws SQLException {
        var result = new BackupEligibility().evaluate(protectedRoot,
                protectedRoot.resolve(item.candidate().relativePath()));
        if (!result.eligible()) return;
        try {
            Path path = result.path();
            String sha = Hashing.sha256(path);
            if (!sha.equals(item.candidate().sha256())) queue.enqueueOrUpdate(new BackupCandidate(
                    item.candidate().relativePath(), Files.size(path), Files.getLastModifiedTime(path).toInstant(), sha));
        } catch (java.io.IOException ignored) { }
    }

    private java.time.Instant nextAttempt(int attempts) {
        long seconds = Math.min(60, 1L << Math.min(attempts, 6));
        return clock.instant().plus(Duration.ofSeconds(seconds));
    }
}
