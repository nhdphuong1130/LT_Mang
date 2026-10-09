package vn.edu.ltm.agent.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.agent.config.AgentConfig;
import vn.edu.ltm.common.protocol.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Maintains one authenticated control connection; monitoring and file work run independently. */
public final class ControlClient implements AutoCloseable {
    private final AgentConfig config;
    private final Supplier<Socket> sockets;
    private final ReconnectPolicy reconnect = new ReconnectPolicy();
    private final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();
    private final Object writeLock = new Object();
    private volatile Consumer<ControlEnvelope> inbound = message -> { };
    private final AtomicReference<ConnectionState> connection = new AtomicReference<>();
    private volatile boolean closed;
    private Thread worker;

    public ControlClient(AgentConfig config) { this(config, Socket::new); }

    ControlClient(AgentConfig config, Supplier<Socket> sockets) {
        this.config = Objects.requireNonNull(config);
        this.sockets = Objects.requireNonNull(sockets);
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Client is closed");
        if (worker != null) return;
        worker = Thread.ofVirtual().name("agent-control").start(this::connectLoop);
    }

    public void setInboundHandler(Consumer<ControlEnvelope> handler) { inbound = Objects.requireNonNull(handler); }
    public boolean isRegistered() {
        ConnectionState state = connection.get();
        return state != null && state.registered() && !closed;
    }

    public void send(ControlEnvelope message) throws IOException {
        Objects.requireNonNull(message);
        if (!config.deviceId().equals(message.deviceId())) throw new IllegalArgumentException("Message has wrong device ID");
        synchronized (writeLock) {
            ConnectionState state = connection.get();
            if (closed || state == null || !state.registered() || state.socket().isClosed()) {
                throw new IOException("Control connection is offline");
            }
            try { codec.write(state.socket().getOutputStream(), message); }
            catch (IOException failure) {
                retire(state);
                throw failure;
            }
        }
    }

    private void connectLoop() {
        while (!closed) {
            Socket socket = sockets.get();
            ConnectionState state = new ConnectionState(socket, false);
            connection.set(state);
            try (socket) {
                if (closed) return;
                socket.connect(new InetSocketAddress(config.serverHost(), config.controlPort()), 5000);
                socket.setSoTimeout(5000);
                String requestId = UUID.randomUUID().toString();
                codec.write(socket.getOutputStream(), new ControlEnvelope(MessageType.REGISTER, requestId, config.deviceId(),
                        JsonNodeFactory.instance.objectNode().put("token", config.token())));
                var response = codec.read(socket.getInputStream());
                if (response.type() != MessageType.REGISTER_ACK || !requestId.equals(response.requestId())
                        || !config.deviceId().equals(response.deviceId()) || response.payload() == null
                        || !response.payload().path("accepted").isBoolean()
                        || !response.payload().path("accepted").booleanValue()) {
                    throw new IOException("Registration rejected");
                }
                socket.setSoTimeout(0);
                var authenticated = new ConnectionState(socket, true);
                if (closed || !connection.compareAndSet(state, authenticated)) return;
                state = authenticated;
                reconnect.reset();
                while (!closed) {
                    var message = codec.read(socket.getInputStream());
                    if (!config.deviceId().equals(message.deviceId())) throw new IOException("Inbound message has wrong device ID");
                    try { inbound.accept(message); }
                    catch (RuntimeException failure) {
                        System.getLogger(ControlClient.class.getName()).log(System.Logger.Level.WARNING, "Inbound control handler failed");
                    }
                }
            } catch (IOException failure) {
                // Reconnect without logging REGISTER envelopes or credentials.
            } finally {
                retire(state);
            }
            if (!closed) {
                try { Thread.sleep(reconnect.nextDelay()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    @Override public void close() {
        Thread currentWorker;
        synchronized (this) {
            closed = true;
            currentWorker = worker;
        }
        ConnectionState state = connection.getAndSet(null);
        if (state != null) closeSocket(state.socket());
        if (currentWorker != null) {
            currentWorker.interrupt();
            if (currentWorker != Thread.currentThread()) {
                try { currentWorker.join(3000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /** Delayed cleanup of an old generation must never unpublish a replacement connection. */
    private void retire(ConnectionState state) {
        connection.compareAndSet(state, null);
        closeSocket(state.socket());
    }

    private record ConnectionState(Socket socket, boolean registered) { }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
