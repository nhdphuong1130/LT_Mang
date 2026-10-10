package vn.edu.ltm.server.session;

import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.server.device.DeviceRepository;

import java.io.IOException;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One owner per device. Socket writes never hold lifecycle locks, so expiry can unblock them. */
public final class SessionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final DeviceRepository devices;
    private final Clock clock;
    private volatile boolean closed;
    private volatile Consumer<String> onRegistered = deviceId -> { };

    public SessionRegistry(DeviceRepository devices) { this(devices, Clock.systemUTC()); }

    public SessionRegistry(DeviceRepository devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    /** Caller must authenticate before transferring socket ownership to this registry. */
    public RegistrationResult register(String deviceId, Socket socket) {
        Objects.requireNonNull(socket);
        var result = registerCandidate(new AgentSession(deviceId, socket, clock.instant()));
        if (result.accepted()) onRegistered.accept(deviceId);
        return result;
    }

    /** Publishes ownership under the output lock so notifications cannot precede the ACK. */
    public RegistrationResult registerWithAcknowledgement(String deviceId, Socket socket, ControlEnvelope acknowledgement)
            throws IOException {
        Objects.requireNonNull(socket);
        var candidate = new AgentSession(deviceId, socket, clock.instant());
        RegistrationResult result;
        synchronized (candidate) {
            result = registerCandidate(candidate);
            if (result.accepted()) {
                try { candidate.send(acknowledgement); }
                catch (IOException failure) {
                    removeIfSame(deviceId, candidate.sessionId());
                    throw failure;
                }
            }
        }
        if (result.accepted()) onRegistered.accept(deviceId);
        return result;
    }

    /** Called after registration (and after its ACK, when one is sent). */
    public void setOnRegistered(Consumer<String> listener) { onRegistered = Objects.requireNonNull(listener); }

    private synchronized RegistrationResult registerCandidate(AgentSession candidate) {
        String deviceId = candidate.deviceId();
        if (closed || !candidate.isOpen() || devices.findById(deviceId).isEmpty()) {
            return new RegistrationResult(false, null, "Device unavailable");
        }
        var result = new AtomicReference<RegistrationResult>();
        sessions.compute(deviceId, (id, current) -> {
            if (current != null && current.isOpen()) {
                result.set(new RegistrationResult(false, null, "Device already connected"));
                return current;
            }
            Instant now = clock.instant();
            devices.markOnline(id, now);
            candidate.touch(now);
            result.set(new RegistrationResult(true, candidate, null));
            return candidate;
        });
        return result.get();
    }

    public Optional<AgentSession> find(String deviceId) { return Optional.ofNullable(sessions.get(deviceId)); }

    public void touch(String deviceId, Instant now) {
        sessions.computeIfPresent(deviceId, (id, session) -> {
            devices.markOnline(id, now);
            session.touch(now);
            return session;
        });
    }

    /** A failed write retires only the session on which that write was attempted. */
    public boolean send(String deviceId, ControlEnvelope message) {
        return sendOnSession(deviceId, null, message);
    }

    public boolean sendIfSame(String deviceId, UUID sessionId, ControlEnvelope message) {
        return sendOnSession(deviceId, Objects.requireNonNull(sessionId), message);
    }

    private boolean sendOnSession(String deviceId, UUID expectedSessionId, ControlEnvelope message) {
        Objects.requireNonNull(message);
        if (!deviceId.equals(message.deviceId())) throw new IllegalArgumentException("Message device does not match recipient");
        AgentSession session = sessions.get(deviceId);
        if (session == null || expectedSessionId != null && !expectedSessionId.equals(session.sessionId())) return false;
        try {
            session.send(message);
            return true;
        } catch (IOException failure) {
            removeIfSame(deviceId, session.sessionId());
            return false;
        }
    }

    public void removeIfSame(String deviceId, UUID sessionId) {
        sessions.computeIfPresent(deviceId, (id, session) -> {
            if (!session.sessionId().equals(sessionId)) return session;
            retire(id, session);
            return null;
        });
    }

    public void expireThrough(Instant cutoff) {
        for (String deviceId : sessions.keySet()) {
            sessions.computeIfPresent(deviceId, (id, session) -> {
                if (session.lastSeen().isAfter(cutoff)) return session;
                retire(id, session);
                return null;
            });
        }
    }

    private void retire(String deviceId, AgentSession session) {
        session.close();
        devices.markOffline(deviceId);
    }

    @Override public synchronized void close() {
        closed = true;
        for (String id : sessions.keySet()) sessions.computeIfPresent(id, (deviceId, session) -> {
            retire(deviceId, session);
            return null;
        });
    }

    public record RegistrationResult(boolean accepted, AgentSession session, String reason) { }
}
