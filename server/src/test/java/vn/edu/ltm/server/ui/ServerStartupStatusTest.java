package vn.edu.ltm.server.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ServerStartupStatusTest {
    @TempDir Path temp;
    @Test void staleOnlineRowsAreOfflineAfterRestart() throws Exception {
        var db = new Database(temp.resolve("server.db")); db.initialize();
        var repo = new JdbcDeviceRepository(db);
        repo.insert(new Device("pc-1", "PC", "Linux", "hash", Device.Status.OFFLINE, null));
        repo.markOnline("pc-1", Instant.now());
        assertEquals(Device.Status.ONLINE, repo.findById("pc-1").orElseThrow().status());
        repo.markAllOffline();
        assertEquals(Device.Status.OFFLINE, repo.findById("pc-1").orElseThrow().status());
    }
}
