package vn.edu.ltm.server.backup;

import java.nio.file.Path;
import java.time.Instant;

public record BackupVersion(long id, long fileId, long sizeBytes, String sha256, Path storagePath,
                            Instant createdAt) { }
