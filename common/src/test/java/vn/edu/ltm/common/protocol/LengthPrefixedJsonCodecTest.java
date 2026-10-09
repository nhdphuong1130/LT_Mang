package vn.edu.ltm.common.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LengthPrefixedJsonCodecTest {
    private final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();

    @Test
    void roundTripsUnicodeJsonWithBigEndianUtf8ByteLength() throws Exception {
        var envelope = new ControlEnvelope(MessageType.METRICS, "req-1", "agent-1",
                new ObjectMapper().readTree("{\"message\":\"Tiếng Việt 🌡\",\"cpu\":92.5}"));
        var output = new ByteArrayOutputStream();

        codec.write(output, envelope);

        byte[] frame = output.toByteArray();
        assertEquals(frame.length - 4, ByteBuffer.wrap(frame).getInt());
        String json = new String(frame, 4, frame.length - 4, StandardCharsets.UTF_8);
        assertEquals("Tiếng Việt 🌡", new ObjectMapper().readTree(json).get("payload").get("message").textValue());
        var decoded = codec.read(new ByteArrayInputStream(frame));
        assertEquals(MessageType.METRICS, decoded.type());
        assertEquals("req-1", decoded.requestId());
        assertEquals("agent-1", decoded.deviceId());
        assertEquals("Tiếng Việt 🌡", decoded.payload().get("message").textValue());
        assertEquals(92.5, decoded.payload().get("cpu").doubleValue());
    }

    @Test
    void readsConsecutiveFramesWithoutConsumingTheNextMessage() throws Exception {
        var output = new ByteArrayOutputStream();
        codec.write(output, new ControlEnvelope(MessageType.REGISTER, "1", "a", TextNode.valueOf("first")));
        codec.write(output, new ControlEnvelope(MessageType.HEARTBEAT, "2", "b", TextNode.valueOf("second")));
        var input = new ByteArrayInputStream(output.toByteArray());

        assertEquals("first", codec.read(input).payload().textValue());
        assertEquals("second", codec.read(input).payload().textValue());
        assertEquals(0, input.available());
    }

    @Test
    void readsFragmentedNetworkInput() throws Exception {
        var input = new ByteArrayInputStream(frame(json("hello"))) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };

        assertEquals("hello", codec.read(input).payload().textValue());
    }

    @Test
    void reportsCleanDisconnectAsEof() {
        assertThrows(EOFException.class, () -> codec.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void rejectsTruncatedLengthPrefix() {
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(new byte[]{0, 0, 1})));
    }

    @Test
    void rejectsTruncatedPayload() throws Exception {
        byte[] complete = frame(json("hello"));
        var input = new ByteArrayInputStream(complete, 0, complete.length - 1);

        assertThrows(ProtocolException.class, () -> codec.read(input));
    }

    @Test
    void rejectsNegativeLengthBeforeReadingPayload() {
        assertThrows(ProtocolException.class, () -> codec.read(lengthOnly(-1)));
    }

    @Test
    void rejectsZeroLength() {
        assertThrows(ProtocolException.class, () -> codec.read(lengthOnly(0)));
    }

    @Test
    void rejects65537ByteLengthBeforeReadingPayload() {
        assertThrows(ProtocolException.class, () -> codec.read(lengthOnly(65_537)));
    }

    @Test
    void acceptsExactly65536BytesOnReadAndWrite() throws Exception {
        String payload = "x".repeat(65_536 - json("").getBytes(StandardCharsets.UTF_8).length);
        assertEquals(payload, codec.read(new ByteArrayInputStream(frame(json(payload)))).payload().textValue());

        var output = new ByteArrayOutputStream();
        codec.write(output, new ControlEnvelope(MessageType.HEARTBEAT, "req-1", "agent-1", TextNode.valueOf(payload)));
        assertEquals(65_536, ByteBuffer.wrap(output.toByteArray()).getInt());
    }

    @Test
    void rejectsOversizedSerializedMessageWithoutWritingPartialFrame() {
        var output = new ByteArrayOutputStream();
        var envelope = new ControlEnvelope(MessageType.METRICS, "req-1", "agent-1", TextNode.valueOf("ế".repeat(30_000)));

        assertThrows(ProtocolException.class, () -> codec.write(output, envelope));
        assertEquals(0, output.size());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame("{"))));
    }

    @Test
    void rejectsTrailingJsonWithinAFrame() throws Exception {
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame(json("hello") + " {}"))));
    }

    @Test
    void rejectsUnknownMessageType() throws Exception {
        String invalid = json("hello").replace("HEARTBEAT", "UNKNOWN");
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame(invalid))));
    }

    @Test
    void rejectsNullEnvelopeJson() throws Exception {
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame("null"))));
    }

    @Test
    void leavesCallerOwnedStreamsOpen() throws Exception {
        class OpenOutput extends ByteArrayOutputStream {
            boolean closed;
            @Override public void close() { closed = true; }
        }
        class OpenInput extends ByteArrayInputStream {
            boolean closed;
            OpenInput(byte[] bytes) { super(bytes); }
            @Override public void close() { closed = true; }
        }
        var output = new OpenOutput();
        codec.write(output, new ControlEnvelope(MessageType.HEARTBEAT, "req-1", "agent-1", TextNode.valueOf("hello")));
        var input = new OpenInput(output.toByteArray());
        codec.read(input);

        assertFalse(output.closed);
        assertFalse(input.closed);
    }

    private static String json(String payload) {
        return "{\"type\":\"HEARTBEAT\",\"requestId\":\"req-1\",\"deviceId\":\"agent-1\",\"payload\":\"" + payload + "\"}";
    }

    private static byte[] frame(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        data.writeInt(bytes.length);
        data.write(bytes);
        return output.toByteArray();
    }

    private static ByteArrayInputStream lengthOnly(int length) {
        return new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(length).array());
    }
}
