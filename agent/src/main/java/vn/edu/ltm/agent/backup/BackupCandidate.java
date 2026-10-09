package vn.edu.ltm.agent.backup;

import java.time.Instant;
import java.util.Objects;

/** Portable, root-relative metadata for a file whose contents have been checked for stability. */
public record BackupCandidate(String relativePath, long size, Instant modifiedAt, String sha256) {
    public BackupCandidate {
        Objects.requireNonNull(relativePath);
        Objects.requireNonNull(modifiedAt);
        Objects.requireNonNull(sha256);
        if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("\\")
                || relativePath.contains(":")) throw new IllegalArgumentException("Unsafe relative path");
        for (String part : relativePath.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase("Recovered")
                    || part.indexOf('\0') >= 0) throw new IllegalArgumentException("Unsafe relative path");
        }
        if (size < 0 || size > BackupEligibility.MAX_FILE_SIZE) throw new IllegalArgumentException("Invalid file size");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
    }
}
