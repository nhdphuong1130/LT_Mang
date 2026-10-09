package vn.edu.ltm.agent.backup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.Locale;
import java.util.Set;

public final class BackupEligibility {
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final LinkOption[] NO_FOLLOW = { LinkOption.NOFOLLOW_LINKS };

    public record EligibilityResult(boolean eligible, String reason, Path path, String relativePath) {
        private static EligibilityResult excluded(String reason) { return new EligibilityResult(false, reason, null, null); }
    }

    public EligibilityResult evaluate(Path root, Path candidate) {
        try {
            Path realRoot = realRoot(root);
            Path path = resolve(realRoot, candidate);
            String invalid = invalidPath(realRoot, path);
            if (invalid != null) return EligibilityResult.excluded(invalid);
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
            if (!attributes.isRegularFile()) return EligibilityResult.excluded("Not a regular file");
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.startsWith("~$") || name.endsWith(".tmp") || name.endsWith(".swp"))
                return EligibilityResult.excluded("Temporary file");
            if (!Files.isReadable(path) || hasNoReadPermission(path)) return EligibilityResult.excluded("File is unreadable");
            if (attributes.size() > MAX_FILE_SIZE) return EligibilityResult.excluded("File exceeds 100 MiB");
            String relative = realRoot.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
            // Keep persisted metadata portable and safe for both Windows and Linux consumers.
            if (relative.contains("\\") || relative.contains(":")) return EligibilityResult.excluded("Unsupported path characters");
            return new EligibilityResult(true, "Eligible", path, relative);
        } catch (IOException | SecurityException failure) {
            return EligibilityResult.excluded("Cannot access file: " + failure.getMessage());
        }
    }

    static Path realRoot(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) throw new IOException("Protected root is a symbolic link");
        Path real = normalized.toRealPath();
        if (!Files.isDirectory(real, NO_FOLLOW)) throw new IOException("Protected root is not a directory");
        // Callers retain this canonical root as their authority throughout their lifetime.
        return real;
    }

    static Path resolve(Path root, Path candidate) {
        return (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toAbsolutePath().normalize();
    }

    static boolean protectedDirectory(Path root, Path directory) {
        try {
            return invalidPath(root, directory) == null && Files.isDirectory(directory, NO_FOLLOW);
        } catch (IOException | SecurityException failure) { return false; }
    }

    private static String invalidPath(Path root, Path path) throws IOException {
        if (!path.startsWith(root)) return "Path is outside protected root";
        if (Files.isSymbolicLink(root) || !root.toRealPath().equals(root)) return "Protected root was replaced";
        Path current = root;
        for (Path part : root.relativize(path)) {
            if (part.toString().equalsIgnoreCase("Recovered")) return "Recovered directory is excluded";
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return "Symbolic links are excluded";
        }
        if (!path.toRealPath().startsWith(root)) return "Resolved path is outside protected root";
        return null;
    }

    private static boolean hasNoReadPermission(Path path) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, NO_FOLLOW);
        if (view == null) return false;
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        return !permissions.contains(PosixFilePermission.OWNER_READ)
                && !permissions.contains(PosixFilePermission.GROUP_READ)
                && !permissions.contains(PosixFilePermission.OTHERS_READ);
    }
}
