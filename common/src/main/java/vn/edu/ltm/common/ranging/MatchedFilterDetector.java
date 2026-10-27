package vn.edu.ltm.common.ranging;

import java.util.Objects;

/** Normalized matched filter with an ambiguity check outside the main correlation lobe. */
public final class MatchedFilterDetector {
    private final AcousticRangingConfig config;

    public MatchedFilterDetector(AcousticRangingConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public ChirpDetection detect(double[] recording, double[] reference) {
        Objects.requireNonNull(recording);
        Objects.requireNonNull(reference);
        if (reference.length == 0 || recording.length < reference.length)
            throw new IllegalArgumentException("Recording must contain the reference waveform");
        int positions = recording.length - reference.length + 1;
        double[] scores = new double[positions];
        double referenceEnergy = energy(reference, 0, reference.length);
        if (referenceEnergy == 0) throw new IllegalArgumentException("Reference waveform has no energy");

        int bestIndex = 0;
        double best = -1;
        for (int offset = 0; offset < positions; offset++) {
            double dot = 0;
            for (int i = 0; i < reference.length; i++) dot += recording[offset + i] * reference[i];
            double windowEnergy = energy(recording, offset, reference.length);
            double score = windowEnergy == 0 ? 0 : Math.abs(dot) / Math.sqrt(referenceEnergy * windowEnergy);
            scores[offset] = score;
            if (score > best) { best = score; bestIndex = offset; }
        }

        double secondary = 0;
        double noiseSquares = 0;
        int noiseCount = 0;
        for (int offset = 0; offset < positions; offset++) {
            if (Math.abs(offset - bestIndex) <= reference.length) continue;
            secondary = Math.max(secondary, scores[offset]);
            noiseSquares += scores[offset] * scores[offset];
            noiseCount++;
        }
        double noiseRms = (noiseCount == 0 || noiseSquares == 0) ? 1e-6 : Math.sqrt(noiseSquares / noiseCount);
        double snrDb = Math.min(100.0, 20.0 * Math.log10(Math.max(best, Double.MIN_NORMAL) / noiseRms));
        double peakRatio = secondary <= 0 ? 100.0 : Math.min(100.0, best / secondary);
        boolean accepted = snrDb >= config.minSnrDb() && peakRatio >= config.minPeakRatio();
        return new ChirpDetection(bestIndex, snrDb, peakRatio, accepted);
    }

    private static double energy(double[] samples, int offset, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) sum += samples[offset + i] * samples[offset + i];
        return sum;
    }
}
