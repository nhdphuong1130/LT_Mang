package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TwoWayDistanceCalculatorTest {
    @Test void convertsRoundTripSamplesToCentimetreScaleDistance() {
        var config = AcousticRangingConfigTest.validBuilder().responseDelayMs(50).build();
        var calculator = new TwoWayDistanceCalculator(config);
        double expectedMeters = 1.25;
        double acousticMillis = expectedMeters * 2_000.0 / config.speedOfSoundMetersPerSecond();
        long samples = Math.round((config.responseDelayMs() + acousticMillis)
                * config.sampleRateHz() / 1_000.0);

        assertEquals(expectedMeters, calculator.distanceMeters(samples), 0.002);
        assertEquals(125, calculator.distanceCentimeters(samples), 1);
    }

    @Test void appliesConfiguredLatencyOffsetAndRejectsImpossibleResult() {
        var config = AcousticRangingConfigTest.validBuilder().responseDelayMs(0)
                .calibrationOffsetMillis(1).build();
        var calculator = new TwoWayDistanceCalculator(config);
        long oneWayMillisecondRoundTrip = Math.round(2 * config.sampleRateHz() / 1_000.0);
        assertEquals(config.speedOfSoundMetersPerSecond() / 2_000.0,
                calculator.distanceMeters(oneWayMillisecondRoundTrip), 0.002);
        assertThrows(IllegalArgumentException.class, () -> calculator.distanceMeters(0));
    }
}
