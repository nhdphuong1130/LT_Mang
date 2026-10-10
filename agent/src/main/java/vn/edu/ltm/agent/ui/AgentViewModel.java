package vn.edu.ltm.agent.ui;

import vn.edu.ltm.common.model.SystemMetrics;
import java.util.Objects;
import java.util.function.Consumer;

/** UI-thread-owned snapshot; callers may update it from worker threads. */
public final class AgentViewModel {
    private final Consumer<Runnable> ui;
    private volatile String status = "Offline";
    private volatile String warning = "";
    private volatile String cpu = "N/A", ram = "N/A", disk = "N/A", temperature = "N/A", battery = "N/A";
    private volatile int queued;

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
}
