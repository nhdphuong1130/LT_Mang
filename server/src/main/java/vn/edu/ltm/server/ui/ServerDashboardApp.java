package vn.edu.ltm.server.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
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
import vn.edu.ltm.common.lifecycle.AsyncCloseWorkflow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;

public final class ServerDashboardApp extends Application {
    static final long DASHBOARD_REFRESH_MILLIS = 500;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("server-admin").factory());
    private final DashboardViewModel model = new DashboardViewModel(action -> {
        if (Platform.isFxApplicationThread()) action.run(); else Platform.runLater(action);
    });
    private Stage stage;
    private volatile Services services;
    private Timeline refresh;
    private volatile boolean stopping;
    private AsyncCloseWorkflow closeWorkflow;

    @Override public void start(Stage stage) {
        Platform.setImplicitExit(false);
        this.stage = stage;
        closeWorkflow = new AsyncCloseWorkflow(() -> Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("server-shutdown").factory()), Platform::runLater, () -> {
            stopping = true;
            worker.shutdownNow();
            if (!worker.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Server admin worker did not stop");
            if (services != null) services.close();
        }, () -> {
            if (refresh != null) refresh.stop();
            stage.setTitle("LAN Backup Server — closing…");
            stage.setScene(new Scene(new Label("Closing server…"), 820, 540));
        }, () -> {
            stage.setOnCloseRequest(null);
            if (getParameters().getRaw().contains("--smoke")) System.out.println("SERVER_SMOKE_SHUTDOWN_COMPLETE");
            Platform.exit();
        }, failure -> {
            stage.setTitle("LAN Backup Server — close failed; retry to close");
            stage.setScene(new Scene(new Label("Shutdown failed: " + failure.getMessage() + " — close again to retry"), 820, 540));
        });
        stage.setOnCloseRequest(event -> { event.consume(); closeWorkflow.request(); });
        stage.setTitle("LAN Backup Server");
        stage.setScene(new Scene(new Label("Starting server…"), 820, 540));
        stage.show();
        worker.submit(() -> {
            try {
                Path dataDir = Path.of(System.getProperty("ltm.server.dataDir",
                        Path.of(System.getProperty("user.home"), ".ltm-server").toString()));
                int controlPort = Integer.getInteger("ltm.server.controlPort", 5050);
                int filePort = Integer.getInteger("ltm.server.filePort", 5051);
                String bind = System.getProperty("ltm.server.bind", "0.0.0.0");
                services = new Services(dataDir, bind, controlPort, filePort);
                services.start();
                if (stopping) { services.close(); return; }
                if (!stopping) Platform.runLater(this::showLogin);
            } catch (Exception failure) {
                failure.printStackTrace();
                if (!stopping) Platform.runLater(() -> stage.setScene(new Scene(
                        new Label("Server startup failed: " + failure.getMessage()), 820, 540)));
            }
        });
    }
    private void showLogin() {
        if (stopping) return;
        try {
            if (refresh != null) { refresh.stop(); refresh = null; }
            var loader = new FXMLLoader(ServerDashboardApp.class.getResource("login.fxml"));
            var scene = new Scene(loader.load(), 820, 540);
            scene.getStylesheets().add(ServerDashboardApp.class.getResource("dashboard.css").toExternalForm());
            LoginController controller = loader.getController();
            controller.configure(services.auth, model, worker, this::showDashboard);
            stage.setScene(scene);
            if (getParameters().getRaw().contains("--smoke")) {
                var close = new Timeline(new KeyFrame(Duration.seconds(2), event ->
                        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))));
                close.play();
            }
        } catch (Exception failure) { stage.setScene(new Scene(new Label("Login UI failed: " + failure.getMessage()))); }
    }
    private void showDashboard() {
        if (stopping) return;
        try {
            var loader = new FXMLLoader(ServerDashboardApp.class.getResource("dashboard.fxml"));
            var scene = new Scene(loader.load(), 1380, 820);
            scene.getStylesheets().add(ServerDashboardApp.class.getResource("dashboard.css").toExternalForm());
            DashboardController controller = loader.getController();
            controller.configure(model, services.data, services.provisioning, services.restores,
                    services.auth, services.sessions, services.ansibleSync, services.calibrationStore,
                    worker, () -> worker.submit(() -> {
                        services.auth.logout(); model.logout(); Platform.runLater(this::showLogin);
                    }));
            stage.setScene(scene);
            stage.setMaximized(true);
            refresh = new Timeline(new KeyFrame(Duration.millis(DASHBOARD_REFRESH_MILLIS), event -> controller.refresh()));
            refresh.setCycleCount(Timeline.INDEFINITE); refresh.play();
        } catch (Exception failure) { stage.setScene(new Scene(new Label("Dashboard UI failed: " + failure.getMessage()))); }
    }
    @Override public void stop() {
        if (refresh != null) refresh.stop();
        if (closeWorkflow != null) closeWorkflow.requestUntilComplete();
    }

    static final class Services implements AutoCloseable {
        private final Database database;
        private final DeviceRepository devices;
        private final AdminAuthService auth;
        private final DeviceProvisioningService provisioning;
        private final DashboardDataService data;
        private final RestoreService restores;
        private final SessionRegistry sessions;
        private final vn.edu.ltm.server.ansible.AnsibleSyncService ansibleSync;
        private final DistanceCalibrationStore calibrationStore;
        private final ControlTcpServer control;
        private final FileTransferServer files;
        private final ServerListeners listeners;
        Services(Path dataDir, String bind, int controlPort, int filePort) throws Exception {
            database = new Database(dataDir.resolve("server.db")); database.initialize();
            var auditLogs = new vn.edu.ltm.server.audit.JdbcAuditLogRepository(database);
            devices = new JdbcDeviceRepository(database);
            devices.markAllOffline();
            provisioning = new DeviceProvisioningService(devices);
            auth = new AdminAuthService(new JdbcAdminUserRepository(database));
            sessions = new SessionRegistry(devices);
            var metrics = new JdbcMetricsRepository(database);
            var incidents = new JdbcIncidentRepository(database);
            var incidentService = new vn.edu.ltm.server.incident.IncidentService(incidents);
            provisioning.setAuditAndIncidents(auditLogs, incidentService);
            sessions.setAuditAndIncidents(auditLogs, incidentService);
            var directLinks = new vn.edu.ltm.server.metrics.DirectLinkRegistry();
            var stationCollector = new vn.edu.ltm.server.metrics.LinuxP2pStationCollector();
            calibrationStore = new vn.edu.ltm.server.metrics.DistanceCalibrationStore(
                    dataDir.resolve("distance-calibration.properties"));
            var distanceTracker = new RealtimeDistanceTracker(directLinks, stationCollector::collect,
                    Clock.systemUTC(), calibrationStore::activeModel);
            data = new DashboardDataService(database, devices, metrics, incidents, sessions, distanceTracker, auditLogs);
            var backups = new BackupService(new JdbcBackupRepository(database), new StoragePathResolver(dataDir.resolve("backup-storage")));
            restores = new RestoreService(new JdbcRestoreJobRepository(database), auth, sessions, Clock.systemUTC(),
                    java.time.Duration.ofMinutes(2), backups);
            restores.setProvisioning(provisioning);
            Path playbookDir = Path.of(System.getProperty("ltm.server.ansibleDir", "ansible")).toAbsolutePath();
            ansibleSync = new vn.edu.ltm.server.ansible.AnsibleSyncService(dataDir, playbookDir, provisioning, restores);
            var metricsHandler = new MetricsMessageHandler(metrics, incidentService, sessions,
                    devices, distanceTracker, directLinks);
            var rangingConfig = new vn.edu.ltm.common.ranging.AcousticRangingConfigLoader().load();
            var audioEndpoint = new vn.edu.ltm.common.ranging.JavaSoundAudioEndpoint(rangingConfig);
            var ranging = new vn.edu.ltm.server.ranging.RangingCoordinator(
                    sessions::send,
                    sessions::activeDeviceIds,
                    audioEndpoint,
                    rangingConfig,
                    distanceTracker::onAcousticSample
            );
            control = new ControlTcpServer(new ServerConfig(bind, controlPort), provisioning, sessions,
                    Clock.systemUTC(), (deviceId, message) -> {
                        if (message.type() == vn.edu.ltm.common.protocol.MessageType.FILE_LIST_REQUEST) {
                            handleClientFileListRequest(deviceId, message, database, sessions);
                        } else if (message.type() == vn.edu.ltm.common.protocol.MessageType.PULL_DATA_REQUEST) {
                            handleClientPullDataRequest(deviceId, message, database, restores, provisioning, sessions, auditLogs);
                        } else {
                            metricsHandler.accept(deviceId, message);
                            ranging.handleInboundMessage(deviceId, message);
                        }
                    });
            files = new FileTransferServer(bind, filePort, provisioning, backups, restores, auditLogs);
            listeners = new ServerListeners(new ServerListeners.Listener() {
                public void start() throws Exception { files.start(); }
                public void close() { files.close(); }
            }, new ServerListeners.Listener() {
                public void start() throws Exception {
                    control.start();
                    ranging.start();
                }
                public void close() {
                    try { ranging.close(); } catch (Exception ignored) {}
                    control.close();
                }
            });
        }
        void start() throws Exception { listeners.start(); }
        @Override public void close() { listeners.close(); }

        private static void handleClientFileListRequest(String deviceId, vn.edu.ltm.common.protocol.ControlEnvelope request,
                                                       Database database, vn.edu.ltm.server.session.SessionRegistry sessions) {
            var filesArray = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            String sql = """
                    SELECT f.relative_path, v.id, v.size_bytes, v.sha256, v.created_at
                    FROM backup_files f JOIN backup_versions v ON v.file_id = f.id
                    WHERE f.device_id = ?
                    ORDER BY f.relative_path, v.id DESC
                    """;
            try (var conn = database.openConnection(); var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, deviceId);
                try (var rs = stmt.executeQuery()) {
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    while (rs.next()) {
                        String path = rs.getString(1);
                        if (seen.add(path)) {
                            var obj = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                            obj.put("relativePath", path);
                            obj.put("versionId", rs.getLong(2));
                            obj.put("size", rs.getLong(3));
                            obj.put("sha256", rs.getString(4));
                            obj.put("createdAt", rs.getString(5));
                            filesArray.add(obj);
                        }
                    }
                }
            } catch (Exception ex) {
                System.getLogger("FileList").log(System.Logger.Level.WARNING, "Failed to query files for " + deviceId, ex);
            }
            var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            payload.set("files", filesArray);
            sessions.send(deviceId, new vn.edu.ltm.common.protocol.ControlEnvelope(
                    vn.edu.ltm.common.protocol.MessageType.FILE_LIST_RESPONSE,
                    request.requestId(),
                    deviceId,
                    payload));
        }

        private static void handleClientPullDataRequest(String deviceId, vn.edu.ltm.common.protocol.ControlEnvelope request,
                                                       Database database, vn.edu.ltm.server.restore.RestoreService restores,
                                                       vn.edu.ltm.server.device.DeviceProvisioningService provisioning,
                                                       vn.edu.ltm.server.session.SessionRegistry sessions,
                                                       vn.edu.ltm.server.audit.AuditLogRepository auditLogs) {
            java.time.Instant now = java.time.Instant.now();
            if (provisioning != null && !provisioning.isRestoreAllowed(deviceId)) {
                if (auditLogs != null) {
                    auditLogs.log(now, deviceId, "PULL_DATA_BLOCKED", deviceId,
                            "Client bị từ chối kéo dữ liệu: Quyền nhận file đang bị khóa hoặc thiết bị đang bị cô lập");
                }
                if (sessions != null) {
                    var errObj = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("error", "Quyền nhận file từ Server đang bị Chặn hoặc thiết bị đang bị Cô lập!");
                    sessions.send(deviceId, new vn.edu.ltm.common.protocol.ControlEnvelope(
                            vn.edu.ltm.common.protocol.MessageType.ERROR,
                            request.requestId(),
                            deviceId,
                            errObj));
                }
                return;
            }
            var payload = request.payload();
            String target = payload != null && payload.has("target") ? payload.get("target").asText("all") : "all";
            String targetRelPath = payload != null && payload.has("relativePath") ? payload.get("relativePath").asText("") : "";

            if (auditLogs != null) {
                auditLogs.log(now, deviceId, "CLIENT_PULL_DATA", deviceId,
                        "Client yêu cầu kéo dữ liệu: " + ("all".equalsIgnoreCase(target) || targetRelPath.isEmpty() ? "Tất cả file đã sao lưu" : targetRelPath));
            }

            if ("all".equalsIgnoreCase(target) || targetRelPath.isEmpty()) {
                String sql = """
                        SELECT v.id FROM backup_files f JOIN backup_versions v ON v.file_id = f.id
                        WHERE f.device_id = ?
                        GROUP BY f.relative_path
                        HAVING v.id = MAX(v.id)
                        """;
                try (var conn = database.openConnection(); var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, deviceId);
                    try (var rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            long versionId = rs.getLong(1);
                            restores.requestForDevice(versionId, deviceId);
                        }
                    }
                } catch (Exception ex) {
                    System.getLogger("PullData").log(System.Logger.Level.WARNING, "Failed to pull all files for " + deviceId, ex);
                }
            } else {
                String sql = """
                        SELECT v.id FROM backup_files f JOIN backup_versions v ON v.file_id = f.id
                        WHERE f.device_id = ? AND f.relative_path = ?
                        ORDER BY v.id DESC LIMIT 1
                        """;
                try (var conn = database.openConnection(); var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, deviceId);
                    stmt.setString(2, targetRelPath);
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            long versionId = rs.getLong(1);
                            restores.requestForDevice(versionId, deviceId);
                        }
                    }
                } catch (Exception ex) {
                    System.getLogger("PullData").log(System.Logger.Level.WARNING, "Failed to pull file " + targetRelPath + " for " + deviceId, ex);
                }
            }
        }
    }
}
