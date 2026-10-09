package vn.edu.ltm.agent.backup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

public final class StartupReconciler {
    private final Path root;
    private final BackupQueueRepository queue;
    private final FileStabilityChecker stability;
    private final Duration stableFor;
    private final Duration timeout;
    private final BackupEligibility eligibility = new BackupEligibility();

    public StartupReconciler(Path root, BackupQueueRepository queue) throws IOException {
        this(root, queue, new FileStabilityChecker(root), FileStabilityChecker.DEFAULT_STABLE_FOR, FileStabilityChecker.DEFAULT_TIMEOUT);
    }

    public StartupReconciler(Path root, BackupQueueRepository queue, FileStabilityChecker stability,
                             Duration stableFor, Duration timeout) throws IOException {
        this.root = BackupEligibility.realRoot(root);
        this.queue = Objects.requireNonNull(queue);
        this.stability = Objects.requireNonNull(stability);
        this.stableFor = Objects.requireNonNull(stableFor);
        this.timeout = Objects.requireNonNull(timeout);
    }

    public void reconcile() throws IOException, SQLException, InterruptedException {
        // Collect paths first: waiting for stability must not keep directory handles open.
        var files = new java.util.ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return BackupEligibility.protectedDirectory(root, directory) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (eligibility.evaluate(root, file).eligible()) files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path file : files) {
            FileStabilityChecker.StableFile stable;
            try { stable = stability.awaitStable(file, stableFor, timeout); }
            catch (IOException disappearedOrUnstable) { continue; }
            var result = eligibility.evaluate(root, stable.path());
            if (result.eligible()) queue.enqueueOrUpdate(new BackupCandidate(result.relativePath(), stable.size(),
                    stable.modifiedTime().toInstant(), stable.sha256()));
        }
    }
}
