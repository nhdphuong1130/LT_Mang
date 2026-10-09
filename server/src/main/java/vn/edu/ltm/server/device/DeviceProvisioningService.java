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
        if (device.isEmpty()) return false;
        char[] chars = token.toCharArray();
        try {
            return BCrypt.verifyer().verify(chars, device.get().tokenHash()).verified;
        } finally {
            Arrays.fill(chars, '\0');
        }
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
