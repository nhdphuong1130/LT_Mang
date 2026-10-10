package vn.edu.ltm.server.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path storage = Files.createDirectory(root.resolve("storage"));
        Files.createSymbolicLink(storage.resolve("device"), outside);
        var resolver = new StoragePathResolver(storage);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("device", "a.txt", "version"));
    }
}
