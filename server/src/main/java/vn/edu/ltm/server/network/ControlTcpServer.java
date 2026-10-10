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
import java.util.function.Consumer;

public final class ControlTcpServer implements AutoCloseable {
    private final ServerConfig config;
    private final DeviceProvisioningService provisioning;
    private final SessionRegistry sessions;
    private final Clock clock;
    private final BiConsumer<String, ControlEnvelope> inbound;
    private final Consumer<Socket> handlerOverride;
    private final Duration closeWait;
    private final HeartbeatMonitor heartbeat;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private boolean closeRequested;
    private boolean terminated;
    private ServerSocket listener;
    private Thread acceptThread;

    public ControlTcpServer(ServerConfig config, DeviceProvisioningService provisioning, SessionRegistry sessions) {
        this(config, provisioning, sessions, Clock.systemUTC(), (id, message) -> { });
    }

    /** The application callback receives only messages from the matching authenticated device. */
    public ControlTcpServer(ServerConfig config, DeviceProvisioningService provisioning, SessionRegistry sessions,
                            Clock clock, BiConsumer<String, ControlEnvelope> inbound) {
        this(config, provisioning, sessions, clock, inbound, null, Duration.ofSeconds(3));
    }

    ControlTcpServer(ServerConfig config, DeviceProvisioningService provisioning, SessionRegistry sessions,
                     Clock clock, BiConsumer<String, ControlEnvelope> inbound, Consumer<Socket> handlerOverride,
                     Duration closeWait) {
        this.config = config;
        this.provisioning = provisioning;
        this.sessions = sessions;
        this.clock = clock;
        this.inbound = inbound;
        this.handlerOverride = handlerOverride;
        this.closeWait = closeWait;
        heartbeat = new HeartbeatMonitor(sessions, Duration.ofSeconds(15), clock);
    }

    public synchronized void start() throws IOException {
        if (closeRequested) throw new IllegalStateException("Server is closing");
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
        acceptThread = Thread.ofVirtual().name("control-listener").start(this::acceptLoop);
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
                        try {
                            if (handlerOverride != null) handlerOverride.accept(socket);
                            else new ControlClientHandler(socket, provisioning, sessions, clock, inbound).run();
                        } finally { closeSocket(socket); sockets.remove(socket); }
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
        if (terminated) return;
        closeRequested = true;
        running = false;
        heartbeat.close();
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        workers.shutdownNow();
        try {
            if (acceptThread != null) acceptThread.join(Math.max(1, closeWait.toMillis()));
            boolean listenerStopped = acceptThread == null || !acceptThread.isAlive();
            boolean workersStopped = workers.awaitTermination(closeWait.toNanos(), TimeUnit.NANOSECONDS);
            boolean heartbeatStopped = heartbeat.awaitStopped(closeWait);
            if (!listenerStopped || !workersStopped || !heartbeatStopped)
                throw new IllegalStateException("Control server workers did not stop");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping Control server", interrupted);
        }
        sessions.close();
        terminated = true;
    }

    synchronized boolean terminated() { return terminated; }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
