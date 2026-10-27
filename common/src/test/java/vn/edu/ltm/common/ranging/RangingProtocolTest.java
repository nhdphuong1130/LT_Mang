package vn.edu.ltm.common.ranging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.edu.ltm.common.protocol.MessageType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangingProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void supportsAllRangingMessageTypes() {
        assertNotNull(MessageType.valueOf("RANGING_CAPABILITIES"));
        assertNotNull(MessageType.valueOf("RANGING_START"));
        assertNotNull(MessageType.valueOf("RANGING_RESULT"));
        assertNotNull(MessageType.valueOf("RANGING_ABORT"));
    }

    @Test void roundTripsCapabilityRequestAndResultWithoutAudioPayload() throws Exception {
        var capability = new RangingCapability(true, true, 96_000, 20_500, 22_500,
                "speaker", "microphone", RangingStatus.ACOUSTIC_READY);
        var request = new RangingRequest("session-1", "linear-v1", 96_000, 20_500, 22_500,
                20, 250, 50, "nonce-1");
        var result = strongResult();

        assertEquals(capability, mapper.readValue(mapper.writeValueAsBytes(capability), RangingCapability.class));
        assertEquals(request, mapper.readValue(mapper.writeValueAsBytes(request), RangingRequest.class));
        assertEquals(result, mapper.readValue(mapper.writeValueAsBytes(result), RangingResult.class));

        String json = mapper.writeValueAsString(result).toLowerCase();
        assertFalse(json.contains("rawaudio"));
        assertFalse(json.contains("pcm"));
        assertFalse(json.contains("waveform"));
    }

    @Test void rejectsInvalidProtocolValues() {
        assertThrows(IllegalArgumentException.class, () -> new RangingRequest("", "linear-v1",
                96_000, 20_500, 22_500, 20, 250, 50, "nonce"));
        assertThrows(IllegalArgumentException.class, () -> new RangingResult("session-1", -1, 2,
                96_000, 20, 3, -50, 0, 1, true, RangingStatus.ACOUSTIC_READY, List.of(), 1));
    }

    static RangingResult strongResult() {
        return new RangingResult("session-1", 1_000, 8_000, 96_000,
                30, 5, -60, 0, 1, true, RangingStatus.ACOUSTIC_READY, List.of(), 1_782_600_000_000L);
    }
}
