package vn.edu.ltm.common.model;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Evidence and coefficients for RSSI = A - 10*n*log10(distance). */
public record DistanceCalibrationModel(
        String id,
        Instant calibratedAt,
        double referenceRssiAtOneMeter,
        double pathLossExponent,
        int sampleCount,
        double maeMeters,
        double rmseMeters
) {
    public DistanceCalibrationModel {
        id = Objects.requireNonNull(id, "id").strip();
        calibratedAt = Objects.requireNonNull(calibratedAt, "calibratedAt");
        if (id.isEmpty()) throw new IllegalArgumentException("Calibration ID is required");
        if (!Double.isFinite(referenceRssiAtOneMeter)) throw new IllegalArgumentException("A must be finite");
        if (!Double.isFinite(pathLossExponent) || pathLossExponent <= 0) throw new IllegalArgumentException("n must be positive");
        if (sampleCount < 0) throw new IllegalArgumentException("Sample count cannot be negative");
        if (!validError(maeMeters) || !validError(rmseMeters)) throw new IllegalArgumentException("Errors must be non-negative");
    }

    public static DistanceCalibrationModel fit(String id, Instant calibratedAt,
                                               List<DistanceCalibrationPoint> points) {
        if (points == null || points.size() < 3) {
            throw new IllegalArgumentException("At least three calibration samples are required");
        }
        SimpleRegression regression = new SimpleRegression(true);
        for (var point : points) regression.addData(Math.log10(point.distanceMeters()), point.rssiDbm());
        double a = regression.getIntercept();
        double n = -regression.getSlope() / 10.0;
        if (!Double.isFinite(a) || !Double.isFinite(n) || n <= 0.0) {
            throw new IllegalArgumentException("Calibration samples do not produce a valid path-loss model");
        }
        double absolute = 0.0;
        double squared = 0.0;
        for (var point : points) {
            double error = Math.abs(estimate(point.rssiDbm(), a, n) - point.distanceMeters());
            absolute += error;
            squared += error * error;
        }
        return new DistanceCalibrationModel(id, calibratedAt, a, n, points.size(),
                absolute / points.size(), Math.sqrt(squared / points.size()));
    }

    public static DistanceCalibrationModel uncalibratedDefault() {
        return new DistanceCalibrationModel("default-unverified", Instant.EPOCH,
                KalmanDistanceEstimator.DEFAULT_TX_POWER_1M_DBM,
                KalmanDistanceEstimator.DEFAULT_PATH_LOSS_INDEX, 0, 0.0, 0.0);
    }

    public boolean calibrated() { return sampleCount >= 3 && !"default-unverified".equals(id); }

    public double estimateMeters(double rssiDbm) {
        if (!Double.isFinite(rssiDbm)) throw new IllegalArgumentException("RSSI must be finite");
        return estimate(rssiDbm, referenceRssiAtOneMeter, pathLossExponent);
    }

    private static double estimate(double rssi, double a, double n) {
        return Math.pow(10.0, (a - rssi) / (10.0 * n));
    }

    private static boolean validError(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
