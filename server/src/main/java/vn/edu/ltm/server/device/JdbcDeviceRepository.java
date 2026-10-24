package vn.edu.ltm.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.edu.ltm.common.model.DxdiagProfile;
import vn.edu.ltm.server.persistence.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcDeviceRepository implements DeviceRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Database database;

    public JdbcDeviceRepository(Database database) {
        this.database = database;
    }

    @Override public void insert(Device device) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "INSERT INTO devices(device_id, display_name, platform, token_hash, status, last_seen, isolated, allow_backup, allow_restore, dxdiag_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, device.deviceId());
            statement.setString(2, device.displayName());
            statement.setString(3, device.platform());
            statement.setString(4, device.tokenHash());
            statement.setString(5, device.status().name());
            statement.setString(6, device.lastSeen() == null ? null : device.lastSeen().toString());
            statement.setInt(7, device.isolated() ? 1 : 0);
            statement.setInt(8, device.allowBackup() ? 1 : 0);
            statement.setInt(9, device.allowRestore() ? 1 : 0);
            statement.setString(10, serializeDxdiag(device.dxdiag()));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot provision device", failure);
        }
    }

    @Override public Optional<Device> findById(String deviceId) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM devices WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot load device", failure);
        }
    }

    @Override public List<Device> findAll() {
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM devices ORDER BY device_id")) {
            var devices = new ArrayList<Device>();
            while (rows.next()) devices.add(read(rows));
            return List.copyOf(devices);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot list devices", failure);
        }
    }

    @Override public void markOnline(String deviceId, Instant lastSeen) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "UPDATE devices SET status = 'ONLINE', last_seen = ? WHERE device_id = ?")) {
            statement.setString(1, lastSeen.toString());
            statement.setString(2, deviceId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot update device heartbeat", failure);
        }
    }

    @Override public void markOffline(String deviceId) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "UPDATE devices SET status = 'OFFLINE' WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot mark device offline", failure);
        }
    }

    @Override public void setIsolated(String deviceId, boolean isolated) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                isolated
                        ? "UPDATE devices SET isolated = 1, status = 'OFFLINE' WHERE device_id = ?"
                        : "UPDATE devices SET isolated = 0 WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Device does not exist: " + deviceId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot update device isolation", failure);
        }
    }

    @Override public void setPermissions(String deviceId, boolean allowBackup, boolean allowRestore) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "UPDATE devices SET allow_backup = ?, allow_restore = ? WHERE device_id = ?")) {
            statement.setInt(1, allowBackup ? 1 : 0);
            statement.setInt(2, allowRestore ? 1 : 0);
            statement.setString(3, deviceId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Device does not exist: " + deviceId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot update device permissions", failure);
        }
    }

    @Override public void updateDxdiag(String deviceId, DxdiagProfile dxdiag) {
        if (dxdiag == null) return;
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "UPDATE devices SET dxdiag_json = ? WHERE device_id = ?")) {
            statement.setString(1, serializeDxdiag(dxdiag));
            statement.setString(2, deviceId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot update device dxdiag profile", failure);
        }
    }

    private static String serializeDxdiag(DxdiagProfile dxdiag) {
        if (dxdiag == null) return null;
        try { return MAPPER.writeValueAsString(dxdiag); }
        catch (Exception ignored) { return null; }
    }

    private static DxdiagProfile deserializeDxdiag(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, DxdiagProfile.class); }
        catch (Exception ignored) { return null; }
    }

    private static Device read(ResultSet rows) throws SQLException {
        String lastSeen = rows.getString("last_seen");
        boolean isolated = false;
        boolean allowBackup = true;
        boolean allowRestore = true;
        DxdiagProfile dxdiag = null;
        try { isolated = rows.getInt("isolated") != 0; } catch (SQLException ignored) { }
        try { allowBackup = rows.getInt("allow_backup") != 0; } catch (SQLException ignored) { }
        try { allowRestore = rows.getInt("allow_restore") != 0; } catch (SQLException ignored) { }
        try { dxdiag = deserializeDxdiag(rows.getString("dxdiag_json")); } catch (SQLException ignored) { }
        return new Device(rows.getString("device_id"), rows.getString("display_name"), rows.getString("platform"),
                rows.getString("token_hash"), Device.Status.valueOf(rows.getString("status")),
                lastSeen == null ? null : Instant.parse(lastSeen), isolated, allowBackup, allowRestore, dxdiag);
    }
}
