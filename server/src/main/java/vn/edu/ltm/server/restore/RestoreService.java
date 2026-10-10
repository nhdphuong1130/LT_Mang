package vn.edu.ltm.server.restore;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.edu.ltm.common.protocol.ControlEnvelope;
import vn.edu.ltm.common.protocol.MessageType;
import vn.edu.ltm.common.transfer.TransferResult;
import vn.edu.ltm.server.auth.AdminAuthService;
import vn.edu.ltm.server.session.SessionRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class RestoreService {
    private static final Duration DEFAULT_CLAIM_LEASE = Duration.ofMinutes(2);
    private static final ReentrantLock[] OFFER_LOCKS = new ReentrantLock[256];
    static { for (int i = 0; i < OFFER_LOCKS.length; i++) OFFER_LOCKS[i] = new ReentrantLock(); }
    private final RestoreJobRepository jobs;
    private final AdminAuthService auth;
    private final SessionRegistry sessions;
    private final Clock clock;
    private final Duration claimLease;
    private final SecureRandom random = new SecureRandom();
    private final String leaseOwner = UUID.randomUUID().toString();

    public RestoreService(RestoreJobRepository jobs, AdminAuthService auth, SessionRegistry sessions, Clock clock) {
        this(jobs, auth, sessions, clock, DEFAULT_CLAIM_LEASE);
    }

    public RestoreService(RestoreJobRepository jobs, AdminAuthService auth, SessionRegistry sessions, Clock clock,
                          Duration claimLease) {
        this.jobs = Objects.requireNonNull(jobs);
        this.auth = Objects.requireNonNull(auth);
        this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
        this.claimLease = Objects.requireNonNull(claimLease);
        if (claimLease.isZero() || claimLease.isNegative()) throw new IllegalArgumentException("Claim lease must be positive");
        try {
            jobs.recoverExpired(clock.instant());
            cleanupOrphanedBlobs();
        } catch (SQLException failure) { throw new IllegalStateException("Cannot recover restore jobs", failure); }
        sessions.setOnRegistered(deviceId -> {
            try { notifyPending(deviceId); }
            catch (SQLException failure) {
                System.getLogger(RestoreService.class.getName()).log(System.Logger.Level.ERROR,
                        "Could not offer pending restore jobs", failure);
            }
        });
    }

    public RestoreJob request(long backupVersionId, String targetDeviceId, String adminUsername) throws SQLException {
        auth.requireAuthenticated(adminUsername);
        var backup = jobs.backup(backupVersionId).orElseThrow(() -> new IllegalArgumentException("Backup version does not exist"));
        if (!backup.deviceId().equals(targetDeviceId)) throw new SecurityException("Backup belongs to another device");
        Instant now = clock.instant();
        var job = new RestoreJob(UUID.randomUUID(), backupVersionId, targetDeviceId, adminUsername,
                now.plusSeconds(600), RestoreJob.Status.PENDING, now, null, null);
        jobs.create(job);
        jobs.audit(now, adminUsername, "RESTORE_REQUEST", targetDeviceId, "job=" + job.id() + ", version=" + backupVersionId);
        notifyPending(targetDeviceId);
        return jobs.find(job.id()).orElseThrow();
    }

    public int notifyPending(String deviceId) throws SQLException {
        jobs.recoverExpired(clock.instant());
        if (sessions.find(deviceId).isEmpty()) return 0;
        int sent = 0;
        for (var job : jobs.offerable(deviceId)) {
            var lock = OFFER_LOCKS[Math.floorMod(job.id().hashCode(), OFFER_LOCKS.length)];
            lock.lock();
            try {
                var session = sessions.find(deviceId);
                if (session.isEmpty()) continue;
                var snapshot = jobs.offerSnapshot(job.id());
                if (snapshot.isEmpty() || snapshot.get().status() == RestoreJob.Status.IN_PROGRESS
                        || snapshot.get().status() == RestoreJob.Status.COMPLETED
                        || snapshot.get().status() == RestoreJob.Status.FAILED) continue;
                var prior = snapshot.get();
                Instant now = clock.instant();
                if (prior.status() == RestoreJob.Status.AVAILABLE && prior.expiresAt().isAfter(now)
                        && session.get().sessionId().equals(prior.sessionId())) continue;
                var backup = jobs.metadata(job.id());
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                String tokenHash = hash(token);
                Instant expires = now.plusSeconds(600);
                if (!jobs.offer(job.id(), prior.status(), prior.tokenHash(), tokenHash, expires,
                        session.get().sessionId())) continue;
                var payload = JsonNodeFactory.instance.objectNode()
                        .put("jobId", job.id().toString()).put("token", token)
                        .put("versionId", backup.versionId()).put("relativePath", backup.relativePath())
                        .put("size", backup.size()).put("sha256", backup.sha256())
                        .put("requestedAt", job.requestedAt().toString());
                var notification = new ControlEnvelope(MessageType.RESTORE_AVAILABLE, job.id().toString(), deviceId, payload);
                if (sessions.sendIfSame(deviceId, session.get().sessionId(), notification)) {
                    jobs.audit(clock.instant(), "server", "RESTORE_OFFER", deviceId, "job=" + job.id());
                    sent++;
                } else jobs.revertOffer(job.id(), tokenHash);
            } finally {
                lock.unlock();
            }
        }
        return sent;
    }

    public ClaimedRestore claim(String oneTimeToken, String deviceId) throws SQLException {
        if (oneTimeToken == null || !oneTimeToken.matches("[A-Za-z0-9_-]{43}") || deviceId == null)
            throw new SecurityException("Invalid restore token");
        var job = jobs.claim(hash(oneTimeToken), deviceId, leaseOwner, clock.instant(), claimLease)
                .orElseThrow(() -> new SecurityException("Restore token unavailable"));
        jobs.audit(clock.instant(), deviceId, "RESTORE_CLAIM", deviceId, "job=" + job.id());
        var backup = jobs.metadata(job.id());
        return new ClaimedRestore(job.id(), backup.versionId(), backup.relativePath(), backup.size(),
                backup.sha256(), backup.storagePath());
    }

    public void complete(UUID jobId, TransferResult result) throws SQLException {
        var job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("Restore job does not exist"));
        boolean success = result.status() == TransferResult.Status.STORED;
        String error = success ? null : result.message();
        jobs.complete(jobId, leaseOwner, success, clock.instant(), error);
        jobs.audit(clock.instant(), job.deviceId(), success ? "RESTORE_COMPLETE" : "RESTORE_FAILURE",
                job.deviceId(), "job=" + jobId + (error == null ? "" : ", reason=" + error));
        cleanupOrphanedBlobs();
    }

    public Optional<RestoreJob> find(UUID jobId) throws SQLException { return jobs.find(jobId); }

    public boolean renewLease(UUID jobId) throws SQLException {
        return jobs.renewLease(jobId, leaseOwner, clock.instant(), claimLease);
    }

    public Duration heartbeatInterval() { return claimLease.dividedBy(4); }

    private void cleanupOrphanedBlobs() throws SQLException {
        for (var path : jobs.orphanedBlobs()) {
            try { Files.deleteIfExists(path); }
            catch (IOException failure) {
                System.getLogger(RestoreService.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not remove expired restore blob", failure);
            }
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record ClaimedRestore(UUID jobId, long versionId, String relativePath, long size, String sha256,
                                 java.nio.file.Path storagePath) { }
}
