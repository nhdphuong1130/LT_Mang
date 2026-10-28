package vn.edu.ltm.server.network;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.ltm.agent.restore.RestoreHandler;
import vn.edu.ltm.common.model.RiskEvent;
import vn.edu.ltm.common.model.RiskType;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.LengthPrefixedJsonCodec;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.transfer.FileOperation;
import vn.edu.ltm.common.transfer.FileTransferCodec;
import vn.edu.ltm.common.transfer.FileTransferHeader;
import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.common.util.Hashing;
import vn.edu.ltm.server.ansible.AnsibleSyncService;
import vn.edu.ltm.server.audit.AuditLogRepository;
import vn.edu.ltm.server.audit.JdbcAuditLogRepository;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.auth.JdbcAdminUserRepository;
import vn.edu.ltm.server.backup.BackupService;
import vn.edu.ltm.server.backup.JdbcBackupRepository;
import vn.edu.ltm.server.backup.StoragePathResolver;
import vn.edu.ltm.server.config.ServerConfig;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.device.JdbcDeviceRepository;
import vn.edu.ltm.server.incident.IncidentService;
import vn.edu.ltm.server.incident.JdbcIncidentRepository;
import vn.edu.ltm.server.metrics.JdbcMetricsRepository;
import vn.edu.ltm.server.persistence.Database;
import vn.edu.ltm.server.restore.JdbcRestoreJobRepository;
import vn.edu.ltm.server.restore.RestoreService;
import vn.edu.ltm.server.session.SessionRegistry;
import vn.edu.ltm.server.ui.DashboardDataService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferAndAnsibleVerificationTest {
    @TempDir Path tempDir;
    private Database database;
    private DeviceProvisioningService provisioning;
    private AdminAuthService auth;
    private SessionRegistry sessions;
    private RestoreService restores;
    private BackupService backups;
    private AnsibleSyncService ansibleSync;
    private AuditLogRepository auditLogs;
    private IncidentService incidentService;
    private JdbcIncidentRepository incidentRepo;
    private DashboardDataService dataService;
    private ControlTcpServer controlServer;
    private FileTransferServer fileServer;

    private String deviceId = "agent-verify-01";
    private String token;

    @BeforeEach
    void setup() throws Exception {
        database = new Database(tempDir.resolve("server.db"));
        database.initialize();
        auditLogs = new JdbcAuditLogRepository(database);
        var devices = new JdbcDeviceRepository(database);
        provisioning = new DeviceProvisioningService(devices);
        auth = new AdminAuthService(new JdbcAdminUserRepository(database));
        auth.createInitialAdmin("admin", "AdminPassword123!".toCharArray());
        auth.authenticate("admin", "AdminPassword123!".toCharArray());

        sessions = new SessionRegistry(devices);
        incidentRepo = new JdbcIncidentRepository(database);
        incidentService = new IncidentService(incidentRepo);
        provisioning.setAuditAndIncidents(auditLogs, incidentService);
        sessions.setAuditAndIncidents(auditLogs, incidentService);

        var metrics = new JdbcMetricsRepository(database);
        dataService = new DashboardDataService(database, devices, metrics, incidentRepo, sessions, null, auditLogs);
        backups = new BackupService(new JdbcBackupRepository(database), new StoragePathResolver(tempDir.resolve("backup-storage")));
        restores = new RestoreService(new JdbcRestoreJobRepository(database), auth, sessions, Clock.systemUTC(),
                Duration.ofMinutes(2), backups);
        restores.setProvisioning(provisioning);

        Path playbookDir = Files.isDirectory(Path.of("ansible"))
                ? Path.of("ansible").toAbsolutePath()
                : Path.of(".worktrees/agent-monitoring-backup/ansible").toAbsolutePath();
        ansibleSync = new AnsibleSyncService(tempDir, playbookDir, provisioning, restores);

        var created = provisioning.provision(deviceId, "Agent Verify", "Linux");
        token = created.token();

        fileServer = new FileTransferServer("127.0.0.1", 0, provisioning, backups, restores, auditLogs);
        fileServer.start();

        controlServer = new ControlTcpServer(new ServerConfig("127.0.0.1", 0), provisioning, sessions,
                Clock.systemUTC(), (dev, msg) -> {});
        controlServer.start();
    }

    @AfterEach
    void tearDown() {
        if (fileServer != null) fileServer.close();
        if (controlServer != null) controlServer.close();
        if (sessions != null) sessions.close();
    }

    @Test
    void ansibleSyncExecutesAndBroadcastsWithoutAuthFailure() throws Exception {
        // Run ansible sync in a separate thread (simulating UI worker pool)
        var outcome = ansibleSync.initializeAndSyncAllClients("Noi dung kiem tra Ansible", "admin");
        assertNotNull(outcome);
        assertTrue(Files.isRegularFile(outcome.generatedFile()));
        String content = Files.readString(outcome.generatedFile());
        assertTrue(content.contains("Noi dung kiem tra Ansible"));
        assertEquals(1, outcome.broadcastJobs().size());
    }

    @Test
    void fileTransferUploadAndDownloadAreRecordedInAuditLogs() throws Exception {
        // 1. Client uploads backup
        Path uploadSrc = tempDir.resolve("uploadSrc.txt");
        Files.writeString(uploadSrc, "Hello LAN Backup World");
        byte[] payload = Files.readAllBytes(uploadSrc);
        String sha = Hashing.sha256(uploadSrc);
        var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                deviceId, token, "docs/hello.txt", payload.length, sha, "1");

        try (var socket = new Socket("127.0.0.1", fileServer.port())) {
            var codec = new FileTransferCodec();
            codec.writeHeader(socket.getOutputStream(), header);
            var ready = codec.readResult(socket.getInputStream());
            assertEquals(TransferResult.Status.READY, ready.status());
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            var stored = codec.readResult(socket.getInputStream());
            assertEquals(TransferResult.Status.STORED, stored.status());
        }

        // Verify audit log for BACKUP_UPLOAD
        var recent = auditLogs.findRecent(10);
        assertTrue(recent.stream().anyMatch(e -> "BACKUP_UPLOAD".equals(e.action()) && e.details().contains("docs/hello.txt")));

        // 2. Register a mock agent session to receive RESTORE_AVAILABLE
        BlockingQueue<ControlEnvelope> receivedEnvelopes = new LinkedBlockingQueue<>();
        var agentSocket = new Socket("127.0.0.1", controlServer.port());
        var controlCodec = new LengthPrefixedJsonCodec();
        // Register agent
        var regPayload = JsonNodeFactory.instance.objectNode().put("token", token);
        controlCodec.write(agentSocket.getOutputStream(), new ControlEnvelope(MessageType.REGISTER, "req-1", deviceId, regPayload));
        var ack = controlCodec.read(agentSocket.getInputStream());
        assertEquals(MessageType.REGISTER_ACK, ack.type());

        Thread listenerThread = new Thread(() -> {
            try {
                while (!agentSocket.isClosed()) {
                    var env = controlCodec.read(agentSocket.getInputStream());
                    receivedEnvelopes.add(env);
                }
            } catch (Exception ignored) {}
        });
        listenerThread.setDaemon(true);
        listenerThread.start();

        // 3. Request restore from server
        var versions = new JdbcBackupRepository(database).versions(deviceId, "docs/hello.txt");
        assertFalse(versions.isEmpty());
        long vId = versions.getFirst().id();

        var job = restores.request(vId, deviceId, "admin");
        assertNotNull(job);

        // Client receives RESTORE_AVAILABLE
        var restoreEnvelope = receivedEnvelopes.poll(5, TimeUnit.SECONDS);
        assertNotNull(restoreEnvelope);
        assertEquals(MessageType.RESTORE_AVAILABLE, restoreEnvelope.type());

        // Agent downloads and recovers file
        Path clientProtectedRoot = tempDir.resolve("agent-protected");
        Files.createDirectories(clientProtectedRoot);
        var restoreHandler = new RestoreHandler(clientProtectedRoot, "127.0.0.1", fileServer.port(), deviceId, token);
        Path restoredPath = restoreHandler.restore(restoreEnvelope);

        assertTrue(Files.isRegularFile(restoredPath));
        assertEquals(new String(payload, StandardCharsets.UTF_8), Files.readString(restoredPath));

        // Verify audit log for RESTORE_DOWNLOAD (wait briefly for server worker to complete)
        boolean hasRestoreDownload = false;
        for (int i = 0; i < 50; i++) {
            recent = auditLogs.findRecent(20);
            if (recent.stream().anyMatch(e -> "RESTORE_DOWNLOAD".equals(e.action()) && e.details().contains("docs/hello.txt"))) {
                hasRestoreDownload = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(hasRestoreDownload, "Audit logs should contain RESTORE_DOWNLOAD: " + recent);

        agentSocket.close();
    }

    @Test
    void isolationFeatureLogsEventAndBlocksAccess() throws Exception {
        // Toggle isolation to true
        boolean isolated = provisioning.toggleIsolated(deviceId, sessions);
        assertTrue(isolated);

        var recent = auditLogs.findRecent(5);
        assertTrue(recent.stream().anyMatch(e -> "DEVICE_ISOLATED".equals(e.action()) && deviceId.equals(e.deviceId())));

        var incs = incidentRepo.findAll(deviceId);
        assertTrue(incs.stream().anyMatch(i -> i.riskType() == RiskType.ISOLATED && i.status() == RiskEvent.Status.OPEN));
        assertFalse(incs.stream().anyMatch(i -> i.riskType() == RiskType.CONNECTION_LOST
                        && i.status() == RiskEvent.Status.OPEN),
                "Administrator isolation is intentional and must not be reported as a connection failure");

        // Attempt upload while isolated -> must be rejected
        Path testFile = tempDir.resolve("testFile.txt");
        Files.writeString(testFile, "test data");
        byte[] payload = Files.readAllBytes(testFile);
        String sha = Hashing.sha256(testFile);
        var header = new FileTransferHeader(FileOperation.BACKUP_UPLOAD, UUID.randomUUID().toString(),
                deviceId, token, "test.txt", payload.length, sha, "1");

        try (var socket = new Socket("127.0.0.1", fileServer.port())) {
            var codec = new FileTransferCodec();
            codec.writeHeader(socket.getOutputStream(), header);
            var res = codec.readResult(socket.getInputStream());
            assertEquals(TransferResult.Status.REJECTED, res.status());
        }

        // Un-isolate
        boolean next = provisioning.toggleIsolated(deviceId, sessions);
        assertFalse(next);

        recent = auditLogs.findRecent(5);
        assertTrue(recent.stream().anyMatch(e -> "DEVICE_UNISOLATED".equals(e.action()) && deviceId.equals(e.deviceId())));

        incs = incidentRepo.findAll(deviceId);
        assertTrue(incs.stream().anyMatch(i -> i.riskType() == RiskType.ISOLATED && i.status() == RiskEvent.Status.RESOLVED));
    }

    @Test
    void dashboardIncidentsDisplayTimestamps() throws Exception {
        incidentService.record(deviceId, new RiskEvent(RiskType.DISK_FULL, RiskEvent.Status.OPEN, Clock.systemUTC().instant(), "Disk 85%"));
        var snapshot = dataService.load();
        assertFalse(snapshot.incidents().isEmpty());
        // Verify format starts with [yyyy-MM-dd HH:mm:ss]
        String row = snapshot.incidents().getFirst();
        assertTrue(row.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\].*"), "Incident row must have timestamp: " + row);
    }
}
