package vn.edu.ltm.agent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.TestCapabilities;
import vn.edu.ltm.agent.persistence.AgentDatabase;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class StartupReconcilerTest {
    @TempDir Path temporary;

    @Test void scansOnlyEligibleFilesAndRequeuesChangedContentExactlyOnce() throws Exception {
        TestCapabilities.assumeSymlinks(temporary);
        Path root = Files.createDirectory(temporary.resolve("protected"));
        Path file = Files.writeString(Files.createDirectories(root.resolve("nested")).resolve("file.txt"), "first");
        Files.writeString(Files.createDirectories(root.resolve("Recovered")).resolve("restore.txt"), "restore");
        Files.writeString(root.resolve("ignored.tmp"), "temp");
        Path outside = Files.writeString(temporary.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("escape.txt"), outside);
        var database = new AgentDatabase(temporary.resolve("state.db"));
        database.initialize();
        var queue = new JdbcBackupQueueRepository(database);
        var scanner = new StartupReconciler(root, queue, new FileStabilityChecker(root), Duration.ZERO, Duration.ofSeconds(1));
        scanner.reconcile();
        var initial = queue.nextReady(10);
        assertEquals(1, initial.size());
        queue.markUploaded(initial.getFirst().id(), initial.getFirst().candidate().sha256());
        scanner.reconcile();
        assertTrue(queue.nextReady(10).isEmpty());
        Files.writeString(file, "second version");
        scanner.reconcile();
        scanner.reconcile();
        var changed = queue.nextReady(10);
        assertEquals(1, changed.size());
        assertNotEquals(initial.getFirst().candidate().sha256(), changed.getFirst().candidate().sha256());
        assertEquals("nested/file.txt", changed.getFirst().candidate().relativePath());
    }
}
