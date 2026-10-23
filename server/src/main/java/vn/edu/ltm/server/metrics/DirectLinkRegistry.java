package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.DirectLinkIdentity;
import vn.edu.ltm.common.model.DirectLinkStatus;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Joins Agent-reported P2P adapter identities to radio stations observed by the Server. */
public final class DirectLinkRegistry {
    public static final String REQUIRED_CAPABILITY = "WIFI_DIRECT_IDENTITY_V1";
    private static final Duration IDENTITY_MAX_AGE = Duration.ofSeconds(10);
    private static final Duration SIGNAL_MAX_AGE = Duration.ofSeconds(5);

    public record Resolution(DirectLinkStatus status, LinuxP2pStationCollector.StationSample sample,
                             String reason) { }

    private final Clock clock;
    private final Map<String, DirectLinkIdentity> identities = new ConcurrentHashMap<>();

    public DirectLinkRegistry() { this(Clock.systemUTC()); }
    DirectLinkRegistry(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public void update(String deviceId, DirectLinkIdentity identity) {
        if (deviceId != null && !deviceId.isBlank() && identity != null) identities.put(deviceId, identity);
    }

    public Resolution resolve(String deviceId, List<LinuxP2pStationCollector.StationSample> samples) {
        DirectLinkIdentity identity = identities.get(deviceId);
        if (identity == null || !identity.supports(REQUIRED_CAPABILITY)) {
            return result(DirectLinkStatus.AGENT_UPDATE_REQUIRED, "Agent chưa hỗ trợ định danh Wi-Fi Direct");
        }
        if (identity.status() != DirectLinkStatus.CONNECTED) {
            return result(identity.status(), "Liên kết Wi-Fi Direct chưa sẵn sàng");
        }
        Instant now = clock.instant();
        if (identity.sampledAt() == null || Duration.between(identity.sampledAt(), now).abs().compareTo(IDENTITY_MAX_AGE) > 0) {
            return result(DirectLinkStatus.STALE_SIGNAL, "Định danh Wi-Fi Direct đã cũ");
        }
        for (var sample : samples == null ? List.<LinuxP2pStationCollector.StationSample>of() : samples) {
            if (!identity.localP2pMac().equals(sample.stationMac())) continue;
            if (!LinuxP2pStationCollector.isGroupInterface(sample.interfaceName())) {
                return result(DirectLinkStatus.WRONG_SIGNAL_SOURCE, "RSSI không đến từ nhóm Wi-Fi Direct");
            }
            if (Duration.between(sample.sampledAt(), now).abs().compareTo(SIGNAL_MAX_AGE) > 0) {
                return result(DirectLinkStatus.STALE_SIGNAL, "Mẫu RSSI Wi-Fi Direct đã cũ");
            }
            return new Resolution(DirectLinkStatus.CONNECTED, sample, "OK");
        }
        return result(DirectLinkStatus.WRONG_SIGNAL_SOURCE, "Server chưa thấy MAC Client trong nhóm Wi-Fi Direct");
    }

    private static Resolution result(DirectLinkStatus status, String reason) {
        return new Resolution(status, null, reason);
    }
}
