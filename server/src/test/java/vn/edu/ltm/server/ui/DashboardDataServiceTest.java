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

    @Test void groupsBackupsByDeviceFolderAndFiltersBySubfolderOrFile() throws Exception {
        var db = new Database(temp.resolve("grouped.db")); db.initialize();
        var devices = new JdbcDeviceRepository(db);
        devices.insert(new Device("pc-1", "May Ke Toan", "Windows", "hash1", Device.Status.ONLINE, null));
        devices.insert(new Device("pc-2", "May Ky Thuat", "Linux", "hash2", Device.Status.OFFLINE, null));
        try (var connection = db.openConnection(); var st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO backup_files(id,device_id,relative_path) VALUES (1,'pc-1','baocao/q1.txt')");
            st.executeUpdate("INSERT INTO backup_files(id,device_id,relative_path) VALUES (2,'pc-1','readme.txt')");
            st.executeUpdate("INSERT INTO backup_files(id,device_id,relative_path) VALUES (3,'pc-2','code/main.java')");
            st.executeUpdate("INSERT INTO backup_versions(file_id,size_bytes,sha256,storage_path,created_at) VALUES (1,10,'" + "a".repeat(64) + "','/p1','2026-10-10T00:00:00Z')");
            st.executeUpdate("INSERT INTO backup_versions(file_id,size_bytes,sha256,storage_path,created_at) VALUES (2,20,'" + "b".repeat(64) + "','/p2','2026-10-10T00:01:00Z')");
            st.executeUpdate("INSERT INTO backup_versions(file_id,size_bytes,sha256,storage_path,created_at) VALUES (3,30,'" + "c".repeat(64) + "','/p3','2026-10-10T00:02:00Z')");
        }
        var snapshot = new DashboardDataService(db, devices, new JdbcMetricsRepository(db), new JdbcIncidentRepository(db)).load();
        assertEquals(2, snapshot.deviceFolders().size());
        assertEquals("pc-1", snapshot.deviceFolders().get(0).deviceId());
        assertEquals(java.util.List.of("baocao/q1.txt", "readme.txt"), snapshot.deviceFolders().get(0).relativePaths());
        assertEquals(2, snapshot.filterVersions("pc-1", "").size());
        assertEquals(1, snapshot.filterVersions("pc-1", "baocao").size());
        assertEquals(1, snapshot.filterVersions("pc-2", "code/main.java").size());
    }

    @Test void synchronizesDxdiagMemoryAndDiskWithLiveMetrics() throws Exception {
        var db = new Database(temp.resolve("sync.db")); db.initialize();
        var devices = new JdbcDeviceRepository(db);
        var metrics = new JdbcMetricsRepository(db);
        devices.insert(new Device("pc-b", "Binh", "Windows", "hash", Device.Status.ONLINE, java.time.Instant.now(),
                false, true, true,
                new vn.edu.ltm.common.model.DxdiagProfile(
                        "DESKTOP-B", "Windows 11", "ASUS", "Vivobook", "BIOS 1.0",
                        "Core i5", "16384 MB (16.0 GB) RAM", "4096 MB swap", "DirectX 12",
                        "Iris Xe", "Intel", "8120 MB", "1920x1080",
                        "C: (NVMe SSD 512.0 GB, 318.4 GB free)",
                        "Wi-Fi [IPv4: 10.35.92.115, MAC: A8:5E:45:B1:22:09]"
                )));
        metrics.upsert("pc-b", new vn.edu.ltm.common.model.SystemMetrics(
                java.time.Instant.now(), 16.0, 55.0, 91.0, null, 45.0, true));

        var snapshot = new DashboardDataService(db, devices, metrics, new JdbcIncidentRepository(db)).load();
        var sphere = snapshot.clientSpheres().getFirst();
        assertTrue(sphere.dxdiag().memory().contains("Đang dùng 55%"), "Memory should reflect 55% live RAM: " + sphere.dxdiag().memory());
        assertTrue(sphere.dxdiag().diskSummary().contains("Đang dùng 91%"), "Disk should reflect 91% live Disk: " + sphere.dxdiag().diskSummary());
        assertFalse(sphere.dxdiag().diskSummary().contains("318.4 GB free"), "Stale free disk must be replaced: " + sphere.dxdiag().diskSummary());
    }

    @Test void doesNotFabricateDxdiagCapacityWhenClientHasNotReportedHardware() throws Exception {
        var db = new Database(temp.resolve("unknown-dxdiag.db")); db.initialize();
        var devices = new JdbcDeviceRepository(db);
        var metrics = new JdbcMetricsRepository(db);
        devices.insert(new Device("pc-t", "Trung", "Windows", "hash", Device.Status.ONLINE, java.time.Instant.now()));
        metrics.upsert("pc-t", new vn.edu.ltm.common.model.SystemMetrics(
                java.time.Instant.now(), 20.0, 55.0, 65.0, null, 75.0, false));

        var sphere = new DashboardDataService(db, devices, metrics, new JdbcIncidentRepository(db))
                .load().clientSpheres().getFirst();

        assertEquals("N/A", sphere.dxdiag().memory());
        assertEquals("N/A", sphere.dxdiag().diskSummary());
        assertNull(sphere.temperatureCelsius());
        assertTrue(sphere.metricsSummary().contains("Temp N/A"));
    }

    @Test void chronologicallySortsIncidentsAndAuditLogsDescendingWithNewestFirst() throws Exception {
        var db = new Database(temp.resolve("sort.db")); db.initialize();
        var devices = new JdbcDeviceRepository(db);
        var incidents = new JdbcIncidentRepository(db);
        var auditLogs = new vn.edu.ltm.server.audit.JdbcAuditLogRepository(db);
        devices.insert(new Device("pc-x", "May X", "Linux", "hash", Device.Status.ONLINE, java.time.Instant.now()));

        java.time.Instant t1 = java.time.Instant.parse("2026-10-29T03:00:00Z");
        java.time.Instant t2 = java.time.Instant.parse("2026-10-29T03:05:00Z");
        java.time.Instant t3 = java.time.Instant.parse("2026-10-29T03:10:00Z");

        incidents.apply("pc-x", new vn.edu.ltm.common.model.RiskEvent(
                vn.edu.ltm.common.model.RiskType.HIGH_CPU, vn.edu.ltm.common.model.RiskEvent.Status.OPEN, t1, "CPU 95%"));
        auditLogs.log(t2, "pc-x", "CLIENT_PULL_DATA", "pc-x", "Yêu cầu kéo file test.txt");
        incidents.apply("pc-x", new vn.edu.ltm.common.model.RiskEvent(
                vn.edu.ltm.common.model.RiskType.CONNECTION_LOST, vn.edu.ltm.common.model.RiskEvent.Status.OPEN, t3, "Mất kết nối"));

        var snapshot = new DashboardDataService(db, devices, new JdbcMetricsRepository(db), incidents, null, null, auditLogs).load();
        assertEquals(3, snapshot.incidents().size());
        assertTrue(snapshot.incidents().get(0).contains("CONNECTION_LOST"), "Newest event (t3) must be first: " + snapshot.incidents().get(0));
        assertTrue(snapshot.incidents().get(1).contains("CLIENT_PULL_DATA"), "Middle event (t2) must be second: " + snapshot.incidents().get(1));
        assertTrue(snapshot.incidents().get(2).contains("HIGH_CPU"), "Oldest event (t1) must be third: " + snapshot.incidents().get(2));
    }
}
