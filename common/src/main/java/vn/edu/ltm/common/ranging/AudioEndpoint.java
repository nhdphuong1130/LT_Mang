package vn.edu.ltm.common.ranging;

/**
 * Abstraction for audio playback and capture in ultrasonic ranging.
 */
public interface AudioEndpoint extends AutoCloseable {

    /**
     * Plays the specified normalized audio samples ([-1.0, 1.0]).
     *
     * @param samples normalized PCM samples
     */
    void play(double[] samples);

    /**
     * Captures audio for the specified duration and returns normalized samples.
     *
     * @param durationMs capture window in milliseconds
     * @return normalized PCM samples
     */
    double[] capture(int durationMs);

    /**
     * Starts capturing and plays the given samples simultaneously, returning the captured buffer.
     *
     * @param samplesToPlay     waveform to transmit
     * @param captureDurationMs capture window in milliseconds
     * @return normalized PCM samples captured during the window
     */
    double[] playAndCapture(double[] samplesToPlay, int captureDurationMs);

    /**
     * @return true if playback output line is available and configured
     */
    boolean isPlaybackSupported();

    /**
     * @return true if capture input line is available and configured
     */
    boolean isCaptureSupported();

    @Override
    void close();
}
