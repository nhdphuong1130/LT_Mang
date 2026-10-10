package vn.edu.ltm.server.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.incident.JdbcIncidentRepository;
import vn.edu.ltm.server.metrics.JdbcMetricsRepository;
import vn.edu.ltm.server.persistence.Database;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DashboardDataServiceTest {
    @TempDir Path temp;
    @Test void listsEveryStoredVersionAcrossManyFiles() throws Exception {
        var db = new Database(temp.resolve("server.db")); db.initialize();
        var devices = new JdbcDeviceRepository(db);
        devices.insert(new Device("pc-1", "PC", "Linux", "hash", Device.Status.OFFLINE, null));
        try (var connection = db.openConnection()) {
            connection.setAutoCommit(false);
            try (var files = connection.prepareStatement("INSERT INTO backup_files(device_id,relative_path) VALUES ('pc-1',?)");
                 var versions = connection.prepareStatement("""
                         INSERT INTO backup_versions(file_id,size_bytes,sha256,storage_path,created_at)
                         VALUES (?,1,?,?,?)
                         """)) {
                for (int file = 0; file < 101; file++) {
                    files.setString(1, "file-" + file + ".txt"); files.executeUpdate();
                    long fileId;
                    try (var id = connection.createStatement(); var row = id.executeQuery("SELECT last_insert_rowid()")) {
                        fileId = row.getLong(1);
                    }
                    for (int version = 0; version < 5; version++) {
                        versions.setLong(1, fileId);
                        versions.setString(2, "a".repeat(64));
                        versions.setString(3, temp.resolve("blob-" + file + "-" + version).toString());
                        versions.setString(4, "2026-10-10T00:00:00Z");
                        versions.executeUpdate();
                    }
                }
            }
            connection.commit();
        }
        var dashboard = new DashboardDataService(db, devices, new JdbcMetricsRepository(db), new JdbcIncidentRepository(db));
        assertEquals(505, dashboard.load().versions().size());
    }
}
