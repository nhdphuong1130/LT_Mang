package vn.edu.ltm.server.incident;

import vn.edu.ltm.common.model.*;
import vn.edu.ltm.server.persistence.Database;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.*;
import java.util.*;

public final class JdbcIncidentRepository implements IncidentRepository {
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private final Database database;
    public JdbcIncidentRepository(Database database) { this.database = database; }

    @Override public void apply(String deviceId, RiskEvent event) {
        String time = TIMESTAMP.format(event.occurredAt());
        boolean resolved = event.status() == RiskEvent.Status.RESOLVED;
        String sql = resolved ? """
                UPDATE incidents SET status = 'RESOLVED', updated_at = ?, resolved_at = ?, message = ?
                WHERE device_id = ? AND risk_type = ? AND status = 'OPEN' AND updated_at <= ?
                """ : """
                INSERT INTO incidents(device_id, risk_type, status, opened_at, updated_at, message)
                VALUES (?, ?, 'OPEN', ?, ?, ?)
                ON CONFLICT(device_id, risk_type) WHERE status = 'OPEN'
                DO UPDATE SET updated_at = excluded.updated_at, message = excluded.message
                WHERE excluded.updated_at >= incidents.updated_at
                """;
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(sql)) {
            if (resolved) {
                statement.setString(1, time); statement.setString(2, time); statement.setString(3, event.message());
                statement.setString(4, deviceId); statement.setString(5, event.riskType().name()); statement.setString(6, time);
            } else {
                statement.setString(1, deviceId); statement.setString(2, event.riskType().name());
                statement.setString(3, time); statement.setString(4, time); statement.setString(5, event.message());
            }
            statement.executeUpdate();
        } catch (SQLException failure) { throw new IllegalStateException("Cannot persist incident", failure); }
    }

    @Override public List<Incident> findAll(String deviceId) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM incidents WHERE device_id = ? ORDER BY id")) {
            statement.setString(1, deviceId);
            try (var rows = statement.executeQuery()) {
                var incidents = new ArrayList<Incident>();
                while (rows.next()) {
                    String resolved = rows.getString("resolved_at");
                    incidents.add(new Incident(rows.getLong("id"), rows.getString("device_id"),
                            RiskType.valueOf(rows.getString("risk_type")), RiskEvent.Status.valueOf(rows.getString("status")),
                            Instant.parse(rows.getString("opened_at")), Instant.parse(rows.getString("updated_at")),
                            resolved == null ? null : Instant.parse(resolved), rows.getString("message")));
                }
                return List.copyOf(incidents);
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot load incidents", failure); }
    }
}
