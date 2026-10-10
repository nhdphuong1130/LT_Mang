package vn.edu.ltm.server.ui;

import vn.edu.ltm.server.auth.AdminAuthService;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.util.concurrent.ExecutorService;

public final class LoginController {
    private AdminAuthService auth;
    private DashboardViewModel model;
    private ExecutorService worker;
    private Runnable onSuccess;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label messageLabel;
    public LoginController() { }
    public LoginController(AdminAuthService auth, DashboardViewModel model) {
        this.auth = Objects.requireNonNull(auth); this.model = Objects.requireNonNull(model);
    }
    public void configure(AdminAuthService auth, DashboardViewModel model, ExecutorService worker, Runnable onSuccess) {
        this.auth = Objects.requireNonNull(auth); this.model = Objects.requireNonNull(model);
        this.worker = Objects.requireNonNull(worker); this.onSuccess = Objects.requireNonNull(onSuccess);
    }
    @FXML private void submitLogin() {
        String username = usernameField.getText().trim();
        char[] password = passwordField.getText().toCharArray(); passwordField.clear();
        worker.submit(() -> {
            try {
                boolean accepted = login(username, password);
                Platform.runLater(() -> { if (accepted) onSuccess.run(); else messageLabel.setText("Invalid credentials"); });
            } catch (SQLException failure) { Platform.runLater(() -> messageLabel.setText("Login failed: " + failure.getMessage())); }
        });
    }
    @FXML private void createInitialAdmin() {
        String username = usernameField.getText().trim();
        char[] password = passwordField.getText().toCharArray(); passwordField.clear();
        worker.submit(() -> {
            try {
                auth.createInitialAdmin(username, password);
                Platform.runLater(() -> messageLabel.setText("Administrator created. Sign in now."));
            } catch (SQLException | RuntimeException failure) {
                Platform.runLater(() -> messageLabel.setText("Setup failed: " + failure.getMessage()));
            }
        });
    }
    public boolean login(String username, char[] password) throws SQLException {
        Objects.requireNonNull(password);
        try {
            var result = auth.authenticate(username, password);
            if (result.authenticated()) model.login(result.username());
            return result.authenticated();
        } finally { Arrays.fill(password, '\0'); }
    }
    public void logout() { auth.logout(); model.logout(); }
}
