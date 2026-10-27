package vn.edu.ltm.server.ranging;

import vn.edu.ltm.common.ranging.RangingStatus;

import java.util.Objects;

/**
 * Derived physical ranging measurement on the Server for a Client device.
 */
public record RangingSample(
        String deviceId,
        String sessionId,
        double distanceMeters,
        long distanceCentimeters,
        double confidence,
        RangingStatus status,
        double snrDb,
        double peakRatio,
        double twoWayErrorSamples,
        double audibleLeakageDb,
        long timestampEpochMillis
) {
    public RangingSample {
        Objects.requireNonNull(deviceId, "deviceId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }

    public static RangingSample failed(String deviceId, String sessionId, RangingStatus status) {
        return new RangingSample(
                deviceId,
                sessionId == null ? "" : sessionId,
                -1.0,
                -1L,
                0.0,
                status,
                0.0,
                0.0,
                0.0,
                -100.0,
                System.currentTimeMillis()
        );
    }
}
