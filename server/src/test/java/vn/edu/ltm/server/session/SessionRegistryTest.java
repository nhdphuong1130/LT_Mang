package vn.edu.ltm.server.session;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.persistence.Database;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {
    @TempDir Path temp;

    private SessionRegistry registry() throws Exception {
        var database = new Database(temp.resolve("server.db"));
        database.initialize();
        var repository = new JdbcDeviceRepository(database);
        new DeviceProvisioningService(repository).provision("lab-1", "One", "Linux");
        return new SessionRegistry(repository, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void rejectsLiveDuplicateAndOldCleanupCannotRemoveReplacement() throws Exception {
        var registry = registry();
        try (var firstSocket = new Socket(); var duplicate = new Socket(); var replacement = new Socket()) {
            var first = registry.register("lab-1", firstSocket);
            assertTrue(first.accepted());
            assertFalse(registry.register("lab-1", duplicate).accepted());
            firstSocket.close();
            var second = registry.register("lab-1", replacement);
            assertTrue(second.accepted());
            registry.removeIfSame("lab-1", first.session().sessionId());
            assertEquals(second.session().sessionId(), registry.find("lab-1").orElseThrow().sessionId());
            registry.removeIfSame("lab-1", second.session().sessionId());
            assertTrue(registry.find("lab-1").isEmpty());
            assertTrue(replacement.isClosed());
        }
    }

    @Test void concurrentRegistrationHasExactlyOneOwner() throws Exception {
        var registry = registry();
        var sockets = new ArrayList<Socket>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++) sockets.add(new Socket());
            var tasks = sockets.stream().<java.util.concurrent.Callable<Boolean>>map(socket ->
                    () -> registry.register("lab-1", socket).accepted()).toList();
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertEquals(1, accepted);
        } finally {
            registry.close();
            for (var socket : sockets) socket.close();
        }
    }

    @Test void sendsCompleteFramesFromConcurrentCallersAndReturnsFalseForOffline() throws Exception {
        var registry = registry();
        try (var listener = new ServerSocket(0); var peer = new Socket("127.0.0.1", listener.getLocalPort());
             var owned = listener.accept(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            peer.setSoTimeout(3000);
            registry.register("lab-1", owned);
            var sends = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 20; i++) {
                var envelope = new ControlEnvelope(MessageType.RESTORE_AVAILABLE, "r" + i, "lab-1",
                        JsonNodeFactory.instance.objectNode().put("data", "x".repeat(1000)));
                sends.add(executor.submit(() -> registry.send("lab-1", envelope)));
            }
            var ids = new java.util.HashSet<String>();
            for (int i = 0; i < 20; i++) ids.add(new LengthPrefixedJsonCodec().read(peer.getInputStream()).requestId());
            for (var send : sends) assertTrue(send.get());
            assertEquals(20, ids.size());
            assertFalse(registry.send("missing", new ControlEnvelope(MessageType.ERROR, "r", "missing",
                    JsonNodeFactory.instance.objectNode())));
            owned.close();
            assertFalse(registry.send("lab-1", new ControlEnvelope(MessageType.ERROR, "r", "lab-1",
                    JsonNodeFactory.instance.objectNode())));
            assertTrue(registry.find("lab-1").isEmpty());
        }
    }

    @Test void heartbeatExpiryCanCloseASocketWhileItsOutputIsBlocked() throws Exception {
        var registry = registry();
        var writing = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        try (var socket = new Socket() {
            @Override public java.io.OutputStream getOutputStream() {
                return new java.io.OutputStream() {
                    @Override public void write(int value) throws java.io.IOException {
                        writing.countDown();
                        try {
                            if (!closed.await(2, TimeUnit.SECONDS)) throw new java.io.IOException("Write timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(failure);
                        }
                        throw new java.io.IOException("Socket closed");
                    }
                };
            }
            @Override public synchronized void close() throws java.io.IOException {
                super.close();
                closed.countDown();
            }
        }; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var session = registry.register("lab-1", socket).session();
            var send = executor.submit(() -> registry.send("lab-1", new ControlEnvelope(MessageType.RESTORE_AVAILABLE,
                    "r", "lab-1", JsonNodeFactory.instance.objectNode())));
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            var expiry = executor.submit(() -> registry.expireThrough(session.lastSeen()));
            assertTrue(closed.await(1, TimeUnit.SECONDS), "Expiry must close blocked output without waiting for its write lock");
            expiry.get(1, TimeUnit.SECONDS);
            assertFalse(send.get(1, TimeUnit.SECONDS));
            assertTrue(registry.find("lab-1").isEmpty());
        }
    }

    @Test void registrationAckPrecedesConcurrentNotification() throws Exception {
        var registry = registry();
        var ackStarted = new CountDownLatch(1);
        var releaseAck = new CountDownLatch(1);
        var output = new java.io.ByteArrayOutputStream();
        try (var socket = new Socket() {
            @Override public java.io.OutputStream getOutputStream() {
                return new java.io.OutputStream() {
                    @Override public void write(int value) throws java.io.IOException {
                        ackStarted.countDown();
                        try {
                            if (!releaseAck.await(2, TimeUnit.SECONDS)) throw new java.io.IOException("Test ACK timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(failure);
                        }
                        output.write(value);
                    }
                };
            }
        }; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ack = new ControlEnvelope(MessageType.REGISTER_ACK, "register", "lab-1",
                    JsonNodeFactory.instance.objectNode().put("accepted", true));
            var registration = executor.submit(() -> registry.registerWithAcknowledgement("lab-1", socket, ack));
            assertTrue(ackStarted.await(1, TimeUnit.SECONDS));
            var notification = executor.submit(() -> registry.send("lab-1", new ControlEnvelope(MessageType.RESTORE_AVAILABLE,
                    "restore", "lab-1", JsonNodeFactory.instance.objectNode())));
            releaseAck.countDown();
            assertTrue(registration.get(2, TimeUnit.SECONDS).accepted());
            assertTrue(notification.get(2, TimeUnit.SECONDS));
            var input = new java.io.ByteArrayInputStream(output.toByteArray());
            var codec = new LengthPrefixedJsonCodec();
            assertEquals(MessageType.REGISTER_ACK, codec.read(input).type());
            assertEquals(MessageType.RESTORE_AVAILABLE, codec.read(input).type());
        }
    }

    @Test void offerBoundToOldSessionCannotBeSentToReplacement() throws Exception {
        var registry = registry();
        try (var listener = new ServerSocket(0); var firstPeer = new Socket("127.0.0.1", listener.getLocalPort());
             var firstOwned = listener.accept()) {
            var old = registry.register("lab-1", firstOwned).session().sessionId();
            registry.removeIfSame("lab-1", old);
            try (var nextPeer = new Socket("127.0.0.1", listener.getLocalPort());
                 var nextOwned = listener.accept()) {
                nextPeer.setSoTimeout(250);
                assertTrue(registry.register("lab-1", nextOwned).accepted());
                assertFalse(registry.sendIfSame("lab-1", old, new ControlEnvelope(MessageType.RESTORE_AVAILABLE,
                        "old", "lab-1", JsonNodeFactory.instance.objectNode())));
                assertThrows(java.net.SocketTimeoutException.class,
                        () -> new LengthPrefixedJsonCodec().read(nextPeer.getInputStream()));
            }
        }
    }
}
