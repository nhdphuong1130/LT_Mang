package vn.edu.ltm.server.e2e;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.alert.AlertSink;
import vn.edu.ltm.agent.backup.*;
import vn.edu.ltm.agent.config.AgentConfig;
import vn.edu.ltm.agent.metrics.MetricsCollector;
import vn.edu.ltm.agent.monitoring.MonitoringCoordinator;
import vn.edu.ltm.agent.network.ControlClient;
import vn.edu.ltm.agent.persistence.AgentDatabase;
import vn.edu.ltm.agent.restore.RestoreHandler;
import vn.edu.ltm.agent.risk.RiskDetector;
import vn.edu.ltm.agent.risk.RiskThresholds;
import vn.edu.ltm.common.model.*;
import vn.edu.ltm.common.protocol.*;
import vn.edu.ltm.common.transfer.*;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.auth.*;
import vn.edu.ltm.server.backup.*;
import vn.edu.ltm.server.config.ServerConfig;
import vn.edu.ltm.server.device.*;
import vn.edu.ltm.server.incident.*;
import vn.edu.ltm.server.metrics.*;
import vn.edu.ltm.server.network.*;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.restore.*;
import vn.edu.ltm.server.session.SessionRegistry;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MonitoringBackupRestoreE2ETest {
    @TempDir Path tempDir;

    @Test
    void goldenPath_registration_heartbeat_ramAlert_backupTwoVersions_authenticatedRestore() throws Exception {
        Path serverDir = tempDir.resolve("server");
        Path agentDir = tempDir.resolve("agent-protected");
        Files.createDirectories(serverDir);
        Files.createDirectories(agentDir);

        try (var server = new TestServer(serverDir)) {
            server.start();
            var provisioned = server.provision("device-e2e-1", "Laptop-E2E", "Linux");
            server.createAdmin("admin", "admin-password-123");
            server.auth().authenticate("admin", "admin-password-123".toCharArray());

            var fakeClock = new MutableClock(Instant.parse("2026-10-10T10:00:00Z"));
            var fakeMetrics = new SettableMetricsCollector(
                    new SystemMetrics(fakeClock.instant(), 15.0, 40.0, 30.0, 45.0, 95.0, true));
            var alerts = new CopyOnWriteArrayList<RiskEvent>();

            try (var agent = new HeadlessAgent("device-e2e-1", provisioned.token(), agentDir,
                    tempDir.resolve("agent-e2e-1.db"), "127.0.0.1", server.controlPort(), server.filePort(),
                    fakeMetrics, alerts::add, fakeClock)) {
                agent.start();

                // 1. Assert Registration
                await(() -> agent.isConnected() && server.sessions().find("device-e2e-1").isPresent(),
                        Duration.ofSeconds(5));
                assertTrue(agent.isConnected());
                var session = server.sessions().find("device-e2e-1").orElseThrow();
                assertEquals("device-e2e-1", session.deviceId());

                // 2. Assert Heartbeat
                Instant initialHeartbeat = session.lastSeen();
                fakeClock.advance(Duration.ofSeconds(6));
                agent.sampleMonitoring();
                await(() -> {
                    var current = server.sessions().find("device-e2e-1");
                    return current.isPresent() && current.get().lastSeen().isAfter(initialHeartbeat);
                }, Duration.ofSeconds(5));

                // 3. Assert Immediate RAM Alert at the current 80% policy threshold
                fakeMetrics.set(new SystemMetrics(fakeClock.instant(), 15.0, 95.0, 30.0, 45.0, 95.0, true));
                agent.sampleMonitoring();

                await(() -> !alerts.isEmpty() && !server.incidents().findAll("device-e2e-1").isEmpty(),
                        Duration.ofSeconds(5));
                assertEquals(1, alerts.size());
                assertEquals(RiskType.HIGH_RAM, alerts.getFirst().riskType());
                assertEquals(RiskEvent.Status.OPEN, alerts.getFirst().status());

                var incidents = server.incidents().findAll("device-e2e-1");
                assertEquals(1, incidents.size());
                assertEquals(RiskType.HIGH_RAM, incidents.getFirst().riskType());
                assertEquals(RiskEvent.Status.OPEN, incidents.getFirst().status());

                // 4. Assert Saved File Upload (Version 1)
                Path docFile = agentDir.resolve("contract.txt");
                String v1Content = "First version draft content of contract";
                Files.writeString(docFile, v1Content);

                await(() -> server.repo().versions("device-e2e-1", "contract.txt").size() == 1,
                        Duration.ofSeconds(10));
                var v1 = server.repo().versions("device-e2e-1", "contract.txt").getFirst();
                assertEquals(Hashing.sha256(docFile), v1.sha256());
                assertEquals(Files.size(docFile), v1.sizeBytes());
                assertEquals(v1Content, Files.readString(v1.storagePath()));

                // 5. Assert Second Version Upload (Version 2)
                // Small sleep to ensure modified timestamp and stability distinctness
                Thread.sleep(150);
                String v2Content = "Second version amended content of contract - revision 2";
                Files.writeString(docFile, v2Content);

                await(() -> server.repo().versions("device-e2e-1", "contract.txt").size() == 2,
                        Duration.ofSeconds(10));
                var versions = server.repo().versions("device-e2e-1", "contract.txt");
                assertEquals(2, versions.size());
                var v2 = versions.getFirst(); // ordered newest first
                var v1Archived = versions.getLast();
                assertEquals(Hashing.sha256(docFile), v2.sha256());
                assertEquals(v2Content, Files.readString(v2.storagePath()));
                assertEquals(v1Content, Files.readString(v1Archived.storagePath()));

                // 6. Assert Authenticated Restore of Version 1
                RestoreJob restoreJob = server.restores().request(v1Archived.id(), "device-e2e-1", "admin");
                assertEquals(RestoreJob.Status.AVAILABLE, restoreJob.status());

                // Agent receives RESTORE_AVAILABLE via control socket and restores into Recovered/
                await(() -> {
                    var jobOpt = server.restores().find(restoreJob.id());
                    return jobOpt.isPresent() && jobOpt.get().status() == RestoreJob.Status.COMPLETED;
                }, Duration.ofSeconds(10));

                Path recoveredRoot = agentDir.resolve("Recovered");
                assertTrue(Files.isDirectory(recoveredRoot), "Recovered directory must exist");

                Path restoredFile;
                try (var walk = Files.walk(recoveredRoot)) {
                    restoredFile = walk.filter(Files::isRegularFile).findFirst()
                            .orElseThrow(() -> new AssertionError("Restored file not found in Recovered/"));
                }
                assertTrue(restoredFile.startsWith(recoveredRoot));
                assertEquals(Hashing.sha256(v1Archived.storagePath()), Hashing.sha256(restoredFile));
                assertEquals(v1Content, Files.readString(restoredFile));

                // Source file must NOT be overwritten!
                assertEquals(v2Content, Files.readString(docFile));
            }
        }
    }

    @Test
    void serverRestart_withPersistentSqliteAndStorage_reconnectsAndResumes() throws Exception {
        Path serverDir = tempDir.resolve("server-persistent");
        Path agentDir = tempDir.resolve("agent-persistent");
        Files.createDirectories(serverDir);
        Files.createDirectories(agentDir);

        int controlPort;
        int filePort;
        String token;

        // Phase 1: Initial server run, device registers and uploads initial file
        try (var server1 = new TestServer(serverDir)) {
            server1.start();
            controlPort = server1.controlPort();
            filePort = server1.filePort();
            token = server1.provision("dev-restart", "Laptop", "Linux").token();

            try (var agent = new HeadlessAgent("dev-restart", token, agentDir,
                    tempDir.resolve("agent-persist.db"), "127.0.0.1", controlPort, filePort)) {
                agent.start();
                await(agent::isConnected, Duration.ofSeconds(5));

                Files.writeString(agentDir.resolve("first.txt"), "hello before restart");
                await(() -> server1.repo().versions("dev-restart", "first.txt").size() == 1,
                        Duration.ofSeconds(10));
            }
        }

        // Phase 2: Start an Agent that targets the same control & file ports, then restart Server with SAME data directory
        try (var server2 = new TestServer(serverDir, controlPort, filePort)) {
            server2.start();
            assertEquals(controlPort, server2.controlPort());
            assertEquals(filePort, server2.filePort());

            try (var agent = new HeadlessAgent("dev-restart", token, agentDir,
                    tempDir.resolve("agent-persist.db"), "127.0.0.1", controlPort, filePort)) {
                agent.start();
                await(agent::isConnected, Duration.ofSeconds(5));
                assertTrue(server2.sessions().find("dev-restart").isPresent());

                // Old backups still exist in SQLite
                assertEquals(1, server2.repo().versions("dev-restart", "first.txt").size());

                // New file upload works on restarted server
                Files.writeString(agentDir.resolve("second.txt"), "hello after restart");
                await(() -> server2.repo().versions("dev-restart", "second.txt").size() == 1,
                        Duration.ofSeconds(10));
                assertEquals("hello after restart",
                        Files.readString(server2.repo().versions("dev-restart", "second.txt").getFirst().storagePath()));
            }
        }
    }

    @Test
    void queuedUpload_resumesOnce_afterNetworkOrServerError() throws Exception {
        Path serverDir = tempDir.resolve("server-queued");
        Path agentDir = tempDir.resolve("agent-queued");
        Files.createDirectories(serverDir);
        Files.createDirectories(agentDir);

        try (var server = new TestServer(serverDir)) {
            server.start();
            var provisioned = server.provision("dev-queued", "Laptop", "Linux");

            // Stop file server so agent cannot upload immediately
            server.stopFileServer();

            try (var agent = new HeadlessAgent("dev-queued", provisioned.token(), agentDir,
                    tempDir.resolve("agent-queued.db"), "127.0.0.1", server.controlPort(), server.filePort())) {
                agent.start();
                await(agent::isConnected, Duration.ofSeconds(5));

                // Write file when file transfer cannot connect
                Files.writeString(agentDir.resolve("offline-created.txt"), "data while file transfer down");

                // Wait until item is enqueued and attempt fails into RETRY (attempts >= 1)
                await(() -> {
                    try (var conn = new AgentDatabase(tempDir.resolve("agent-queued.db")).openConnection();
                         var stmt = conn.createStatement();
                         var rs = stmt.executeQuery("SELECT attempts FROM backup_queue WHERE status = 'RETRY'")) {
                        return rs.next() && rs.getInt(1) >= 1;
                    } catch (Exception failure) { return false; }
                }, Duration.ofSeconds(5));

                // Now resume the file server on the same port!
                server.startFileServer();

                // Wait for the agent's scheduled worker to resume upload once backoff expires
                await(() -> server.repo().versions("dev-queued", "offline-created.txt").size() == 1,
                        Duration.ofSeconds(10));
                assertEquals("data while file transfer down",
                        Files.readString(server.repo().versions("dev-queued", "offline-created.txt").getFirst().storagePath()));
            }
        }
    }

    @Test
    void pendingRestore_resumesOnce_whenOfflineAgentRegisters() throws Exception {
        Path serverDir = tempDir.resolve("server-offline-restore");
        Path agentDir = tempDir.resolve("agent-offline-restore");
        Files.createDirectories(serverDir);
        Files.createDirectories(agentDir);

        try (var server = new TestServer(serverDir)) {
            server.start();
            var provisioned = server.provision("dev-offline", "Laptop", "Linux");
            server.createAdmin("admin", "admin-password-123");
            server.auth().authenticate("admin", "admin-password-123".toCharArray());

            // Pre-seed a backup in the server for dev-offline
            Path fakeStorage = serverDir.resolve("backup-storage/dev-offline/notes.txt/blob");
            Files.createDirectories(fakeStorage.getParent());
            Files.writeString(fakeStorage, "valuable pre-existing notes");
            server.repo().commitVersion("dev-offline", "notes.txt", Files.size(fakeStorage),
                    Hashing.sha256(fakeStorage), fakeStorage);
            long versionId = server.repo().versions("dev-offline", "notes.txt").getFirst().id();

            // Device is currently OFFLINE. Request restore.
            RestoreJob pendingJob = server.restores().request(versionId, "dev-offline", "admin");
            assertEquals(RestoreJob.Status.PENDING, pendingJob.status());

            // Now device comes online!
            try (var agent = new HeadlessAgent("dev-offline", provisioned.token(), agentDir,
                    tempDir.resolve("agent-offline.db"), "127.0.0.1", server.controlPort(), server.filePort())) {
                agent.start();
                await(agent::isConnected, Duration.ofSeconds(5));

                // The registration trigger (setOnRegistered) offers the job automatically
                await(() -> {
                    var jobOpt = server.restores().find(pendingJob.id());
                    return jobOpt.isPresent() && jobOpt.get().status() == RestoreJob.Status.COMPLETED;
                }, Duration.ofSeconds(10));

                Path recoveredRoot = agentDir.resolve("Recovered");
                assertTrue(Files.isDirectory(recoveredRoot));
                try (var walk = Files.walk(recoveredRoot)) {
                    Path restoredFile = walk.filter(Files::isRegularFile).findFirst()
                            .orElseThrow(() -> new AssertionError("Restored file not found in Recovered/"));
                    assertEquals("valuable pre-existing notes", Files.readString(restoredFile));
                }
            }
        }
    }

    @Test
    void oneBrokenAgent_doesNotBlockHealthyAgent() throws Exception {
        Path serverDir = tempDir.resolve("server-multi");
        Path agentHealthyDir = tempDir.resolve("agent-healthy");
        Files.createDirectories(serverDir);
        Files.createDirectories(agentHealthyDir);

        try (var server = new TestServer(serverDir)) {
            server.start();
            var healthyProv = server.provision("dev-healthy", "Healthy-PC", "Linux");
            server.provision("dev-broken", "Broken-PC", "Linux"); // real device id, bad actor

            // Start healthy agent
            try (var healthyAgent = new HeadlessAgent("dev-healthy", healthyProv.token(), agentHealthyDir,
                    tempDir.resolve("agent-healthy.db"), "127.0.0.1", server.controlPort(), server.filePort())) {
                healthyAgent.start();
                await(healthyAgent::isConnected, Duration.ofSeconds(5));

                // Broken agent 1: connects and sends invalid JSON / wrong auth token
                try (var badSocket = new Socket("127.0.0.1", server.controlPort())) {
                    badSocket.setSoTimeout(3000);
                    // Send garbage / invalid registration
                    byte[] garbage = new byte[]{0, 0, 0, 10, '{', '"', 'b', 'a', 'd', '}'};
                    badSocket.getOutputStream().write(garbage);
                    badSocket.getOutputStream().flush();
                }

                // Broken agent 2: connects to file transfer port and sends garbage
                try (var badFileSocket = new Socket("127.0.0.1", server.filePort())) {
                    badFileSocket.setSoTimeout(3000);
                    badFileSocket.getOutputStream().write(new byte[]{0, 0, 0, 5, 'n', 'o', 'p', 'e'});
                    badFileSocket.getOutputStream().flush();
                }

                // Assert healthy agent is STILL online and functioning
                assertTrue(healthyAgent.isConnected());
                assertTrue(server.sessions().find("dev-healthy").isPresent());

                // Healthy agent can still upload files without hindrance
                Files.writeString(agentHealthyDir.resolve("healthy-doc.txt"), "healthy document content");
                await(() -> server.repo().versions("dev-healthy", "healthy-doc.txt").size() == 1,
                        Duration.ofSeconds(10));
                assertEquals("healthy document content",
                        Files.readString(server.repo().versions("dev-healthy", "healthy-doc.txt").getFirst().storagePath()));
            }
        }
    }

    // =========================================================================
    // Test Harness: TestServer and HeadlessAgent
    // =========================================================================

    static final class TestServer implements AutoCloseable {
        private final Path dataDir;
        private final int requestedControlPort;
        private final int requestedFilePort;
        private final Database database;
        private final JdbcDeviceRepository devices;
        private final DeviceProvisioningService provisioning;
        private final AdminAuthService auth;
        private final SessionRegistry sessions;
        private final JdbcMetricsRepository metrics;
        private final JdbcIncidentRepository incidents;
        private final JdbcBackupRepository repo;
        private final BackupService backups;
        private final RestoreService restores;
        private ControlTcpServer control;
        private FileTransferServer files;

        TestServer(Path dataDir) { this(dataDir, 0, 0); }

        TestServer(Path dataDir, int controlPort, int filePort) {
            this.dataDir = dataDir;
            this.requestedControlPort = controlPort;
            this.requestedFilePort = filePort;
            this.database = new Database(dataDir.resolve("server.db"));
            try { this.database.initialize(); }
            catch (SQLException failure) { throw new RuntimeException(failure); }
            this.devices = new JdbcDeviceRepository(database);
            this.provisioning = new DeviceProvisioningService(devices);
            this.auth = new AdminAuthService(new JdbcAdminUserRepository(database));
            this.sessions = new SessionRegistry(devices);
            this.metrics = new JdbcMetricsRepository(database);
            this.incidents = new JdbcIncidentRepository(database);
            this.repo = new JdbcBackupRepository(database);
            this.backups = new BackupService(repo, new StoragePathResolver(dataDir.resolve("backup-storage")));
            this.restores = new RestoreService(new JdbcRestoreJobRepository(database), auth, sessions, Clock.systemUTC());
        }

        private int actualControlPort;
        private int actualFilePort;

        void start() throws IOException {
            control = new ControlTcpServer(new ServerConfig("127.0.0.1", requestedControlPort), provisioning, sessions,
                    Clock.systemUTC(), new MetricsMessageHandler(metrics, new IncidentService(incidents), sessions));
            control.start();
            actualControlPort = control.port();
            startFileServer();
        }

        void stopFileServer() {
            if (files != null) files.close();
        }

        void startFileServer() throws IOException {
            int portToBind = actualFilePort != 0 ? actualFilePort : requestedFilePort;
            files = new FileTransferServer("127.0.0.1", portToBind, provisioning, backups, restores);
            files.start();
            actualFilePort = files.port();
        }

        int controlPort() { return actualControlPort; }
        int filePort() { return actualFilePort; }
        DeviceProvisioningService.ProvisionedDevice provision(String id, String name, String os) throws SQLException {
            return provisioning.provision(id, name, os);
        }
        void createAdmin(String user, String pass) throws SQLException {
            auth.createInitialAdmin(user, pass.toCharArray());
        }
        AdminAuthService auth() { return auth; }
        BackupService backups() { return backups; }
        RestoreService restores() { return restores; }
        JdbcMetricsRepository metrics() { return metrics; }
        JdbcIncidentRepository incidents() { return incidents; }
        SessionRegistry sessions() { return sessions; }
        JdbcBackupRepository repo() { return repo; }

        @Override public void close() {
            if (control != null) control.close();
            if (files != null) files.close();
        }
    }

    static final class HeadlessAgent implements AutoCloseable {
        private final String deviceId;
        private final String token;
        private final Path protectedRoot;
        private final Path dbPath;
        private final String host;
        private final int controlPort;
        private final int filePort;
        private final MetricsCollector collector;
        private final AlertSink alertSink;
        private final Clock clock;
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofPlatform().daemon().name("agent-test", 0).factory());
        private final ExecutorService fileTasks = Executors.newVirtualThreadPerTaskExecutor();
        private ControlClient control;
        private MonitoringCoordinator monitoring;
        private ProtectedFolderWatcher watcher;
        private BackupQueueRepository queue;
        private volatile boolean closed;

        HeadlessAgent(String deviceId, String token, Path protectedRoot, Path dbPath, String host, int controlPort, int filePort) {
            this(deviceId, token, protectedRoot, dbPath, host, controlPort, filePort,
                    () -> new SystemMetrics(Instant.now(), 5.0, 30.0, 20.0, null, 100.0, true),
                    event -> { }, Clock.systemUTC());
        }

        HeadlessAgent(String deviceId, String token, Path protectedRoot, Path dbPath, String host, int controlPort,
                      int filePort, MetricsCollector collector, AlertSink alertSink, Clock clock) {
            this.deviceId = deviceId;
            this.token = token;
            this.protectedRoot = protectedRoot;
            this.dbPath = dbPath;
            this.host = host;
            this.controlPort = controlPort;
            this.filePort = filePort;
            this.collector = collector;
            this.alertSink = alertSink;
            this.clock = clock;
        }

        void start() throws IOException, SQLException {
            var db = new AgentDatabase(dbPath);
            db.initialize();
            queue = new JdbcBackupQueueRepository(db, clock);
            var stability = new FileStabilityChecker(protectedRoot);
            var restore = new RestoreHandler(protectedRoot, host, filePort, deviceId, token);

            control = new ControlClient(new AgentConfig(host, controlPort, deviceId, token));
            control.setInboundHandler(message -> {
                if (message.type() == MessageType.RESTORE_AVAILABLE) fileTasks.submit(() -> {
                    try { restore.restore(message); }
                    catch (IOException | RuntimeException failure) {
                        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Restore failed", failure);
                    }
                });
            });

            monitoring = new MonitoringCoordinator(deviceId, control, collector, new RiskDetector(), alertSink, clock);
            watcher = new ProtectedFolderWatcher(protectedRoot, Duration.ofMillis(50));
            watcher.start(path -> fileTasks.submit(() -> {
                try {
                    var stable = stability.awaitStable(path, Duration.ofMillis(50), Duration.ofSeconds(5));
                    var eligible = new BackupEligibility().evaluate(protectedRoot, stable.path());
                    if (eligible.eligible()) queue.enqueueOrUpdate(new BackupCandidate(eligible.relativePath(),
                            stable.size(), stable.modifiedTime().toInstant(), stable.sha256()));
                } catch (Exception failure) {
                    if (!closed) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Enqueue failed", failure);
                }
            }));

            control.start();
            monitoring.start();

            var worker = new BackupWorker(queue, new BackupUploader(protectedRoot, host, filePort, deviceId, token)::upload,
                    protectedRoot, clock);
            scheduler.scheduleWithFixedDelay(() -> {
                if (closed) return;
                try { worker.runOnce(); }
                catch (SQLException failure) {
                    if (!closed) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Worker error", failure);
                }
            }, 50, 50, TimeUnit.MILLISECONDS);
        }

        boolean isConnected() { return control != null && control.isRegistered(); }
        void sampleMonitoring() { if (monitoring != null) monitoring.sample(); }
        BackupQueueRepository queue() { return queue; }

        @Override public void close() {
            closed = true;
            scheduler.shutdownNow();
            if (control != null) control.close();
            if (monitoring != null) monitoring.close();
            if (watcher != null) try { watcher.close(); } catch (IOException ignored) { }
            fileTasks.shutdownNow();
        }
    }

    static final class SettableMetricsCollector implements MetricsCollector {
        private volatile SystemMetrics current;
        SettableMetricsCollector(SystemMetrics current) { this.current = current; }
        void set(SystemMetrics current) { this.current = current; }
        @Override public SystemMetrics collect() { return current; }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        synchronized void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return now; }
    }

    @FunctionalInterface
    interface ThrowingBooleanSupplier { boolean getAsBoolean() throws Exception; }

    static void await(ThrowingBooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (Exception ignored) { }
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Condition not met within " + timeout);
    }
}
