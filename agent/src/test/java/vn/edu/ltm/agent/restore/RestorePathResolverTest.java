package vn.edu.ltm.agent.restore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.TestCapabilities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RestorePathResolverTest {
    @TempDir Path root;
    private final RestorePathResolver resolver = new RestorePathResolver();

    @Test void restoresIntoRecoveredTimestampWithoutReplacingExistingFile() throws Exception {
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectories(protectedRoot);
        Path source = protectedRoot.resolve("report.txt");
        Files.writeString(source, "original");
        Path first = resolver.resolve(protectedRoot, Instant.parse("2026-10-10T00:00:00Z"), "report.txt");
        assertTrue(first.startsWith(protectedRoot.resolve("Recovered")));
        assertNotEquals(source, first);
        Files.createDirectories(first.getParent());
        Files.writeString(first, "recovered");
        Path second = resolver.resolve(protectedRoot, Instant.parse("2026-10-10T00:00:00Z"), "report.txt");
        assertNotEquals(first, second);
        assertEquals("original", Files.readString(source));
    }

    @Test void rejectsTraversalAbsoluteAndSymlinkEscape() throws Exception {
        Path protectedRoot = root.resolve("Protected");
        Files.createDirectories(protectedRoot);
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        for (String hostile : new String[]{"../outside", "/tmp/outside", "a/../../outside", "C:\\outside"}) {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(protectedRoot, now, hostile));
        }
        TestCapabilities.assumeSymlinks(root);
        Path base = protectedRoot.resolve("Recovered/20261010T000000Z");
        Files.createDirectories(base);
        Files.createSymbolicLink(base.resolve("link"), root);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(protectedRoot, now, "link/escape.txt"));
    }
}
