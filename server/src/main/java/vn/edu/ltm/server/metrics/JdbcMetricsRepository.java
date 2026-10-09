package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.SystemMetrics;
import vn.edu.ltm.server.persistence.Database;
import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Optional;

public final class JdbcMetricsRepository implements MetricsRepository {
    // Fixed fractions make textual SQLite ordering exact down to nanoseconds.
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final Database database;
    public JdbcMetricsRepository(Database database) { this.database = database; }

    @Override public void upsert(String deviceId, SystemMetrics metrics) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement("""
                INSERT INTO latest_metrics(device_id, collected_at, cpu_percent, ram_percent, disk_percent,
                    temperature_celsius, battery_percent, charging) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET collected_at = excluded.collected_at,
                    cpu_percent = excluded.cpu_percent, ram_percent = excluded.ram_percent,
                    disk_percent = excluded.disk_percent, temperature_celsius = excluded.temperature_celsius,
                    battery_percent = excluded.battery_percent, charging = excluded.charging
                WHERE excluded.collected_at >= latest_metrics.collected_at
                """)) {
            statement.setString(1, deviceId);
            statement.setString(2, TIMESTAMP.format(metrics.collectedAt()));
            statement.setDouble(3, metrics.cpuPercent()); statement.setDouble(4, metrics.ramPercent());
            statement.setDouble(5, metrics.diskPercent());
            statement.setObject(6, metrics.temperatureCelsius()); statement.setObject(7, metrics.batteryPercent());
            statement.setBoolean(8, metrics.charging());
            statement.executeUpdate();
        } catch (SQLException failure) { throw new IllegalStateException("Cannot persist metrics", failure); }
    }

    @Override public Optional<SystemMetrics> findLatest(String deviceId) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM latest_metrics WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new SystemMetrics(Instant.parse(rows.getString("collected_at")),
                        rows.getDouble("cpu_percent"), rows.getDouble("ram_percent"), rows.getDouble("disk_percent"),
                        nullable(rows, "temperature_celsius"), nullable(rows, "battery_percent"), rows.getBoolean("charging")))
                        : Optional.empty();
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot load metrics", failure); }
    }

    private static Double nullable(ResultSet rows, String column) throws SQLException {
        double value = rows.getDouble(column); return rows.wasNull() ? null : value;
    }
}
