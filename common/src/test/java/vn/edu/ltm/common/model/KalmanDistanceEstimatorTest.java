package vn.edu.ltm.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KalmanDistanceEstimatorTest {
    @Test
    void kalmanFilterSmoothsRssiAndCalculatesLogDistanceMeters() {
        var estimator = new KalmanDistanceEstimator(-45.0, 2.5);
        DistanceTelemetry t1 = estimator.updateFromRssiAndRtt(-60.0, 12.0, 128);
        DistanceTelemetry t2 = estimator.updateFromRssiAndRtt(-80.0, 15.0, 128); // spike
        DistanceTelemetry t3 = estimator.updateFromRssiAndRtt(-60.0, 13.0, 128);

        assertTrue(t2.rssiFilteredDbm() > -80.0 && t2.rssiFilteredDbm() < -60.0,
                "Kalman filter should dampen single RSSI spike: " + t2.rssiFilteredDbm());
        assertTrue(t3.estimatedMeters() > 1.5 && t3.estimatedMeters() < 8.0,
                "Estimated meters at ~-60 dBm should be in classroom range: " + t3.estimatedMeters());
        assertEquals(1, t3.hopCount(), "TTL 128 should indicate 1 hop (direct LAN)");
        assertEquals("Trong phòng (2m - 8m)", t3.zoneLabel());
        assertTrue(t3.method().contains("Apache Commons Math"));
    }

    @Test
    void estimatesDistanceFromRttAndJitterWhenAgentDoesNotSendRssi() {
        var estimator = new KalmanDistanceEstimator(-45.0, 2.5);
        DistanceTelemetry sample = estimator.updateFromRttOnly(14.5, 128);
        assertTrue(sample.isOnline());
        assertFalse(sample.hasPhysicalDistance(), "LAN RTT must not be presented as a physical distance");
        assertEquals(-1.0, sample.estimatedMeters());
        assertEquals(1, sample.hopCount());
        assertEquals(0, sample.signalQualityPercent());
    }

    @Test
    void defaultIndoorCalibrationClassifiesStrongWifiAsUnderTwoMeters() {
        var estimator = new KalmanDistanceEstimator();

        DistanceTelemetry sample = estimator.updateFromRssi(-58.0);

        assertTrue(sample.hasPhysicalDistance());
        assertTrue(sample.estimatedMeters() < 2.0,
                "A strong -58 dBm classroom signal should be classified under 2m: " + sample.estimatedMeters());
        assertEquals("Rất gần (< 2m)", sample.zoneLabel());
    }
}
