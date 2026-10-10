package vn.edu.ltm.server.restore;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RestoreJobRepository {
    record BackupMetadata(long versionId, String deviceId, String relativePath, long size, String sha256, Path storagePath) { }
    record OfferSnapshot(RestoreJob.Status status, String tokenHash, Instant expiresAt, UUID sessionId) { }
    Optional<BackupMetadata> backup(long versionId) throws SQLException;
    void create(RestoreJob job) throws SQLException;
    BackupMetadata metadata(UUID jobId) throws SQLException;
    Optional<RestoreJob> find(UUID id) throws SQLException;
    List<RestoreJob> offerable(String deviceId) throws SQLException;
    void recoverExpired(Instant now) throws SQLException;
    Optional<OfferSnapshot> offerSnapshot(UUID id) throws SQLException;
    boolean offer(UUID id, RestoreJob.Status expectedStatus, String expectedHash, String tokenHash,
                  Instant expiresAt, UUID sessionId) throws SQLException;
    void revertOffer(UUID id, String tokenHash) throws SQLException;
    Optional<RestoreJob> claim(String tokenHash, String deviceId, String leaseOwner, Instant now, Duration leaseDuration) throws SQLException;
    boolean renewLease(UUID id, String leaseOwner, Instant now, Duration leaseDuration) throws SQLException;
    /** Publishes a terminal status and its audit as one observable transaction. */
    void completeAndAudit(UUID id, String leaseOwner, boolean success, Instant now, String error,
                          String actor, String deviceId, String details) throws SQLException;
    void audit(Instant at, String actor, String action, String deviceId, String details) throws SQLException;
    List<Path> orphanedBlobs() throws SQLException;
}
