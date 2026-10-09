package vn.edu.ltm.server.incident;

import vn.edu.ltm.common.model.RiskEvent;
import java.util.Objects;

public final class IncidentService {
    private final IncidentRepository repository;
    public IncidentService(IncidentRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public void record(String deviceId, RiskEvent event) {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("Device ID is required");
        repository.apply(deviceId, Objects.requireNonNull(event));
    }
}
