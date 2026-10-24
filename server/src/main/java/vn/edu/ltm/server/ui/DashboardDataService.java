package vn.edu.ltm.server.ui;

import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.model.DxdiagProfile;
import vn.edu.ltm.common.model.RiskEvent;
import vn.edu.ltm.common.model.SystemMetrics;
import vn.edu.ltm.server.device.Device;
import vn.edu.ltm.server.device.DeviceRepository;
import vn.edu.ltm.server.incident.IncidentRepository;
import vn.edu.ltm.server.metrics.MetricsRepository;
import vn.edu.ltm.server.metrics.RealtimeDistanceTracker;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.AgentSession;
import vn.edu.ltm.server.session.SessionRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only projection for the dashboard; domain repositories remain the write authority. */
public final class DashboardDataService {
    private static final Pattern LEADING_MB = Pattern.compile("(\\d+)\\s*MB");
    private static final Pattern TOTAL_GB = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*GB");
    private static final Pattern IPV4_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)");

    public record VersionRow(long id, String deviceId, String relativePath, Instant createdAt, long sizeBytes) {
        @Override public String toString() { return deviceId + "  " + relativePath + "  " + createdAt + "  " + sizeBytes + " B"; }
    }
    public record DeviceBackupFolder(String deviceId, String displayName, List<String> relativePaths) { }
    public record ClientSphereItem(
            String deviceId,
            String displayName,
            String platform,
            boolean online,
            boolean isolated,
            boolean allowBackup,
            boolean allowRestore,
            boolean alertOver80OrRisky,
            double cpuPercent,
            double ramPercent,
            double diskPercent,
            Double temperatureCelsius,
            String metricsSummary,
            DxdiagProfile dxdiag,
            DistanceTelemetry distance
    ) {
        public ClientSphereItem(
                String deviceId, String displayName, String platform, boolean online, boolean isolated,
                boolean allowBackup, boolean allowRestore, boolean alertOver80OrRisky,
                double cpuPercent, double ramPercent, double diskPercent, Double temperatureCelsius,
                String metricsSummary, DxdiagProfile dxdiag
        ) {
            this(deviceId, displayName, platform, online, isolated, allowBackup, allowRestore, alertOver80OrRisky,
                    cpuPercent, ramPercent, diskPercent, temperatureCelsius, metricsSummary, dxdiag,
                    DistanceTelemetry.offline());
        }
    }

    public record Snapshot(List<String> devices, List<String> incidents, List<VersionRow> versions,
                           List<DeviceBackupFolder> deviceFolders, List<ClientSphereItem> clientSpheres) {
        public Snapshot(List<String> devices, List<String> incidents, List<VersionRow> versions) {
            this(devices, incidents, versions, List.of(), List.of());
        }
        public Snapshot(List<String> devices, List<String> incidents, List<VersionRow> versions,
                        List<DeviceBackupFolder> deviceFolders) {
            this(devices, incidents, versions, deviceFolders, List.of());
        }
        public List<VersionRow> filterVersions(String deviceId, String pathPrefix) {
            if (deviceId == null || deviceId.isBlank()) return versions;
            String normalizedPrefix = pathPrefix == null ? "" : pathPrefix.strip();
            return versions.stream()
                    .filter(v -> v.deviceId().equals(deviceId))
                    .filter(v -> normalizedPrefix.isEmpty()
                            || v.relativePath().equals(normalizedPrefix)
                            || v.relativePath().startsWith(normalizedPrefix + "/"))
                    .toList();
        }
    }

    private final Database database;
    private final DeviceRepository devices;
    private final MetricsRepository metrics;
    private final IncidentRepository incidents;
    private final SessionRegistry sessions;
    private final RealtimeDistanceTracker distanceTracker;

    public DashboardDataService(Database database, DeviceRepository devices, MetricsRepository metrics, IncidentRepository incidents) {
        this(database, devices, metrics, incidents, null, new RealtimeDistanceTracker());
    }

    public DashboardDataService(Database database, DeviceRepository devices, MetricsRepository metrics,
                                IncidentRepository incidents, SessionRegistry sessions) {
        this(database, devices, metrics, incidents, sessions, new RealtimeDistanceTracker());
    }

    public DashboardDataService(Database database, DeviceRepository devices, MetricsRepository metrics,
                                IncidentRepository incidents, SessionRegistry sessions, RealtimeDistanceTracker distanceTracker) {
        this.database = database;
        this.devices = devices;
        this.metrics = metrics;
        this.incidents = incidents;
        this.sessions = sessions;
        this.distanceTracker = distanceTracker != null ? distanceTracker : new RealtimeDistanceTracker();
    }

    public Snapshot load() throws SQLException {
        var deviceRows = new ArrayList<String>();
        var incidentRows = new ArrayList<String>();
        var sphereItems = new ArrayList<ClientSphereItem>();
        var deviceList = devices.findAll();
        for (var device : deviceList) {
            var latest = metrics.findLatest(device.deviceId());
            double cpu = latest.map(SystemMetrics::cpuPercent).orElse(0.0);
            double ram = latest.map(SystemMetrics::ramPercent).orElse(0.0);
            double disk = latest.map(SystemMetrics::diskPercent).orElse(0.0);
            Double temp = latest.map(SystemMetrics::temperatureCelsius).orElse(null);
            String summary = latest.map(sample -> String.format(Locale.ROOT,
                    "CPU %.0f%%  RAM %.0f%%  Disk %.0f%%  Temp %s%s",
                    sample.cpuPercent(), sample.ramPercent(), sample.diskPercent(),
                    sample.temperatureCelsius() == null ? "N/A" : String.format(Locale.ROOT, "%.0f°C", sample.temperatureCelsius()),
                    sample.batteryPercent() == null ? "" : String.format(Locale.ROOT, "  Pin %.0f%% (%s)",
                            sample.batteryPercent(), sample.charging() ? "Sạc" : "Rời")))
                    .orElse("No metrics");
            var deviceIncidents = incidents.findAll(device.deviceId());
            boolean hasOpenRisk = false;
            for (var incident : deviceIncidents) {
                if (incident.status() != RiskEvent.Status.RESOLVED) hasOpenRisk = true;
                incidentRows.add(device.deviceId() + " — " + incident.riskType() + " — " + incident.status()
                        + " — " + incident.message());
            }
            boolean over80 = latest.isPresent() && (cpu >= 80.0 || ram >= 80.0 || disk >= 80.0 || (temp != null && temp >= 80.0));
            boolean alertState = over80 || hasOpenRisk;
            String permTag = (!device.allowBackup() || !device.allowRestore())
                    ? " [HẠN CHẾ:" + (device.allowBackup() ? "" : " Chặn-Backup") + (device.allowRestore() ? "" : " Chặn-NhậnFile") + "]"
                    : "";
            String alertTag = alertState ? " [⚠ CẢNH BÁO >=80% / RỦI RO]" : "";
            String statusLabel = device.isolated() ? "ISOLATED (CÔ LẬP)" : device.status().name();
            deviceRows.add(device.deviceId() + " — " + device.displayName() + " — " + statusLabel + permTag + alertTag + " — " + summary);

            DxdiagProfile baseDx = device.dxdiag() != null
                    ? device.dxdiag()
                    : DxdiagProfile.unknown(device.deviceId(), device.platform());
            String liveIp = sessions == null ? null : sessions.find(device.deviceId()).map(AgentSession::remoteAddress).orElse(null);
            DxdiagProfile dxdiag = syncWithLiveTelemetry(baseDx, latest, liveIp);
            boolean isOnline = device.status() == Device.Status.ONLINE && !device.isolated();
            String probeIp = liveIp != null ? liveIp : extractIpv4(dxdiag.networkSummary());
            DistanceTelemetry dist = distanceTracker.getOrProbe(device.deviceId(), probeIp, isOnline);
            String distTag = dist.hasPhysicalDistance()
                    ? String.format(Locale.ROOT, " [📍 ~%.1fm | RTT %.0fms]", dist.estimatedMeters(), Math.max(0.0, dist.rttMs()))
                    : "";
            deviceRows.set(deviceRows.size() - 1, deviceRows.getLast() + distTag);

            sphereItems.add(new ClientSphereItem(
                    device.deviceId(),
                    device.displayName(),
                    device.platform(),
                    isOnline,
                    device.isolated(),
                    device.allowBackup(),
                    device.allowRestore(),
                    alertState,
                    cpu, ram, disk, temp,
                    summary,
                    dxdiag,
                    dist
            ));
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
        var folders = new ArrayList<DeviceBackupFolder>();
        for (var device : deviceList) {
            var paths = versions.stream()
                    .filter(v -> v.deviceId().equals(device.deviceId()))
                    .map(VersionRow::relativePath)
                    .distinct()
                    .sorted()
                    .toList();
            folders.add(new DeviceBackupFolder(device.deviceId(), device.displayName(), paths));
        }
        return new Snapshot(List.copyOf(deviceRows), List.copyOf(incidentRows), List.copyOf(versions),
                List.copyOf(folders), List.copyOf(sphereItems));
    }

    private static DxdiagProfile syncWithLiveTelemetry(DxdiagProfile base, Optional<SystemMetrics> latest, String liveIp) {
        String memory = base.memory();
        String diskSummary = base.diskSummary();
        String networkSummary = base.networkSummary();

        if (latest.isPresent()) {
            var sample = latest.get();
            Long totalMb = extractTotalMb(memory);
            if (totalMb != null) {
                long usedMb = Math.round(totalMb * (sample.ramPercent() / 100.0));
                long freeMb = Math.max(0L, totalMb - usedMb);
                memory = String.format(Locale.ROOT, "%d MB (%.1f GB) RAM — Đang dùng %.0f%% (%d MB used / %d MB free)",
                        totalMb, totalMb / 1024.0, sample.ramPercent(), usedMb, freeMb);
            }

            if (!diskSummary.contains("Đang dùng ")) {
                Double totalGb = extractTotalGb(diskSummary);
                if (totalGb != null) {
                    double freeGb = Math.max(0.0, totalGb * (1.0 - sample.diskPercent() / 100.0));
                    String prefix = diskSummary.startsWith("/") ? "/ (Disk" : "Disk (";
                    int parenIdx = diskSummary.indexOf('(');
                    if (parenIdx > 0) prefix = diskSummary.substring(0, parenIdx).trim() + " (";
                    diskSummary = String.format(Locale.ROOT, "%s%.1f GB — Đang dùng %.0f%%, còn trống %.1f GB)",
                            prefix, totalGb, sample.diskPercent(), freeGb);
                }
            }
        }

        if (liveIp != null && !liveIp.isBlank() && !liveIp.equals("127.0.0.1") && !liveIp.equals("::1")) {
            String arpMac = lookupArpMac(liveIp);
            if (arpMac != null) {
                String adapterName = networkSummary.contains("[") ? networkSummary.substring(0, networkSummary.indexOf('[')).trim() : "LAN / Wi-Fi Adapter";
                networkSummary = adapterName + " [IPv4: " + liveIp + ", MAC: " + arpMac + "]";
            } else if (!networkSummary.contains(liveIp)) {
                networkSummary = networkSummary + " [Active IP: " + liveIp + "]";
            }
        }

        return new DxdiagProfile(
                base.computerName(), base.operatingSystem(), base.systemManufacturer(), base.systemModel(),
                base.biosVersion(), base.processor(), memory, base.pageFileOrSwap(), base.directXOrKernelVersion(),
                base.gpuName(), base.gpuVendor(), base.gpuVram(), base.displayInfo(),
                diskSummary, networkSummary
        );
    }

    private static Long extractTotalMb(String text) {
        if (text == null) return null;
        Matcher m = LEADING_MB.matcher(text);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static Double extractTotalGb(String text) {
        if (text == null) return null;
        Matcher m = TOTAL_GB.matcher(text);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static String extractIpv4(String text) {
        if (text == null) return null;
        Matcher m = IPV4_PATTERN.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String lookupArpMac(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            Path arp = Path.of("/proc/net/arp");
            if (!Files.isReadable(arp)) return null;
            for (String line : Files.readAllLines(arp)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4 && parts[0].equals(ip) && !parts[3].equals("00:00:00:00:00:00")) {
                    return parts[3].toUpperCase(Locale.ROOT);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
