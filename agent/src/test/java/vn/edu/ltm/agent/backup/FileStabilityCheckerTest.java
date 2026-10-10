package vn.edu.ltm.agent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.TestCapabilities;
import vn.edu.ltm.common.util.Hashing;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FileStabilityCheckerTest {
    @TempDir Path root;

    @Test void changesRestartTheFullThreeSecondWindow() throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "first");
        var elapsed = new AtomicLong();
        var checker = new FileStabilityChecker(root, elapsed::get, duration -> {
            long before = elapsed.getAndAdd(duration.toNanos());
            if (before < Duration.ofSeconds(2).toNanos() && elapsed.get() >= Duration.ofSeconds(2).toNanos())
                Files.writeString(file, "updated contents");
        });
        var stable = checker.awaitStable(file, Duration.ofSeconds(3), Duration.ofSeconds(10));
        assertTrue(elapsed.get() >= Duration.ofSeconds(5).toNanos());
        assertEquals(Files.size(file), stable.size());
        assertEquals(Hashing.sha256(file), stable.sha256());
    }

    @Test void constantlyChangingFileTimesOut() throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "");
        var elapsed = new AtomicLong();
        var checker = new FileStabilityChecker(root, elapsed::get, duration -> {
            elapsed.addAndGet(duration.toNanos());
            Files.writeString(file, "x", StandardOpenOption.APPEND);
        });
        assertThrows(java.io.IOException.class,
                () -> checker.awaitStable(file, Duration.ofSeconds(3), Duration.ofSeconds(4)));
        assertEquals(Duration.ofSeconds(4).toNanos(), elapsed.get());
    }

    @Test void symlinkReplacementDuringWindowIsRejected() throws Exception {
        TestCapabilities.assumeSymlinks(root);
        Path file = Files.writeString(root.resolve("file.txt"), "original");
        Path other = Files.writeString(root.resolve("other.txt"), "other");
        var elapsed = new AtomicLong();
        var checker = new FileStabilityChecker(root, elapsed::get, duration -> {
            elapsed.addAndGet(duration.toNanos());
            Files.delete(file);
            Files.createSymbolicLink(file, other);
        });
        assertThrows(java.io.IOException.class,
                () -> checker.awaitStable(file, Duration.ofSeconds(3), Duration.ofSeconds(5)));
    }

    @Test void interruptionAndInvalidDurationsAreNotIgnored() throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "hello");
        var checker = new FileStabilityChecker(root, () -> 0L, duration -> { throw new InterruptedException(); });
        assertThrows(InterruptedException.class,
                () -> checker.awaitStable(file, Duration.ofSeconds(3), Duration.ofSeconds(4)));
        assertThrows(IllegalArgumentException.class,
                () -> checker.awaitStable(file, Duration.ofSeconds(-1), Duration.ofSeconds(4)));
    }
}
