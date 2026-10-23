package vn.edu.ltm.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApacheRssiKalmanFilterTest {
    @Test
    void initializesFromFirstFiniteMeasurement() {
        var filter = new ApacheRssiKalmanFilter(0.08, 2.2);

        assertEquals(-60.0, filter.update(-60.0), 0.0001);
    }

    @Test
    void dampensSingleRssiSpike() {
        var filter = new ApacheRssiKalmanFilter(0.08, 2.2);
        filter.update(-60.0);

        double spike = filter.update(-80.0);
        double recovered = filter.update(-60.0);

        assertTrue(spike > -80.0 && spike < -60.0, "Spike must be damped: " + spike);
        assertTrue(recovered > spike && recovered < -60.0, "Filter must move back toward the stable RSSI: " + recovered);
    }

    @Test
    void rejectsNonFiniteMeasurements() {
        var filter = new ApacheRssiKalmanFilter(0.08, 2.2);

        assertThrows(IllegalArgumentException.class, () -> filter.update(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> filter.update(Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsInvalidNoiseParameters() {
        assertThrows(IllegalArgumentException.class, () -> new ApacheRssiKalmanFilter(-0.01, 2.2));
        assertThrows(IllegalArgumentException.class, () -> new ApacheRssiKalmanFilter(0.08, 0.0));
    }
}
