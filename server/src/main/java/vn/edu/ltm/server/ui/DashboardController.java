package vn.edu.ltm.server.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import vn.edu.ltm.common.model.DxdiagProfile;
import vn.edu.ltm.server.ansible.AnsibleSyncService;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.restore.RestoreService;
import vn.edu.ltm.server.session.SessionRegistry;
import vn.edu.ltm.server.metrics.DistanceCalibrationSession;
import vn.edu.ltm.server.metrics.DistanceCalibrationStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public final class DashboardController {
    public record BackupTreeNode(String label, String deviceId, String pathPrefix) {
        @Override public String toString() { return label; }
    }
    @FXML private ListView<String> devicesList, incidentsList;
    @FXML private FlowPane sphereContainer;
    @FXML private TreeView<BackupTreeNode> backupTree;
    @FXML private ListView<DashboardDataService.VersionRow> backupsList;
    @FXML private TextField deviceIdField, displayNameField, platformField;
    @FXML private ComboBox<String> targetDeviceCombo;
    @FXML private CheckBox allowBackupCheck, allowRestoreCheck;
    @FXML private TextArea dxdiagSummaryArea;
    @FXML private Label countsLabel, messageLabel, selectedFolderLabel, dxdiagTitleLabel;
    @FXML private Button restoreButton, restoreFolderButton, broadcastAllButton, isolateButton;

    private DashboardViewModel model;
    private DashboardDataService data;
    private DeviceProvisioningService provisioning;
    private RestoreService restores;
    private AdminAuthService auth;
    private SessionRegistry sessions;
    private AnsibleSyncService ansibleSync;
    private DistanceCalibrationStore calibrationStore;
    private ExecutorService worker;
    private Runnable logout;
    private DashboardDataService.Snapshot latestSnapshot;
    private String selectedDeviceId;
    private String selectedPathPrefix = "";
    private String selectedSphereDeviceId;
    private String inspectorDeviceId;
    private boolean rebuildingTree;
    private List<DashboardDataService.DeviceBackupFolder> lastTreeFolders;
    private int lastTreeVersionCount = -1;
    private String latestBroadcastMessage;

    public void configure(DashboardViewModel model, DashboardDataService data,
                          DeviceProvisioningService provisioning, RestoreService restores,
                          AdminAuthService auth, ExecutorService worker, Runnable logout) {
        configure(model, data, provisioning, restores, auth, null, null, worker, logout);
    }

    public void configure(DashboardViewModel model, DashboardDataService data,
                          DeviceProvisioningService provisioning, RestoreService restores,
                          AdminAuthService auth, SessionRegistry sessions, ExecutorService worker, Runnable logout) {
        configure(model, data, provisioning, restores, auth, sessions, null, worker, logout);
    }

    public void configure(DashboardViewModel model, DashboardDataService data,
                          DeviceProvisioningService provisioning, RestoreService restores,
                          AdminAuthService auth, SessionRegistry sessions, AnsibleSyncService ansibleSync,
                          ExecutorService worker, Runnable logout) {
        configure(model, data, provisioning, restores, auth, sessions, ansibleSync, null, worker, logout);
    }

    public void configure(DashboardViewModel model, DashboardDataService data,
                          DeviceProvisioningService provisioning, RestoreService restores,
                          AdminAuthService auth, SessionRegistry sessions, AnsibleSyncService ansibleSync,
                          DistanceCalibrationStore calibrationStore, ExecutorService worker, Runnable logout) {
        this.model = model; this.data = data; this.provisioning = provisioning; this.restores = restores;
        this.auth = auth; this.sessions = sessions; this.ansibleSync = ansibleSync;
        this.calibrationStore = calibrationStore;
        this.worker = worker; this.logout = logout;
        if (devicesList != null) {
            devicesList.getSelectionModel().selectedItemProperty().addListener((unused, prev, row) -> {
                if (row != null && !row.isBlank()) {
                    String devId = row.split(" — ")[0].trim();
                    if (!devId.equals(selectedSphereDeviceId)) {
                        selectedSphereDeviceId = devId;
                        updateSphereInspector();
                        if (latestSnapshot != null) renderSpheres(latestSnapshot.clientSpheres());
                    }
                }
            });
        }
        if (backupTree != null) {
            backupTree.getSelectionModel().selectedItemProperty().addListener((unused, prev, item) -> {
                if (rebuildingTree) return;
                if (item == null || item.getValue() == null) {
                    selectedDeviceId = null;
                    selectedPathPrefix = "";
                } else {
                    selectedDeviceId = item.getValue().deviceId();
                    selectedPathPrefix = item.getValue().pathPrefix();
                }
                applyBackupFilter();
                updateRestoreButtons();
            });
        }
        backupsList.getSelectionModel().selectedItemProperty().addListener((unused, previous, row) -> {
            if (row == null) model.clearSelection();
            else {
                model.selectVersion(row.id(), row.deviceId(), row.relativePath(), row.createdAt());
                if (targetDeviceCombo != null && (targetDeviceCombo.getValue() == null || targetDeviceCombo.getValue().isBlank())) {
                    targetDeviceCombo.setValue(row.deviceId());
                }
            }
            updateRestoreButtons();
        });
        if (targetDeviceCombo != null) {
            targetDeviceCombo.valueProperty().addListener((unused, prev, next) -> updateRestoreButtons());
        }
        refresh();
    }

    private void updateRestoreButtons() {
        String target = resolveTargetDevice();
        if (restoreButton != null) {
            restoreButton.setDisable(!model.canRestore() || target == null);
        }
        if (broadcastAllButton != null) {
            broadcastAllButton.setDisable(!model.canRestore());
        }
        if (restoreFolderButton != null) {
            restoreFolderButton.setDisable(model.adminUsername() == null || selectedDeviceId == null || target == null);
        }
    }

    private String resolveTargetDevice() {
        if (targetDeviceCombo != null && targetDeviceCombo.getValue() != null && !targetDeviceCombo.getValue().isBlank()) {
            return targetDeviceCombo.getValue().trim();
        }
        if (model.selectedVersion() != null) return model.selectedVersion().deviceId();
        return selectedDeviceId;
    }

    @FXML public void refresh() {
        if (worker == null) return;
        worker.submit(() -> {
            try {
                var snapshot = data.load();
                Platform.runLater(() -> applySnapshot(snapshot));
            } catch (Exception failure) { Platform.runLater(() -> messageLabel.setText("Refresh failed: " + failure.getMessage())); }
        });
    }

    void applySnapshot(DashboardDataService.Snapshot snapshot) {
        latestSnapshot = snapshot;
        String selectedDevRow = devicesList.getSelectionModel().getSelectedItem();
        if (selectedDevRow != null && selectedSphereDeviceId == null) {
            selectedSphereDeviceId = selectedDevRow.split(" — ")[0].trim();
        }
        devicesList.getItems().setAll(snapshot.devices());
        if (selectedSphereDeviceId != null) {
            String idPrefix = selectedSphereDeviceId + " — ";
            for (String item : snapshot.devices()) {
                if (item.startsWith(idPrefix)) {
                    devicesList.getSelectionModel().select(item);
                    break;
                }
            }
        } else if (!snapshot.clientSpheres().isEmpty()) {
            selectedSphereDeviceId = snapshot.clientSpheres().getFirst().deviceId();
        }
        renderSpheres(snapshot.clientSpheres());
        updateSphereInspector();

        String selectedIncident = incidentsList.getSelectionModel().getSelectedItem();
        if (!incidentsList.getItems().equals(snapshot.incidents())) {
            incidentsList.getItems().setAll(snapshot.incidents());
            if (selectedIncident != null && snapshot.incidents().contains(selectedIncident)) {
                incidentsList.getSelectionModel().select(selectedIncident);
            }
        }
        if (!snapshot.incidents().isEmpty()) {
            String newest = snapshot.incidents().getFirst();
            if (!newest.equals(latestBroadcastMessage)) {
                latestBroadcastMessage = newest;
                messageLabel.setText("🔔 Sự kiện mới: " + newest);
            }
        }
        if (targetDeviceCombo != null) {
            String currentTarget = targetDeviceCombo.getValue();
            var deviceIds = snapshot.deviceFolders().stream().map(DashboardDataService.DeviceBackupFolder::deviceId).toList();
            if (!targetDeviceCombo.getItems().equals(deviceIds)) {
                targetDeviceCombo.getItems().setAll(deviceIds);
            }
            if (currentTarget != null && deviceIds.contains(currentTarget)) {
                targetDeviceCombo.setValue(currentTarget);
            } else if (!deviceIds.isEmpty() && targetDeviceCombo.getValue() == null) {
                targetDeviceCombo.setValue(deviceIds.getFirst());
            }
        }
        rebuildBackupTree(snapshot);
        applyBackupFilter();
        updateRestoreButtons();
        model.updateCounts(snapshot.devices().size(), snapshot.incidents().size(), snapshot.versions().size());
        countsLabel.setText("Devices: " + snapshot.devices().size() + "   Incidents: " + snapshot.incidents().size()
                + "   Backups: " + snapshot.versions().size());
    }

    private void renderSpheres(List<DashboardDataService.ClientSphereItem> spheres) {
        if (sphereContainer == null) return;
        sphereContainer.getChildren().clear();
        for (var item : spheres) {
            VBox card = new VBox(8);
            card.setAlignment(Pos.CENTER);
            card.setMinWidth(320);
            card.setPrefWidth(320);
            card.setMaxWidth(320);
            boolean isSelected = item.deviceId().equals(selectedSphereDeviceId);
            card.getStyleClass().add(isSelected ? "sphere-card-selected" : "sphere-card");

            Circle sphere = new Circle(48);
            Color lightColor;
            Color darkColor;
            String badgeText;
            if (item.isolated()) {
                lightColor = Color.web("#b2bec3");
                darkColor = Color.web("#2d3436");
                badgeText = "CÔ LẬP";
            } else if (!item.online()) {
                lightColor = Color.web("#dfe6e9");
                darkColor = Color.web("#636e72");
                badgeText = "OFFLINE";
            } else if (item.alertOver80OrRisky()) {
                lightColor = Color.web("#ff7675");
                darkColor = Color.web("#c0392b");
                badgeText = "⚠ >=80%";
            } else if (!item.allowBackup() || !item.allowRestore()) {
                lightColor = Color.web("#ffeaa7");
                darkColor = Color.web("#d35400");
                badgeText = "HẠN CHẾ";
            } else {
                lightColor = Color.web("#55efc4");
                darkColor = Color.web("#009432");
                badgeText = "ONLINE";
            }
            RadialGradient gradient = new RadialGradient(
                    0, 0, 0.32, 0.30, 0.75, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.WHITE),
                    new Stop(0.25, lightColor),
                    new Stop(1.0, darkColor)
            );
            sphere.setFill(gradient);
            sphere.setEffect(new DropShadow(12, 0, 4, Color.rgb(0, 0, 0, 0.32)));

            Label centerLabel = new Label(badgeText);
            centerLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px;");
            StackPane sphereStack = new StackPane(sphere, centerLabel);

            Label nameLabel = new Label(item.deviceId());
            configureCardLine(nameLabel, "client-device-id");
            Label displayLabel = new Label(item.displayName() + " (" + item.platform() + ")");
            configureCardLine(displayLabel, "client-detail-line");
            Label metricsMini = new Label(item.online()
                    ? String.format(Locale.ROOT, "CPU %.0f%% | RAM %.0f%% | Disk %.0f%%",
                            item.cpuPercent(), item.ramPercent(), item.diskPercent())
                    : (item.isolated() ? "CÔ LẬP | Ngắt kết nối" : "OFFLINE | Mất kết nối"));
            configureCardLine(metricsMini, "client-detail-line");
            String tempStr = item.temperatureCelsius() != null
                    ? String.format(Locale.ROOT, "%.0f°C", item.temperatureCelsius())
                    : "N/A";
            Label healthMini = new Label(String.format(Locale.ROOT, "Nhiệt độ: %s | Backup: %s | Nhận file: %s",
                    tempStr,
                    item.allowBackup() ? "Cho phép" : "Chặn", item.allowRestore() ? "Cho phép" : "Chặn"));
            configureCardLine(healthMini, "client-detail-line");
            Label distMini = new Label(item.distance() != null ? item.distance().badgeText() : "Khoảng cách: OFFLINE");
            configureCardLine(distMini, "client-detail-line");
            distMini.getStyleClass().add(item.online() ? "client-distance-online" : "client-distance-offline");
            Label detailHint = new Label("Chọn để xem đầy đủ bên phải • Nhấp đúp mở dxdiag");
            configureCardLine(detailHint, "client-card-hint");

            card.getChildren().addAll(sphereStack, nameLabel, displayLabel, metricsMini, healthMini, distMini, detailHint);
            card.setOnMouseClicked(event -> {
                selectedSphereDeviceId = item.deviceId();
                String idPrefix = item.deviceId() + " — ";
                for (String row : devicesList.getItems()) {
                    if (row.startsWith(idPrefix)) {
                        devicesList.getSelectionModel().select(row);
                        break;
                    }
                }
                renderSpheres(spheres);
                updateSphereInspector();
                if (event.getClickCount() >= 2) {
                    openDxdiagDialog();
                }
            });
            sphereContainer.getChildren().add(card);
        }
    }

    private static void configureCardLine(Label label, String styleClass) {
        label.setWrapText(true);
        label.setTextOverrun(OverrunStyle.CLIP);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        label.getStyleClass().add(styleClass);
    }

    private DashboardDataService.ClientSphereItem findSelectedSphere() {
        if (latestSnapshot == null || latestSnapshot.clientSpheres().isEmpty()) return null;
        if (selectedSphereDeviceId != null) {
            for (var item : latestSnapshot.clientSpheres()) {
                if (item.deviceId().equals(selectedSphereDeviceId)) return item;
            }
        }
        return latestSnapshot.clientSpheres().getFirst();
    }

    private void updateSphereInspector() {
        var item = findSelectedSphere();
        if (item == null) return;
        if (dxdiagTitleLabel != null) {
            dxdiagTitleLabel.setText("Thông tin dxdiag: " + item.deviceId() + " (" + item.displayName() + ")");
        }
        boolean deviceChanged = !item.deviceId().equals(inspectorDeviceId);
        if (deviceChanged) {
            if (allowBackupCheck != null) allowBackupCheck.setSelected(item.allowBackup());
            if (allowRestoreCheck != null) allowRestoreCheck.setSelected(item.allowRestore());
        }
        if (dxdiagSummaryArea != null) {
            boolean preserveViewport = item.deviceId().equals(inspectorDeviceId);
            double scrollTop = dxdiagSummaryArea.getScrollTop();
            int anchor = dxdiagSummaryArea.getAnchor();
            int caret = dxdiagSummaryArea.getCaretPosition();
            String text = formatDxdiagText(item);
            dxdiagSummaryArea.setText(text);
            inspectorDeviceId = item.deviceId();
            if (preserveViewport) {
                int restoredAnchor = Math.min(anchor, text.length());
                int restoredCaret = Math.min(caret, text.length());
                Runnable restoreViewport = () -> {
                    if (!item.deviceId().equals(inspectorDeviceId) || dxdiagSummaryArea == null) return;
                    dxdiagSummaryArea.selectRange(restoredAnchor, restoredCaret);
                    dxdiagSummaryArea.setScrollTop(scrollTop);
                };
                restoreViewport.run();
                Platform.runLater(restoreViewport);
            } else {
                dxdiagSummaryArea.positionCaret(0);
                dxdiagSummaryArea.setScrollTop(0.0);
            }
        }
    }

    static String formatDxdiagText(DashboardDataService.ClientSphereItem item) {
        DxdiagProfile dx = item.dxdiag() != null ? item.dxdiag() : DxdiagProfile.unknown(item.deviceId(), item.platform());
        var dist = item.distance() != null ? item.distance() : vn.edu.ltm.common.model.DistanceTelemetry.offline();
        String distMetersText = dist.hasPhysicalDistance()
                ? String.format(Locale.ROOT, "~%.2f mét — %s", dist.estimatedMeters(), dist.zoneLabel())
                : dist.isOnline() ? dist.method() : "OFFLINE (Mất kết nối)";
        String distSignalText = (dist.hasPhysicalDistance() || dist.hasSignal())
                ? String.format(Locale.ROOT, "%.1f dBm (Raw: %.1f dBm) | Chất lượng sóng: %d%%",
                        dist.rssiFilteredDbm(), dist.rssiRawDbm(), dist.signalQualityPercent())
                : "N/A";
        String distRttText = dist.isOnline() && dist.rttMs() >= 0.0
                ? String.format(Locale.ROOT, "RTT %.1f ms | Jitter %.1f ms | Số bước nhảy: %d Hop (TTL=%d)",
                        dist.rttMs(), dist.jitterMs(), dist.hopCount(), dist.ttl())
                : dist.isOnline() ? "Đang chờ phép đo mạng" : "N/A";
        return """
                ================== SYSTEM INFORMATION (dxdiag) ==================
                Machine ID          : %s (%s)
                Computer Name       : %s
                Operating System    : %s
                System Manufacturer : %s
                System Model        : %s
                BIOS                : %s
                Processor           : %s
                Memory (RAM)        : %s
                Page File / Swap    : %s
                DirectX / Graphics  : %s
                ================== DISPLAY DEVICES ==============================
                Card Name (GPU)     : %s
                Manufacturer        : %s
                Display Memory VRAM : %s
                Display Mode / Info : %s
                ================== STORAGE & NETWORK ============================
                Disk Drives         : %s
                Network Adapters    : %s
                ================== REALTIME DISTANCE & SIGNAL (Kalman) ==========
                Khoảng cách ước tính: %s
                Cường độ sóng Wi-Fi : %s
                Độ trễ & Bước nhảy  : %s
                Thuật toán lọc nhiễu: %s
                ================== LIVE TELEMETRY & PERMISSIONS =================
                Trạng thái          : %s
                Thông số hiện tại   : %s
                Quyền hạn Client    : Backup=%s | Nhận file=%s | Cô lập=%s
                """.formatted(
                item.deviceId(), item.displayName(),
                dx.computerName(), dx.operatingSystem(), dx.systemManufacturer(), dx.systemModel(),
                dx.biosVersion(), dx.processor(), dx.memory(), dx.pageFileOrSwap(), dx.directXOrKernelVersion(),
                dx.gpuName(), dx.gpuVendor(), dx.gpuVram(), dx.displayInfo(),
                dx.diskSummary(), dx.networkSummary(),
                distMetersText, distSignalText, distRttText, dist.method(),
                item.isolated() ? "ISOLATED (ĐANG CÔ LẬP)" : (item.online() ? "ONLINE" : "OFFLINE"),
                item.metricsSummary(),
                item.allowBackup() ? "CHO PHÉP" : "BỊ CHẶN",
                item.allowRestore() ? "CHO PHÉP" : "BỊ CHẶN",
                item.isolated() ? "CÓ" : "KHÔNG"
        );
    }

    static String formatDistanceDetails(vn.edu.ltm.common.model.DistanceTelemetry dist) {
        if (dist == null || !dist.isOnline()) return "Trạng thái liên kết: OFFLINE\nLAN RTT: N/A";
        String physical = dist.hasPhysicalDistance()
                ? String.format(Locale.ROOT, "~%.2f m (%s)", dist.estimatedMeters(), dist.zoneLabel())
                : "Chưa có khoảng cách vật lý — " + dist.method();
        String signal = (dist.hasPhysicalDistance() || dist.hasSignal())
                ? String.format(Locale.ROOT, "Raw %.1f dBm | Kalman %.1f dBm | %d%%",
                        dist.rssiRawDbm(), dist.rssiFilteredDbm(), dist.signalQualityPercent())
                : "N/A";
        String calibration = dist.calibrated()
                ? dist.calibrationId()
                : (dist.calibrationId() == null || dist.calibrationId().isBlank()
                    ? "CHƯA HIỆU CHUẨN" : dist.calibrationId() + " (CHƯA KIỂM CHỨNG)");
        String rtt = dist.rttMs() >= 0
                ? String.format(Locale.ROOT, "%.1f ms | Jitter %.1f ms | %d Hop", dist.rttMs(), dist.jitterMs(), dist.hopCount())
                : "Đang chờ phép đo mạng";
        return "Nguồn RSSI: " + dist.measurementSource()
                + "\nTrạng thái liên kết: " + dist.directLinkStatus()
                + "\nInterface Server: " + emptyAsNA(dist.directInterfaceName())
                + "\nMAC Client P2P: " + emptyAsNA(dist.peerMac())
                + "\nMô hình hiệu chuẩn: " + calibration
                + "\nKhoảng cách vật lý: " + physical
                + "\nRSSI Wi-Fi Direct: " + signal
                + "\nLAN RTT: " + rtt;
    }

    static String formatNetworkDiagnostics(DashboardDataService.ClientSphereItem item) {
        DxdiagProfile dx = item.dxdiag() != null ? item.dxdiag() : DxdiagProfile.unknown(item.deviceId(), item.platform());
        var dist = item.distance() != null ? item.distance() : vn.edu.ltm.common.model.DistanceTelemetry.offline();
        return """
                Disk & Network Diagnostics
                --------------------------
                Local Storage       : %s
                Network Interfaces  : %s
                %s
                Live Resource Usage : %s
                Alert Status (>=80%%): %s
                """.formatted(dx.diskSummary(), dx.networkSummary(), formatDistanceDetails(dist),
                item.metricsSummary(),
                item.alertOver80OrRisky() ? "CẢNH BÁO VƯỢT NGƯỠNG >=80% HOẶC FILE RỦI RO" : "Bình thường (< 80%)");
    }

    private static String emptyAsNA(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    @FXML public void openDxdiagDialog() {
        var item = findSelectedSphere();
        if (item == null) {
            messageLabel.setText("Vui lòng chọn một Khối cầu Client để xem dxdiag");
            return;
        }
        DxdiagProfile dx = item.dxdiag() != null ? item.dxdiag() : DxdiagProfile.unknown(item.deviceId(), item.platform());
        var dialog = new Dialog<Void>();
        dialog.setTitle("DirectX / Cross-Platform Diagnostic Tool (dxdiag) — " + item.deviceId());
        dialog.setHeaderText("Thông tin cấu hình phần cứng Client [" + item.deviceId() + " — " + item.displayName() + "]");

        var tabs = new TabPane();
        tabs.setPrefSize(900, 540);
        String areaStyle = "-fx-font-family: 'Monospaced'; -fx-font-size: 17px; -fx-font-weight: bold;";

        var sysArea = new TextArea("""
                System Information
                ------------------
                Computer Name       : %s
                Operating System    : %s
                System Manufacturer : %s
                System Model        : %s
                BIOS                : %s
                Processor           : %s
                Memory              : %s
                Page File / Swap    : %s
                DirectX / Kernel    : %s
                """.formatted(dx.computerName(), dx.operatingSystem(), dx.systemManufacturer(),
                dx.systemModel(), dx.biosVersion(), dx.processor(), dx.memory(),
                dx.pageFileOrSwap(), dx.directXOrKernelVersion()));
        sysArea.setEditable(false);
        sysArea.setStyle(areaStyle);

        var dispArea = new TextArea("""
                Device
                ------
                Name                : %s
                Manufacturer        : %s
                Approx. Total Memory: %s
                Display Summary     : %s
                """.formatted(dx.gpuName(), dx.gpuVendor(), dx.gpuVram(), dx.displayInfo()));
        dispArea.setEditable(false);
        dispArea.setStyle(areaStyle);

        var netArea = new TextArea(formatNetworkDiagnostics(item));
        netArea.setEditable(false);
        netArea.setStyle(areaStyle);

        tabs.getTabs().addAll(
                new Tab("System", sysArea),
                new Tab("Display", dispArea),
                new Tab("Disk & Network", netArea)
        );
        for (Tab t : tabs.getTabs()) t.setClosable(false);

        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    @FXML public void openRadarDialog() {
        var dialog = new Dialog<Void>();
        dialog.setTitle("🎯 Sơ đồ Radar Khoảng cách Realtime (1D Kalman Filter + Wi-Fi RSSI / RTT)");
        dialog.setHeaderText("Đo khoảng cách Vật lý (m) & Độ trễ Mạng (RTT/TTL) từ Server đến các Client theo thời gian thực");

        var radarPane = new Pane();
        radarPane.setPrefSize(920, 620);
        radarPane.setStyle("-fx-background-color: #0b132b; -fx-background-radius: 12; -fx-border-color: #1c2541; -fx-border-width: 2;");

        Runnable drawRadar = () -> {
            radarPane.getChildren().clear();
            double cx = 460.0;
            double cy = 310.0;
            double maxRadius = 265.0; // represents 15m

            // Concentric distance rings: 1m, 3m, 5m, 10m, 15m
            double[] ringMeters = {1.0, 3.0, 5.0, 10.0, 15.0};
            for (double rm : ringMeters) {
                double r = Math.min(maxRadius, (rm / 15.0) * maxRadius);
                var ring = new Circle(cx, cy, r);
                ring.setFill(Color.TRANSPARENT);
                ring.setStroke(Color.web("#1e3a8a"));
                ring.setStrokeWidth(1.5);
                var ringLabel = new Label(String.format(Locale.ROOT, "%.0fm", rm));
                ringLabel.setStyle("-fx-text-fill: #38bdf8; -fx-font-size: 14px; -fx-font-weight: bold;");
                ringLabel.setLayoutX(cx + 6);
                ringLabel.setLayoutY(cy - r + 2);
                radarPane.getChildren().addAll(ring, ringLabel);
            }

            // Center Server / Hotspot node
            var centerBeacon = new Circle(cx, cy, 24, new RadialGradient(
                    0, 0, 0.3, 0.3, 0.8, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.WHITE),
                    new Stop(0.35, Color.web("#38bdf8")),
                    new Stop(1.0, Color.web("#0284c7"))
            ));
            centerBeacon.setEffect(new DropShadow(16, 0, 0, Color.web("#38bdf8")));
            var serverLabel = new Label("SERVER / HOTSPOT (0.0m)");
            serverLabel.setStyle("-fx-text-fill: #f8fafc; -fx-font-size: 15px; -fx-font-weight: bold; -fx-background-color: rgba(15,23,42,0.85); -fx-padding: 3 8; -fx-background-radius: 6;");
            serverLabel.setLayoutX(cx - 95);
            serverLabel.setLayoutY(cy + 28);
            radarPane.getChildren().addAll(centerBeacon, serverLabel);

            var spheres = latestSnapshot == null ? List.<DashboardDataService.ClientSphereItem>of() : latestSnapshot.clientSpheres();
            int n = Math.max(1, spheres.size());
            for (int i = 0; i < spheres.size(); i++) {
                var item = spheres.get(i);
                var dist = item.distance();
                boolean connected = item.online() && dist != null && dist.isOnline();
                boolean hasDistance = connected && dist.hasPhysicalDistance();
                double meters = hasDistance ? dist.estimatedMeters() : 14.5;
                double radiusPx = Math.max(48.0, Math.min(maxRadius - 12.0, (meters / 15.0) * maxRadius));
                double angle = (-Math.PI / 4.0) + (2.0 * Math.PI * i / n);
                double px = cx + radiusPx * Math.cos(angle);
                double py = cy + radiusPx * Math.sin(angle);

                Color cLight = !hasDistance ? Color.web("#94a3b8") : (item.alertOver80OrRisky() ? Color.web("#ff6b6b") : Color.web("#4ade80"));
                Color cDark = !hasDistance ? Color.web("#475569") : (item.alertOver80OrRisky() ? Color.web("#b91c1c") : Color.web("#15803d"));
                var clientNode = new Circle(px, py, 28, new RadialGradient(
                        0, 0, 0.32, 0.30, 0.75, true, CycleMethod.NO_CYCLE,
                        new Stop(0.0, Color.WHITE),
                        new Stop(0.25, cLight),
                        new Stop(1.0, cDark)
                ));
                clientNode.setEffect(new DropShadow(14, 0, 0, cLight));

                String tagText = hasDistance
                        ? String.format(Locale.ROOT, "%s (%s)\n📍 ~%.2f m | RTT %.0f ms | %.0f dBm",
                                item.deviceId(), item.displayName(), dist.estimatedMeters(), dist.rttMs(), dist.rssiFilteredDbm())
                        : connected
                                ? String.format(Locale.ROOT, "%s (%s)\nONLINE — Đang chờ RSSI", item.deviceId(), item.displayName())
                                : String.format(Locale.ROOT, "%s (%s)\nOFFLINE", item.deviceId(), item.displayName());
                var tag = new Label(tagText);
                tag.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 15px; -fx-font-weight: bold; -fx-background-color: rgba(15,23,42,0.88); -fx-border-color: #334155; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 5 10;");
                tag.setLayoutX(Math.max(12, Math.min(680, px - 95)));
                tag.setLayoutY(Math.max(12, Math.min(540, py + 32)));
                radarPane.getChildren().addAll(clientNode, tag);
            }
        };

        drawRadar.run();
        var radarTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(ServerDashboardApp.DASHBOARD_REFRESH_MILLIS), e -> {
                    refresh();
                    drawRadar.run();
                })
        );
        radarTimeline.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        radarTimeline.play();

        dialog.setOnHidden(e -> radarTimeline.stop());
        dialog.getDialogPane().setContent(radarPane);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    @FXML public void openCalibrationDialog() {
        var selected = findSelectedSphere();
        if (selected == null) {
            messageLabel.setText("Vui lòng chọn Client trước khi hiệu chuẩn khoảng cách");
            return;
        }
        if (calibrationStore == null) {
            messageLabel.setText("Kho lưu hiệu chuẩn chưa được cấu hình");
            return;
        }
        var session = new DistanceCalibrationSession(selected.deviceId());
        var dialog = new Dialog<Void>();
        dialog.setTitle("Hiệu chuẩn khoảng cách Wi-Fi Direct — " + selected.deviceId());
        dialog.setHeaderText("Đặt Client lần lượt tại 0,5 m, 1 m, 2 m, 3 m và 5 m; mỗi mốc thu 100 mẫu mới.");

        var distanceChoice = new ComboBox<Double>();
        distanceChoice.getItems().setAll(DistanceCalibrationSession.REQUIRED_DISTANCES_METERS);
        distanceChoice.setValue(1.0);
        var state = new Label();
        state.setWrapText(true);
        state.getStyleClass().add("calibration-status");
        var result = new TextArea("Chưa đủ dữ liệu để khớp mô hình.");
        result.setEditable(false);
        result.setPrefRowCount(5);
        var collecting = new javafx.beans.property.SimpleBooleanProperty(false);
        var collectButton = new Button("Bắt đầu thu mẫu");
        var exportButton = new Button("Xuất CSV bằng chứng");
        var activateButton = new Button("Kích hoạt mô hình");
        activateButton.setDisable(true);

        Runnable updateState = () -> {
            StringBuilder text = new StringBuilder("Client: ").append(session.targetDeviceId()).append('\n');
            for (double distance : DistanceCalibrationSession.REQUIRED_DISTANCES_METERS) {
                text.append(String.format(Locale.ROOT, "%.0f m: %d/%d mẫu%n", distance,
                        session.acceptedCount(distance), session.samplesPerDistance()));
            }
            var current = findSelectedSphere();
            if (current != null && current.distance() != null) {
                text.append("\n").append(formatDistanceDetails(current.distance()));
            }
            state.setText(text.toString());
            activateButton.setDisable(!session.complete());
            if (session.complete()) {
                var model = session.fit();
                result.setText(String.format(Locale.ROOT,
                        "Mô hình chờ kích hoạt: %s%nA = %.3f dBm%n n = %.3f%nMAE = %.3f m%nRMSE = %.3f m%nSố mẫu = %d",
                        model.id(), model.referenceRssiAtOneMeter(), model.pathLossExponent(),
                        model.maeMeters(), model.rmseMeters(), model.sampleCount()));
            }
        };

        var sampler = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(250), event -> {
                    if (!collecting.get()) return;
                    var current = findSelectedSphere();
                    if (current != null) session.accept(current.deviceId(), distanceChoice.getValue(), current.distance());
                    updateState.run();
                }));
        sampler.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        sampler.play();
        collectButton.setOnAction(event -> {
            collecting.set(!collecting.get());
            collectButton.setText(collecting.get() ? "Tạm dừng thu mẫu" : "Tiếp tục thu mẫu");
            updateState.run();
        });
        exportButton.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("Lưu dữ liệu hiệu chuẩn CSV");
            chooser.setInitialFileName("wifi-direct-calibration-" + selected.deviceId() + ".csv");
            File output = chooser.showSaveDialog(messageLabel.getScene().getWindow());
            if (output == null) return;
            try {
                Files.writeString(output.toPath(), session.toCsv(), StandardCharsets.UTF_8);
                result.setText("Đã xuất dữ liệu: " + output.getAbsolutePath());
            } catch (Exception failure) {
                result.setText("Không thể xuất CSV: " + failure.getMessage());
            }
        });
        activateButton.setOnAction(event -> {
            try {
                var fitted = session.activate(calibrationStore);
                result.setText(String.format(Locale.ROOT,
                        "ĐÃ KÍCH HOẠT %s%nA = %.3f dBm | n = %.3f%nMAE = %.3f m | RMSE = %.3f m",
                        fitted.id(), fitted.referenceRssiAtOneMeter(), fitted.pathLossExponent(),
                        fitted.maeMeters(), fitted.rmseMeters()));
                collecting.set(false);
            } catch (Exception failure) {
                result.setText("Không thể kích hoạt: " + failure.getMessage());
            }
        });
        distanceChoice.valueProperty().addListener((unused, oldValue, newValue) -> updateState.run());
        updateState.run();
        var actions = new HBox(8, collectButton, exportButton, activateButton);
        var content = new VBox(10, new Label("Khoảng cách thực tế (m):"), distanceChoice, state, result, actions);
        content.setPrefSize(760, 560);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnHidden(event -> sampler.stop());
        dialog.showAndWait();
    }

    @FXML private void savePermissions() {
        var item = findSelectedSphere();
        if (item == null) {
            messageLabel.setText("Vui lòng chọn một Khối cầu Client để phân quyền");
            return;
        }
        boolean allowBkp = allowBackupCheck == null || allowBackupCheck.isSelected();
        boolean allowRst = allowRestoreCheck == null || allowRestoreCheck.isSelected();
        worker.submit(() -> {
            try {
                auth.requireAuthenticated(model.adminUsername());
                provisioning.setPermissions(item.deviceId(), allowBkp, allowRst, model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText("Đã cập nhật phân quyền cho [" + item.deviceId()
                            + "]: Cho phép Backup=" + allowBkp + ", Cho phép Nhận file=" + allowRst);
                    refresh();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> messageLabel.setText("Permission update failed: " + failure.getMessage()));
            }
        });
    }

    private void rebuildBackupTree(DashboardDataService.Snapshot snapshot) {
        if (backupTree == null) return;
        if (backupTree.getRoot() != null
                && snapshot.versions().size() == lastTreeVersionCount
                && snapshot.deviceFolders().equals(lastTreeFolders)) {
            return;
        }
        lastTreeVersionCount = snapshot.versions().size();
        lastTreeFolders = snapshot.deviceFolders();
        String keepDevice = selectedDeviceId;
        String keepPrefix = selectedPathPrefix == null ? "" : selectedPathPrefix;
        var rootItem = new TreeItem<>(new BackupTreeNode("All Devices", null, ""));
        var allItem = new TreeItem<>(new BackupTreeNode("📁 Tất cả thiết bị (" + snapshot.versions().size() + " bản sao lưu)", null, ""));
        rootItem.getChildren().add(allItem);
        TreeItem<BackupTreeNode> toSelect = keepDevice == null ? allItem : null;
        for (var folder : snapshot.deviceFolders()) {
            var devNode = new TreeItem<>(new BackupTreeNode(
                    "📁 " + folder.deviceId() + " (" + folder.displayName() + ") — " + folder.relativePaths().size() + " file",
                    folder.deviceId(), ""));
            devNode.setExpanded(true);
            if (folder.deviceId().equals(keepDevice) && keepPrefix.isEmpty()) toSelect = devNode;
            for (String relPath : folder.relativePaths()) {
                var matched = addRelativePathToTree(devNode, folder.deviceId(), relPath, keepDevice, keepPrefix);
                if (matched != null) toSelect = matched;
            }
            rootItem.getChildren().add(devNode);
        }
        rebuildingTree = true;
        try {
            backupTree.setRoot(rootItem);
            if (toSelect != null) {
                backupTree.getSelectionModel().select(toSelect);
            }
        } finally {
            rebuildingTree = false;
        }
    }

    private static TreeItem<BackupTreeNode> addRelativePathToTree(
            TreeItem<BackupTreeNode> deviceRoot, String deviceId, String relativePath, String keepDevice, String keepPrefix) {
        String[] parts = relativePath.split("/");
        TreeItem<BackupTreeNode> current = deviceRoot;
        TreeItem<BackupTreeNode> matched = null;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) prefix.append("/");
            prefix.append(parts[i]);
            String currentPrefix = prefix.toString();
            boolean isFile = (i == parts.length - 1);
            String label = (isFile ? "📄 " : "📁 ") + parts[i];
            TreeItem<BackupTreeNode> existing = null;
            for (var child : current.getChildren()) {
                if (child.getValue().pathPrefix().equals(currentPrefix)) {
                    existing = child;
                    break;
                }
            }
            if (existing == null) {
                existing = new TreeItem<>(new BackupTreeNode(label, deviceId, currentPrefix));
                existing.setExpanded(true);
                current.getChildren().add(existing);
            }
            if (deviceId.equals(keepDevice) && currentPrefix.equals(keepPrefix)) matched = existing;
            current = existing;
        }
        return matched;
    }

    private void applyBackupFilter() {
        if (latestSnapshot == null) return;
        Long keepVersionId = null;
        if (model != null && model.selectedVersion() != null) {
            keepVersionId = model.selectedVersion().id();
        } else if (backupsList.getSelectionModel().getSelectedItem() != null) {
            keepVersionId = backupsList.getSelectionModel().getSelectedItem().id();
        }
        var filtered = latestSnapshot.filterVersions(selectedDeviceId, selectedPathPrefix);
        if (!backupsList.getItems().equals(filtered)) {
            backupsList.getItems().setAll(filtered);
        }
        if (keepVersionId != null) {
            DashboardDataService.VersionRow toReselect = null;
            for (var row : filtered) {
                if (row.id() == keepVersionId) {
                    toReselect = row;
                    break;
                }
            }
            if (toReselect != null) {
                if (backupsList.getSelectionModel().getSelectedItem() != toReselect) {
                    backupsList.getSelectionModel().select(toReselect);
                }
                model.selectVersion(toReselect.id(), toReselect.deviceId(), toReselect.relativePath(), toReselect.createdAt());
            } else {
                backupsList.getSelectionModel().clearSelection();
                model.clearSelection();
            }
        } else if (!filtered.isEmpty() && selectedPathPrefix != null && !selectedPathPrefix.isEmpty()) {
            var first = filtered.getFirst();
            backupsList.getSelectionModel().select(first);
            if (model != null) {
                model.selectVersion(first.id(), first.deviceId(), first.relativePath(), first.createdAt());
            }
        }
        if (selectedFolderLabel != null) {
            if (selectedDeviceId == null) {
                selectedFolderLabel.setText("Thư mục lưu trữ Server: ~/.ltm-server/backup-storage/ (Đang hiển thị tất cả máy)");
            } else {
                String sub = (selectedPathPrefix == null || selectedPathPrefix.isEmpty()) ? "" : "/" + selectedPathPrefix;
                selectedFolderLabel.setText("Thư mục máy [" + selectedDeviceId + "]: ~/.ltm-server/backup-storage/" + selectedDeviceId + sub);
            }
        }
    }

    @FXML private void provision() {
        String id = deviceIdField.getText().trim(), name = displayNameField.getText().trim(), platform = platformField.getText().trim();
        worker.submit(() -> {
            try {
                auth.requireAuthenticated(model.adminUsername());
                var created = provisioning.provision(id, name, platform);
                Platform.runLater(() -> {
                    model.showProvisioned(created.deviceId(), created.token());
                    var alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Device provisioned"); alert.setHeaderText("Save this token now — it is shown once");
                    alert.setContentText("Device: " + created.deviceId() + "\nToken: " + model.oneTimeToken());
                    var copy = new ButtonType("Copy token");
                    alert.getButtonTypes().setAll(copy, ButtonType.OK);
                    try {
                        var answer = alert.showAndWait();
                        if (answer.isPresent() && answer.get() == copy) {
                            var content = new ClipboardContent(); content.putString(model.oneTimeToken());
                            Clipboard.getSystemClipboard().setContent(content);
                        }
                    } finally { model.dismissToken(); }
                    refresh();
                });
            } catch (Exception failure) { Platform.runLater(() -> messageLabel.setText("Provision failed: " + failure.getMessage())); }
        });
    }

    @FXML private void toggleIsolation() {
        String devId = selectedSphereDeviceId;
        if (devId == null || devId.isBlank()) {
            String selectedRow = devicesList.getSelectionModel().getSelectedItem();
            if (selectedRow != null && !selectedRow.isBlank()) {
                devId = selectedRow.split(" — ")[0].trim();
            }
        }
        if (devId == null || devId.isBlank()) {
            messageLabel.setText("Vui lòng chọn một thiết bị (Khối cầu hoặc dòng) để Cô lập / Bỏ cô lập");
            return;
        }
        final String targetDev = devId;
        worker.submit(() -> {
            try {
                auth.requireAuthenticated(model.adminUsername());
                boolean isolated = provisioning.toggleIsolated(targetDev, sessions, model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText(isolated
                            ? "Đã CÔ LẬP thiết bị [" + targetDev + "] — đã ngắt kết nối và chặn truy cập"
                            : "Đã BỎ CÔ LẬP thiết bị [" + targetDev + "] — thiết bị có thể kết nối lại");
                    refresh();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> messageLabel.setText("Isolation failed: " + failure.getMessage()));
            }
        });
    }

    @FXML private void restore() {
        var selected = model.selectedVersion();
        String target = resolveTargetDevice();
        if (!model.canRestore() || selected == null || target == null) return;
        var confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Máy nguồn (Source): " + selected.deviceId()
                        + "\nMáy nhận (Target): " + target
                        + "\nFile: " + selected.relativePath()
                        + "\nPhiên bản: " + selected.createdAt()
                        + "\n\nTruyền file sang máy [" + target + "]?",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Xác nhận truyền / khôi phục file");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        worker.submit(() -> {
            try {
                var job = restores.request(selected.id(), target, model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText("Đã xếp hàng truyền file sang [" + target + "]: job=" + job.id());
                    refresh();
                });
            } catch (Exception failure) { Platform.runLater(() -> messageLabel.setText("Restore failed: " + failure.getMessage())); }
        });
    }

    @FXML private void broadcastSelectedFileToAll() {
        var selected = model.selectedVersion();
        if (!model.canRestore() || selected == null || latestSnapshot == null) return;
        List<String> allTargets = latestSnapshot.deviceFolders().stream()
                .map(DashboardDataService.DeviceBackupFolder::deviceId)
                .toList();
        var confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "File: " + selected.relativePath() + " (từ máy " + selected.deviceId() + ")"
                        + "\nDanh sách máy nhận: " + String.join(", ", allTargets)
                        + "\n\nCopy đồng loạt file này xuống TẤT CẢ Client đủ quyền nhận?",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("NetOp School Broadcast: Copy file cho tất cả Client");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        worker.submit(() -> {
            try {
                var jobs = restores.requestBroadcast(selected.id(), allTargets, model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText("Đã phát (Broadcast) file [" + selected.relativePath() + "] tới " + jobs.size() + " máy Client!");
                    refresh();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> messageLabel.setText("Broadcast failed: " + failure.getMessage()));
            }
        });
    }

    @FXML private void sendServerFileToMultipleClients() {
        if (model.adminUsername() == null || latestSnapshot == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn file từ máy Server để gửi cho nhiều Client");
        File chosen = chooser.showOpenDialog(devicesList.getScene().getWindow());
        if (chosen == null) return;

        var dialog = new Dialog<List<String>>();
        dialog.setTitle("Chọn các máy Client nhận file: " + chosen.getName());
        dialog.setHeaderText("Tích chọn các máy Client bạn muốn gửi file [" + chosen.getName() + "]:");
        VBox box = new VBox(8);
        var checks = new ArrayList<CheckBox>();
        for (var sphere : latestSnapshot.clientSpheres()) {
            CheckBox cb = new CheckBox(sphere.deviceId() + " (" + sphere.displayName() + ") — "
                    + (sphere.isolated() ? "ĐANG CÔ LẬP" : (!sphere.allowRestore() ? "CHẶN NHẬN FILE" : (sphere.online() ? "ONLINE" : "OFFLINE"))));
            cb.setUserData(sphere.deviceId());
            cb.setSelected(!sphere.isolated() && sphere.allowRestore());
            cb.setDisable(sphere.isolated() || !sphere.allowRestore());
            checks.add(cb);
            box.getChildren().add(cb);
        }
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            return checks.stream().filter(CheckBox::isSelected).map(c -> (String) c.getUserData()).toList();
        });
        var targets = dialog.showAndWait().orElse(null);
        if (targets == null || targets.isEmpty()) return;

        worker.submit(() -> {
            try {
                var jobs = restores.broadcastServerLocalFile(chosen.toPath(), chosen.getName(), targets, model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText("Đã gửi file [" + chosen.getName() + "] từ Server tới " + jobs.size() + " Client: " + String.join(", ", targets));
                    refresh();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> messageLabel.setText("Send server file failed: " + failure.getMessage()));
            }
        });
    }

    @FXML private void runAnsibleSyncAll() {
        if (model.adminUsername() == null || ansibleSync == null) {
            messageLabel.setText("Dịch vụ Ansible Sync chưa sẵn sàng");
            return;
        }
        TextInputDialog input = new TextInputDialog(
                "Thông báo phòng máy LAN: Tài liệu khởi tạo bằng Ansible và đồng bộ tự động cho tất cả Client.");
        input.setTitle("Ansible Playbook: Khởi tạo & Đồng bộ File văn bản cho tất cả Client");
        input.setHeaderText("Thực thi ansible/init_sync_file.yml -> tạo file THONG_BAO_DONG_BO_LAN.txt và đồng bộ cho toàn bộ Client");
        input.setContentText("Nội dung văn bản đồng bộ:");
        var content = input.showAndWait();
        if (content.isEmpty()) return;

        worker.submit(() -> {
            try {
                var outcome = ansibleSync.initializeAndSyncAllClients(content.get(), model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText("Ansible Sync hoàn tất ("
                            + (outcome.ranAnsibleCli() ? "ansible-playbook CLI" : "Ansible Playbook Engine")
                            + "): Đã khởi tạo [" + AnsibleSyncService.SYNC_FILE_NAME + "] và đồng bộ tới "
                            + outcome.broadcastJobs().size() + " Client!");
                    refresh();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> messageLabel.setText("Ansible sync failed: " + failure.getMessage()));
            }
        });
    }

    @FXML private void restoreFolder() {
        String source = selectedDeviceId;
        String prefix = selectedPathPrefix == null ? "" : selectedPathPrefix;
        String target = resolveTargetDevice();
        if (model.adminUsername() == null || source == null || target == null) return;
        String folderDisplay = prefix.isEmpty() ? source + " (toàn bộ folder)" : source + "/" + prefix;
        var confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Folder nguồn: " + folderDisplay
                        + "\nMáy nhận (Target): " + target
                        + "\n\nTruyền toàn bộ file (bản mới nhất) sang máy [" + target + "]?",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Xác nhận truyền toàn bộ Folder sang máy khác");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        worker.submit(() -> {
            try {
                var jobs = restores.requestFolderTransfer(source, prefix, target, model.adminUsername());
                Platform.runLater(() -> {
                    messageLabel.setText("Đã xếp hàng truyền " + jobs.size() + " file từ [" + folderDisplay + "] sang máy [" + target + "]");
                    refresh();
                });
            } catch (Exception failure) {
                Platform.runLater(() -> messageLabel.setText("Folder transfer failed: " + failure.getMessage()));
            }
        });
    }

    @FXML private void logout() { if (logout != null) logout.run(); }
}
