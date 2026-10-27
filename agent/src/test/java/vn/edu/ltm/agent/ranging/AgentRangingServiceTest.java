package vn.edu.ltm.agent.ranging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.ranging.*;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentRangingServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishCapabilitiesSendsRangingCapabilitiesMessage() throws Exception {
        BlockingQueue<ControlEnvelope> sent = new LinkedBlockingQueue<>();
        var config = defaultConfig();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);

        try (var service = new AgentRangingService("agent-1", sent::add, mockEndpoint, config)) {
            service.publishCapabilities();

            ControlEnvelope env = sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(env);
            assertEquals(MessageType.RANGING_CAPABILITIES, env.type());
            assertEquals("agent-1", env.deviceId());

            RangingCapability cap = mapper.treeToValue(env.payload(), RangingCapability.class);
            assertEquals(RangingStatus.ACOUSTIC_READY, cap.status());
            assertEquals(config.sampleRateHz(), cap.sampleRateHz());
        }
    }

    @Test
    void handlesRangingStartAndSendsCorrelatedResult() throws Exception {
        BlockingQueue<ControlEnvelope> sent = new LinkedBlockingQueue<>();
        var config = defaultConfig();

        // Synthetic capture buffer where server chirp starts at sample 500
        double[] chirp = new LinearChirpGenerator(config).generate();
        int totalSamples = config.sampleRateHz() * config.captureDurationMs() / 1000;
        double[] captureBuffer = new double[totalSamples];
        System.arraycopy(chirp, 0, captureBuffer, 500, chirp.length);

        var mockEndpoint = new MockAudioEndpoint(captureBuffer);

        try (var service = new AgentRangingService("agent-1", sent::add, mockEndpoint, config)) {
            RangingRequest request = new RangingRequest("session-xyz", "chirp-v1",
                    config.sampleRateHz(), config.chirpStartHz(), config.chirpEndHz(),
                    config.chirpDurationMs(), config.captureDurationMs(), config.responseDelayMs(), "nonce-1");

            ControlEnvelope startMsg = new ControlEnvelope(MessageType.RANGING_START, "req-1", "agent-1",
                    mapper.valueToTree(request));

            service.handleMessage(startMsg);

            ControlEnvelope response = sent.poll(3, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(MessageType.RANGING_RESULT, response.type());
            assertEquals("agent-1", response.deviceId());

            RangingResult result = mapper.treeToValue(response.payload(), RangingResult.class);
            assertEquals("session-xyz", result.sessionId());
            assertEquals(500, result.peerPeakSample());
            assertTrue(result.selfPeakSample() >= result.peerPeakSample());
            assertEquals(RangingStatus.ACOUSTIC_READY, result.status());
            assertTrue(mockEndpoint.played);
        }
    }

    @Test
    void handlesRangingAbortForActiveSession() throws Exception {
        BlockingQueue<ControlEnvelope> sent = new LinkedBlockingQueue<>();
        var config = defaultConfig();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz()]);

        try (var service = new AgentRangingService("agent-1", sent::add, mockEndpoint, config)) {
            ControlEnvelope abortMsg = new ControlEnvelope(MessageType.RANGING_ABORT, "req-abort", "agent-1",
                    mapper.createObjectNode().put("sessionId", "session-to-cancel"));

            assertDoesNotThrow(() -> service.handleMessage(abortMsg));
        }
    }

    @Test
    void handlesSenderFailureGracefullyWithoutCrashing() {
        var config = defaultConfig();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);

        try (var service = new AgentRangingService("agent-1", msg -> {
            throw new IOException("Simulated network outage");
        }, mockEndpoint, config)) {
            assertDoesNotThrow(service::publishCapabilities);
        }
    }

    @Test
    void closeShutsDownCleanly() {
        var config = defaultConfig();
        var mockEndpoint = new MockAudioEndpoint(new double[config.sampleRateHz() / 4]);
        var service = new AgentRangingService("agent-1", msg -> {}, mockEndpoint, config);

        assertDoesNotThrow(service::close);
        assertTrue(mockEndpoint.closed);
    }

    private static AcousticRangingConfig defaultConfig() {
        return AcousticRangingConfig.builder()
                .enabled(true).playbackMixer("").captureMixer("")
                .sampleRateHz(96_000).chirpStartHz(20_500).chirpEndHz(22_500)
                .playbackAmplitude(0.5)
                .chirpDurationMs(20).captureDurationMs(250).responseDelayMs(50)
                .measurementIntervalMs(500).maxRetries(2).rollingWindow(5)
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
        private final double[] captureData;
        boolean played = false;
        boolean closed = false;

        MockAudioEndpoint(double[] captureData) {
            this.captureData = captureData;
        }

        @Override public void play(double[] samples) {
            played = true;
        }

        @Override public double[] capture(int durationMs) {
            return captureData;
        }

        @Override public double[] playAndCapture(double[] samplesToPlay, int captureDurationMs) {
            played = true;
            return captureData;
        }

        @Override public boolean isPlaybackSupported() { return true; }
        @Override public boolean isCaptureSupported() { return true; }
        @Override public void close() { closed = true; }
    }
}
