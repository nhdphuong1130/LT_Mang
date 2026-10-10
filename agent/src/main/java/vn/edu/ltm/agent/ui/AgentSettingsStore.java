package vn.edu.ltm.agent.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AgentSettingsStore {
    private final Path directory;
    public AgentSettingsStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    /** Stable per-device/root database path; credentials never influence or appear in its name. */
    public Path stateDatabasePath(AgentSettings settings) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(settings.deviceId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(settings.protectedRoot().toString().getBytes(StandardCharsets.UTF_8));
            return directory.resolve("state").resolve(HexFormat.of().formatHex(digest.digest()) + ".db");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public Optional<AgentSettings> load() throws IOException {
        Path config = directory.resolve("agent.properties"), tokenFile = directory.resolve("device.token");
        if (!Files.exists(config) || !Files.exists(tokenFile)) return Optional.empty();
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { properties.load(reader); }
        return Optional.of(new AgentSettings(properties.getProperty("serverHost"),
                Integer.parseInt(properties.getProperty("controlPort", "5050")),
                Integer.parseInt(properties.getProperty("filePort", "5051")),
                properties.getProperty("deviceId"), Files.readString(tokenFile, StandardCharsets.UTF_8).strip(),
                Path.of(properties.getProperty("protectedRoot"))));
    }
    public void save(AgentSettings settings) throws IOException {
        Files.createDirectories(directory);
        Path tokenFile = directory.resolve("device.token");
        Path tempToken = Files.createTempFile(directory, "device-", ".tmp");
        try {
            restrict(tempToken);
            Files.writeString(tempToken, settings.token(), StandardCharsets.UTF_8);
            try { Files.move(tempToken, tokenFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tempToken, tokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(tokenFile);
        } finally { Files.deleteIfExists(tempToken); }
        var props = new Properties();
        props.setProperty("serverHost", settings.serverHost());
        props.setProperty("controlPort", Integer.toString(settings.controlPort()));
        props.setProperty("filePort", Integer.toString(settings.filePort()));
        props.setProperty("deviceId", settings.deviceId());
        props.setProperty("protectedRoot", settings.protectedRoot().toString());
        try (var writer = Files.newBufferedWriter(directory.resolve("agent.properties"), StandardCharsets.UTF_8)) {
            props.store(writer, "Agent non-secret configuration");
        }
    }
    private static void restrict(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
}
