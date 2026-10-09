package vn.edu.ltm.agent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.persistence.AgentDatabase;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcBackupQueueRepositoryTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String A = "a".repeat(64), B = "b".repeat(64);

    private JdbcBackupQueueRepository reopen() throws Exception {
        var database = new AgentDatabase(root.resolve("agent.db"));
        database.initialize();
        return new JdbcBackupQueueRepository(database, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private BackupCandidate candidate(String hash) { return new BackupCandidate("nested/file.txt", 10, NOW, hash); }

    @Test void pendingRowsSurviveReopenAndUpdatesKeepOneRow() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        queue.enqueueOrUpdate(candidate(B));
        var items = reopen().nextReady(10);
        assertEquals(1, items.size());
        assertEquals(B, items.getFirst().candidate().sha256());
        assertEquals(BackupQueueItem.Status.UPLOADING, items.getFirst().status());
        assertTrue(queue.nextReady(10).isEmpty(), "Claimed rows cannot be claimed twice");
    }

    @Test void uploadedChecksumSuppressesUnchangedFilesAcrossRestart() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        long id = queue.nextReady(1).getFirst().id();
        queue.markUploaded(id, A);
        queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        assertTrue(queue.nextReady(10).isEmpty());
        queue.enqueueOrUpdate(candidate(B));
        assertEquals(1, queue.nextReady(10).size());
    }

    @Test void abandonedUploadsAreRetriedAfterReopen() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        var abandoned = queue.nextReady(1).getFirst();
        var recovered = reopen().nextReady(1).getFirst();
        assertEquals(abandoned.id(), recovered.id());
        assertEquals(A, recovered.candidate().sha256());
    }

    @Test void retryRespectsDueTimeAndRetainsFailureMetadata() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        var item = queue.nextReady(1).getFirst();
        queue.markRetry(item.id(), NOW.plusSeconds(60), "offline");
        assertTrue(queue.nextReady(1).isEmpty());
        var database = new AgentDatabase(root.resolve("agent.db"));
        var later = new JdbcBackupQueueRepository(database, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        var retry = later.nextReady(1).getFirst();
        assertEquals(1, retry.attempts());
        assertEquals("offline", retry.lastError());
    }

    @Test void acknowledgmentCannotDiscardAnEditReceivedDuringUpload() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        var uploading = queue.nextReady(1).getFirst();
        queue.enqueueOrUpdate(candidate(B));
        assertTrue(queue.nextReady(10).isEmpty(), "New edit waits for existing transfer to finish");
        queue.markUploaded(uploading.id(), A);
        var changed = queue.nextReady(10);
        assertEquals(1, changed.size());
        assertEquals(B, changed.getFirst().candidate().sha256());
        queue.markUploaded(changed.getFirst().id(), B);
        assertTrue(queue.nextReady(10).isEmpty());
    }

    @Test void wrongChecksumCannotAcknowledgeAnUpload() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        var item = queue.nextReady(1).getFirst();
        assertThrows(java.sql.SQLException.class, () -> queue.markUploaded(item.id(), B));
        var retry = reopen().nextReady(1).getFirst();
        assertEquals(A, retry.candidate().sha256());
    }

    @Test void revertingAfterAnUnacknowledgedTransferIsStillQueued() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        var original = queue.nextReady(1).getFirst();
        queue.markUploaded(original.id(), A);
        queue.enqueueOrUpdate(candidate(B));
        var uncertain = queue.nextReady(1).getFirst();
        // Server may have committed B even though its acknowledgment was lost.
        queue.markRetry(uncertain.id(), NOW.plusSeconds(60), "Acknowledgment lost");
        queue.enqueueOrUpdate(candidate(A));
        var reverted = queue.nextReady(1);
        assertEquals(1, reverted.size(), "A must be sent again because B may now be the Server's latest version");
        assertEquals(A, reverted.getFirst().candidate().sha256());
    }

    @Test void unchangedReconciliationDoesNotResetRetryBackoff() throws Exception {
        var queue = reopen();
        queue.enqueueOrUpdate(candidate(A));
        var item = queue.nextReady(1).getFirst();
        queue.markRetry(item.id(), NOW.plusSeconds(60), "offline");
        queue.enqueueOrUpdate(candidate(A));
        assertTrue(queue.nextReady(10).isEmpty());
    }

    @Test void concurrentRepositoriesCannotClaimTheSameRows() throws Exception {
        var queue = reopen();
        for (int index = 0; index < 20; index++)
            queue.enqueueOrUpdate(new BackupCandidate("file-" + index, 10, NOW, A));
        var database = new AgentDatabase(root.resolve("agent.db"));
        var other = new JdbcBackupQueueRepository(database, Clock.fixed(NOW, ZoneOffset.UTC));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> { start.await(); return queue.nextReady(20); });
            var second = workers.submit(() -> { start.await(); return other.nextReady(20); });
            start.countDown();
            var claimed = new java.util.ArrayList<>(first.get(3, java.util.concurrent.TimeUnit.SECONDS));
            claimed.addAll(second.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(20, claimed.size());
            assertEquals(20, claimed.stream().map(BackupQueueItem::id).distinct().count());
        }
    }

    @Test void rejectsUnsafeQueuePathsAndInvalidLimits() throws Exception {
        var queue = reopen();
        for (String path : new String[]{"../outside", "/absolute", "a/../b", "C:\\outside", "a\\b", "Recovered/a"})
            assertThrows(IllegalArgumentException.class, () -> queue.enqueueOrUpdate(new BackupCandidate(path, 1, NOW, A)));
        assertThrows(IllegalArgumentException.class, () -> queue.nextReady(0));
    }
}
