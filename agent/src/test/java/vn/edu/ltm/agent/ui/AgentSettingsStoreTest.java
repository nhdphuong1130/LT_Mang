package vn.edu.ltm.agent.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import vn.edu.ltm.agent.backup.BackupCandidate;
import vn.edu.ltm.agent.backup.JdbcBackupQueueRepository;
import vn.edu.ltm.agent.persistence.AgentDatabase;
import java.time.Instant;
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

    @Test void switchingDeviceOrProtectedRootUsesIndependentBackupHistory() throws Exception {
        Path firstRoot = Files.createDirectory(temp.resolve("first"));
        Path secondRoot = Files.createDirectory(temp.resolve("second"));
        var store = new AgentSettingsStore(temp.resolve("config"));
        var first = new AgentSettings("host", 5050, 5051, "device-one", "token-one", firstRoot);
        var differentDevice = new AgentSettings("host", 5050, 5051, "device-two", "token-two", firstRoot);
        var differentRoot = new AgentSettings("host", 5050, 5051, "device-one", "token-one", secondRoot);
        var rotatedToken = new AgentSettings("host", 5050, 5051, "device-one", "rotated-token", firstRoot);
        assertEquals(store.stateDatabasePath(first), store.stateDatabasePath(rotatedToken));
        var candidate = new BackupCandidate("report.txt", 5, Instant.parse("2026-10-10T00:00:00Z"), "a".repeat(64));
        var initial = new AgentDatabase(store.stateDatabasePath(first)); initial.initialize();
        var queue = new JdbcBackupQueueRepository(initial);
        queue.enqueueOrUpdate(candidate);
        var claimed = queue.nextReady(1).getFirst();
        queue.markUploaded(claimed.id(), candidate.sha256());
        assertTrue(queue.nextReady(1).isEmpty());
        for (var identity : new AgentSettings[]{differentDevice, differentRoot}) {
            var database = new AgentDatabase(store.stateDatabasePath(identity)); database.initialize();
            var isolated = new JdbcBackupQueueRepository(database);
            isolated.enqueueOrUpdate(candidate);
            assertEquals(1, isolated.nextReady(1).size(), "New identity must back up identical bytes");
        }
    }
}
