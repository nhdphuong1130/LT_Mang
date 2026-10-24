package vn.edu.ltm.agent.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import vn.edu.ltm.agent.alert.ConnectionStatusNotifier;
import vn.edu.ltm.agent.alert.JavaFxAlertSink;
import vn.edu.ltm.agent.backup.*;
import vn.edu.ltm.agent.metrics.OshiMetricsCollector;
import vn.edu.ltm.agent.monitoring.MonitoringCoordinator;
import vn.edu.ltm.agent.network.ControlClient;
import vn.edu.ltm.agent.persistence.AgentDatabase;
import vn.edu.ltm.agent.restore.RestoreHandler;
import vn.edu.ltm.agent.risk.*;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.lifecycle.AsyncCloseWorkflow;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.*;

public final class AgentApp extends Application {
    private final ExecutorService setup = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("agent-setup").factory());
    private final AgentViewModel model = new AgentViewModel(Platform::runLater);
    private final AgentSettingsStore settingsStore = new AgentSettingsStore(Path.of(System.getProperty("user.home"), ".ltm-agent"));
    private AgentController controller;
    private final RuntimeSwitch<AgentRuntime> runtimes = new RuntimeSwitch<>();
    private AsyncCloseWorkflow closeWorkflow;
    private Stage stage;
    private Timeline refresh;

    @Override public void start(Stage stage) throws IOException {
        Platform.setImplicitExit(false);
        this.stage = stage;
        var loader = new FXMLLoader(AgentApp.class.getResource("agent.fxml"));
        var scene = new Scene(loader.load(), 880, 520);
        scene.getStylesheets().add(AgentApp.class.getResource("agent.css").toExternalForm());
        controller = loader.getController();
        controller.setOnStart(this::startRuntime);
        try {
            settingsStore.load().ifPresent(saved -> {
                controller.load(saved);
                if (!getParameters().getRaw().contains("--smoke")) {
                    startRuntime(saved);
                }
            });
        } catch (Exception failure) { controller.error("Cannot read saved configuration: " + failure.getMessage()); }
        refresh = new Timeline(new KeyFrame(Duration.seconds(1), event -> controller.refresh(model)));
        refresh.setCycleCount(Timeline.INDEFINITE); refresh.play();
        closeWorkflow = new AsyncCloseWorkflow(() -> Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("agent-shutdown").factory()), Platform::runLater, () -> {
            setup.shutdownNow();
            runtimes.close();
            if (!setup.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Agent setup worker did not stop");
        }, () -> {
            if (refresh != null) refresh.stop();
            stage.getScene().getRoot().setDisable(true);
            stage.setTitle("LAN Backup Agent — closing…");
        }, () -> {
            stage.setOnCloseRequest(null);
            if (getParameters().getRaw().contains("--smoke")) System.out.println("AGENT_SMOKE_SHUTDOWN_COMPLETE");
            Platform.exit();
        }, failure -> {
            stage.getScene().getRoot().setDisable(false);
            stage.setTitle("LAN Backup Agent — close failed; retry to close");
            controller.error("Could not stop Agent: " + failure.getMessage());
        });
        stage.setOnCloseRequest(event -> { event.consume(); closeWorkflow.request(); });
        stage.setTitle("LAN Backup Agent"); stage.setScene(scene); stage.show();
        if (getParameters().getRaw().contains("--smoke")) {
            var close = new Timeline(new KeyFrame(Duration.seconds(2), event ->
                    stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))));
            close.play();
        }
    }

    private void startRuntime(AgentSettings settings) {
        setup.submit(() -> {
            try {
                runtimes.replace(() -> {
                    settingsStore.save(settings);
                    var replacement = new AgentRuntime(settings, model, settingsStore.stateDatabasePath(settings));
                    try { replacement.start(); return replacement; }
                    catch (Exception failure) { replacement.close(); throw failure; }
                });
                Platform.runLater(controller::started);
            } catch (Exception failure) {
                Platform.runLater(() -> controller.error("Cannot start Agent: " + failure.getMessage()));
            }
        });
    }

    @Override public void stop() {
        if (refresh != null) refresh.stop();
        if (closeWorkflow != null) closeWorkflow.requestUntilComplete();
    }

    private static final class AgentRuntime implements AutoCloseable {
        private final AgentSettings settings;
        private final AgentViewModel model;
        private final Path databasePath;
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofPlatform().daemon().name("agent-backup", 0).factory());
        private final ExecutorService fileTasks = Executors.newVirtualThreadPerTaskExecutor();
        private ControlClient control;
        private MonitoringCoordinator monitoring;
        private ProtectedFolderWatcher watcher;
        private volatile boolean stopping;
        private boolean fullyClosed;

        AgentRuntime(AgentSettings settings, AgentViewModel model, Path databasePath) {
            this.settings = settings; this.model = model; this.databasePath = databasePath;
        }

        void start() throws IOException, SQLException {
            Path root = settings.protectedRoot();
            var database = new AgentDatabase(databasePath);
            database.initialize();
            var queue = new JdbcBackupQueueRepository(database);
            var stability = new FileStabilityChecker(root);
            var restore = new RestoreHandler(root, settings.serverHost(), settings.filePort(), settings.deviceId(), settings.token());
            control = new ControlClient(settings.controlConfig());
            control.setInboundHandler(message -> {
                if (message.type() == MessageType.RESTORE_AVAILABLE) fileTasks.submit(() -> {
                    try { restore.restore(message); }
                    catch (IOException | RuntimeException failure) {
                        model.warn("Restore failed: " + failure.getMessage());
                    }
                });
            });
            var collector = new OshiMetricsCollector();
            monitoring = new MonitoringCoordinator(settings.deviceId(), control,
                    () -> { var sample = collector.collect(); model.updateMetrics(sample); return sample; },
                    new RiskDetector(), new JavaFxAlertSink(), Clock.systemUTC());
            watcher = new ProtectedFolderWatcher(root);
            watcher.start(path -> fileTasks.submit(() -> enqueueStable(path, root, stability, queue)));
            control.start();
            monitoring.start();
            fileTasks.submit(() -> {
                try { new StartupReconciler(root, queue).reconcile(); }
                catch (IOException | SQLException | InterruptedException failure) {
                    if (!stopping) model.warn("Initial folder scan failed: " + failure.getMessage());
                }
            });
            var worker = new BackupWorker(queue,
                    new BackupUploader(root, settings.serverHost(), settings.filePort(), settings.deviceId(), settings.token()), root);
            scheduler.scheduleWithFixedDelay(() -> {
                if (stopping) return;
                try { worker.runOnce(); }
                catch (SQLException failure) { model.warn("Backup queue error: " + failure.getMessage()); }
            }, 3, 3, TimeUnit.SECONDS);
            var disconnected = new ConnectionStatusNotifier(() -> {
                model.warn("Server mất liên lạc");
                Platform.runLater(() -> {
                    var alert = new Alert(Alert.AlertType.WARNING, "Server mất liên lạc");
                    alert.setHeaderText("Agent disconnected"); alert.show();
                });
            });
            scheduler.scheduleAtFixedRate(() -> {
                boolean online = control.isRegistered();
                disconnected.poll(online);
                model.setConnected(online);
                try (var connection = database.openConnection(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM backup_queue WHERE status IN ('PENDING','RETRY')")) {
                    if (rows.next()) model.setQueued(rows.getInt(1));
                } catch (SQLException failure) { model.warn("Queue status unavailable"); }
            }, 0, 1, TimeUnit.SECONDS);
        }

        private void enqueueStable(Path path, Path root, FileStabilityChecker stability, BackupQueueRepository queue) {
            try {
                var stable = stability.awaitStable(path, FileStabilityChecker.DEFAULT_STABLE_FOR, FileStabilityChecker.DEFAULT_TIMEOUT);
                var eligible = new BackupEligibility().evaluate(root, stable.path());
                if (eligible.risky()) {
                    model.warn("CẢNH BÁO FILE RỦI RO (Đã chặn): " + eligible.relativePath() + " — " + eligible.reason());
                    if (monitoring != null) {
                        monitoring.reportRiskyFile(eligible.relativePath(), eligible.reason());
                    }
                    return;
                }
                if (eligible.eligible()) queue.enqueueOrUpdate(new BackupCandidate(eligible.relativePath(), stable.size(),
                        stable.modifiedTime().toInstant(), stable.sha256()));
            } catch (IOException | InterruptedException | SQLException failure) {
                if (!stopping) model.warn("Could not queue changed file: " + failure.getMessage());
            }
        }

        @Override public synchronized void close() {
            if (fullyClosed) return;
            stopping = true;
            scheduler.shutdownNow();
            if (control != null) control.close();
            if (monitoring != null) monitoring.close();
            IOException watcherFailure = null;
            if (watcher != null) try { watcher.close(); } catch (IOException failure) { watcherFailure = failure; }
            fileTasks.shutdownNow();
            try {
                boolean schedulerStopped = scheduler.awaitTermination(10, TimeUnit.SECONDS);
                boolean fileTasksStopped = fileTasks.awaitTermination(10, TimeUnit.SECONDS);
                boolean monitoringStopped = monitoring == null || monitoring.awaitStopped(java.time.Duration.ofSeconds(10));
                boolean controlStopped = control == null || control.awaitStopped(java.time.Duration.ofSeconds(10));
                boolean watcherStopped = watcher == null || watcher.awaitStopped(java.time.Duration.ofSeconds(10));
                if (!schedulerStopped || !fileTasksStopped || !monitoringStopped || !controlStopped || !watcherStopped)
                    throw new IllegalStateException("Agent workers did not stop");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping Agent workers", interrupted);
            }
            if (watcherFailure != null) throw new IllegalStateException("Watcher did not close", watcherFailure);
            fullyClosed = true;
        }
    }
}
