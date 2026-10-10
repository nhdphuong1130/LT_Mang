package vn.edu.ltm.server.ui;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

public final class DashboardViewModel {
    public record SelectedVersion(long id, String deviceId, String relativePath, Instant createdAt) { }
    private final Consumer<Runnable> ui;
    private volatile String adminUsername;
    private volatile SelectedVersion selectedVersion;
    private volatile String oneTimeToken;
    private volatile int deviceCount, incidentCount, backupCount;

    public DashboardViewModel(Consumer<Runnable> ui) { this.ui = Objects.requireNonNull(ui); }
    public void login(String username) { ui.accept(() -> adminUsername = username); }
    public void logout() { ui.accept(() -> { adminUsername = null; selectedVersion = null; oneTimeToken = null; }); }
    public void selectVersion(long id, String deviceId, String relativePath, Instant createdAt) {
        ui.accept(() -> selectedVersion = new SelectedVersion(id, deviceId, relativePath, createdAt));
    }
    public void clearSelection() { ui.accept(() -> selectedVersion = null); }
    public boolean canRestore() { return adminUsername != null && selectedVersion != null; }
    public void showProvisioned(String deviceId, String token) { ui.accept(() -> oneTimeToken = token); }
    public void dismissToken() { ui.accept(() -> oneTimeToken = null); }
    public void updateCounts(int devices, int incidents, int backups) {
        ui.accept(() -> { deviceCount = devices; incidentCount = incidents; backupCount = backups; });
    }
    public String adminUsername() { return adminUsername; }
    public SelectedVersion selectedVersion() { return selectedVersion; }
    public String oneTimeToken() { return oneTimeToken; }
    public int deviceCount() { return deviceCount; }
    public int incidentCount() { return incidentCount; }
    public int backupCount() { return backupCount; }
    @Override public String toString() { return "DashboardViewModel[admin=" + adminUsername + ", devices=" + deviceCount + "]"; }
}
