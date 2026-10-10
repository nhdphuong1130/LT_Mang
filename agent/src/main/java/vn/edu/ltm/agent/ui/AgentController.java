package vn.edu.ltm.agent.ui;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class AgentController {
    @FXML private TextField hostField, controlPortField, filePortField, deviceIdField, rootField;
    @FXML private PasswordField tokenField;
    @FXML private Label statusLabel, metricsLabel, queueLabel, messageLabel;
    private Consumer<AgentSettings> onStart;

    public void setOnStart(Consumer<AgentSettings> action) { onStart = action; }
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
    @FXML private void startAgent() {
        try {
            var settings = new AgentSettings(hostField.getText().trim(), Integer.parseInt(controlPortField.getText().trim()),
                    Integer.parseInt(filePortField.getText().trim()), deviceIdField.getText().trim(),
                    tokenField.getText(), Path.of(rootField.getText().trim()));
            onStart.accept(settings);
            messageLabel.setText("Starting monitoring and backup…");
        } catch (RuntimeException failure) { messageLabel.setText(failure.getMessage()); }
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
