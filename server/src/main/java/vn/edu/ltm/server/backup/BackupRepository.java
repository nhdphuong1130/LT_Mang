package vn.edu.ltm.server.backup;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

public interface BackupRepository {
    List<BackupVersion> versions(String deviceId, String relativePath) throws SQLException;
    /** Commits the new row and trims excess rows; returns paths safe to remove after commit. */
    List<Path> commitVersion(String deviceId, String relativePath, long size, String sha256, Path path)
            throws SQLException;
}
