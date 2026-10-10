package vn.edu.ltm.server.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Validates portable logical names and forbids symlink parents in the storage tree. */
public final class StoragePathResolver {
    private final Path root;

    public StoragePathResolver(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Path root() { return root; }

    public Path resolve(String deviceId, String relativePath, String versionId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._-]{1,64}")
                || deviceId.equals(".") || deviceId.equals("..")
                || versionId == null || !versionId.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid storage identifier");
        validateRelativePath(relativePath);
        Path device = root.resolve(deviceId);
        checkParents(device);
        return device.resolve(versionId + ".blob");
    }

    public Path tempPath(String transferId) {
        if (transferId == null || !transferId.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid transfer ID");
        Path tempDir = root.resolve(".tmp");
        checkParents(tempDir);
        return tempDir.resolve(transferId + ".part");
    }

    public void ensureParent(Path path) throws IOException {
        checkParents(path.getParent());
        Files.createDirectories(path.getParent());
        checkParents(path.getParent());
    }

    private void checkParents(Path target) {
        Path current = target.getRoot();
        for (Path segment : target) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlink storage parent");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Storage parent is not a directory");
        }
    }

    public static void validateRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Unsafe relative path");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase("Recovered"))
                throw new IllegalArgumentException("Unsafe relative path");
        }
    }
}
