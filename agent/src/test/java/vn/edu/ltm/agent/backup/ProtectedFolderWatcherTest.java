package vn.edu.ltm.agent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedFolderWatcherTest {
    @TempDir Path root;

    @Test void watchesExistingAndNewSubdirectoriesIncludingFilesCreatedBeforeRegistration() throws Exception {
        Path existing = Files.createDirectories(root.resolve("existing/deep"));
        var events = new LinkedBlockingQueue<Path>();
        try (var watcher = new ProtectedFolderWatcher(root, Duration.ofMillis(80))) {
            watcher.start(events::add);
            Path first = Files.writeString(existing.resolve("first.txt"), "first");
            assertEquals(first, events.poll(3, TimeUnit.SECONDS));
            Path added = Files.createDirectories(root.resolve("new/deep"));
            Path second = Files.writeString(added.resolve("second.txt"), "second");
            assertEquals(second, events.poll(3, TimeUnit.SECONDS));
            Files.writeString(second, "changed");
            assertEquals(second, events.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test void duplicateCreateAndModifyEventsCoalesce() throws Exception {
        var events = new LinkedBlockingQueue<Path>();
        try (var watcher = new ProtectedFolderWatcher(root, Duration.ofMillis(150))) {
            watcher.start(events::add);
            Path file = root.resolve("file.txt");
            for (int index = 0; index < 20; index++) Files.writeString(file, "change " + index);
            assertEquals(file, events.poll(3, TimeUnit.SECONDS));
            assertNull(events.poll(400, TimeUnit.MILLISECONDS));
        }
    }

    @Test void excludesRecoveredSymlinksTemporaryFilesAndStopsOnClose() throws Exception {
        Path recovered = Files.createDirectory(root.resolve("Recovered"));
        Path outside = Files.createTempDirectory("watcher-outside-");
        var events = new LinkedBlockingQueue<Path>();
        var watcher = new ProtectedFolderWatcher(root, Duration.ofMillis(50));
        try {
            watcher.start(events::add);
            Files.writeString(recovered.resolve("ignored.txt"), "restore");
            Files.writeString(root.resolve("ignored.tmp"), "temp");
            Files.createSymbolicLink(root.resolve("escape"), outside);
            Files.writeString(outside.resolve("secret.txt"), "secret");
            Path valid = Files.writeString(root.resolve("valid.txt"), "valid");
            assertEquals(valid, events.poll(3, TimeUnit.SECONDS));
            assertNull(events.poll(250, TimeUnit.MILLISECONDS));
            watcher.close();
            Files.writeString(valid, "after close");
            assertNull(events.poll(250, TimeUnit.MILLISECONDS));
        } finally {
            watcher.close();
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }
}
