package vn.edu.ltm.server.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.server.config.ServerConfig;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.device.JdbcDeviceRepository;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.session.SessionRegistry;

import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerShutdownTest {
    @TempDir Path temp;

    @Test void controlCloseRetainsUnfinishedHandlerForRetry() throws Exception {
        var database = new Database(temp.resolve("control.db")); database.initialize();
        var devices = new JdbcDeviceRepository(database);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = new SessionRegistry(devices);
        var server = new ControlTcpServer(new ServerConfig("127.0.0.1", 0),
                new DeviceProvisioningService(devices), registry, Clock.systemUTC(), (id, msg) -> { },
                socket -> waitUninterruptibly(entered, release), Duration.ofMillis(100));
        try {
            server.start();
            try (var peer = new Socket("127.0.0.1", server.port())) {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, server::close);
                assertFalse(server.terminated());
                release.countDown();
                server.close();
                assertTrue(server.terminated());
            }
        } finally { release.countDown(); server.close(); }
    }

    @Test void fileCloseRetainsUnfinishedHandlerForRetry() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = new FileTransferServer("127.0.0.1", 0, Duration.ofMillis(100),
                socket -> waitUninterruptibly(entered, release));
        try {
            server.start();
            try (var peer = new Socket("127.0.0.1", server.port())) {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, server::close);
                assertFalse(server.terminated());
                release.countDown();
                server.close();
                assertTrue(server.terminated());
            }
        } finally { release.countDown(); server.close(); }
    }

    private static void waitUninterruptibly(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        boolean interrupted = false;
        while (true) {
            try { release.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
