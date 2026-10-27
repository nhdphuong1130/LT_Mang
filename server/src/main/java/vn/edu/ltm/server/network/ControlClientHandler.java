package vn.edu.ltm.server.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.session.AgentSession;
import vn.edu.ltm.server.session.SessionRegistry;

import java.io.IOException;
import java.net.Socket;
import java.time.Clock;
import java.util.Arrays;
import java.util.function.BiConsumer;

/** A socket cannot reach application dispatch until it owns an authenticated session. */
public final class ControlClientHandler implements Runnable {
    private static final System.Logger LOGGER = System.getLogger(ControlClientHandler.class.getName());
    private final Socket socket;
    private final DeviceProvisioningService provisioning;
    private final SessionRegistry sessions;
    private final Clock clock;
    private final BiConsumer<String, ControlEnvelope> inbound;
    private final LengthPrefixedJsonCodec codec = new LengthPrefixedJsonCodec();

    public ControlClientHandler(Socket socket, DeviceProvisioningService provisioning, SessionRegistry sessions,
                                Clock clock, BiConsumer<String, ControlEnvelope> inbound) {
        this.socket = socket;
        this.provisioning = provisioning;
        this.sessions = sessions;
        this.clock = clock;
        this.inbound = inbound;
    }

    @Override public void run() {
        AgentSession session = null;
        MessageType currentType = null;
        try (socket) {
            socket.setSoTimeout(5000);
            ControlEnvelope registration = codec.read(socket.getInputStream());
            currentType = registration.type();
            if (registration.type() != MessageType.REGISTER) {
                writeError(registration, "Registration required");
                return;
            }
            var token = registration.payload().path("token");
            if (!token.isTextual() || !provisioning.authenticate(registration.deviceId(), token.asText())) {
                writeAck(registration, false, "Invalid credentials");
                return;
            }
            var result = sessions.registerWithAcknowledgement(registration.deviceId(), socket, ack(registration, true, null));
            if (!result.accepted()) {
                writeAck(registration, false, result.reason());
                return;
            }
            session = result.session();
            socket.setSoTimeout(0);
            while (session.isOpen()) {
                ControlEnvelope message = codec.read(socket.getInputStream());
                currentType = message.type();
                if (!session.deviceId().equals(message.deviceId())) {
                    sessions.send(session.deviceId(), error(session.deviceId(), message.requestId(), "Device does not match authenticated session"));
                    return;
                }
                if (message.type() == MessageType.HEARTBEAT) {
                    sessions.touch(session.deviceId(), clock.instant());
                } else if (message.type() == MessageType.METRICS || message.type() == MessageType.RISK_ALERT
                        || message.type() == MessageType.RISK_RESOLVED || message.type() == MessageType.RESTORE_RESULT
                        || message.type() == MessageType.RANGING_CAPABILITIES
                        || message.type() == MessageType.RANGING_RESULT
                        || message.type() == MessageType.RANGING_ABORT
                        || message.type() == MessageType.ERROR) {
                    inbound.accept(session.deviceId(), message);
                } else {
                    sessions.send(session.deviceId(), error(session.deviceId(), message.requestId(), "Unexpected message type"));
                    return;
                }
            }
        } catch (IOException failure) {
            // Ordinary disconnects and malformed frames are isolated to this socket.
        } catch (RuntimeException failure) {
            // Exception messages, causes, and envelopes may contain plaintext credentials.
            // Keep the exception type and stack locations for diagnosis without logging those values.
            LOGGER.log(System.Logger.Level.ERROR,
                    "Unexpected control handler failure [deviceId={0}, sessionId={1}, messageType={2}, exceptionType={3}, stack={4}]",
                    session == null ? "<unregistered>" : session.deviceId(),
                    session == null ? "<none>" : session.sessionId(), currentType,
                    failure.getClass().getName(), Arrays.toString(failure.getStackTrace()));
        } finally {
            if (session != null) sessions.removeIfSame(session.deviceId(), session.sessionId());
        }
    }

    private void writeAck(ControlEnvelope request, boolean accepted, String reason) throws IOException {
        codec.write(socket.getOutputStream(), ack(request, accepted, reason));
    }

    private static ControlEnvelope ack(ControlEnvelope request, boolean accepted, String reason) {
        var payload = JsonNodeFactory.instance.objectNode().put("accepted", accepted);
        if (reason != null) payload.put("reason", reason);
        return new ControlEnvelope(MessageType.REGISTER_ACK, request.requestId(), request.deviceId(), payload);
    }

    private void writeError(ControlEnvelope request, String reason) throws IOException {
        codec.write(socket.getOutputStream(), error(request.deviceId(), request.requestId(), reason));
    }

    private static ControlEnvelope error(String deviceId, String requestId, String reason) {
        return new ControlEnvelope(MessageType.ERROR, requestId, deviceId,
                JsonNodeFactory.instance.objectNode().put("reason", reason));
    }
}
