package vn.edu.ltm.server.network;

import vn.edu.ltm.server.backup.BackupService;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.restore.RestoreService;
import vn.edu.ltm.common.transfer.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Dedicated short-lived file sessions; control/heartbeat sockets never enter this listener. */
public final class FileTransferServer implements AutoCloseable {
    private final String bindAddress;
    private final int requestedPort;
    private final DeviceProvisioningService provisioning;
    private final BackupService backups;
    private final RestoreService restores;
    private final FileUploadHandler.TempOutputFactory outputFactory;
    private final Consumer<Socket> handlerOverride;
    private final Duration closeWait;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private ServerSocket listener;
    private volatile boolean running;
    private boolean closeRequested;
    private boolean terminated;
    private Thread acceptThread;

    public FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                              BackupService backups) {
        this(bindAddress, port, provisioning, backups, FileUploadHandler.diskOutput());
    }

    public FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                              BackupService backups, FileUploadHandler.TempOutputFactory outputFactory) {
        this(bindAddress, port, provisioning, backups, null, outputFactory);
    }

    public FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                              BackupService backups, RestoreService restores) {
        this(bindAddress, port, provisioning, backups, restores, FileUploadHandler.diskOutput());
    }

    private FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                              BackupService backups, RestoreService restores,
                              FileUploadHandler.TempOutputFactory outputFactory) {
        this(bindAddress, port, provisioning, backups, restores, outputFactory, null, Duration.ofSeconds(3));
    }

    FileTransferServer(String bindAddress, int port, Duration closeWait, Consumer<Socket> handlerOverride) {
        this(bindAddress, port, null, null, null, null, handlerOverride, closeWait);
    }

    private FileTransferServer(String bindAddress, int port, DeviceProvisioningService provisioning,
                               BackupService backups, RestoreService restores,
                               FileUploadHandler.TempOutputFactory outputFactory,
                               Consumer<Socket> handlerOverride, Duration closeWait) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.provisioning = provisioning;
        this.backups = backups;
        this.restores = restores;
        this.outputFactory = outputFactory;
        this.handlerOverride = handlerOverride;
        this.closeWait = closeWait;
    }

    public synchronized void start() throws IOException {
        if (closeRequested) throw new IllegalStateException("File server is closing");
        if (running) return;
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(bindAddress, requestedPort)); }
        catch (IOException failure) { listener.close(); throw failure; }
        running = true;
        acceptThread = Thread.ofVirtual().name("file-listener").start(this::acceptLoop);
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
                if (!running) { closeSocket(socket); sockets.remove(socket); return; }
                try { workers.submit(() -> {
                    try {
                        if (handlerOverride != null) { handlerOverride.accept(socket); return; }
                        socket.setSoTimeout(30_000);
                        var codec = new FileTransferCodec();
                        var header = codec.readHeader(socket.getInputStream());
                        if (header.operation() == FileOperation.BACKUP_UPLOAD)
                            new FileUploadHandler(socket, provisioning, backups, outputFactory, header).run();
                        else if (header.operation() == FileOperation.RESTORE_DOWNLOAD && restores != null)
                            new FileRestoreHandler(socket, header, provisioning, restores).run();
                        else {
                            codec.writeResult(socket.getOutputStream(),
                                    new TransferResult(TransferResult.Status.REJECTED, "Unsupported operation"));
                            socket.close();
                        }
                    } catch (IOException ignored) { }
                    finally { closeSocket(socket); sockets.remove(socket); }
                }); }
                catch (RejectedExecutionException stopped) { closeSocket(socket); sockets.remove(socket); }
            } catch (IOException failure) {
                if (running) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                        "File accept failed");
            }
        }
    }

    @Override public synchronized void close() {
        if (terminated) return;
        closeRequested = true;
        running = false;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        workers.shutdownNow();
        try {
            if (acceptThread != null) acceptThread.join(Math.max(1, closeWait.toMillis()));
            boolean listenerStopped = acceptThread == null || !acceptThread.isAlive();
            boolean workersStopped = workers.awaitTermination(closeWait.toNanos(), TimeUnit.NANOSECONDS);
            if (!listenerStopped || !workersStopped)
                throw new IllegalStateException("File server workers did not stop");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping File server", interrupted);
        }
        terminated = true;
    }

    synchronized boolean terminated() { return terminated; }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
