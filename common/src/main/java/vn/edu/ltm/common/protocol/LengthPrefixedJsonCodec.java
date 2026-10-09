package vn.edu.ltm.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Encodes UTF-8 JSON with a four-byte big-endian byte length. Caller owns streams. */
public final class LengthPrefixedJsonCodec {
    private static final int MAX_PAYLOAD_BYTES = 65_536;

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public void write(OutputStream output, ControlEnvelope envelope) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(envelope);
        validateLength(payload.length);
        var data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    /** @throws EOFException if the peer disconnects before starting the next frame. */
    public ControlEnvelope read(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) {
            throw new EOFException("End of control stream");
        }
        byte[] remainingLength = input.readNBytes(3);
        if (remainingLength.length != 3) {
            throw new ProtocolException("Truncated frame length");
        }
        int length = (first << 24) | ((remainingLength[0] & 0xff) << 16)
                | ((remainingLength[1] & 0xff) << 8) | (remainingLength[2] & 0xff);
        validateLength(length);
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new ProtocolException("Truncated frame payload");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
            ControlEnvelope envelope = mapper.readValue(json, ControlEnvelope.class);
            if (envelope == null) {
                throw new ProtocolException("Control envelope must not be null");
            }
            return envelope;
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Control payload must be valid UTF-8", exception);
        } catch (JsonProcessingException exception) {
            throw new ProtocolException("Invalid control envelope JSON", exception);
        }
    }

    private static void validateLength(int length) throws ProtocolException {
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            throw new ProtocolException("Control payload length must be between 1 and " + MAX_PAYLOAD_BYTES);
        }
    }
}
