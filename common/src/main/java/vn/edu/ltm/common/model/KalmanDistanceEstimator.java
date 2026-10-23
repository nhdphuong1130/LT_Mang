package vn.edu.ltm.common.model;

/**
 * Apache Commons Math 1D Kalman Filter + Log-Distance Path Loss distance estimator.
 */
public final class KalmanDistanceEstimator {
    public static final double DEFAULT_TX_POWER_1M_DBM = -50.0;
    public static final double DEFAULT_PATH_LOSS_INDEX = 3.0;
    private static final double PROCESS_NOISE_Q = 0.08;
    private static final double MEASUREMENT_NOISE_R = 2.2;

    private final double txPowerAt1mDbm;
    private final double pathLossExponent;
    private final ApacheRssiKalmanFilter rssiFilter;

    private boolean rttInitialized;
    private double rttEstLast;
    private double rttPLast = 1.0;
    private double jitterEstMs;

    public KalmanDistanceEstimator() {
        this(DEFAULT_TX_POWER_1M_DBM, DEFAULT_PATH_LOSS_INDEX);
    }

    public KalmanDistanceEstimator(double txPowerAt1mDbm, double pathLossExponent) {
        if (!Double.isFinite(txPowerAt1mDbm)) throw new IllegalArgumentException("Reference RSSI must be finite");
        if (!Double.isFinite(pathLossExponent) || pathLossExponent <= 0.0) {
            throw new IllegalArgumentException("Path-loss exponent must be finite and positive");
        }
        this.txPowerAt1mDbm = txPowerAt1mDbm;
        this.pathLossExponent = pathLossExponent;
        this.rssiFilter = new ApacheRssiKalmanFilter(PROCESS_NOISE_Q, MEASUREMENT_NOISE_R);
    }

    /**
     * Full hybrid update when both Wi-Fi RSSI (dBm) and network RTT (ms) are available.
     */
    public synchronized DistanceTelemetry updateFromRssiAndRtt(double rawRssiDbm, double rawRttMs, int ttl) {
        double filteredRssi = kalmanUpdateRssi(rawRssiDbm);
        double filteredRtt = kalmanUpdateRtt(Math.max(0.2, rawRttMs));
        double meters = calculateMeters(filteredRssi);
        int quality = rssiToQualityPercent(filteredRssi);
        int hops = estimateHops(ttl);
        return new DistanceTelemetry(
                rawRssiDbm, filteredRssi, quality, meters,
                filteredRtt, jitterEstMs, ttl, hops,
                classifyZone(meters), "Wi-Fi Direct RSSI + RTT (Apache Commons Math KalmanFilter)"
        );
    }

    /** Updates physical distance from a real Agent RSSI sample before the first network probe completes. */
    public synchronized DistanceTelemetry updateFromRssi(double rawRssiDbm) {
        double filteredRssi = kalmanUpdateRssi(rawRssiDbm);
        double meters = calculateMeters(filteredRssi);
        return new DistanceTelemetry(
                rawRssiDbm, filteredRssi, rssiToQualityPercent(filteredRssi), meters,
                -1.0, 0.0, 0, 0, classifyZone(meters), "Wi-Fi Direct RSSI (Apache Commons Math KalmanFilter)"
        );
    }

    /**
     * Records network latency when the client has not reported RSSI yet. RTT is intentionally not
     * converted to metres because LAN scheduling and congestion dominate propagation time.
     */
    public synchronized DistanceTelemetry updateFromRttOnly(double rawRttMs, int ttl) {
        double safeRtt = Math.max(0.2, rawRttMs);
        double filteredRtt = kalmanUpdateRtt(safeRtt);
        int hops = estimateHops(ttl);
        return new DistanceTelemetry(
                -100.0, -100.0, 0, -1.0,
                filteredRtt, jitterEstMs, ttl, hops,
                "CHỜ RSSI", "Chỉ đo RTT/Jitter (không quy đổi thành mét)"
        );
    }

    private double kalmanUpdateRssi(double rawVal) {
        return rssiFilter.update(rawVal);
    }

    private double kalmanUpdateRtt(double rawRtt) {
        if (!rttInitialized) {
            rttInitialized = true;
            rttEstLast = rawRtt;
            rttPLast = 1.0;
            jitterEstMs = 0.5;
            return rawRtt;
        }
        double delta = Math.abs(rawRtt - rttEstLast);
        jitterEstMs = 0.75 * jitterEstMs + 0.25 * delta;
        double pTemp = rttPLast + 0.15;
        double k = pTemp / (pTemp + 1.8);
        double xEst = rttEstLast + k * (rawRtt - rttEstLast);
        rttPLast = (1.0 - k) * pTemp;
        rttEstLast = xEst;
        return xEst;
    }

    private double calculateMeters(double filteredRssi) {
        // Log-Distance Path Loss equation from Scanare_Bluetooth_kalman.c:
        // ratio = (dynamic_rssi_1m - filtered_rssi) / (10.0 * PATH_LOSS_INDEX)
        // dist_m = 10 ^ ratio
        double ratio = (txPowerAt1mDbm - filteredRssi) / (10.0 * pathLossExponent);
        double pathLossMeters = Math.pow(10.0, ratio);
        // Clamp to realistic classroom/building range [0.3m .. 35.0m]
        double clamped = Math.max(0.3, Math.min(35.0, pathLossMeters));
        return Math.round(clamped * 10.0) / 10.0;
    }

    public static int rssiToQualityPercent(double rssiDbm) {
        if (rssiDbm <= -100.0) return 0;
        if (rssiDbm >= -45.0) return 100;
        return (int) Math.round(2.0 * (rssiDbm + 100.0));
    }

    public static int estimateHops(int ttl) {
        if (ttl <= 0) return 1;
        int initialTtl = ttl <= 64 ? 64 : (ttl <= 128 ? 128 : 255);
        return Math.max(1, initialTtl - ttl + 1);
    }

    public static String classifyZone(double meters) {
        if (meters < 0) return "OFFLINE";
        if (meters < 2.0) return "Rất gần (< 2m)";
        if (meters <= 8.0) return "Trong phòng (2m - 8m)";
        return "Cách phòng / Xa (> 8m)";
    }
}
