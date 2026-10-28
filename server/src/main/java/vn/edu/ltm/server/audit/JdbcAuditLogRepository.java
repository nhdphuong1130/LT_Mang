package vn.edu.ltm.server.audit;

import vn.edu.ltm.server.persistence.Database;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcAuditLogRepository implements AuditLogRepository {
    private final Database database;

    public JdbcAuditLogRepository(Database database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override
    public void log(Instant occurredAt, String actor, String action, String deviceId, String details) {
        String sql = "INSERT INTO audit_logs(occurred_at, actor, action, device_id, details) VALUES (?, ?, ?, ?, ?)";
        try (var connection = database.openConnection(); var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, (occurredAt == null ? Instant.now() : occurredAt).toString());
            stmt.setString(2, actor == null ? "system" : actor);
            stmt.setString(3, action == null ? "UNKNOWN" : action);
            stmt.setString(4, deviceId);
            stmt.setString(5, details == null ? "" : details);
            stmt.executeUpdate();
        } catch (SQLException failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Cannot write audit log", failure);
        }
    }

    @Override
    public List<AuditLogEntry> findRecent(int limit) {
        String sql = "SELECT id, occurred_at, actor, action, device_id, details FROM audit_logs ORDER BY id DESC LIMIT ?";
        try (var connection = database.openConnection(); var stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                var entries = new ArrayList<AuditLogEntry>();
                while (rs.next()) {
                    entries.add(new AuditLogEntry(
                            rs.getLong("id"),
                            Instant.parse(rs.getString("occurred_at")),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("device_id"),
                            rs.getString("details")
                    ));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException failure) {
            return List.of();
        }
    }
}
