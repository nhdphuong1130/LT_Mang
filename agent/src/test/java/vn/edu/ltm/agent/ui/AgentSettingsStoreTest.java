package vn.edu.ltm.agent.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import static org.junit.jupiter.api.Assertions.*;

class AgentSettingsStoreTest {
    @TempDir Path temp;

    @Test void storesTokenSeparateFromNonSecretProperties() throws Exception {
        Path protectedDir = Files.createDirectory(temp.resolve("Protected"));
        var store = new AgentSettingsStore(temp.resolve("config"));
        var settings = new AgentSettings("127.0.0.1", 5050, 5051, "pc-1", "secret-token", protectedDir);
        store.save(settings);
        assertEquals(settings, store.load().orElseThrow());
        assertFalse(Files.readString(temp.resolve("config/agent.properties")).contains("secret-token"));
        if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(temp.resolve("config/device.token")));
        }
    }

    @Test void rejectsUnreadableRootAndBadPort() throws Exception {
        Path protectedDir = Files.createDirectory(temp.resolve("Protected"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSettings("host", 5050, 0, "pc-1", "token", protectedDir));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSettings("host", 5050, 5051, "pc-1", "token", temp.resolve("missing")));
    }
}
