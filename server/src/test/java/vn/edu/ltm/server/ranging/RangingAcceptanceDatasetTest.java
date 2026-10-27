package vn.edu.ltm.server.ranging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance test harness for acoustic ranging evaluations.
 * Evaluates held-out validation datasets at 0.5, 1.0, 2.0, 3.0, and 5.0 meters.
 * Explicitly guards against claiming >90% physical accuracy without real dual-laptop field data.
 */
class RangingAcceptanceDatasetTest {

    public static final List<Double> REQUIRED_DISTANCES_METERS = List.of(0.5, 1.0, 2.0, 3.0, 5.0);
    public static final double DEFAULT_TOLERANCE_METERS = 0.05; // 5 cm
    public static final double FIELD_TARGET_HIT_RATE = 0.90; // 90%

    public enum SessionType {
        CALIBRATION,
        VALIDATION
    }

    public enum ValidationState {
        FIELD_VALIDATED,
        EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK
    }

    public record DatasetSample(
            String sampleId,
            SessionType sessionType,
            double groundTruthMeters,
            double measuredMeters,
            double confidence,
            double snrDb,
            double peakRatio,
            boolean isPhysicalDualLaptopMeasurement
    ) {
        public double errorMeters() {
            return Math.abs(measuredMeters - groundTruthMeters);
        }

        public boolean isHit(double toleranceMeters) {
            return errorMeters() <= toleranceMeters;
        }
    }

    public record DistanceMetric(
            double groundTruthMeters,
            int totalSamples,
            int hitCount,
            double hitRate,
            double meanAbsoluteErrorMeters,
            double maxErrorMeters
    ) {}

    public record EvaluationReport(
            int totalValidationSamples,
            int totalValidationHits,
            double overallHitRate,
            double overallMeanAbsoluteErrorMeters,
            Map<Double, DistanceMetric> metricsByDistance,
            ValidationState state,
            String notes
    ) {
        public boolean isAccuracyTargetMet() {
            return state == ValidationState.FIELD_VALIDATED && overallHitRate >= FIELD_TARGET_HIT_RATE;
        }
    }

    public static class HeldOutDatasetEvaluator {

        public static List<DatasetSample> parseCsv(String csvContent, boolean isPhysicalDualLaptop) throws IOException {
            List<DatasetSample> samples = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
                String line;
                boolean headerSkipped = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (!headerSkipped) {
                        headerSkipped = true;
                        if (line.toLowerCase(Locale.ROOT).startsWith("sample_id")
                                || line.toLowerCase(Locale.ROOT).startsWith("session_type")) {
                            continue;
                        }
                    }
                    String[] parts = line.split(",");
                    if (parts.length < 7) continue;
                    String id = parts[0].trim();
                    SessionType type = SessionType.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
                    double truth = Double.parseDouble(parts[2].trim());
                    double measured = Double.parseDouble(parts[3].trim());
                    double confidence = Double.parseDouble(parts[4].trim());
                    double snr = Double.parseDouble(parts[5].trim());
                    double peak = Double.parseDouble(parts[6].trim());
                    samples.add(new DatasetSample(id, type, truth, measured, confidence, snr, peak, isPhysicalDualLaptop));
                }
            }
            return samples;
        }

        public static EvaluationReport evaluate(List<DatasetSample> dataset, double toleranceMeters) {
            // Strictly evaluate ONLY held-out validation samples
            List<DatasetSample> validationSamples = dataset.stream()
                    .filter(s -> s.sessionType() == SessionType.VALIDATION)
                    .toList();

            if (validationSamples.isEmpty()) {
                return new EvaluationReport(0, 0, 0.0, 0.0, Map.of(),
                        ValidationState.EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK,
                        "No held-out validation samples found");
            }

            Map<Double, List<DatasetSample>> byDistance = validationSamples.stream()
                    .collect(Collectors.groupingBy(DatasetSample::groundTruthMeters));

            Map<Double, DistanceMetric> metrics = new HashMap<>();
            int totalHits = 0;
            double sumAbsoluteError = 0.0;

            for (Map.Entry<Double, List<DatasetSample>> entry : byDistance.entrySet()) {
                double distance = entry.getKey();
                List<DatasetSample> distanceSamples = entry.getValue();
                int hits = (int) distanceSamples.stream().filter(s -> s.isHit(toleranceMeters)).count();
                double hitRate = (double) hits / distanceSamples.size();
                double mae = distanceSamples.stream().mapToDouble(DatasetSample::errorMeters).average().orElse(0.0);
                double maxErr = distanceSamples.stream().mapToDouble(DatasetSample::errorMeters).max().orElse(0.0);

                totalHits += hits;
                sumAbsoluteError += distanceSamples.stream().mapToDouble(DatasetSample::errorMeters).sum();
                metrics.put(distance, new DistanceMetric(distance, distanceSamples.size(), hits, hitRate, mae, maxErr));
            }

            double overallHitRate = (double) totalHits / validationSamples.size();
            double overallMae = sumAbsoluteError / validationSamples.size();

            boolean allPhysical = validationSamples.stream().allMatch(DatasetSample::isPhysicalDualLaptopMeasurement);
            ValidationState state = (allPhysical && overallHitRate >= FIELD_TARGET_HIT_RATE)
                    ? ValidationState.FIELD_VALIDATED
                    : ValidationState.EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK;

            String notes = allPhysical
                    ? String.format(Locale.ROOT, "Physical field data evaluated. Overall hit rate: %.1f%%", overallHitRate * 100.0)
                    : "Synthetic or simulated data evaluated. Physical dual-laptop field validation remains unverified.";

            return new EvaluationReport(validationSamples.size(), totalHits, overallHitRate, overallMae, metrics, state, notes);
        }
    }

    @Test
    @DisplayName("Evaluates synthetic held-out dataset and computes per-distance hit rates")
    void testSyntheticHeldOutDatasetEvaluator() {
        List<DatasetSample> samples = new ArrayList<>();
        Random random = new Random(42);

        // Generate synthetic samples for calibration and validation
        for (double d : REQUIRED_DISTANCES_METERS) {
            for (int i = 0; i < 50; i++) {
                // Calibration set
                double noise = random.nextGaussian() * 0.015; // std dev 1.5 cm
                samples.add(new DatasetSample("cal-" + d + "-" + i, SessionType.CALIBRATION, d, d + noise, 95.0, 25.0, 4.0, false));
            }
            for (int i = 0; i < 50; i++) {
                // Held-out validation set
                double noise = random.nextGaussian() * 0.015; // std dev 1.5 cm
                samples.add(new DatasetSample("val-" + d + "-" + i, SessionType.VALIDATION, d, d + noise, 95.0, 25.0, 4.0, false));
            }
        }

        EvaluationReport report = HeldOutDatasetEvaluator.evaluate(samples, DEFAULT_TOLERANCE_METERS);

        assertEquals(250, report.totalValidationSamples());
        assertTrue(report.overallHitRate() >= 0.90, "Synthetic noise within 1.5cm must yield >=90% within 5cm tolerance");
        for (double d : REQUIRED_DISTANCES_METERS) {
            assertTrue(report.metricsByDistance().containsKey(d));
            DistanceMetric m = report.metricsByDistance().get(d);
            assertEquals(50, m.totalSamples());
            assertTrue(m.hitRate() >= 0.85);
        }

        // Even though synthetic hit rate is >= 90%, it is NOT marked FIELD_VALIDATED because isPhysicalDualLaptopMeasurement is false
        assertEquals(ValidationState.EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK, report.state());
        assertFalse(report.isAccuracyTargetMet(), "Must NOT claim field target is met with synthetic data");
    }

    @Test
    @DisplayName("Detects calibration errors on held-out validation when turnaround offset is misconfigured")
    void testMiscalibratedTurnaroundFailsHeldOutTolerance() {
        List<DatasetSample> samples = new ArrayList<>();

        for (double d : REQUIRED_DISTANCES_METERS) {
            for (int i = 0; i < 20; i++) {
                // Calibration set
                samples.add(new DatasetSample("cal-" + d + "-" + i, SessionType.CALIBRATION, d, d, 95.0, 25.0, 4.0, false));
                // Validation set with 15 cm systematic offset (error > 5cm)
                samples.add(new DatasetSample("val-" + d + "-" + i, SessionType.VALIDATION, d, d + 0.15, 95.0, 25.0, 4.0, false));
            }
        }

        EvaluationReport report = HeldOutDatasetEvaluator.evaluate(samples, DEFAULT_TOLERANCE_METERS);

        assertEquals(0.0, report.overallHitRate(), 0.001, "A 15 cm error must yield 0% hit rate with 5 cm tolerance");
        assertEquals(0.15, report.overallMeanAbsoluteErrorMeters(), 0.001);
    }

    @Test
    @DisplayName("Parses CSV formatted dataset with session types and metrics")
    void testCsvDatasetParsing() throws IOException {
        String csv = """
                sample_id,session_type,true_distance_m,measured_distance_m,confidence_score,snr_db,peak_ratio
                s1,CALIBRATION,0.5,0.51,96.0,26.0,5.0
                s2,CALIBRATION,1.0,0.99,95.0,24.0,4.8
                s3,VALIDATION,0.5,0.52,94.0,22.0,4.2
                s4,VALIDATION,1.0,1.03,93.0,21.0,4.1
                s5,VALIDATION,2.0,2.01,95.0,25.0,4.9
                s6,VALIDATION,3.0,2.98,92.0,20.0,3.9
                s7,VALIDATION,5.0,5.04,91.0,19.0,3.5
                """;

        List<DatasetSample> parsed = HeldOutDatasetEvaluator.parseCsv(csv, false);
        assertEquals(7, parsed.size());

        EvaluationReport report = HeldOutDatasetEvaluator.evaluate(parsed, DEFAULT_TOLERANCE_METERS);
        assertEquals(5, report.totalValidationSamples());
        assertEquals(5, report.totalValidationHits());
        assertEquals(1.0, report.overallHitRate(), 0.001);
        assertEquals(ValidationState.EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK, report.state());
    }

    @Test
    @DisplayName("Physical dual-laptop dataset only validates field target when actual empirical hit rate >= 90%")
    void testPhysicalDualLaptopTargetValidation() {
        List<DatasetSample> physicalSuccess = new ArrayList<>();
        List<DatasetSample> physicalFailure = new ArrayList<>();

        for (double d : REQUIRED_DISTANCES_METERS) {
            for (int i = 0; i < 20; i++) {
                // High accuracy physical measurement (within 3 cm)
                physicalSuccess.add(new DatasetSample("phy-s-" + d + "-" + i, SessionType.VALIDATION, d, d + 0.02, 98.0, 30.0, 6.0, true));
                // Low accuracy physical measurement (multipath or drift 10 cm)
                physicalFailure.add(new DatasetSample("phy-f-" + d + "-" + i, SessionType.VALIDATION, d, d + 0.10, 85.0, 15.0, 2.5, true));
            }
        }

        EvaluationReport successReport = HeldOutDatasetEvaluator.evaluate(physicalSuccess, DEFAULT_TOLERANCE_METERS);
        assertEquals(ValidationState.FIELD_VALIDATED, successReport.state());
        assertTrue(successReport.isAccuracyTargetMet());

        EvaluationReport failureReport = HeldOutDatasetEvaluator.evaluate(physicalFailure, DEFAULT_TOLERANCE_METERS);
        assertEquals(ValidationState.EXPLICITLY_INCOMPLETE_PENDING_TWO_LAPTOP_BENCHMARK, failureReport.state());
        assertFalse(failureReport.isAccuracyTargetMet());
    }

    @Test
    @DisplayName("Explicit assertion that current project state has not yet completed physical dual-laptop field testing")
    void testCurrentProjectFieldStatusIsUnverifiedPendingDualLaptopExperiment() {
        // This test serves as a permanent architectural invariant:
        // Until actual CSV logs from physical field experiments on 2 laptops are gathered,
        // field accuracy >90% within 5cm CANNOT be claimed as verified.
        boolean physicalFieldTestingCompletedOnTwoLaptops = false; // Must be toggled only with real field logs

        assertFalse(physicalFieldTestingCompletedOnTwoLaptops,
                "Physical field validation on two real laptops is still pending. Do not claim >90% field accuracy.");
    }
}
