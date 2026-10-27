package vn.edu.ltm.server.ranging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.ranging.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RangingCoordinatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void correlationMatchesSessionIdAndProducesAcceptedSample() throws Exception {
        var config = defaultConfig();
        List<ControlEnvelope> sent = new CopyOnWriteArrayList<>();
        List<RangingSample> receivedSamples = new CopyOnWriteArrayList<>();

        // Create audio buffers: server chirp at 100, agent chirp reply at 6000 (attenuated by distance)
        double[] chirp = new LinearChirpGenerator(config).generate();
        int totalSamples = config.sampleRateHz() * config.captureDurationMs() / 1000;
        double[] serverAudio = new double[totalSamples];
        System.arraycopy(chirp, 0, serverAudio, 100, chirp.length);
        for (int i = 0; i < chirp.length; i++) {
            serverAudio[6000 + i] = chirp[i] * 0.15;
        }

        var mockEndpoint = new MockAudioEndpoint(serverAudio);

        try (var coordinator = new RangingCoordinator(
                (deviceId, env) -> sent.add(env),
                () -> List.of("agent-1"),
                mockEndpoint,
                config,
                receivedSamples::add
        )) {
            // Publish capability first
            var capability = new RangingCapability(true, true, config.sampleRateHz(),
                    config.chirpStartHz(), config.chirpEndHz(), "", "", RangingStatus.ACOUSTIC_READY);
            coordinator.handleInboundMessage("agent-1", new ControlEnvelope(
                    MessageType.RANGING_CAPABILITIES, "cap-1", "agent-1", mapper.valueToTree(capability)));

            // Trigger a ranging slot for agent-1
            Future<?> future = coordinator.scheduleOnce("agent-1");

            // Wait until RANGING_START is sent
            Thread.sleep(50);
            assertFalse(sent.isEmpty());
            ControlEnvelope startEnvelope = sent.getFirst();
            assertEquals(MessageType.RANGING_START, startEnvelope.type());
            RangingRequest request = mapper.treeToValue(startEnvelope.payload(), RangingRequest.class);

            // Simulate Agent replying with correlated RANGING_RESULT
            long expectedDelaySamples = Math.round((double) config.responseDelayMs() * config.sampleRateHz() / 1000.0);
            long agentPeerPeak = 500;
            long agentSelfPeak = agentPeerPeak + expectedDelaySamples;

            RangingResult agentResult = new RangingResult(
                    request.sessionId(),
                    agentSelfPeak,
                    agentPeerPeak,
                    config.sampleRateHz(),
                    25.0,
                    4.0,
                    -50.0,
                    0.0,
                    1.0,
                    true,
                    RangingStatus.ACOUSTIC_READY,
                    List.of(),
                    System.currentTimeMillis()
            );

            coordinator.handleInboundMessage("agent-1", new ControlEnvelope(
                    MessageType.RANGING_RESULT, "res-1", "agent-1", mapper.valueToTree(agentResult)));

            future.get(2, TimeUnit.SECONDS);

            assertFalse(receivedSamples.isEmpty());
            RangingSample sample = receivedSamples.getFirst();
            assertEquals("agent-1", sample.deviceId());
            assertEquals(request.sessionId(), sample.sessionId());
            assertTrue(sample.distanceMeters() > 0, "Distance should be positive, got: " + sample.distanceMeters());
            assertTrue(sample.confidence() >= 90.0, "Confidence should be high, got: " + sample.confidence());
        }
    }

    @Test
    void retryAndTimeoutOnMissingAgentResponse() throws Exception {
        var config = defaultConfig();
        List<ControlEnvelope> sent = new CopyOnWriteArrayList<>();
        List<RangingSample> receivedSamples = new CopyOnWriteArrayList<>();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);

        try (var coordinator = new RangingCoordinator(
                (deviceId, env) -> sent.add(env),
                () -> List.of("agent-1"),
                mockEndpoint,
                config,
                receivedSamples::add
        )) {
            var capability = new RangingCapability(true, true, config.sampleRateHz(),
                    config.chirpStartHz(), config.chirpEndHz(), "", "", RangingStatus.ACOUSTIC_READY);
            coordinator.handleInboundMessage("agent-1", new ControlEnvelope(
                    MessageType.RANGING_CAPABILITIES, "cap-1", "agent-1", mapper.valueToTree(capability)));

            Future<?> future = coordinator.scheduleOnce("agent-1");
            future.get(3, TimeUnit.SECONDS);

            // Should have timed out and emitted a timeout sample
            assertFalse(receivedSamples.isEmpty());
            RangingSample lastSample = receivedSamples.getLast();
            assertEquals(RangingStatus.RANGING_TIMEOUT, lastSample.status());

            // Should have sent RANGING_ABORT
            boolean hasAbort = sent.stream().anyMatch(e -> e.type() == MessageType.RANGING_ABORT);
            assertTrue(hasAbort, "Expected RANGING_ABORT to be sent on timeout");
        }
    }

    @Test
    void capabilityFailureEmitsUnsupportedWithoutAudioPlayback() throws Exception {
        var config = defaultConfig();
        List<ControlEnvelope> sent = new CopyOnWriteArrayList<>();
        List<RangingSample> receivedSamples = new CopyOnWriteArrayList<>();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);

        try (var coordinator = new RangingCoordinator(
                (deviceId, env) -> sent.add(env),
                () -> List.of("agent-1"),
                mockEndpoint,
                config,
                receivedSamples::add
        )) {
            // Agent reports AUDIO_UNSUPPORTED
            var capability = new RangingCapability(false, false, config.sampleRateHz(),
                    config.chirpStartHz(), config.chirpEndHz(), "", "", RangingStatus.AUDIO_UNSUPPORTED);
            coordinator.handleInboundMessage("agent-1", new ControlEnvelope(
                    MessageType.RANGING_CAPABILITIES, "cap-1", "agent-1", mapper.valueToTree(capability)));

            coordinator.scheduleOnce("agent-1").get(1, TimeUnit.SECONDS);

            assertFalse(receivedSamples.isEmpty());
            assertEquals(RangingStatus.AUDIO_UNSUPPORTED, receivedSamples.getFirst().status());
            assertFalse(mockEndpoint.played, "Should not play audio when device is unsupported");
        }
    }

    @Test
    void reconnectCleanupCancelsActiveSession() throws Exception {
        var config = defaultConfig();
        List<ControlEnvelope> sent = new CopyOnWriteArrayList<>();
        List<RangingSample> receivedSamples = new CopyOnWriteArrayList<>();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);

        try (var coordinator = new RangingCoordinator(
                (deviceId, env) -> sent.add(env),
                () -> List.of("agent-1"),
                mockEndpoint,
                config,
                receivedSamples::add
        )) {
            var capability = new RangingCapability(true, true, config.sampleRateHz(),
                    config.chirpStartHz(), config.chirpEndHz(), "", "", RangingStatus.ACOUSTIC_READY);
            coordinator.handleInboundMessage("agent-1", new ControlEnvelope(
                    MessageType.RANGING_CAPABILITIES, "cap-1", "agent-1", mapper.valueToTree(capability)));

            Future<?> future = coordinator.scheduleOnce("agent-1");
            Thread.sleep(50);

            // Disconnect agent-1 mid-session
            coordinator.onDeviceDisconnected("agent-1");

            future.get(1, TimeUnit.SECONDS);
            assertNull(coordinator.getActiveSessionId());
        }
    }

    @Test
    void fairnessAcrossMultipleDevicesCyclesDevices() throws Exception {
        var config = defaultConfig();
        List<ControlEnvelope> sent = new CopyOnWriteArrayList<>();
        List<RangingSample> receivedSamples = new CopyOnWriteArrayList<>();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);

        List<String> devices = List.of("agent-A", "agent-B");

        try (var coordinator = new RangingCoordinator(
                (deviceId, env) -> sent.add(env),
                () -> devices,
                mockEndpoint,
                config,
                receivedSamples::add
        )) {
            // First device schedule
            coordinator.scheduleOnce("agent-A").get(1, TimeUnit.SECONDS);
            // Second device schedule
            coordinator.scheduleOnce("agent-B").get(1, TimeUnit.SECONDS);

            var startEnvelopes = sent.stream()
                    .filter(e -> e.type() == MessageType.RANGING_START)
                    .toList();
            assertFalse(startEnvelopes.isEmpty());
            assertEquals("agent-A", startEnvelopes.get(0).deviceId());
            assertTrue(startEnvelopes.stream().anyMatch(e -> e.deviceId().equals("agent-B")));
        }
    }

    private static AcousticRangingConfig defaultConfig() {
        return AcousticRangingConfig.builder()
                .enabled(true).playbackMixer("").captureMixer("")
                .sampleRateHz(96_000).chirpStartHz(20_500).chirpEndHz(22_500)
                .playbackAmplitude(0.5)
                .chirpDurationMs(20).captureDurationMs(150).responseDelayMs(30)
                .measurementIntervalMs(300).maxRetries(1).rollingWindow(5)
                .confidenceThreshold(90).minSnrDb(12).minPeakRatio(2.5)
                .targetSnrDb(20).targetPeakRatio(4.0).maxTwoWayErrorSamples(50)
                .audibleCeilingHz(20_000).maxAudibleLeakageDb(-40)
                .maxDistanceMeters(10).ambientTemperatureCelsius(25)
                .calibrationOffsetMillis(0).maxJumpMeters(1.5)
                .snrWeight(0.30).peakWeight(0.25).twoWayWeight(0.20)
                .stabilityWeight(0.15).hardwareWeight(0.10)
                .build();
    }

    private static class MockAudioEndpoint implements AudioEndpoint {
        private final double[] audioToReturn;
        boolean played = false;

        MockAudioEndpoint(double[] audioToReturn) {
            this.audioToReturn = audioToReturn;
        }

        @Override public void play(double[] samples) { played = true; }
        @Override public double[] capture(int durationMs) { return audioToReturn; }
        @Override public double[] playAndCapture(double[] samplesToPlay, int captureDurationMs) {
            played = true;
            return audioToReturn;
        }
        @Override public boolean isPlaybackSupported() { return true; }
        @Override public boolean isCaptureSupported() { return true; }
        @Override public void close() {}
    }
}
