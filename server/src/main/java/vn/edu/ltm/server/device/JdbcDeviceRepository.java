package vn.edu.ltm.server.device;

import vn.edu.ltm.server.persistence.Database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcDeviceRepository implements DeviceRepository {
    private final Database database;

    public JdbcDeviceRepository(Database database) {
        this.database = database;
    }

    @Override public void insert(Device device) {
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "INSERT INTO devices(device_id, display_name, platform, token_hash, status, last_seen) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, device.deviceId());
            statement.setString(2, device.displayName());
            statement.setString(3, device.platform());
            statement.setString(4, device.tokenHash());
            statement.setString(5, device.status().name());
            statement.setString(6, device.lastSeen() == null ? null : device.lastSeen().toString());
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

    private static Device read(ResultSet rows) throws SQLException {
        String lastSeen = rows.getString("last_seen");
        return new Device(rows.getString("device_id"), rows.getString("display_name"), rows.getString("platform"),
                rows.getString("token_hash"), Device.Status.valueOf(rows.getString("status")),
                lastSeen == null ? null : Instant.parse(lastSeen));
    }
}
