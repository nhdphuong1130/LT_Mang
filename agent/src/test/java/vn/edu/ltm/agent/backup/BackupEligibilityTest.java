package vn.edu.ltm.agent.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import vn.edu.ltm.agent.TestCapabilities;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.RandomAccessFile;
import static org.junit.jupiter.api.Assertions.*;

class BackupEligibilityTest {
    @TempDir Path temporary;
    private final BackupEligibility eligibility = new BackupEligibility();

    @Test void acceptsNestedAndRelativeRegularFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path file = Files.writeString(Files.createDirectories(root.resolve("a/b")).resolve("file.txt"), "hello");
        var result = eligibility.evaluate(root, file);
        assertTrue(result.eligible(), result.reason());
        assertEquals(file.toRealPath(), result.path());
        assertEquals("a/b/file.txt", result.relativePath());
        assertTrue(eligibility.evaluate(root, Path.of("a/b/file.txt")).eligible());
    }

    @Test void rejectsTemporaryNamesDirectoriesMissingAndOutsidePathsWithReasons() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        for (String name : new String[]{"~$notes.docx", "file.tmp", "file.swp"})
            rejected(root, Files.writeString(root.resolve(name), "temp"));
        rejected(root, root);
        rejected(root, root.resolve("missing"));
        rejected(root, Files.writeString(temporary.resolve("outside.txt"), "outside"));
        rejected(root, Path.of("../outside.txt"));
    }

    @Test void excludesRecoveredAtAnyDepth() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        rejected(root, Files.writeString(Files.createDirectories(root.resolve("Recovered")).resolve("restore.txt"), "restore"));
        rejected(root, Files.writeString(Files.createDirectories(root.resolve("a/Recovered")).resolve("restore.txt"), "restore"));
    }

    @Test void rejectsFileAndAncestorSymlinksEvenWhenTargetIsInsideRoot() throws Exception {
        TestCapabilities.assumeSymlinks(temporary);
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path inner = Files.createDirectory(root.resolve("inner"));
        Path file = Files.writeString(inner.resolve("file.txt"), "hello");
        rejected(root, Files.createSymbolicLink(root.resolve("link.txt"), file));
        Path link = Files.createSymbolicLink(root.resolve("link-dir"), inner);
        rejected(root, link.resolve("file.txt"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        rejected(root, Files.createSymbolicLink(root.resolve("escape"), outside).resolve("secret.txt"));
    }

    @Test void rejectsRootReplacedBySymlink() throws Exception {
        TestCapabilities.assumeSymlinks(temporary);
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.delete(root);
        Files.createSymbolicLink(root, outside);
        rejected(root, root.resolve("secret.txt"));
    }

    @Test void rejectsUnreadableFile() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path file = Files.writeString(root.resolve("locked"), "secret");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try { rejected(root, file); }
        finally { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
    }

    @Test void boundsSparseFilesAtOneHundredMiBWithoutAllocatingPayload() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path file = root.resolve("sparse");
        try (var sparse = new RandomAccessFile(file.toFile(), "rw")) {
            sparse.setLength(100L * 1024 * 1024);
            assertTrue(eligibility.evaluate(root, file).eligible());
            sparse.setLength(100L * 1024 * 1024 + 1);
            rejected(root, file);
        }
    }

    private void rejected(Path root, Path path) {
        var result = eligibility.evaluate(root, path);
        assertFalse(result.eligible(), path.toString());
        assertFalse(result.reason().isBlank());
    }
}
