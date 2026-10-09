package vn.edu.ltm.agent.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import vn.edu.ltm.agent.config.AgentConfig;
import vn.edu.ltm.common.protocol.*;

import java.net.ServerSocket;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AgentControlRegistrationIntegrationTest {
    final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();

    @Test void registersBeforeSendingAndDispatchesInboundMessagesThenClosesCleanly() throws Exception {
        try (var listener = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(6000);
            var ackReceived = new CountDownLatch(1);
            var inbound = new LinkedBlockingQueue<ControlEnvelope>();
            var peer = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    var register = codec.read(socket.getInputStream());
                    assertEquals(MessageType.REGISTER, register.type());
                    assertEquals("secret", register.payload().path("token").asText());
                    codec.write(socket.getOutputStream(), ack(register.requestId(), true));
                    codec.write(socket.getOutputStream(), new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "job", "lab-1",
                            JsonNodeFactory.instance.objectNode()));
                    var outgoing = codec.read(socket.getInputStream());
                    assertEquals(MessageType.HEARTBEAT, outgoing.type());
                    ackReceived.countDown();
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });
            var config = new AgentConfig("127.0.0.1", listener.getLocalPort(), "lab-1", "secret");
            assertFalse(config.toString().contains("secret"));
            try (var client = new ControlClient(config)) {
                assertThrows(java.io.IOException.class, () -> client.send(heartbeat()));
                client.setInboundHandler(inbound::add);
                client.start();
                assertEquals(MessageType.RESTORE_AVAILABLE, inbound.poll(3, TimeUnit.SECONDS).type());
                assertTrue(client.isRegistered());
                client.send(heartbeat());
                assertTrue(ackReceived.await(3, TimeUnit.SECONDS));
            }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void rejectedRegistrationReconnectsAndDoesNotPublishAuthenticatedState() throws Exception {
        try (var listener = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(6000);
            var rejected = new CountDownLatch(1);
            var allowAck = new CountDownLatch(1);
            var inbound = new LinkedBlockingQueue<ControlEnvelope>();
            var peer = executor.submit(() -> {
                try (var first = listener.accept()) {
                    first.setSoTimeout(3000);
                    var register = codec.read(first.getInputStream());
                    codec.write(first.getOutputStream(), ack(register.requestId(), false));
                    assertEquals(-1, first.getInputStream().read());
                    rejected.countDown();
                }
                try (var second = listener.accept()) {
                    second.setSoTimeout(3000);
                    var register = codec.read(second.getInputStream());
                    assertTrue(allowAck.await(3, TimeUnit.SECONDS));
                    codec.write(second.getOutputStream(), ack(register.requestId(), true));
                    codec.write(second.getOutputStream(), new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "job", "lab-1",
                            JsonNodeFactory.instance.objectNode()));
                    assertEquals(-1, second.getInputStream().read());
                }
                return null;
            });
            try (var client = new ControlClient(new AgentConfig("127.0.0.1", listener.getLocalPort(), "lab-1", "secret"))) {
                client.setInboundHandler(inbound::add);
                client.start();
                assertTrue(rejected.await(3, TimeUnit.SECONDS));
                assertFalse(client.isRegistered());
                assertThrows(java.io.IOException.class, () -> client.send(heartbeat()));
                allowAck.countDown();
                assertNotNull(inbound.poll(5, TimeUnit.SECONDS));
                assertTrue(client.isRegistered());
            }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void malformedAcknowledgementDoesNotKillReconnectLoop() throws Exception {
        try (var listener = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(6000);
            var inbound = new LinkedBlockingQueue<ControlEnvelope>();
            var peer = executor.submit(() -> {
                try (var first = listener.accept()) {
                    first.setSoTimeout(3000);
                    var registration = codec.read(first.getInputStream());
                    byte[] incompleteAck = ("{\"type\":\"REGISTER_ACK\",\"requestId\":\"" + registration.requestId()
                            + "\",\"deviceId\":\"lab-1\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    var output = new java.io.DataOutputStream(first.getOutputStream());
                    output.writeInt(incompleteAck.length);
                    output.write(incompleteAck);
                    output.flush();
                    assertEquals(-1, first.getInputStream().read());
                }
                try (var second = listener.accept()) {
                    second.setSoTimeout(3000);
                    var registration = codec.read(second.getInputStream());
                    codec.write(second.getOutputStream(), ack(registration.requestId(), true));
                    codec.write(second.getOutputStream(), new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "job", "lab-1",
                            JsonNodeFactory.instance.objectNode()));
                    assertEquals(-1, second.getInputStream().read());
                }
                return null;
            });
            try (var client = new ControlClient(new AgentConfig("127.0.0.1", listener.getLocalPort(), "lab-1", "secret"))) {
                client.setInboundHandler(inbound::add);
                client.start();
                assertNotNull(inbound.poll(5, TimeUnit.SECONDS));
            }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    private ControlEnvelope heartbeat() {
        return new ControlEnvelope(MessageType.HEARTBEAT, "h", "lab-1", JsonNodeFactory.instance.objectNode());
    }

    private ControlEnvelope ack(String requestId, boolean accepted) {
        return new ControlEnvelope(MessageType.REGISTER_ACK, requestId, "lab-1",
                JsonNodeFactory.instance.objectNode().put("accepted", accepted));
    }
}
