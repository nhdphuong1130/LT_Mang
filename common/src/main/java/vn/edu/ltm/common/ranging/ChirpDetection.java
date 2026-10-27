package vn.edu.ltm.common.ranging;

public record ChirpDetection(int sampleIndex, double snrDb, double peakRatio, boolean accepted) {
    public ChirpDetection {
        if (sampleIndex < 0) throw new IllegalArgumentException("Sample index cannot be negative");
        if (!Double.isFinite(snrDb)) throw new IllegalArgumentException("SNR must be finite");
        if (Double.isNaN(peakRatio) || peakRatio < 0)
            throw new IllegalArgumentException("Peak ratio must be non-negative");
    }
}
