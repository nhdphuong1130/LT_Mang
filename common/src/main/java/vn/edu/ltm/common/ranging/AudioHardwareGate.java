package vn.edu.ltm.common.ranging;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import java.util.Objects;

/**
 * Validates audio hardware capabilities, ultrasonic frequency band support,
 * loopback SNR, and audible leakage before enabling acoustic ranging.
 */
public final class AudioHardwareGate {

    private final AcousticRangingConfig config;
    private final AudioEndpoint endpoint;

    public AudioHardwareGate(AcousticRangingConfig config, AudioEndpoint endpoint) {
        this.config = Objects.requireNonNull(config);
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    public AudioHardwareGate(AcousticRangingConfig config) {
        this(config, new JavaSoundAudioEndpoint(config));
    }

    public RangingCapability evaluate() {
        return evaluate(false);
    }

    public RangingCapability evaluate(boolean runActiveLoopbackTest) {
        if (!config.enabled()) {
            return new RangingCapability(endpoint.isPlaybackSupported(), endpoint.isCaptureSupported(),
                    config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                    config.playbackMixer(), config.captureMixer(), RangingStatus.CONFIG_DISABLED);
        }

        if (!endpoint.isPlaybackSupported() || !endpoint.isCaptureSupported() || config.sampleRateHz() < 44_100) {
            return new RangingCapability(endpoint.isPlaybackSupported(), endpoint.isCaptureSupported(),
                    config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                    config.playbackMixer(), config.captureMixer(), RangingStatus.AUDIO_UNSUPPORTED);
        }

        if (config.chirpStartHz() <= config.audibleCeilingHz()
                || config.chirpEndHz() >= config.sampleRateHz() / 2.0) {
            return new RangingCapability(true, true,
                    config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                    config.playbackMixer(), config.captureMixer(), RangingStatus.AUDIO_UNSUPPORTED);
        }

        double[] referenceChirp = new LinearChirpGenerator(config).generate();
        double chirpLeakage = measureAudibleLeakageDb(referenceChirp, config.sampleRateHz(), config.audibleCeilingHz());
        if (chirpLeakage > config.maxAudibleLeakageDb()) {
            return new RangingCapability(true, true,
                    config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                    config.playbackMixer(), config.captureMixer(), RangingStatus.AUDIBLE_LEAKAGE);
        }

        if (runActiveLoopbackTest) {
            try {
                double[] captured = endpoint.playAndCapture(referenceChirp, config.captureDurationMs());
                double capturedLeakage = measureAudibleLeakageDb(captured, config.sampleRateHz(), config.audibleCeilingHz());
                if (capturedLeakage > config.maxAudibleLeakageDb()) {
                    return new RangingCapability(true, true,
                            config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                            config.playbackMixer(), config.captureMixer(), RangingStatus.AUDIBLE_LEAKAGE);
                }

                MatchedFilterDetector detector = new MatchedFilterDetector(config);
                ChirpDetection detection = detector.detect(captured, referenceChirp);
                if (!detection.accepted()) {
                    return new RangingCapability(true, true,
                            config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                            config.playbackMixer(), config.captureMixer(), RangingStatus.LOW_SNR);
                }
            } catch (Exception e) {
                return new RangingCapability(false, false,
                        config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                        config.playbackMixer(), config.captureMixer(), RangingStatus.AUDIO_UNSUPPORTED);
            }
        }

        return new RangingCapability(true, true,
                config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                config.playbackMixer(), config.captureMixer(), RangingStatus.ACOUSTIC_READY);
    }

    /**
     * Calculates the ratio of energy below audibleCeilingHz to ultrasonic energy in decibels (dB).
     *
     * @param samples          audio samples
     * @param sampleRateHz     sampling frequency in Hz
     * @param audibleCeilingHz upper bound of human audible range (e.g. 20000 Hz)
     * @return audible leakage in dB
     */
    public static double measureAudibleLeakageDb(double[] samples, int sampleRateHz, double audibleCeilingHz) {
        if (samples == null || samples.length < 64) {
            return -100.0;
        }

        int n = Integer.highestOneBit(Math.min(samples.length, 8192));
        if (n < 64) {
            return -100.0;
        }

        double[] windowed = new double[n];
        for (int i = 0; i < n; i++) {
            double hanning = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)));
            windowed[i] = samples[i] * hanning;
        }

        FastFourierTransformer transformer = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] spectrum = transformer.transform(windowed, TransformType.FORWARD);

        double audiblePower = 0.0;
        double ultrasonicPower = 0.0;
        int half = n / 2;

        for (int k = 1; k < half; k++) {
            double freq = (double) k * sampleRateHz / n;
            double power = spectrum[k].getReal() * spectrum[k].getReal()
                    + spectrum[k].getImaginary() * spectrum[k].getImaginary();

            if (freq <= audibleCeilingHz) {
                audiblePower += power;
            } else {
                ultrasonicPower += power;
            }
        }

        if (audiblePower <= 1e-12) {
            return -100.0;
        }
        if (ultrasonicPower <= 1e-12) {
            return 0.0;
        }

        return 10.0 * Math.log10(audiblePower / ultrasonicPower);
    }
}
