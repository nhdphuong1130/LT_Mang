package vn.edu.ltm.server.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.TestCapabilities;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StoragePathResolverTest {
    @TempDir Path root;

    @Test void rejectsTraversalAbsoluteAndEncodedDeviceSeparator() throws Exception {
        var resolver = new StoragePathResolver(root);
        for (String path : new String[]{"../secret", "a/../secret", "/etc/passwd", "C:\\secret", "a\\secret"})
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve("device", path, "version"), path);
        for (String id : new String[]{"a/b", "a\\b", "..", "a%2Fb"})
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(id, "a.txt", "version"), id);
    }

    @Test void rejectsSymlinkStorageParent() throws Exception {
        TestCapabilities.assumeSymlinks(root);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path storage = Files.createDirectory(root.resolve("storage"));
        Files.createSymbolicLink(storage.resolve("device"), outside);
        var resolver = new StoragePathResolver(storage);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("device", "a.txt", "version"));
    }

    @Test void resolvesPerDeviceLivePathAndVersionSnapshotWithOriginalFileName() {
        var resolver = new StoragePathResolver(root);
        Path live = resolver.resolveLiveFile("pc-vanphong-B", "tai-lieu/bao-cao.txt");
        assertEquals(root.toAbsolutePath().normalize().resolve("pc-vanphong-B/tai-lieu/bao-cao.txt"), live);

        Path version = resolver.resolve("pc-vanphong-B", "tai-lieu/bao-cao.txt", "v1");
        assertEquals(root.toAbsolutePath().normalize()
                .resolve("pc-vanphong-B/.versions/tai-lieu/bao-cao.txt/v1_bao-cao.txt"), version);
    }
}
