package vn.edu.ltm.server.incident;

import vn.edu.ltm.common.model.*;
import java.time.Instant;
import java.util.List;

public interface IncidentRepository {
    void apply(String deviceId, RiskEvent event);
    List<Incident> findAll(String deviceId);

    record Incident(long id, String deviceId, RiskType riskType, RiskEvent.Status status,
                    Instant openedAt, Instant updatedAt, Instant resolvedAt, String message) { }
}
