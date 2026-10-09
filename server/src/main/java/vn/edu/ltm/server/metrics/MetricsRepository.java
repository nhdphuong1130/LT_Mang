package vn.edu.ltm.server.metrics;

import vn.edu.ltm.common.model.SystemMetrics;
import java.util.Optional;

public interface MetricsRepository {
    void upsert(String deviceId, SystemMetrics metrics);
    Optional<SystemMetrics> findLatest(String deviceId);
}
