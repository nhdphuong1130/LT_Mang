package vn.edu.ltm.server.device;

import java.time.Instant;

public record Device(String deviceId, String displayName, String platform, String tokenHash,
                     Status status, Instant lastSeen) {
    public enum Status { ONLINE, OFFLINE }
}
