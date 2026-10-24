package vn.edu.ltm.server.device;

import vn.edu.ltm.common.model.DxdiagProfile;
import java.time.Instant;

public record Device(String deviceId, String displayName, String platform, String tokenHash,
                     Status status, Instant lastSeen, boolean isolated,
                     boolean allowBackup, boolean allowRestore, DxdiagProfile dxdiag) {
    public Device(String deviceId, String displayName, String platform, String tokenHash,
                  Status status, Instant lastSeen) {
        this(deviceId, displayName, platform, tokenHash, status, lastSeen, false, true, true, null);
    }
    public Device(String deviceId, String displayName, String platform, String tokenHash,
                  Status status, Instant lastSeen, boolean isolated) {
        this(deviceId, displayName, platform, tokenHash, status, lastSeen, isolated, true, true, null);
    }
    public enum Status { ONLINE, OFFLINE }
}
