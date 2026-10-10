package vn.edu.ltm.server.ui;

import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.device.DeviceRepository;
import vn.edu.ltm.server.metrics.MetricsRepository;
import vn.edu.ltm.server.incident.IncidentRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Read-only projection for the dashboard; domain repositories remain the write authority. */
public final class DashboardDataService {
    public record VersionRow(long id, String deviceId, String relativePath, Instant createdAt, long sizeBytes) {
        @Override public String toString() { return deviceId + "  " + relativePath + "  " + createdAt + "  " + sizeBytes + " B"; }
    }
    public record Snapshot(List<String> devices, List<String> incidents, List<VersionRow> versions) { }
    private final Database database;
    private final DeviceRepository devices;
    private final MetricsRepository metrics;
    private final IncidentRepository incidents;
    public DashboardDataService(Database database, DeviceRepository devices, MetricsRepository metrics, IncidentRepository incidents) {
        this.database = database; this.devices = devices; this.metrics = metrics; this.incidents = incidents;
    }
    public Snapshot load() throws SQLException {
        var deviceRows = new ArrayList<String>();
        var incidentRows = new ArrayList<String>();
        for (var device : devices.findAll()) {
            var latest = metrics.findLatest(device.deviceId());
            String summary = latest.map(sample -> String.format(java.util.Locale.ROOT,
                    "CPU %.0f%%  RAM %.0f%%  Disk %.0f%%  Temp %s", sample.cpuPercent(), sample.ramPercent(),
                    sample.diskPercent(), sample.temperatureCelsius() == null ? "N/A" : sample.temperatureCelsius() + "°C"))
                    .orElse("No metrics");
            deviceRows.add(device.deviceId() + " — " + device.displayName() + " — " + device.status() + " — " + summary);
            for (var incident : incidents.findAll(device.deviceId()))
                incidentRows.add(device.deviceId() + " — " + incident.riskType() + " — " + incident.status()
                        + " — " + incident.message());
        }
        var versions = new ArrayList<VersionRow>();
        try (var connection = database.openConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("""
                     SELECT v.id, f.device_id, f.relative_path, v.created_at, v.size_bytes
                     FROM backup_versions v JOIN backup_files f ON f.id = v.file_id
                     ORDER BY v.id DESC
                     """)) {
            while (rows.next()) versions.add(new VersionRow(rows.getLong(1), rows.getString(2), rows.getString(3),
                    Instant.parse(rows.getString(4)), rows.getLong(5)));
        }
        return new Snapshot(List.copyOf(deviceRows), List.copyOf(incidentRows), List.copyOf(versions));
    }
}
