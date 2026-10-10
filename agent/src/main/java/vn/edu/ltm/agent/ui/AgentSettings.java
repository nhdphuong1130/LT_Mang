package vn.edu.ltm.agent.ui;

import vn.edu.ltm.agent.config.AgentConfig;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.io.IOException;
import java.util.Set;

/** Validated launch settings. Only the token belongs in the restricted credential file. */
public record AgentSettings(String serverHost, int controlPort, int filePort, String deviceId,
                            String token, Path protectedRoot) {
    public AgentSettings {
        new AgentConfig(serverHost, controlPort, deviceId, token);
        if (!serverHost.equals(serverHost.trim()) || serverHost.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Invalid Server host");
        if (filePort < 1 || filePort > 65535) throw new IllegalArgumentException("Invalid file port");
        if (protectedRoot == null || Files.isSymbolicLink(protectedRoot)
                || !Files.isDirectory(protectedRoot) || !Files.isReadable(protectedRoot))
            throw new IllegalArgumentException("Protected folder must be a readable directory");
        try {
            if (Files.getFileStore(protectedRoot).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(protectedRoot);
                if (permissions.stream().noneMatch(p -> p == PosixFilePermission.OWNER_READ
                        || p == PosixFilePermission.GROUP_READ || p == PosixFilePermission.OTHERS_READ))
                    throw new IllegalArgumentException("Protected folder must be readable");
            }
            protectedRoot = protectedRoot.toRealPath();
        } catch (IOException failure) { throw new IllegalArgumentException("Cannot read protected folder", failure); }
    }
    public AgentConfig controlConfig() { return new AgentConfig(serverHost, controlPort, deviceId, token); }
    @Override public String toString() { return "AgentSettings[deviceId=" + deviceId + ", token=<redacted>]"; }
}
