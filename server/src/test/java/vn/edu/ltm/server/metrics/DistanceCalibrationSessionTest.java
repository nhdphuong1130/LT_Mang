package vn.edu.ltm.server.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.model.*;

import java.nio.file.Path;
import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DistanceCalibrationSessionTest {
    private static final Instant START = Instant.parse("2026-10-24T00:00:00Z");

    @Test void includesSubMeterReferencePoint() {
        assertEquals(List.of(0.5, 1.0, 2.0, 3.0, 5.0),
                DistanceCalibrationSession.REQUIRED_DISTANCES_METERS);
    }

    @Test void acceptsOneHundredFreshVerifiedSamplesPerLabelAndActivatesModel(@TempDir Path temp) throws Exception {
        Instant now = START.plusSeconds(500);
        var session = new DistanceCalibrationSession("pc-b", 100, Clock.fixed(now, ZoneOffset.UTC));
        double[] distances = {0.5, 1, 2, 3, 5};
        int sequence = 0;
        for (double distance : distances) {
            for (int i = 0; i < 100; i++) {
                double rssi = -52.4 - 10 * 2.83 * Math.log10(distance);
                assertTrue(session.accept("pc-b", distance, telemetry(rssi, now.minusNanos(sequence++))));
            }
            assertEquals(100, session.acceptedCount(distance));
        }
        assertTrue(session.complete());

        var store = new DistanceCalibrationStore(temp.resolve("calibration.properties"));
        var model = session.activate(store);
        assertEquals(model.id(), store.activeModel().id());
        assertEquals(-52.4, model.referenceRssiAtOneMeter(), 0.01);
        assertEquals(2.83, model.pathLossExponent(), 0.01);
    }

    @Test void rejectsWrongDeviceWrongSourceStaleAndDuplicateSamples() {
        var now = START.plusSeconds(10);
        var session = new DistanceCalibrationSession("pc-b", 100, Clock.fixed(now, ZoneOffset.UTC));
        var valid = telemetry(-55, now);

        assertFalse(session.accept("pc-c", 1, valid));
        assertFalse(session.accept("pc-b", 1, DistanceTelemetry.waitingForSignal()));
        assertFalse(session.accept("pc-b", 1, telemetry(-55, START)));
        assertTrue(session.accept("pc-b", 1, valid));
        assertFalse(session.accept("pc-b", 1, valid));
    }

    @Test void exportsEscapedAuditableCsv() {
        var now = START.plusSeconds(1);
        var session = new DistanceCalibrationSession("pc,\"B\"", 1, Clock.fixed(now, ZoneOffset.UTC));
        assertTrue(session.accept("pc,\"B\"", 1, telemetry(-52, now)));

        String csv = session.toCsv();

        assertTrue(csv.startsWith("device_id,distance_m,rssi_dbm,sampled_at,interface_name,peer_mac"));
        assertTrue(csv.contains("\"pc,\"\"B\"\"\""));
        assertTrue(csv.contains("p2p-wlp0s20f3-0"));
    }

    private static DistanceTelemetry telemetry(double rssi, Instant sampledAt) {
        return new DistanceTelemetry(rssi, rssi, 80, 1, 2, 0, 128, 1, "near", "method",
                DistanceTelemetry.VERIFIED_DIRECT_SOURCE, DirectLinkStatus.CONNECTED,
                "p2p-wlp0s20f3-0", "CC:C5:30:08:CF:12", sampledAt, "default-unverified", false);
    }
}
