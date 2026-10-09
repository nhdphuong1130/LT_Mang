package vn.edu.ltm.server.network;

import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.server.config.ServerConfig;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.heartbeat.HeartbeatMonitor;
import vn.edu.ltm.server.session.SessionRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class ControlTcpServer implements AutoCloseable {
    private final ServerConfig config;
    private final DeviceProvisioningService provisioning;
    private final SessionRegistry sessions;
    private final Clock clock;
    private final BiConsumer<String, ControlEnvelope> inbound;
    private final HeartbeatMonitor heartbeat;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private boolean closed;
    private ServerSocket listener;

    public ControlTcpServer(ServerConfig config, DeviceProvisioningService provisioning, SessionRegistry sessions) {
        this(config, provisioning, sessions, Clock.systemUTC(), (id, message) -> { });
    }

    /** The application callback receives only messages from the matching authenticated device. */
    public ControlTcpServer(ServerConfig config, DeviceProvisioningService provisioning, SessionRegistry sessions,
                            Clock clock, BiConsumer<String, ControlEnvelope> inbound) {
        this.config = config;
        this.provisioning = provisioning;
        this.sessions = sessions;
        this.clock = clock;
        this.inbound = inbound;
        heartbeat = new HeartbeatMonitor(sessions, Duration.ofSeconds(15), clock);
    }

    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("Server is closed");
        if (running) return;
        var socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(config.bindAddress(), config.controlPort()));
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
        listener = socket;
        running = true;
        heartbeat.start();
        Thread.ofVirtual().name("control-listener").start(this::acceptLoop);
    }

    public synchronized int port() {
        if (listener == null) throw new IllegalStateException("Server has not started");
        return listener.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = listener.accept();
                sockets.add(socket);
                if (!running) {
                    closeSocket(socket);
                    sockets.remove(socket);
                    return;
                }
                try {
                    workers.submit(() -> {
                        try { new ControlClientHandler(socket, provisioning, sessions, clock, inbound).run(); }
                        finally { sockets.remove(socket); }
                    });
                } catch (RejectedExecutionException stopped) {
                    closeSocket(socket);
                    sockets.remove(socket);
                }
            } catch (IOException failure) {
                if (running) System.getLogger(ControlTcpServer.class.getName())
                        .log(System.Logger.Level.WARNING, "Control accept failed");
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        heartbeat.close();
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        workers.shutdownNow();
        try { workers.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        sessions.close();
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
