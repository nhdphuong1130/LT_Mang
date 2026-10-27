package vn.edu.ltm.common.model;

import java.time.Instant;
import java.util.Locale;

/**
 * Real-time physical and network distance telemetry between Server and Client.
 * Keeps 1D Kalman-filtered Wi-Fi RSSI physical estimates separate from ICMP/TCP network measurements.
 */
public record DistanceTelemetry(
        double rssiRawDbm,
        double rssiFilteredDbm,
        int signalQualityPercent,
        double estimatedMeters,
        double rttMs,
        double jitterMs,
        int ttl,
        int hopCount,
        String zoneLabel,
        String method,
        String measurementSource,
        DirectLinkStatus directLinkStatus,
        String directInterfaceName,
        String peerMac,
        Instant signalSampledAt,
        String calibrationId,
        boolean calibrated
) {
    public static final String VERIFIED_DIRECT_SOURCE = "WIFI_DIRECT_SERVER_STATION";

    /** Compatibility constructor for the original ten-field distance model. */
    public DistanceTelemetry(double rssiRawDbm, double rssiFilteredDbm, int signalQualityPercent,
                             double estimatedMeters, double rttMs, double jitterMs, int ttl, int hopCount,
                             String zoneLabel, String method) {
        this(rssiRawDbm, rssiFilteredDbm, signalQualityPercent, estimatedMeters, rttMs, jitterMs, ttl, hopCount,
                zoneLabel, method,
                estimatedMeters >= 0.0 ? VERIFIED_DIRECT_SOURCE : "NONE",
                estimatedMeters >= 0.0 ? DirectLinkStatus.CONNECTED : DirectLinkStatus.STALE_SIGNAL,
                "", "", null, "default-unverified", false);
    }

    public static DistanceTelemetry offline() {
        return new DistanceTelemetry(-100.0, -100.0, 0, -1.0, -1.0, 0.0, 0, 0,
                "OFFLINE (Mất kết nối)", "N/A", "NONE", DirectLinkStatus.UNAVAILABLE,
                "", "", null, "", false);
    }

    public static DistanceTelemetry waitingForSignal() {
        return waitingFor(DirectLinkStatus.AGENT_UPDATE_REQUIRED, "Đang chờ định danh Wi-Fi Direct từ Agent");
    }

    public static DistanceTelemetry waitingFor(DirectLinkStatus status, String reason) {
        return new DistanceTelemetry(-100.0, -100.0, 0, -1.0, -1.0, 0.0, 0, 0,
                "CHỜ LIÊN KẾT TRỰC TIẾP", reason, "NONE", status,
                "", "", null, "", false);
    }

    public boolean isOnline() {
        return !"N/A".equals(method);
    }

    public boolean hasPhysicalDistance() {
        return estimatedMeters >= 0.0
                && VERIFIED_DIRECT_SOURCE.equals(measurementSource)
                && directLinkStatus == DirectLinkStatus.CONNECTED;
    }

    public String badgeText() {
        if (!isOnline()) {
            return "📍 Khoảng cách: OFFLINE";
        }
        if (!hasPhysicalDistance()) {
            return rttMs >= 0.0
                    ? String.format(Locale.ROOT, "📍 Chờ RSSI | RTT %.1f ms | %d Hop", rttMs, Math.max(1, hopCount))
                    : "📍 Đang chờ tín hiệu RSSI từ Client";
        }
        return String.format(Locale.ROOT, "📍 ~%.2f m | RTT %.0f ms | %.0f dBm (%d Hop)",
                estimatedMeters, Math.max(0.0, rttMs), rssiFilteredDbm, Math.max(0, hopCount));
    }
}
