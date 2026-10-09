package vn.edu.ltm.agent.backup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

public final class FileStabilityChecker {
    public static final Duration DEFAULT_STABLE_FOR = Duration.ofSeconds(3);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_NANOS = Duration.ofMillis(100).toNanos();
    private final Path root;
    private final LongSupplier ticker;
    private final Sleeper sleeper;
    private final BackupEligibility eligibility = new BackupEligibility();

    public record StableFile(Path path, long size, FileTime modifiedTime, String sha256) { }
    @FunctionalInterface interface Sleeper { void sleep(Duration duration) throws IOException, InterruptedException; }

    public FileStabilityChecker(Path root) throws IOException {
        this(root, System::nanoTime, duration -> Thread.sleep(duration));
    }

    FileStabilityChecker(Path root, LongSupplier ticker, Sleeper sleeper) throws IOException {
        this.root = BackupEligibility.realRoot(root);
        this.ticker = Objects.requireNonNull(ticker);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public StableFile awaitStable(Path candidate, Duration stableFor, Duration timeout) throws IOException, InterruptedException {
        if (stableFor.isNegative() || timeout.isNegative() || timeout.isZero() || stableFor.compareTo(timeout) > 0)
            throw new IllegalArgumentException("Require 0 <= stableFor <= timeout and a positive timeout");
        long start = ticker.getAsLong();
        long stableSince = start;
        long timeoutNanos = timeout.toNanos();
        long stableNanos = stableFor.toNanos();
        Path path = BackupEligibility.resolve(root, candidate);
        BasicFileAttributes previous = snapshot(path);
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long now = ticker.getAsLong();
            if (now - start >= timeoutNanos) throw new IOException("File did not stabilize before timeout: " + path);
            BasicFileAttributes current = snapshot(path);
            if (!same(previous, current)) { previous = current; stableSince = now; }
            if (now - stableSince >= stableNanos) {
                String hash = checksum(path, start, timeoutNanos);
                var after = snapshot(path);
                if (same(current, after)) return new StableFile(path, after.size(), after.lastModifiedTime(), hash);
                previous = after;
                stableSince = ticker.getAsLong();
            }
            long remaining = timeoutNanos - (ticker.getAsLong() - start);
            if (remaining <= 0) throw new IOException("File did not stabilize before timeout: " + path);
            sleeper.sleep(Duration.ofNanos(Math.min(POLL_NANOS, remaining)));
        }
    }

    private BasicFileAttributes snapshot(Path path) throws IOException {
        var result = eligibility.evaluate(root, path);
        if (!result.eligible()) throw new IOException(result.reason());
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > BackupEligibility.MAX_FILE_SIZE)
            throw new IOException("File became ineligible");
        return attributes;
    }

    private static boolean same(BasicFileAttributes first, BasicFileAttributes second) {
        return first.size() == second.size() && first.lastModifiedTime().equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey());
    }

    private String checksum(Path path, long start, long timeoutNanos) throws IOException, InterruptedException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        try (var channel = openNoFollow(path)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long bytes = 0;
            int count;
            while ((count = channel.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (ticker.getAsLong() - start >= timeoutNanos) throw new IOException("Hashing exceeded stability timeout");
                bytes += count;
                if (bytes > BackupEligibility.MAX_FILE_SIZE) throw new IOException("File grew beyond 100 MiB while hashing");
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private SeekableByteChannel openNoFollow(Path path) throws IOException {
        // On supporting providers, anchor every directory lookup to an open handle. A concurrent
        // symlink swap cannot redirect the hash through an ancestor between validation and open.
        try (var stream = Files.newDirectoryStream(path.getRoot())) {
            if (stream instanceof SecureDirectoryStream<Path> secure) return openSecure(secure, path, 0);
        }
        // Windows providers do not expose SecureDirectoryStream. Validate all ancestors and open
        // the final file with NOFOLLOW_LINKS; snapshot() also validates again after hashing.
        snapshot(path);
        return Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    private static SeekableByteChannel openSecure(SecureDirectoryStream<Path> directory, Path path, int index)
            throws IOException {
        Path part = path.getName(index);
        if (index == path.getNameCount() - 1)
            return directory.newByteChannel(part, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try (var child = directory.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)) {
            return openSecure(child, path, index + 1);
        }
    }
}
