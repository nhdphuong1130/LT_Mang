package vn.edu.ltm.server.device;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class DeviceProvisioningService {
    private final DeviceRepository devices;
    private final SecureRandom random = new SecureRandom();

    public DeviceProvisioningService(DeviceRepository devices) {
        this.devices = devices;
    }

    /** The plaintext token exists only in this return value; callers must not log it. */
    public ProvisionedDevice provision(String deviceId, String displayName, String platform) {
        String normalized = normalizeDeviceId(deviceId);
        if (displayName == null || displayName.isBlank() || platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("Display name and platform are required");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        char[] chars = token.toCharArray();
        String hash;
        try {
            hash = BCrypt.withDefaults().hashToString(12, chars);
        } finally {
            Arrays.fill(chars, '\0');
            Arrays.fill(bytes, (byte) 0);
        }
        devices.insert(new Device(normalized, displayName.trim(), platform.trim(), hash, Device.Status.OFFLINE, null));
        return new ProvisionedDevice(normalized, token);
    }

    public boolean authenticate(String deviceId, String token) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._-]{1,64}") || token == null
                || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        var device = devices.findById(deviceId);
        if (device.isEmpty() || device.get().isolated()) return false;
        char[] chars = token.toCharArray();
        try {
            return BCrypt.verifyer().verify(chars, device.get().tokenHash()).verified;
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private vn.edu.ltm.server.audit.AuditLogRepository auditLogs;
    private vn.edu.ltm.server.incident.IncidentService incidents;

    public void setAuditAndIncidents(vn.edu.ltm.server.audit.AuditLogRepository auditLogs,
                                     vn.edu.ltm.server.incident.IncidentService incidents) {
        this.auditLogs = auditLogs;
        this.incidents = incidents;
    }

    public void setIsolated(String deviceId, boolean isolated, vn.edu.ltm.server.session.SessionRegistry sessions) {
        setIsolated(deviceId, isolated, sessions, "system");
    }

    public void setIsolated(String deviceId, boolean isolated,
                            vn.edu.ltm.server.session.SessionRegistry sessions, String actor) {
        String normalized = normalizeDeviceId(deviceId);
        devices.setIsolated(normalized, isolated);
        if (isolated && sessions != null) sessions.disconnect(normalized);
        java.time.Instant now = java.time.Instant.now();
        if (auditLogs != null) {
            auditLogs.log(now, auditActor(actor), isolated ? "DEVICE_ISOLATED" : "DEVICE_UNISOLATED",
                    normalized, isolated ? "Đã CÔ LẬP thiết bị (Quarantine / Ngắt kết nối)" : "Đã BỎ CÔ LẬP thiết bị (Khôi phục quyền kết nối)");
        }
        if (incidents != null) {
            incidents.record(normalized, new vn.edu.ltm.common.model.RiskEvent(
                    vn.edu.ltm.common.model.RiskType.ISOLATED,
                    isolated ? vn.edu.ltm.common.model.RiskEvent.Status.OPEN : vn.edu.ltm.common.model.RiskEvent.Status.RESOLVED,
                    now,
                    isolated ? "Thiết bị bị cô lập bởi Quản trị viên (NetOp School Quarantine)" : "Thiết bị đã được bỏ cô lập"));
        }
    }

    public boolean toggleIsolated(String deviceId, vn.edu.ltm.server.session.SessionRegistry sessions) {
        return toggleIsolated(deviceId, sessions, "system");
    }

    public boolean toggleIsolated(String deviceId, vn.edu.ltm.server.session.SessionRegistry sessions, String actor) {
        String normalized = normalizeDeviceId(deviceId);
        var current = devices.findById(normalized).orElseThrow(() -> new IllegalArgumentException("Device does not exist: " + normalized));
        boolean next = !current.isolated();
        setIsolated(normalized, next, sessions, actor);
        return next;
    }

    public void setPermissions(String deviceId, boolean allowBackup, boolean allowRestore) {
        setPermissions(deviceId, allowBackup, allowRestore, "system");
    }

    public void setPermissions(String deviceId, boolean allowBackup, boolean allowRestore, String actor) {
        String normalized = normalizeDeviceId(deviceId);
        devices.setPermissions(normalized, allowBackup, allowRestore);
        java.time.Instant now = java.time.Instant.now();
        if (auditLogs != null) {
            auditLogs.log(now, auditActor(actor), "PERMISSIONS_UPDATED", normalized,
                    "Cập nhật phân quyền: Cho phép Backup=" + (allowBackup ? "Có" : "Chặn")
                    + ", Cho phép Nhận file=" + (allowRestore ? "Có" : "Chặn"));
        }
    }

    private static String auditActor(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor.trim();
    }

    public boolean isBackupAllowed(String deviceId) {
        if (deviceId == null) return false;
        var dev = devices.findById(deviceId.trim());
        return dev.isPresent() && !dev.get().isolated() && dev.get().allowBackup();
    }

    public boolean isRestoreAllowed(String deviceId) {
        if (deviceId == null) return false;
        var dev = devices.findById(deviceId.trim());
        return dev.isPresent() && !dev.get().isolated() && dev.get().allowRestore();
    }

    public java.util.List<Device> findAllDevices() {
        return devices.findAll();
    }

    public static String normalizeDeviceId(String deviceId) {
        if (deviceId == null || !deviceId.trim().matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Device ID must contain 1-64 letters, digits, dots, underscores or hyphens");
        }
        return deviceId.trim();
    }

    public record ProvisionedDevice(String deviceId, String token) {
        @Override public String toString() { return "ProvisionedDevice[deviceId=" + deviceId + ", token=<redacted>]"; }
    }
}
