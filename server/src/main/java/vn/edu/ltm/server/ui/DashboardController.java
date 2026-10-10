package vn.edu.ltm.server.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.device.DeviceProvisioningService;
import vn.edu.ltm.server.restore.RestoreService;
import java.util.concurrent.ExecutorService;

public final class DashboardController {
    @FXML private ListView<String> devicesList, incidentsList;
    @FXML private ListView<DashboardDataService.VersionRow> backupsList;
    @FXML private TextField deviceIdField, displayNameField, platformField;
    @FXML private Button restoreButton;
    @FXML private Label countsLabel, messageLabel;
    private DashboardViewModel model;
    private DashboardDataService data;
    private DeviceProvisioningService provisioning;
    private RestoreService restores;
    private AdminAuthService auth;
    private ExecutorService worker;
    private Runnable logout;

    public void configure(DashboardViewModel model, DashboardDataService data,
                          DeviceProvisioningService provisioning, RestoreService restores,
                          AdminAuthService auth, ExecutorService worker, Runnable logout) {
        this.model = model; this.data = data; this.provisioning = provisioning; this.restores = restores;
        this.auth = auth; this.worker = worker; this.logout = logout;
        backupsList.getSelectionModel().selectedItemProperty().addListener((unused, previous, row) -> {
            if (row == null) model.clearSelection();
            else model.selectVersion(row.id(), row.deviceId(), row.relativePath(), row.createdAt());
            restoreButton.setDisable(!model.canRestore());
        });
        refresh();
    }
    @FXML public void refresh() {
        if (worker == null) return;
        worker.submit(() -> {
            try {
                var snapshot = data.load();
                Platform.runLater(() -> {
                    devicesList.getItems().setAll(snapshot.devices());
                    incidentsList.getItems().setAll(snapshot.incidents());
                    backupsList.getItems().setAll(snapshot.versions());
                    model.updateCounts(snapshot.devices().size(), snapshot.incidents().size(), snapshot.versions().size());
                    countsLabel.setText("Devices: " + snapshot.devices().size() + "   Incidents: " + snapshot.incidents().size()
                            + "   Backups: " + snapshot.versions().size());
                });
            } catch (Exception failure) { Platform.runLater(() -> messageLabel.setText("Refresh failed: " + failure.getMessage())); }
        });
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
    @FXML private void restore() {
        var selected = model.selectedVersion();
        if (!model.canRestore() || selected == null) return;
        var confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Target device: " + selected.deviceId() + "\nPath: " + selected.relativePath()
                        + "\nVersion: " + selected.createdAt() + "\n\nRestore to Recovered/?",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.setHeaderText("Confirm restore");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        worker.submit(() -> {
            try {
                var job = restores.request(selected.id(), selected.deviceId(), model.adminUsername());
                Platform.runLater(() -> messageLabel.setText("Restore queued: " + job.id()));
            } catch (Exception failure) { Platform.runLater(() -> messageLabel.setText("Restore failed: " + failure.getMessage())); }
        });
    }
    @FXML private void logout() { if (logout != null) logout.run(); }
}
