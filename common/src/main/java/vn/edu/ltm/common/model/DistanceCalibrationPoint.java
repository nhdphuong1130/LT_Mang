package vn.edu.ltm.common.model;

/** One measured ground-truth distance paired with Server-observed Wi-Fi Direct RSSI. */
public record DistanceCalibrationPoint(double distanceMeters, double rssiDbm) {
    public DistanceCalibrationPoint {
        if (!Double.isFinite(distanceMeters) || distanceMeters <= 0.0) {
            throw new IllegalArgumentException("Distance must be finite and positive");
        }
        if (!Double.isFinite(rssiDbm) || rssiDbm > 0.0) {
            throw new IllegalArgumentException("RSSI must be a finite dBm value no greater than zero");
        }
    }
}
