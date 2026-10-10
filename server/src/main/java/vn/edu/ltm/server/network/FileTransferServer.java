package vn.edu.ltm.server.network;

import vn.edu.ltm.server.backup.BackupService;
import vn.edu.ltm.server.device.DeviceProvisioningService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated short-lived file sessions; control/heartbeat sockets never enter this listener. */
public final class FileTransferServer implements AutoCloseable {
    private final String bindAddress;
    private final int requestedPort;
    private final DeviceProvisioningService provisioning;
    private final BackupService backups;
    private final FileUploadHandler.TempOutputFactory outputFactory;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private ServerSocket listener;
    private volatile boolean running;
    private boolean closed;

    public FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                              BackupService backups) {
        this(bindAddress, port, provisioning, backups, FileUploadHandler.diskOutput());
    }

    public FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                              BackupService backups, FileUploadHandler.TempOutputFactory outputFactory) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.provisioning = provisioning;
        this.backups = backups;
        this.outputFactory = outputFactory;
    }

    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("File server is closed");
        if (running) return;
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(bindAddress, requestedPort)); }
        catch (IOException failure) { listener.close(); throw failure; }
        running = true;
        Thread.ofVirtual().name("file-listener").start(this::acceptLoop);
    }

    public synchronized int port() {
        if (listener == null) throw new IllegalStateException("File server has not started");
        return listener.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = listener.accept();
                sockets.add(socket);
                workers.submit(() -> {
                    try { new FileUploadHandler(socket, provisioning, backups, outputFactory).run(); }
                    finally { sockets.remove(socket); }
                });
            } catch (IOException | java.util.concurrent.RejectedExecutionException failure) {
                if (running) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "File accept failed");
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        try { workers.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
