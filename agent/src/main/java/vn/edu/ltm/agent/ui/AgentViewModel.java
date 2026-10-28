package vn.edu.ltm.agent.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import vn.edu.ltm.common.model.SystemMetrics;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** UI-thread-owned snapshot; callers may update it from worker threads. */
public final class AgentViewModel {
    private static final DateTimeFormatter LOG_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public record ServerFileRecord(String relativePath, long size, String createdAt, String sha256) {
        public String formattedSize() {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", size / 1024.0);
            return String.format(java.util.Locale.ROOT, "%.2f MB", size / (1024.0 * 1024.0));
        }
    }

    private final Consumer<Runnable> ui;
    private volatile String status = "Offline";
    private volatile String warning = "";
    private volatile String cpu = "N/A", ram = "N/A", disk = "N/A", temperature = "N/A", battery = "N/A";
    private volatile int queued;
    private final ObservableList<ServerFileRecord> serverFiles = FXCollections.observableArrayList();
    private final ObservableList<String> eventLogs = FXCollections.observableArrayList();

    public AgentViewModel(Consumer<Runnable> ui) { this.ui = Objects.requireNonNull(ui); }
    public void updateMetrics(SystemMetrics metrics) {
        Objects.requireNonNull(metrics);
        ui.accept(() -> {
            cpu = percent(metrics.cpuPercent()); ram = percent(metrics.ramPercent()); disk = percent(metrics.diskPercent());
            temperature = metrics.temperatureCelsius() == null ? "N/A" : String.format(java.util.Locale.ROOT, "%.1f°C", metrics.temperatureCelsius());
            battery = metrics.batteryPercent() == null ? "N/A" : percent(metrics.batteryPercent());
        });
    }
    public void setConnected(boolean connected) { ui.accept(() -> { status = connected ? "Online" : "Offline"; if (connected) warning = ""; }); }
    public void warn(String message) { ui.accept(() -> { warning = Objects.requireNonNull(message); if (!"Offline".equals(status)) status = "Warning"; }); }
    public void setQueued(int count) { ui.accept(() -> queued = Math.max(0, count)); }
    private static String percent(double value) { return String.format(java.util.Locale.ROOT, "%.0f%%", value); }
    public String status() { return status; }
    public String warning() { return warning; }
    public String cpu() { return cpu; }
    public String ram() { return ram; }
    public String disk() { return disk; }
    public String temperature() { return temperature; }
    public String battery() { return battery; }
    public int queued() { return queued; }

    public void logEvent(String message) {
        if (message == null || message.isBlank()) return;
        String line = "[" + LOG_FORMAT.format(Instant.now()) + "] " + message;
        ui.accept(() -> {
            eventLogs.add(0, line);
            if (eventLogs.size() > 500) eventLogs.remove(eventLogs.size() - 1);
        });
    }

    public void updateServerFiles(List<ServerFileRecord> files) {
        ui.accept(() -> {
            serverFiles.setAll(files != null ? files : List.of());
        });
    }

    public ObservableList<ServerFileRecord> serverFiles() { return serverFiles; }
    public ObservableList<String> eventLogs() { return eventLogs; }
}
