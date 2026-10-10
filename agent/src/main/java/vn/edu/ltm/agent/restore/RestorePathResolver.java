package vn.edu.ltm.agent.restore;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class RestorePathResolver {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public Path resolve(Path protectedRoot, Instant requestedAt, String relativePath) {
        Objects.requireNonNull(protectedRoot);
        Objects.requireNonNull(requestedAt);
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0 || relativePath.matches("(?i)^[a-z]:.*"))
            throw new IllegalArgumentException("Unsafe restore path");
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) throw new IllegalArgumentException("Unsafe restore path");
        for (Path part : relative) if (part.toString().equals(".") || part.toString().equals(".."))
            throw new IllegalArgumentException("Unsafe restore path");
        Path root = protectedRoot.toAbsolutePath().normalize();
        Path base = root.resolve("Recovered").resolve(STAMP.format(requestedAt));
        Path destination = base.resolve(relative).normalize();
        if (!destination.startsWith(base) || destination.equals(base)) throw new IllegalArgumentException("Unsafe restore path");
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlink in restore path");
        for (Path segment : root.relativize(destination.getParent())) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlink in restore path");
        }
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return destination;
        String name = destination.getFileName().toString();
        for (int n = 1; n < Integer.MAX_VALUE; n++) {
            Path alternative = destination.resolveSibling(name + " (" + n + ")");
            if (!Files.exists(alternative, LinkOption.NOFOLLOW_LINKS)) return alternative;
        }
        throw new IllegalStateException("No available restore filename");
    }
}
