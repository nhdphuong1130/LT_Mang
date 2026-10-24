package vn.edu.ltm.server.session;

import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.LengthPrefixedJsonCodec;

import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.UUID;

public final class AgentSession implements AutoCloseable {
    private final UUID sessionId = UUID.randomUUID();
    private final String deviceId;
    private final Socket socket;
    private final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();
    private volatile Instant lastSeen;

    AgentSession(String deviceId, Socket socket, Instant lastSeen) {
        this.deviceId = deviceId;
        this.socket = socket;
        this.lastSeen = lastSeen;
    }

    public UUID sessionId() { return sessionId; }
    public String deviceId() { return deviceId; }
    public Instant lastSeen() { return lastSeen; }
    public boolean isOpen() { return !socket.isClosed(); }
    public String remoteAddress() {
        var addr = socket.getInetAddress();
        return addr == null ? null : addr.getHostAddress();
    }
    void touch(Instant now) { lastSeen = now; }

    /** All writes, including registration acknowledgements, use the same session lock. */
    public synchronized void send(ControlEnvelope message) throws IOException {
        codec.write(socket.getOutputStream(), message);
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { /* Already disconnected. */ }
    }
}
