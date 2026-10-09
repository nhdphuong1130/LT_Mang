package vn.edu.ltm.server.device;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository {
    void insert(Device device);
    Optional<Device> findById(String deviceId);
    List<Device> findAll();
    void markOnline(String deviceId, Instant lastSeen);
    void markOffline(String deviceId);
}
