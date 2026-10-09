package vn.edu.ltm.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record ControlEnvelope(MessageType type, String requestId, String deviceId, JsonNode payload) {
}
