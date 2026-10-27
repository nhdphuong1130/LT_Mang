package vn.edu.ltm.common.ranging;

import javax.sound.sampled.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java Sound implementation of {@link AudioEndpoint} with 16-bit PCM mono format.
 */
public class JavaSoundAudioEndpoint implements AudioEndpoint {

    private final AcousticRangingConfig config;
    private final AudioFormat format;
    private SourceDataLine sourceLine;
    private TargetDataLine targetLine;
    private final boolean playbackSupported;
    private final boolean captureSupported;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public JavaSoundAudioEndpoint(AcousticRangingConfig config) {
        this.config = Objects.requireNonNull(config);
        this.format = new AudioFormat((float) config.sampleRateHz(), 16, 1, true, false);

        SourceDataLine sl = null;
        TargetDataLine tl = null;
        boolean pOk = false;
        boolean cOk = false;

        try {
            DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, format);
            Mixer.Info pMixer = findMixerInfo(config.playbackMixer(), sourceInfo);
            if (pMixer != null) {
                sl = (SourceDataLine) AudioSystem.getMixer(pMixer).getLine(sourceInfo);
            } else if (AudioSystem.isLineSupported(sourceInfo)) {
                sl = (SourceDataLine) AudioSystem.getLine(sourceInfo);
            }
            pOk = (sl != null);
        } catch (Exception ignored) {
            pOk = false;
        }

        try {
            DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, format);
            Mixer.Info cMixer = findMixerInfo(config.captureMixer(), targetInfo);
            if (cMixer != null) {
                tl = (TargetDataLine) AudioSystem.getMixer(cMixer).getLine(targetInfo);
            } else if (AudioSystem.isLineSupported(targetInfo)) {
                tl = (TargetDataLine) AudioSystem.getLine(targetInfo);
            }
            cOk = (tl != null);
        } catch (Exception ignored) {
            cOk = false;
        }

        this.sourceLine = sl;
        this.targetLine = tl;
        this.playbackSupported = pOk;
        this.captureSupported = cOk;
    }

    private static Mixer.Info findMixerInfo(String namePattern, Line.Info lineInfo) {
        if (namePattern == null || namePattern.isBlank()) {
            return null;
        }
        String pattern = namePattern.trim().toLowerCase();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().toLowerCase().contains(pattern)) {
                try {
                    Mixer mixer = AudioSystem.getMixer(info);
                    if (mixer.isLineSupported(lineInfo)) {
                        return info;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Override
    public synchronized void play(double[] samples) {
        if (samples == null) throw new IllegalArgumentException("Samples array cannot be null");
        if (closed.get()) throw new IllegalStateException("Endpoint is closed");
        if (!playbackSupported || sourceLine == null) throw new IllegalStateException("Playback line not supported");

        byte[] pcm = doublesToPcm(samples);
        try {
            sourceLine.open(format);
            sourceLine.start();
            sourceLine.write(pcm, 0, pcm.length);
            sourceLine.drain();
            sourceLine.stop();
            sourceLine.close();
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("Failed to open or play source line", e);
        }
    }

    @Override
    public synchronized double[] capture(int durationMs) {
        if (durationMs <= 0) throw new IllegalArgumentException("Capture duration must be positive");
        if (closed.get()) throw new IllegalStateException("Endpoint is closed");
        if (!captureSupported || targetLine == null) throw new IllegalStateException("Capture line not supported");

        int totalSamples = Math.multiplyExact(config.sampleRateHz(), durationMs) / 1000;
        byte[] pcm = new byte[totalSamples * 2];

        try {
            targetLine.open(format, pcm.length);
            targetLine.start();
            int bytesRead = 0;
            while (bytesRead < pcm.length) {
                int read = targetLine.read(pcm, bytesRead, pcm.length - bytesRead);
                if (read < 0) break;
                bytesRead += read;
            }
            targetLine.stop();
            targetLine.close();
            return pcmToDoubles(pcm);
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("Failed to open or capture target line", e);
        }
    }

    @Override
    public synchronized double[] playAndCapture(double[] samplesToPlay, int captureDurationMs) {
        if (samplesToPlay == null) throw new IllegalArgumentException("Samples to play cannot be null");
        if (captureDurationMs <= 0) throw new IllegalArgumentException("Capture duration must be positive");
        if (closed.get()) throw new IllegalStateException("Endpoint is closed");
        if (!playbackSupported || !captureSupported || sourceLine == null || targetLine == null) {
            throw new IllegalStateException("Audio lines not available for playAndCapture");
        }

        int totalSamples = Math.multiplyExact(config.sampleRateHz(), captureDurationMs) / 1000;
        byte[] capturePcm = new byte[totalSamples * 2];
        byte[] playPcm = doublesToPcm(samplesToPlay);

        try {
            targetLine.open(format, capturePcm.length);
            sourceLine.open(format);
            targetLine.start();

            Thread playThread = new Thread(() -> {
                try {
                    sourceLine.start();
                    sourceLine.write(playPcm, 0, playPcm.length);
                    sourceLine.drain();
                    sourceLine.stop();
                    sourceLine.close();
                } catch (Exception ignored) {}
            }, "JavaSound-Play-Thread");
            playThread.setDaemon(true);
            playThread.start();

            int bytesRead = 0;
            while (bytesRead < capturePcm.length) {
                int read = targetLine.read(capturePcm, bytesRead, capturePcm.length - bytesRead);
                if (read < 0) break;
                bytesRead += read;
            }

            try {
                playThread.join(Math.max(captureDurationMs + 500, 2000));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            targetLine.stop();
            targetLine.close();
            return pcmToDoubles(capturePcm);
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("Failed during playAndCapture", e);
        }
    }

    @Override
    public boolean isPlaybackSupported() {
        return playbackSupported && !closed.get();
    }

    @Override
    public boolean isCaptureSupported() {
        return captureSupported && !closed.get();
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            if (sourceLine != null) {
                try {
                    if (sourceLine.isOpen()) {
                        sourceLine.stop();
                        sourceLine.close();
                    }
                } catch (Exception ignored) {}
            }
            if (targetLine != null) {
                try {
                    if (targetLine.isOpen()) {
                        targetLine.stop();
                        targetLine.close();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public static byte[] doublesToPcm(double[] samples) {
        if (samples == null) throw new IllegalArgumentException("Samples array cannot be null");
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double clamped = Math.max(-1.0, Math.min(1.0, samples[i]));
            short val = (short) Math.round(clamped * 32767.0);
            pcm[2 * i] = (byte) (val & 0xFF);
            pcm[2 * i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        return pcm;
    }

    public static double[] pcmToDoubles(byte[] pcmBytes) {
        if (pcmBytes == null) throw new IllegalArgumentException("PCM byte array cannot be null");
        int sampleCount = pcmBytes.length / 2;
        double[] samples = new double[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short val = (short) ((pcmBytes[2 * i] & 0xFF) | (pcmBytes[2 * i + 1] << 8));
            samples[i] = val / 32768.0;
        }
        return samples;
    }
}
