package vn.edu.ltm.common.ranging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangingConfidenceScorerTest {
    private final AcousticRangingConfig config = AcousticRangingConfigTest.validBuilder().build();
    private final RangingConfidenceScorer scorer = new RangingConfidenceScorer(config);

    @Test void acceptsStrongMeasurementAtOneHundredPercent() {
        var scored = scorer.score(RangingProtocolTest.strongResult());
        assertEquals(100, scored.confidence(), 1.0e-9);
        assertTrue(scored.accepted());
        assertEquals(RangingStatus.ACOUSTIC_READY, scored.status());
    }

    @Test void rejectsLowSnrWithTypedStatus() {
        var weak = copy(5, 5, -60, 0, 1, true);
        var scored = scorer.score(weak);
        assertFalse(scored.accepted());
        assertEquals(RangingStatus.LOW_SNR, scored.status());
    }

    @Test void rejectsAudibleLeakageEvenWhenOtherMetricsAreStrong() {
        var audible = copy(30, 5, -20, 0, 1, true);
        var scored = scorer.score(audible);
        assertFalse(scored.accepted());
        assertEquals(RangingStatus.AUDIBLE_LEAKAGE, scored.status());
    }

    @Test void preservesUpstreamHardwareFailure() {
        var failed = new RangingResult("session-1", 1, 2, 96_000, 30, 5, -60,
                0, 1, false, RangingStatus.AUDIO_UNSUPPORTED, List.of("NO_CAPTURE"), 1);
        assertEquals(RangingStatus.AUDIO_UNSUPPORTED, scorer.score(failed).status());
    }

    private static RangingResult copy(double snr, double peak, double leakage,
                                      double agreementError, double stability, boolean hardware) {
        return new RangingResult("session-1", 1_000, 8_000, 96_000, snr, peak, leakage,
                agreementError, stability, hardware, RangingStatus.ACOUSTIC_READY, List.of(), 1);
    }
}
