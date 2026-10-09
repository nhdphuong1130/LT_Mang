package vn.edu.ltm.agent.backup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Watches metadata changes; the consumer should hand work off without blocking this event loop. */
public final class ProtectedFolderWatcher implements AutoCloseable {
    private final Path root;
    private final long coalesceNanos;
    private final WatchService service;
    private final BackupEligibility eligibility = new BackupEligibility();
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final Map<Path, Long> pending = new LinkedHashMap<>();
    private volatile boolean closed;
    private volatile Exception lastFailure;
    private Thread thread;

    public ProtectedFolderWatcher(Path root) throws IOException { this(root, Duration.ofMillis(250)); }
    public ProtectedFolderWatcher(Path root, Duration coalesceFor) throws IOException {
        if (coalesceFor.isNegative() || coalesceFor.isZero()) throw new IllegalArgumentException("Positive coalescing duration required");
        this.root = BackupEligibility.realRoot(root);
        this.coalesceNanos = coalesceFor.toNanos();
        this.service = this.root.getFileSystem().newWatchService();
    }

    /** Initial registration finishes synchronously before this method returns. */
    public synchronized void start(Consumer<Path> changedFile) throws IOException {
        Objects.requireNonNull(changedFile);
        if (closed || thread != null) throw new IllegalStateException("Watcher already started or closed");
        try { registerTree(root, false); }
        catch (IOException failure) { close(); throw failure; }
        thread = Thread.ofPlatform().daemon().name("protected-folder-watcher").start(() -> run(changedFile));
    }

    /** Last failed registration/callback, if any; errors never silently kill the watch loop. */
    public Optional<Exception> lastFailure() { return Optional.ofNullable(lastFailure); }

    private void registerTree(Path start, boolean includeFiles) throws IOException {
        if (!BackupEligibility.protectedDirectory(root, start)) return;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!BackupEligibility.protectedDirectory(root, directory)) return FileVisitResult.SKIP_SUBTREE;
                WatchKey key = directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                if (BackupEligibility.protectedDirectory(root, directory)) directories.put(key, directory);
                else key.cancel();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (includeFiles) changed(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                lastFailure = failure;
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void run(Consumer<Path> consumer) {
        try {
            while (!closed) {
                WatchKey key = service.poll(25, TimeUnit.MILLISECONDS);
                if (key != null) {
                    process(key);
                    // Drain the current event burst before considering debounce deadlines.
                    while ((key = service.poll()) != null) process(key);
                }
                long now = System.nanoTime();
                var ready = new ArrayList<Path>();
                pending.entrySet().removeIf(entry -> {
                    if (now - entry.getValue() < coalesceNanos) return false;
                    ready.add(entry.getKey());
                    return true;
                });
                for (Path path : ready) {
                    if (closed) break;
                    var result = eligibility.evaluate(root, path);
                    if (result.eligible()) {
                        try { consumer.accept(result.path()); }
                        catch (RuntimeException failure) { lastFailure = failure; }
                    }
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // close() wakes the poll immediately.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally { pending.clear(); directories.clear(); }
    }

    private void process(WatchKey key) {
        Path directory = directories.get(key);
        if (directory == null || !BackupEligibility.protectedDirectory(root, directory)) {
            key.cancel();
            directories.remove(key);
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            try {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    // Recover missed create/modify events by registering and scanning the tree.
                    registerTree(root, true);
                    continue;
                }
                Path path = directory.resolve((Path) event.context()).normalize();
                if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) { pending.remove(path); continue; }
                if (BackupEligibility.protectedDirectory(root, path)) registerTree(path, true);
                else changed(path);
            } catch (IOException | RuntimeException failure) { lastFailure = failure; }
        }
        if (!key.reset()) directories.remove(key);
    }

    private void changed(Path file) {
        var result = eligibility.evaluate(root, file);
        if (result.eligible()) pending.put(result.path(), System.nanoTime());
    }

    @Override public void close() throws IOException {
        Thread worker;
        synchronized (this) {
            if (closed) return;
            closed = true;
            service.close();
            worker = thread;
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try { worker.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
