package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.model.DistanceCalibrationModel;
import vn.edu.ltm.common.model.DistanceCalibrationPoint;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DistanceCalibrationStoreTest {
    @TempDir Path temp;

    @Test void atomicallyRoundTripsTheActiveCalibration() throws Exception {
        var model = DistanceCalibrationModel.fit("classroom-20261024", Instant.parse("2026-10-24T00:00:00Z"),
                List.of(new DistanceCalibrationPoint(1, -52.4),
                        new DistanceCalibrationPoint(2, -60.918),
                        new DistanceCalibrationPoint(3, -65.902),
                        new DistanceCalibrationPoint(5, -72.181)));
        Path file = temp.resolve("distance-calibration.properties");

        var store = new DistanceCalibrationStore(file);
        store.save(model);
        var loaded = new DistanceCalibrationStore(file).activeModel();

        assertEquals(model.id(), loaded.id());
        assertEquals(model.referenceRssiAtOneMeter(), loaded.referenceRssiAtOneMeter(), 0.000001);
        assertEquals(model.pathLossExponent(), loaded.pathLossExponent(), 0.000001);
        assertTrue(loaded.calibrated());
    }

    @Test void missingFileUsesClearlyUncalibratedFallback() {
        var model = new DistanceCalibrationStore(temp.resolve("missing.properties")).activeModel();
        assertFalse(model.calibrated());
        assertEquals("default-unverified", model.id());
    }
}
