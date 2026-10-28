package vn.edu.ltm.agent.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class AgentController {
    @FXML private TextField hostField, controlPortField, filePortField, deviceIdField, rootField;
    @FXML private PasswordField tokenField;
    @FXML private Label statusLabel, metricsLabel, queueLabel, messageLabel, pullStatusLabel;
    @FXML private TableView<AgentViewModel.ServerFileRecord> serverFilesTable;
    @FXML private TableColumn<AgentViewModel.ServerFileRecord, String> colRelativePath, colSize, colCreatedAt, colSha256;
    @FXML private ListView<String> eventListView;

    private Consumer<AgentSettings> onStart;
    private Runnable onRequestServerFiles;
    private Consumer<String> onPullFile;
    private Runnable onPullAll;

    @FXML public void initialize() {
        if (colRelativePath != null) {
            colRelativePath.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().relativePath()));
            colSize.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().formattedSize()));
            colCreatedAt.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().createdAt()));
            colSha256.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().sha256()));
        }
    }

    public void setOnStart(Consumer<AgentSettings> action) { onStart = action; }
    public void setOnRequestServerFiles(Runnable action) { onRequestServerFiles = action; }
    public void setOnPullFile(Consumer<String> action) { onPullFile = action; }
    public void setOnPullAll(Runnable action) { onPullAll = action; }

    public void load(AgentSettings settings) {
        hostField.setText(settings.serverHost()); controlPortField.setText(Integer.toString(settings.controlPort()));
        filePortField.setText(Integer.toString(settings.filePort())); deviceIdField.setText(settings.deviceId());
        tokenField.setText(settings.token()); rootField.setText(settings.protectedRoot().toString());
    }

    @FXML private void chooseFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Select protected folder");
        var chosen = chooser.showDialog(rootField.getScene().getWindow());
        if (chosen != null) rootField.setText(chosen.toString());
    }

    @FXML private void openProtectedFolder() {
        try {
            Path p = Path.of(rootField.getText().trim());
            Files.createDirectories(p);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
        } catch (Exception ex) {
            messageLabel.setText("Không thể mở thư mục: " + ex.getMessage());
        }
    }

    @FXML private void openRecoveredFolder() {
        try {
            Path p = Path.of(rootField.getText().trim()).resolve("Recovered");
            Files.createDirectories(p);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
        } catch (Exception ex) {
            messageLabel.setText("Không thể mở thư mục Recovered: " + ex.getMessage());
        }
    }

    @FXML private void startAgent() {
        try {
            var settings = new AgentSettings(hostField.getText().trim(), Integer.parseInt(controlPortField.getText().trim()),
                    Integer.parseInt(filePortField.getText().trim()), deviceIdField.getText().trim(),
                    tokenField.getText(), Path.of(rootField.getText().trim()));
            onStart.accept(settings);
            messageLabel.setText("Starting monitoring and backup…");
        } catch (RuntimeException failure) { messageLabel.setText(failure.getMessage()); }
    }

    @FXML private void refreshServerFiles() {
        if (onRequestServerFiles != null) {
            if (pullStatusLabel != null) pullStatusLabel.setText("Đang yêu cầu danh sách file từ Server...");
            onRequestServerFiles.run();
        } else {
            if (pullStatusLabel != null) pullStatusLabel.setText("Agent chưa kết nối với Server.");
        }
    }

    @FXML private void pullSelectedFile() {
        var selected = serverFilesTable != null ? serverFilesTable.getSelectionModel().getSelectedItem() : null;
        if (selected == null) {
            if (pullStatusLabel != null) pullStatusLabel.setText("Vui lòng chọn một file trong bảng để kéo về máy!");
            return;
        }
        if (onPullFile != null) {
            if (pullStatusLabel != null) pullStatusLabel.setText("Đã gửi yêu cầu kéo file [" + selected.relativePath() + "] về máy...");
            onPullFile.accept(selected.relativePath());
        }
    }

    @FXML private void pullAllFiles() {
        if (onPullAll != null) {
            if (pullStatusLabel != null) pullStatusLabel.setText("Đã gửi yêu cầu kéo toàn bộ dữ liệu từ Server về máy...");
            onPullAll.run();
        }
    }

    @FXML private void clearEventLogs() {
        if (eventListView != null) eventListView.getItems().clear();
    }

    public void bindModel(AgentViewModel model) {
        if (serverFilesTable != null) serverFilesTable.setItems(model.serverFiles());
        if (eventListView != null) eventListView.setItems(model.eventLogs());
    }

    public void setPullStatus(String text) {
        if (pullStatusLabel != null) pullStatusLabel.setText(text);
    }

    public void refresh(AgentViewModel model) {
        statusLabel.setText(model.status());
        metricsLabel.setText("CPU " + model.cpu() + "  RAM " + model.ram() + "  Disk " + model.disk()
                + "  Temp " + model.temperature() + "  Battery " + model.battery());
        queueLabel.setText("Pending backups: " + model.queued());
        if (!model.warning().isEmpty()) messageLabel.setText(model.warning());
    }

    public void error(String message) { messageLabel.setText(message); }
    public void started() { messageLabel.setText("Monitoring and backup started"); }
}
