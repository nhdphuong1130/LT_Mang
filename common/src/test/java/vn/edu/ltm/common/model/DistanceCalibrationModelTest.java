package vn.edu.ltm.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DistanceCalibrationModelTest {
    @Test void recoversEvidenceBackedLogDistanceParameters() {
        double expectedA = -52.4;
        double expectedN = 2.83;
        var points = List.of(1.0, 2.0, 3.0, 5.0).stream()
                .map(distance -> new DistanceCalibrationPoint(distance,
                        expectedA - 10.0 * expectedN * Math.log10(distance)))
                .toList();

        var model = DistanceCalibrationModel.fit("room-a", Instant.parse("2026-10-24T00:00:00Z"), points);

        assertEquals(expectedA, model.referenceRssiAtOneMeter(), 0.01);
        assertEquals(expectedN, model.pathLossExponent(), 0.01);
        assertEquals(0.0, model.maeMeters(), 0.0001);
        assertEquals(0.0, model.rmseMeters(), 0.0001);
        assertTrue(model.calibrated());
    }

    @Test void rejectsInvalidOrInsufficientEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new DistanceCalibrationPoint(0, -50));
        assertThrows(IllegalArgumentException.class, () -> new DistanceCalibrationPoint(1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> DistanceCalibrationModel.fit("x", Instant.now(),
                List.of(new DistanceCalibrationPoint(1, -50), new DistanceCalibrationPoint(2, -60))));
    }
}
