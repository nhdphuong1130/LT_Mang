package vn.edu.ltm.server.ranging;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.ranging.*;

import java.io.IOException;
import java.lang.System.Logger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates acoustic distance ranging sessions across registered Clients.
 * Manages device capabilities, round-robin non-overlapping slots, retry/timeout logic,
 * and derives physical distance and confidence metrics.
 */
public final class RangingCoordinator implements AutoCloseable {

    private static final Logger LOGGER = System.getLogger(RangingCoordinator.class.getName());

    @FunctionalInterface
    public interface Sender {
        void send(String deviceId, ControlEnvelope message) throws IOException;
    }

    private record ActiveSession(
            String sessionId,
            String deviceId,
            long startedAtEpochMillis,
            CompletableFuture<RangingResult> resultFuture
    ) {}

    private final Sender sender;
    private final Supplier<Collection<String>> activeDevicesSupplier;
    private final AudioEndpoint endpoint;
    private final AcousticRangingConfig config;
    private final Consumer<RangingSample> sampleSink;

    private final ConcurrentHashMap<String, RangingCapability> capabilities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Double>> distanceHistory = new ConcurrentHashMap<>();
    private final AtomicReference<ActiveSession> activeSession = new AtomicReference<>(null);
    private final AtomicInteger sessionCounter = new AtomicInteger(1000);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("ranging-coordinator-scheduler").factory());
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("ranging-session-worker").factory());
    private final ObjectMapper mapper = new ObjectMapper();

    private final TwoWayDistanceCalculator distanceCalculator;
    private final RangingConfidenceScorer confidenceScorer;
    private final MatchedFilterDetector detector;
    private ScheduledFuture<?> periodicTask;

    public RangingCoordinator(
            Sender sender,
            Supplier<Collection<String>> activeDevicesSupplier,
            AudioEndpoint endpoint,
            AcousticRangingConfig config,
            Consumer<RangingSample> sampleSink
    ) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.activeDevicesSupplier = Objects.requireNonNull(activeDevicesSupplier, "activeDevicesSupplier must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.sampleSink = Objects.requireNonNull(sampleSink, "sampleSink must not be null");

        this.distanceCalculator = new TwoWayDistanceCalculator(config);
        this.confidenceScorer = new RangingConfidenceScorer(config);
        this.detector = new MatchedFilterDetector(config);
    }

    public synchronized void start() {
        if (closed.get() || periodicTask != null) return;
        int intervalMs = Math.max(100, config.measurementIntervalMs());
        periodicTask = scheduler.scheduleWithFixedDelay(this::scheduleNextSlot,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleNextSlot() {
        if (closed.get() || activeSession.get() != null) return;
        Collection<String> devices = activeDevicesSupplier.get();
        if (devices == null || devices.isEmpty()) return;

        for (String deviceId : devices) {
            if (activeSession.get() == null) {
                scheduleOnce(deviceId);
                break;
            }
        }
    }

    public Future<?> scheduleOnce(String deviceId) {
        return sessionExecutor.submit(() -> runRangingSession(deviceId));
    }

    private void runRangingSession(String deviceId) {
        if (closed.get()) return;

        RangingCapability cap = capabilities.get(deviceId);
        if (cap != null && cap.status() != RangingStatus.ACOUSTIC_READY) {
            sampleSink.accept(RangingSample.failed(deviceId, "", cap.status()));
            return;
        }

        int maxAttempts = 1 + config.maxRetries();
        for (int attempt = 0; attempt < maxAttempts && !closed.get(); attempt++) {
            String sessionId = "range-" + sessionCounter.incrementAndGet();
            CompletableFuture<RangingResult> resultFuture = new CompletableFuture<>();
            ActiveSession session = new ActiveSession(sessionId, deviceId, System.currentTimeMillis(), resultFuture);

            if (!activeSession.compareAndSet(null, session)) {
                return; // Another session is already in-flight
            }

            try {
                RangingRequest request = new RangingRequest(
                        sessionId,
                        "chirp-v1",
                        config.sampleRateHz(),
                        config.chirpStartHz(),
                        config.chirpEndHz(),
                        config.chirpDurationMs(),
                        config.captureDurationMs(),
                        config.responseDelayMs(),
                        UUID.randomUUID().toString()
                );

                ControlEnvelope startEnvelope = new ControlEnvelope(
                        MessageType.RANGING_START,
                        UUID.randomUUID().toString(),
                        deviceId,
                        mapper.valueToTree(request)
                );

                sender.send(deviceId, startEnvelope);

                // Pre-roll lead time: gives Agent time to receive network envelope and start audio capture
                int leadDelayMs = Math.min(60, config.measurementIntervalMs() / 5);
                if (leadDelayMs > 0) {
                    try {
                        Thread.sleep(leadDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                double[] referenceChirp = new LinearChirpGenerator(config).generate();
                double[] serverAudio = endpoint.playAndCapture(referenceChirp, config.captureDurationMs());

                long timeoutMs = config.captureDurationMs() + config.responseDelayMs() + 200L;
                RangingResult agentResult;
                try {
                    agentResult = resultFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (CancellationException e) {
                    break;
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    if (attempt == maxAttempts - 1) {
                        sendAbort(deviceId, sessionId);
                        sampleSink.accept(RangingSample.failed(deviceId, sessionId, RangingStatus.RANGING_TIMEOUT));
                    }
                    if (closed.get() || !activeDevicesSupplier.get().contains(deviceId)) {
                        break;
                    }
                    continue;
                }

                // Process successful paired results
                processMeasurements(deviceId, sessionId, serverAudio, referenceChirp, agentResult);
                return;
            } catch (Exception failure) {
                LOGGER.log(Logger.Level.WARNING, "Error during ranging session with " + deviceId, failure);
            } finally {
                activeSession.compareAndSet(session, null);
            }
        }
    }

    private void processMeasurements(String deviceId, String sessionId, double[] serverAudio,
                                     double[] referenceChirp, RangingResult agentResult) {
        if (agentResult == null || agentResult.status() != RangingStatus.ACOUSTIC_READY) {
            sampleSink.accept(RangingSample.failed(deviceId, sessionId,
                    agentResult != null ? agentResult.status() : RangingStatus.RANGING_TIMEOUT));
            return;
        }

        // Detect Server self-chirp within the initial transmission window
        int selfWindowLen = Math.min(serverAudio.length, referenceChirp.length * 3);
        double[] selfSlice = new double[selfWindowLen];
        System.arraycopy(serverAudio, 0, selfSlice, 0, selfWindowLen);
        ChirpDetection serverSelfDetection = detector.detect(selfSlice, referenceChirp);
        long serverSelfPeak = serverSelfDetection.sampleIndex();

        // Search for Agent's chirp response after Server chirp completes
        int searchStart = (int) Math.min(serverAudio.length - 1, serverSelfPeak + referenceChirp.length);
        int searchLen = serverAudio.length - searchStart;

        long serverPeerPeak = -1;
        ChirpDetection peerDetection = null;
        if (searchLen >= referenceChirp.length) {
            double[] searchSlice = new double[searchLen];
            System.arraycopy(serverAudio, searchStart, searchSlice, 0, searchLen);
            peerDetection = detector.detect(searchSlice, referenceChirp);
            serverPeerPeak = searchStart + peerDetection.sampleIndex();
        } else {
            sampleSink.accept(RangingSample.failed(deviceId, sessionId, RangingStatus.LOW_SNR));
            return;
        }

        if (!peerDetection.accepted()) {
            sampleSink.accept(RangingSample.failed(deviceId, sessionId, RangingStatus.LOW_SNR));
            return;
        }

        long expectedDelaySamples = Math.round((double) config.responseDelayMs() * config.sampleRateHz() / 1000.0);
        long agentTurnaround = agentResult.selfPeakSample() - agentResult.peerPeakSample();
        double twoWayErrorSamples = Math.abs(agentTurnaround - expectedDelaySamples);

        long roundTripSamples = (serverPeerPeak - serverSelfPeak) - agentTurnaround + expectedDelaySamples;

        double distanceMeters;
        long distanceCm;
        try {
            distanceMeters = distanceCalculator.distanceMeters(roundTripSamples);
            distanceCm = distanceCalculator.distanceCentimeters(roundTripSamples);
        } catch (IllegalArgumentException e) {
            distanceMeters = -1.0;
            distanceCm = -1L;
        }

        double serverLeakageDb = AudioHardwareGate.measureAudibleLeakageDb(
                serverAudio, config.sampleRateHz(), config.audibleCeilingHz());

        double stability = calculateRollingStability(deviceId, distanceMeters);

        RangingResult scoredResult = new RangingResult(
                sessionId,
                serverSelfPeak,
                serverPeerPeak,
                config.sampleRateHz(),
                Math.min(serverSelfDetection.snrDb(), Math.min(peerDetection.snrDb(), agentResult.snrDb())),
                Math.min(serverSelfDetection.peakRatio(), Math.min(peerDetection.peakRatio(), agentResult.peakRatio())),
                Math.max(serverLeakageDb, agentResult.audibleLeakageDb()),
                twoWayErrorSamples,
                stability,
                true,
                RangingStatus.ACOUSTIC_READY,
                List.of(),
                System.currentTimeMillis()
        );

        RangingConfidenceScorer.Score score = confidenceScorer.score(scoredResult);

        if (score.accepted() && distanceMeters > 0) {
            recordAcceptedDistance(deviceId, distanceMeters);
        }

        RangingSample sample = new RangingSample(
                deviceId,
                sessionId,
                score.accepted() ? distanceMeters : -1.0,
                score.accepted() ? distanceCm : -1L,
                score.confidence(),
                score.status(),
                serverSelfDetection.snrDb(),
                serverSelfDetection.peakRatio(),
                twoWayErrorSamples,
                serverLeakageDb,
                System.currentTimeMillis()
        );

        sampleSink.accept(sample);
    }

    private void sendAbort(String deviceId, String sessionId) {
        try {
            ControlEnvelope abortMsg = new ControlEnvelope(
                    MessageType.RANGING_ABORT,
                    UUID.randomUUID().toString(),
                    deviceId,
                    mapper.createObjectNode().put("sessionId", sessionId)
            );
            sender.send(deviceId, abortMsg);
        } catch (IOException ignored) {}
    }

    public void handleInboundMessage(String deviceId, ControlEnvelope envelope) {
        if (closed.get() || envelope == null) return;

        if (envelope.type() == MessageType.RANGING_CAPABILITIES) {
            try {
                RangingCapability cap = mapper.treeToValue(envelope.payload(), RangingCapability.class);
                capabilities.put(deviceId, cap);
            } catch (Exception e) {
                LOGGER.log(Logger.Level.WARNING, "Failed to parse capability payload: " + e.getMessage());
            }
            return;
        }

        if (envelope.type() == MessageType.RANGING_RESULT) {
            try {
                RangingResult result = mapper.treeToValue(envelope.payload(), RangingResult.class);
                ActiveSession curr = activeSession.get();
                if (curr != null && curr.deviceId().equals(deviceId) && curr.sessionId().equals(result.sessionId())) {
                    curr.resultFuture().complete(result);
                }
            } catch (Exception e) {
                LOGGER.log(Logger.Level.WARNING, "Failed to parse ranging result payload: " + e.getMessage());
            }
            return;
        }

        if (envelope.type() == MessageType.RANGING_ABORT) {
            ActiveSession curr = activeSession.get();
            if (curr != null && curr.deviceId().equals(deviceId)) {
                curr.resultFuture().cancel(true);
            }
        }
    }

    public void onDeviceDisconnected(String deviceId) {
        capabilities.remove(deviceId);
        distanceHistory.remove(deviceId);
        ActiveSession curr = activeSession.get();
        if (curr != null && curr.deviceId().equals(deviceId)) {
            curr.resultFuture().cancel(true);
            activeSession.compareAndSet(curr, null);
        }
    }

    public String getActiveSessionId() {
        ActiveSession session = activeSession.get();
        return session != null ? session.sessionId() : null;
    }

    private double calculateRollingStability(String deviceId, double currentDistance) {
        Deque<Double> history = distanceHistory.get(deviceId);
        if (history == null || history.size() < 2 || currentDistance <= 0) {
            return 1.0;
        }

        double sum = 0;
        for (double d : history) sum += d;
        double mean = sum / history.size();
        if (mean <= 0) return 1.0;

        double variance = 0;
        for (double d : history) variance += (d - mean) * (d - mean);
        double stdDev = Math.sqrt(variance / history.size());

        return Math.max(0.0, Math.min(1.0, 1.0 - (stdDev / mean)));
    }

    private void recordAcceptedDistance(String deviceId, double distance) {
        distanceHistory.compute(deviceId, (k, deque) -> {
            if (deque == null) deque = new ConcurrentLinkedDeque<>();
            deque.addLast(distance);
            while (deque.size() > config.rollingWindow()) {
                deque.pollFirst();
            }
            return deque;
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (periodicTask != null) periodicTask.cancel(true);
            scheduler.shutdownNow();
            sessionExecutor.shutdownNow();
            ActiveSession curr = activeSession.get();
            if (curr != null) {
                curr.resultFuture().cancel(true);
            }
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
                sessionExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            endpoint.close();
        }
    }
}
