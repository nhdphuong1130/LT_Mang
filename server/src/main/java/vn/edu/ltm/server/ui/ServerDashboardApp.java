package vn.edu.ltm.server.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
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
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;

public final class ServerDashboardApp extends Application {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("server-admin").factory());
    private final DashboardViewModel model = new DashboardViewModel(action -> {
        if (Platform.isFxApplicationThread()) action.run(); else Platform.runLater(action);
    });
    private Stage stage;
    private Services services;
    private Timeline refresh;
    private volatile boolean stopping;

    @Override public void start(Stage stage) {
        this.stage = stage;
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
                Platform.runLater(this::showLogin);
            } catch (Exception failure) {
                Platform.runLater(() -> stage.setScene(new Scene(
                        new Label("Server startup failed: " + failure.getMessage()), 820, 540)));
            }
        });
    }
    private void showLogin() {
        try {
            if (refresh != null) { refresh.stop(); refresh = null; }
            var loader = new FXMLLoader(ServerDashboardApp.class.getResource("login.fxml"));
            var scene = new Scene(loader.load(), 820, 540);
            scene.getStylesheets().add(ServerDashboardApp.class.getResource("dashboard.css").toExternalForm());
            LoginController controller = loader.getController();
            controller.configure(services.auth, model, worker, this::showDashboard);
            stage.setScene(scene);
            if (getParameters().getRaw().contains("--smoke")) {
                var close = new Timeline(new KeyFrame(Duration.seconds(2), event -> stage.close()));
                close.play();
            }
        } catch (Exception failure) { stage.setScene(new Scene(new Label("Login UI failed: " + failure.getMessage()))); }
    }
    private void showDashboard() {
        try {
            var loader = new FXMLLoader(ServerDashboardApp.class.getResource("dashboard.fxml"));
            var scene = new Scene(loader.load(), 920, 640);
            scene.getStylesheets().add(ServerDashboardApp.class.getResource("dashboard.css").toExternalForm());
            DashboardController controller = loader.getController();
            controller.configure(model, services.data, services.provisioning, services.restores,
                    services.auth, worker, () -> worker.submit(() -> {
                        services.auth.logout(); model.logout(); Platform.runLater(this::showLogin);
                    }));
            stage.setScene(scene);
            refresh = new Timeline(new KeyFrame(Duration.seconds(5), event -> controller.refresh()));
            refresh.setCycleCount(Timeline.INDEFINITE); refresh.play();
        } catch (Exception failure) { stage.setScene(new Scene(new Label("Dashboard UI failed: " + failure.getMessage()))); }
    }
    @Override public void stop() {
        stopping = true;
        if (refresh != null) refresh.stop();
        worker.shutdownNow();
        if (services != null) services.close();
    }

    private static final class Services implements AutoCloseable {
        private final Database database;
        private final DeviceRepository devices;
        private final AdminAuthService auth;
        private final DeviceProvisioningService provisioning;
        private final DashboardDataService data;
        private final RestoreService restores;
        private final ControlTcpServer control;
        private final FileTransferServer files;
        Services(Path dataDir, String bind, int controlPort, int filePort) throws Exception {
            database = new Database(dataDir.resolve("server.db")); database.initialize();
            devices = new JdbcDeviceRepository(database);
            devices.markAllOffline();
            provisioning = new DeviceProvisioningService(devices);
            auth = new AdminAuthService(new JdbcAdminUserRepository(database));
            var sessions = new SessionRegistry(devices);
            var metrics = new JdbcMetricsRepository(database);
            var incidents = new JdbcIncidentRepository(database);
            data = new DashboardDataService(database, devices, metrics, incidents);
            restores = new RestoreService(new JdbcRestoreJobRepository(database), auth, sessions, Clock.systemUTC());
            var backups = new BackupService(new JdbcBackupRepository(database), new StoragePathResolver(dataDir.resolve("backup-storage")));
            control = new ControlTcpServer(new ServerConfig(bind, controlPort), provisioning, sessions,
                    Clock.systemUTC(), new MetricsMessageHandler(metrics, new IncidentService(incidents), sessions));
            files = new FileTransferServer(bind, filePort, provisioning, backups, restores);
        }
        void start() throws Exception {
            control.start();
            try { files.start(); }
            catch (Exception failure) { control.close(); throw failure; }
        }
        @Override public void close() { files.close(); control.close(); }
    }
}
