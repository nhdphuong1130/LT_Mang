package vn.edu.ltm.agent.restore;

import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.transfer.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Downloads into a verified temporary file, then atomically links it into Recovered without replacement. */
public final class RestoreHandler {
    private final Path protectedRoot;
    private final String host;
    private final int port;
    private final String deviceId;
    private final String deviceCredential;
    private final RestorePathResolver paths = new RestorePathResolver();
    private final FileTransferCodec codec = new FileTransferCodec();
    private final SecureRandom random = new SecureRandom();

    public RestoreHandler(Path protectedRoot, String host, int port, String deviceId, String deviceCredential) {
        this.protectedRoot = protectedRoot;
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.deviceCredential = deviceCredential;
    }

    public Path restore(ControlEnvelope offer) throws IOException {
        if (offer == null || offer.type() != MessageType.RESTORE_AVAILABLE || !deviceId.equals(offer.deviceId())
                || offer.payload() == null) throw new IllegalArgumentException("Invalid restore notification");
        var payload = offer.payload();
        String token = payload.path("token").asText("");
        String relativePath = payload.path("relativePath").asText("");
        String sha256 = payload.path("sha256").asText("");
        long size = payload.path("size").asLong(-1);
        long versionId = payload.path("versionId").asLong(-1);
        if (!token.matches("[A-Za-z0-9_-]{43}") || !sha256.matches("[0-9a-f]{64}")
                || size < 0 || versionId < 1) throw new IllegalArgumentException("Invalid restore metadata");
        Instant requestedAt = Instant.parse(payload.path("requestedAt").asText());
        Path destination = paths.resolve(protectedRoot, requestedAt, relativePath);
        prepareDestinationParent(destination.getParent());
        // Opening every existing parent with NOFOLLOW_LINKS anchors subsequent writes to directory handles.
        if (!paths.resolve(protectedRoot, requestedAt, relativePath).getParent().equals(destination.getParent()))
            throw new IOException("Restore destination changed");
        String partialName = ".restore-" + UUID.randomUUID() + ".part";
        var header = new FileTransferHeader(FileOperation.RESTORE_DOWNLOAD, token, deviceId, deviceCredential,
                relativePath, size, sha256, Long.toString(versionId));
        try (var safe = SafeDirectory.open(protectedRoot, destination.getParent()); var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            codec.writeHeader(socket.getOutputStream(), header);
            TransferResult initial = codec.readResult(socket.getInputStream());
            if (initial.status() != TransferResult.Status.READY) throw new IOException(initial.message());
            try {
                safe.revalidate();
                MessageDigest digest = digest();
                try (var channel = safe.openNew(partialName); var output = Channels.newOutputStream(channel)) {
                    var checked = new java.io.FilterOutputStream(output) {
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                            safe.revalidate();
                            out.write(bytes, offset, length);
                            safe.revalidate();
                        }
                    };
                    codec.copyExactly(socket.getInputStream(), checked, size, digest);
                    output.flush();
                    if (channel.size() != size) throw new IOException("Restore size mismatch");
                    if (channel instanceof FileChannel fileChannel) fileChannel.force(true);
                    else throw new IOException("Safe restore channel cannot be synchronized");
                }
                if (!sha256.equals(HexFormat.of().formatHex(digest.digest())))
                    throw new IOException("Restore checksum mismatch");
                for (int attempt = 0; attempt < 100; attempt++) {
                    destination = paths.resolve(protectedRoot, requestedAt, relativePath);
                    safe.revalidate();
                    byte[] suffix = new byte[16];
                    random.nextBytes(suffix);
                    String name = destination.getFileName() + " (restored-" + HexFormat.of().formatHex(suffix) + ")";
                    if (safe.exists(name)) continue;
                    safe.move(partialName, name);
                    safe.revalidate();
                    Path published = destination.resolveSibling(name);
                    codec.writeResult(socket.getOutputStream(), new TransferResult(TransferResult.Status.STORED, "Restored"));
                    return published;
                }
                throw new IOException("No available restore filename");
            } catch (IOException failure) {
                try { codec.writeResult(socket.getOutputStream(),
                        new TransferResult(TransferResult.Status.REJECTED, "Restore verification failed")); }
                catch (IOException ignored) { }
                throw failure;
            } finally {
                try { safe.deleteIfExists(partialName); }
                catch (IOException cleanupFailure) {
                    System.getLogger(RestoreHandler.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not remove restore partial file", cleanupFailure);
                }
            }
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void prepareDestinationParent(Path parent) throws IOException {
        Path root = protectedRoot.toAbsolutePath().normalize();
        if (!parent.startsWith(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Protected folder is unavailable");
        Path current = root;
        for (Path segment : root.relativize(parent)) {
            BasicFileAttributes before = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isDirectory() || before.fileKey() == null)
                throw new IOException("Unsafe restore parent");
            Path next = current.resolve(segment);
            try { Files.createDirectory(next); }
            catch (FileAlreadyExistsException existing) { /* Validate the existing entry below. */ }
            BasicFileAttributes after = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes child = Files.readAttributes(next, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.fileKey().equals(after.fileKey()) || !child.isDirectory())
                throw new IOException("Restore directory changed during setup");
            current = next;
        }
    }

    private static final class SafeDirectory implements AutoCloseable {
        private final ArrayList<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        private final ArrayList<Path> paths = new ArrayList<>();
        private final ArrayList<Object> keys = new ArrayList<>();

        static SafeDirectory open(Path protectedRoot, Path parent) throws IOException {
            var result = new SafeDirectory();
            Path root = protectedRoot.toAbsolutePath().normalize();
            if (!parent.startsWith(root)) throw new IOException("Restore destination escaped Protected");
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(root);
                if (!(stream instanceof SecureDirectoryStream<?> secure)) {
                    stream.close();
                    throw new IOException("Secure directory operations are unavailable");
                }
                @SuppressWarnings("unchecked") var current = (SecureDirectoryStream<Path>) secure;
                result.add(root, current);
                Path currentPath = root;
                for (Path segment : root.relativize(parent)) {
                    current = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                    currentPath = currentPath.resolve(segment);
                    result.add(currentPath, current);
                }
                result.revalidate();
                return result;
            } catch (IOException | RuntimeException failure) {
                result.close();
                throw failure;
            }
        }

        private void add(Path path, SecureDirectoryStream<Path> stream) throws IOException {
            opened.add(stream);
            paths.add(path);
            var view = stream.getFileAttributeView(BasicFileAttributeView.class);
            Object key = view == null ? null : view.readAttributes().fileKey();
            if (key == null) throw new IOException("Filesystem does not expose stable directory identities");
            keys.add(key);
        }

        void revalidate() throws IOException {
            for (int i = 0; i < paths.size(); i++) {
                BasicFileAttributes attrs = Files.readAttributes(paths.get(i), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isDirectory() || !keys.get(i).equals(attrs.fileKey()))
                    throw new IOException("Restore directory changed during transfer");
            }
        }

        java.nio.channels.SeekableByteChannel openNew(String name) throws IOException {
            revalidate();
            var channel = parent().newByteChannel(Path.of(name),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            try { revalidate(); return channel; }
            catch (IOException failure) { channel.close(); deleteIfExists(name); throw failure; }
        }

        boolean exists(String name) throws IOException {
            return existsInParent(name);
        }

        private boolean existsInParent(String name) throws IOException {
            try {
                parent().getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                return true;
            } catch (java.nio.file.NoSuchFileException missing) { return false; }
        }

        void move(String source, String target) throws IOException {
            revalidate();
            if (existsInParent(target)) throw new FileAlreadyExistsException(target);
            parent().move(Path.of(source), parent(), Path.of(target));
            try { revalidate(); }
            catch (IOException failure) { deleteIfExists(target); throw failure; }
        }

        void deleteIfExists(String name) throws IOException {
            try { parent().deleteFile(Path.of(name)); }
            catch (java.nio.file.NoSuchFileException ignored) { }
        }

        private SecureDirectoryStream<Path> parent() { return opened.getLast(); }

        @Override public void close() throws IOException {
            IOException failure = null;
            for (int i = opened.size() - 1; i >= 0; i--) {
                try { opened.get(i).close(); }
                catch (IOException issue) {
                    if (failure == null) failure = issue;
                    else failure.addSuppressed(issue);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
