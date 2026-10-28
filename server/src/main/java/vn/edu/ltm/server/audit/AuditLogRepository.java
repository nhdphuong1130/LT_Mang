package vn.edu.ltm.server.audit;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository {
    void log(Instant occurredAt, String actor, String action, String deviceId, String details);
    List<AuditLogEntry> findRecent(int limit);

    record AuditLogEntry(long id, Instant occurredAt, String actor, String action, String deviceId, String details) { }
}
