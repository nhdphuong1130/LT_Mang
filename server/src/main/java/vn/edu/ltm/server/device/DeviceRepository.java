package vn.edu.ltm.server.device;

import vn.edu.ltm.common.model.DxdiagProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository {
    void insert(Device device);
    Optional<Device> findById(String deviceId);
    List<Device> findAll();
    void markOnline(String deviceId, Instant lastSeen);
    void markOffline(String deviceId);
    default void setIsolated(String deviceId, boolean isolated) { }
    default void setPermissions(String deviceId, boolean allowBackup, boolean allowRestore) { }
    default void updateDxdiag(String deviceId, DxdiagProfile dxdiag) { }
    default void markAllOffline() { for (Device device : findAll()) markOffline(device.deviceId()); }
}
