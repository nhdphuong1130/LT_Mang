package vn.edu.ltm.agent.ranging;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.ranging.*;

import java.io.IOException;
import java.lang.System.Logger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Responds to acoustic ranging requests from the Server, managing hardware checks,
 * chirp detection, timed response playback, and reporting back to control transport.
 */
public final class AgentRangingService implements AutoCloseable {

    private static final Logger LOGGER = System.getLogger(AgentRangingService.class.getName());

    @FunctionalInterface
    public interface Sender {
        void send(ControlEnvelope message) throws IOException;
    }

    private final String deviceId;
    private final Sender sender;
    private final AudioEndpoint endpoint;
    private final AcousticRangingConfig config;
    private final AudioHardwareGate hardwareGate;
    private final ExecutorService serialExecutor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> activeSessionId = new AtomicReference<>(null);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AgentRangingService(String deviceId, Sender sender, AudioEndpoint endpoint, AcousticRangingConfig config) {
        this(deviceId, sender, endpoint, config,
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("agent-ranging").factory()));
    }

    public AgentRangingService(String deviceId, Sender sender, AudioEndpoint endpoint,
                               AcousticRangingConfig config, ExecutorService serialExecutor) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId must not be null");
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.serialExecutor = Objects.requireNonNull(serialExecutor, "serialExecutor must not be null");
        this.hardwareGate = new AudioHardwareGate(config, endpoint);
    }

    public void publishCapabilities() {
        if (closed.get()) return;
        try {
            RangingCapability capability = hardwareGate.evaluate();
            ControlEnvelope envelope = new ControlEnvelope(
                    MessageType.RANGING_CAPABILITIES,
                    UUID.randomUUID().toString(),
                    deviceId,
                    mapper.valueToTree(capability)
            );
            sender.send(envelope);
        } catch (IOException failure) {
            LOGGER.log(Logger.Level.WARNING, "Failed to send ranging capabilities: " + failure.getMessage());
        } catch (Exception failure) {
            LOGGER.log(Logger.Level.WARNING, "Error evaluating ranging capabilities: " + failure.getMessage());
        }
    }

    public void handleMessage(ControlEnvelope envelope) {
        if (closed.get() || envelope == null) return;

        if (envelope.type() == MessageType.RANGING_ABORT) {
            String abortSession = envelope.payload() != null ? envelope.payload().path("sessionId").asText() : "";
            if (!abortSession.isBlank()) {
                activeSessionId.compareAndSet(abortSession, null);
            }
            return;
        }

        if (envelope.type() == MessageType.RANGING_START) {
            serialExecutor.submit(() -> processRangingStart(envelope));
        }
    }

    private void processRangingStart(ControlEnvelope envelope) {
        if (closed.get()) return;

        RangingRequest request;
        try {
            request = mapper.treeToValue(envelope.payload(), RangingRequest.class);
        } catch (Exception e) {
            LOGGER.log(Logger.Level.WARNING, "Malformed RANGING_START payload: " + e.getMessage());
            return;
        }

        activeSessionId.set(request.sessionId());

        try {
            double[] referenceChirp = new LinearChirpGenerator(config).generate();
            int agentCaptureMs = Math.min(request.captureDurationMs(), 400);
            double[] captured = endpoint.capture(agentCaptureMs);

            if (!Objects.equals(activeSessionId.get(), request.sessionId())) {
                return; // Aborted
            }

            MatchedFilterDetector detector = new MatchedFilterDetector(config);
            ChirpDetection detection = detector.detect(captured, referenceChirp);

            long peerPeakSample = detection.sampleIndex();
            long responseDelaySamples = Math.round((double) request.responseDelayMs() * request.sampleRateHz() / 1000.0);

            double leakageDb = AudioHardwareGate.measureAudibleLeakageDb(referenceChirp,
                    request.sampleRateHz(), config.audibleCeilingHz());

            RangingStatus status;
            long selfPeakSample;
            if (leakageDb > config.maxAudibleLeakageDb()) {
                status = RangingStatus.AUDIBLE_LEAKAGE;
                selfPeakSample = peerPeakSample + responseDelaySamples;
            } else if (!detection.accepted()) {
                status = (detection.snrDb() < config.minSnrDb()) ? RangingStatus.LOW_SNR : RangingStatus.MULTIPATH_AMBIGUOUS;
                selfPeakSample = peerPeakSample + responseDelaySamples;
            } else {
                status = RangingStatus.ACOUSTIC_READY;
                // Wait for scheduled turnaround timing, then emit response chirp
                long elapsedSinceStart = captured.length;
                long targetSample = peerPeakSample + responseDelaySamples;
                long waitSamples = targetSample - elapsedSinceStart;
                if (waitSamples > 0) {
                    long waitMs = waitSamples * 1000L / request.sampleRateHz();
                    if (waitMs > 0 && waitMs < 1000) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ignored) {}
                    }
                }
                endpoint.play(referenceChirp);
                selfPeakSample = Math.max(targetSample, elapsedSinceStart);
            }

            RangingResult result = new RangingResult(
                    request.sessionId(),
                    selfPeakSample,
                    peerPeakSample,
                    request.sampleRateHz(),
                    detection.snrDb(),
                    detection.peakRatio(),
                    leakageDb,
                    0.0,
                    1.0,
                    true,
                    status,
                    List.of(),
                    System.currentTimeMillis()
            );

            ControlEnvelope response = new ControlEnvelope(
                    MessageType.RANGING_RESULT,
                    UUID.randomUUID().toString(),
                    deviceId,
                    mapper.valueToTree(result)
            );

            sender.send(response);
        } catch (IOException e) {
            LOGGER.log(Logger.Level.WARNING, "Failed to send ranging result: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Logger.Level.WARNING, "Error executing ranging session: " + e.getMessage());
        } finally {
            activeSessionId.compareAndSet(request.sessionId(), null);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            activeSessionId.set(null);
            serialExecutor.shutdownNow();
            try {
                serialExecutor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            endpoint.close();
        }
    }
}
