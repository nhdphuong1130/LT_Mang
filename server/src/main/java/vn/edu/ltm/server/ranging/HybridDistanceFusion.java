package vn.edu.ltm.server.ranging;

import vn.edu.ltm.common.model.DistanceTelemetry;
import vn.edu.ltm.common.ranging.AcousticRangingConfig;
import vn.edu.ltm.common.ranging.RangingStatus;

import java.util.Locale;
import java.util.Objects;

/**
 * Confidence-gated fusion service:
 * Combines primary acoustic ranging with Wi-Fi Direct distance fallback.
 * Strictly avoids silent fixed-weight averaging.
 */
public final class HybridDistanceFusion {

    private final AcousticRangingConfig config;

    public HybridDistanceFusion(AcousticRangingConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public DistanceTelemetry fuse(String deviceId, RangingSample acoustic, DistanceTelemetry wifi, long nowEpochMillis) {
        if (wifi == null) {
            wifi = DistanceTelemetry.waitingForSignal();
        }

        long maxAgeMillis = Math.max(1500L, config.measurementIntervalMs() * 4L);
        boolean isStale = (acoustic == null) || (nowEpochMillis - acoustic.timestampEpochMillis() > maxAgeMillis);

        boolean acousticAccepted = !isStale
                && acoustic.status() == RangingStatus.ACOUSTIC_READY
                && acoustic.confidence() >= config.confidenceThreshold()
                && acoustic.distanceMeters() > 0;

        if (acousticAccepted) {
            String zoneLabel = String.format(Locale.ROOT, "%.2f m (%d cm) [Âm học, tin cậy %.0f%%]",
                    acoustic.distanceMeters(), acoustic.distanceCentimeters(), acoustic.confidence());

            return new DistanceTelemetry(
                    wifi.rssiRawDbm(),
                    wifi.rssiFilteredDbm(),
                    wifi.signalQualityPercent(),
                    acoustic.distanceMeters(),
                    wifi.rttMs(),
                    wifi.jitterMs(),
                    wifi.ttl(),
                    wifi.hopCount(),
                    zoneLabel,
                    "HYBRID_ACOUSTIC",
                    DistanceTelemetry.ACOUSTIC_SOURCE,
                    wifi.directLinkStatus(),
                    wifi.directInterfaceName(),
                    wifi.peerMac(),
                    wifi.signalSampledAt(),
                    wifi.calibrationId(),
                    wifi.calibrated()
            );
        }

        // Fallback to Wi-Fi Direct
        String method;
        if (acoustic == null) {
            method = wifi.method();
        } else if (isStale) {
            method = "WIFI_FALLBACK (STALE_ACOUSTIC)";
        } else if (acoustic.status() != RangingStatus.ACOUSTIC_READY) {
            method = "WIFI_FALLBACK (" + acoustic.status() + ")";
        } else {
            method = String.format(Locale.ROOT, "WIFI_FALLBACK (CONFIDENCE_LOW: %.0f%%)", acoustic.confidence());
        }

        return new DistanceTelemetry(
                wifi.rssiRawDbm(),
                wifi.rssiFilteredDbm(),
                wifi.signalQualityPercent(),
                wifi.estimatedMeters(),
                wifi.rttMs(),
                wifi.jitterMs(),
                wifi.ttl(),
                wifi.hopCount(),
                wifi.zoneLabel(),
                method,
                wifi.measurementSource(),
                wifi.directLinkStatus(),
                wifi.directInterfaceName(),
                wifi.peerMac(),
                wifi.signalSampledAt(),
                wifi.calibrationId(),
                wifi.calibrated()
        );
    }
}
