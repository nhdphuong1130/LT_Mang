package vn.edu.ltm.agent.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import vn.edu.ltm.agent.config.AgentConfig;
import vn.edu.ltm.common.protocol.*;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test void staleSendFailureCannotRetireAnAuthenticatedReplacement() throws Exception {
        var failOldWrites = new AtomicBoolean();
        var failedSendStarted = new CountDownLatch(1);
        var releaseFailedSend = new CountDownLatch(1);
        try (var listener = new ServerSocket(0); var executor = Executors.newVirtualThreadPerTaskExecutor();
             var oldSocket = new Socket() {
                 @Override public OutputStream getOutputStream() throws IOException {
                     OutputStream output = super.getOutputStream();
                     return new OutputStream() {
                         @Override public void write(int value) throws IOException {
                             delayFailureIfRequested();
                             output.write(value);
                         }
                         @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                             delayFailureIfRequested();
                             output.write(bytes, offset, length);
                         }
                         private void delayFailureIfRequested() throws IOException {
                             if (!failOldWrites.get()) return;
                             failedSendStarted.countDown();
                             try {
                                 if (!releaseFailedSend.await(8, TimeUnit.SECONDS)) throw new IOException("Test write gate timed out");
                             } catch (InterruptedException interrupted) {
                                 Thread.currentThread().interrupt();
                                 throw new IOException(interrupted);
                             }
                             throw new IOException("Retired socket write failed");
                         }
                     };
                 }
             }) {
            listener.setSoTimeout(6000);
            var inbound = new LinkedBlockingQueue<ControlEnvelope>();
            var healthyHeartbeat = new CountDownLatch(1);
            var peer = executor.submit(() -> {
                try (var first = listener.accept()) {
                    first.setSoTimeout(3000);
                    var registration = codec.read(first.getInputStream());
                    codec.write(first.getOutputStream(), ack(registration.requestId(), true));
                    codec.write(first.getOutputStream(), new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "first", "lab-1",
                            JsonNodeFactory.instance.objectNode()));
                    assertTrue(failedSendStarted.await(3, TimeUnit.SECONDS));
                }
                try (var second = listener.accept()) {
                    second.setSoTimeout(3000);
                    var registration = codec.read(second.getInputStream());
                    codec.write(second.getOutputStream(), ack(registration.requestId(), true));
                    codec.write(second.getOutputStream(), new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "replacement", "lab-1",
                            JsonNodeFactory.instance.objectNode()));
                    assertEquals(MessageType.HEARTBEAT, codec.read(second.getInputStream()).type());
                    healthyHeartbeat.countDown();
                    assertEquals(-1, second.getInputStream().read());
                }
                return null;
            });
            var socketsCreated = new AtomicInteger();
            try (var client = new ControlClient(new AgentConfig("127.0.0.1", listener.getLocalPort(), "lab-1", "secret"),
                    () -> socketsCreated.getAndIncrement() == 0 ? oldSocket : new Socket())) {
                client.setInboundHandler(inbound::add);
                client.start();
                assertEquals("first", inbound.poll(3, TimeUnit.SECONDS).requestId());
                failOldWrites.set(true);
                var oldSend = executor.submit(() -> assertThrows(IOException.class, () -> client.send(heartbeat())));
                try {
                    assertTrue(failedSendStarted.await(3, TimeUnit.SECONDS));
                    assertEquals("replacement", inbound.poll(5, TimeUnit.SECONDS).requestId());
                    assertTrue(client.isRegistered());
                    releaseFailedSend.countDown();
                    oldSend.get(3, TimeUnit.SECONDS);
                    assertTrue(client.isRegistered(), "An old socket failure must not clear replacement registration");
                    client.send(heartbeat());
                    assertTrue(healthyHeartbeat.await(3, TimeUnit.SECONDS));
                } finally {
                    releaseFailedSend.countDown();
                }
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
