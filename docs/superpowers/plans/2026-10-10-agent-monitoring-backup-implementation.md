# LAN Agent Monitoring and Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 21 Client–Server system whose Windows/Linux Agent monitors real system resources, warns users about sustained risks, versions changed files to a LAN Server, and accepts administrator-controlled restores into a safe `Recovered/` directory.

**Architecture:** A Maven multi-module project separates protocol contracts (`common`), the cross-platform endpoint (`agent`), and the central service/dashboard (`server`). Agents keep a persistent length-prefixed JSON control connection on port 5050 and use short-lived binary file-transfer connections on port 5051; SQLite stores state and metadata while backup payloads remain on the Server filesystem.

**Tech Stack:** Java 21, Maven Wrapper pinned to Maven 3.10.0, JavaFX 21, OSHI 7.6.1, Jackson 2.22.3, SQLite JDBC 3.53.4.0, BCrypt 0.10.2, SLF4J 2.0.20, JUnit Jupiter 6.1.3.

**Spec:** `docs/superpowers/specs/2026-10-09-agent-monitoring-backup-design.md`

## Global Constraints

- Support Agent execution on Windows and Linux with Java 21.
- Use control TCP port 5050 and file TCP port 5051 by default; both remain configurable.
- Frame control messages as a 4-byte big-endian payload length followed by UTF-8 JSON; reject payloads over 65,536 bytes.
- Send heartbeat and metrics every 5 seconds; Server marks an Agent Offline after 15 seconds without heartbeat.
- Treat temperature as optional; unavailable sensors produce `null`/`N/A`, never fabricated data.
- Default risk rules: CPU ≥ 90% for 30 seconds, RAM ≥ 90% for 15 seconds, disk ≥ 95%, temperature ≥ 85°C, battery ≤ 10% while discharging.
- Watch exactly one protected root recursively; exclude temporary files and symbolic links; reject files over 100 MiB.
- Wait until a changed file is stable for at least 3 seconds before hashing/uploading it.
- Store file content outside SQLite, verify SHA-256, and retain at most five versions per `deviceId + relativePath`.
- Only an authenticated administrator may create restore requests.
- Restore only to `Protected/Recovered/<timestamp>/<relativePath>` and never overwrite an existing file.
- Run the MVP only on a trusted LAN with synthetic demo files; TLS and real personal data are out of scope.
- Do not implement arbitrary remote commands, automatic Ctrl+S, cloud sync, or power-failure prediction.

## Review Focus

- Path traversal and symlink input must never escape the configured Agent root or Server device storage; Task 4 and Task 5 pin this with hostile-path tests.
- Interrupted, concurrently modified, oversized, or checksum-mismatched files must never become valid backup versions; Task 4 and Task 5 cover each state.
- Duplicate device IDs, wrong tokens, reconnect races, and stale sessions must not displace an authenticated live session; Task 2 covers them.
- Missing sensors and values oscillating around a threshold must not create fake or repeated alerts; Task 3 covers them.
- Pending backups and restores must survive disconnect/restart and resume exactly once; Task 4 and Task 6 cover persistent queue/job transitions.

---

## File Structure

```text
pom.xml                                  Parent build and pinned versions
mvnw, mvnw.cmd, .mvn/wrapper/            Cross-platform Maven Wrapper
common/                                  Network contracts and shared utilities
agent/                                   Windows/Linux Agent application
server/                                  TCP services, persistence, admin dashboard
scripts/run-agent.sh|.bat                 Development launchers
scripts/run-server.sh|.bat
README.md                                Setup, configuration and demo guide
```

The `common` module has no JavaFX, SQLite, OSHI, or application dependency. Network services depend on domain services through focused interfaces so tests can use fakes and ephemeral ports. JavaFX controllers consume immutable view models and never own socket or database logic.

### Task 1: Project Foundation and Shared Protocol

**Files:**
- Create: `.gitignore`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `pom.xml`
- Create: `common/pom.xml`
- Create: `agent/pom.xml`
- Create: `server/pom.xml`
- Create: `common/src/main/java/vn/edu/ltm/common/protocol/MessageType.java`
- Create: `common/src/main/java/vn/edu/ltm/common/protocol/ControlEnvelope.java`
- Create: `common/src/main/java/vn/edu/ltm/common/protocol/ProtocolException.java`
- Create: `common/src/main/java/vn/edu/ltm/common/protocol/LengthPrefixedJsonCodec.java`
- Create: `common/src/main/java/vn/edu/ltm/common/transfer/FileOperation.java`
- Create: `common/src/main/java/vn/edu/ltm/common/transfer/FileTransferHeader.java`
- Create: `common/src/main/java/vn/edu/ltm/common/util/Hashing.java`
- Test: `common/src/test/java/vn/edu/ltm/common/protocol/LengthPrefixedJsonCodecTest.java`
- Test: `common/src/test/java/vn/edu/ltm/common/util/HashingTest.java`

**Interfaces:**
- Produces: `ControlEnvelope(MessageType type, String requestId, String deviceId, JsonNode payload)`.
- Produces: `MessageType` values `REGISTER`, `REGISTER_ACK`, `HEARTBEAT`, `METRICS`, `RISK_ALERT`, `RISK_RESOLVED`, `RESTORE_AVAILABLE`, `RESTORE_RESULT`, and `ERROR`.
- Produces: `LengthPrefixedJsonCodec.write(OutputStream, ControlEnvelope)` and `read(InputStream): ControlEnvelope`.
- Produces: `FileTransferHeader(FileOperation operation, String transferId, String deviceId, String credential, String relativePath, long fileSize, String sha256, String versionId)`.
- Produces: `FileOperation` values `BACKUP_UPLOAD` and `RESTORE_DOWNLOAD`.
- Produces: `Hashing.sha256(Path): String`.

- [ ] **Step 1: Create the Maven Wrapper and empty multi-module build**

Pin Maven 3.10.0 in `maven-wrapper.properties`, Java release 21, and dependency/plugin versions in the parent POM. Configure OS-activated `javafx.platform` profiles (`win` and `linux`), JUnit/Surefire in dependency/plugin management, and `failIfNoSpecifiedTests=false` so targeted reactor test commands work before every module has matching tests.

- [ ] **Step 2: Verify the empty reactor builds**

Run: `./mvnw validate`

Expected: `BUILD SUCCESS` for the parent and all three modules.

- [ ] **Step 3: Write failing codec tests**

Cover round-trip Unicode JSON, multiple consecutive frames, truncated payload, negative length, and 65,537-byte payload rejection with `ProtocolException`.

- [ ] **Step 4: Run the common tests and verify they fail**

Run: `./mvnw -pl common test`

Expected: compilation failure because protocol classes do not exist.

- [ ] **Step 5: Implement the protocol records, enums, codec, and hashing utility**

`LengthPrefixedJsonCodec` owns a configured Jackson `ObjectMapper`, reads exactly the declared bytes, and never closes caller-owned streams.

- [ ] **Step 6: Run the common tests**

Run: `./mvnw -pl common test`

Expected: all common tests PASS.

- [ ] **Step 7: Initialize version control and commit**

Run: `git init && git add .gitignore .mvn mvnw mvnw.cmd pom.xml common agent server docs/superpowers && git commit -m "build: initialize Java monitoring project"`

### Task 2: Server Persistence, Device Provisioning, and Control Sessions

**Files:**
- Create: `server/src/main/resources/db/schema.sql`
- Create: `server/src/main/java/vn/edu/ltm/server/config/ServerConfig.java`
- Create: `server/src/main/java/vn/edu/ltm/server/persistence/Database.java`
- Create: `server/src/main/java/vn/edu/ltm/server/device/Device.java`
- Create: `server/src/main/java/vn/edu/ltm/server/device/DeviceRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/device/JdbcDeviceRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/device/DeviceProvisioningService.java`
- Create: `server/src/main/java/vn/edu/ltm/server/session/AgentSession.java`
- Create: `server/src/main/java/vn/edu/ltm/server/session/SessionRegistry.java`
- Create: `server/src/main/java/vn/edu/ltm/server/network/ControlTcpServer.java`
- Create: `server/src/main/java/vn/edu/ltm/server/network/ControlClientHandler.java`
- Create: `server/src/main/java/vn/edu/ltm/server/heartbeat/HeartbeatMonitor.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/config/AgentConfig.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/network/ReconnectPolicy.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/network/ControlClient.java`
- Test: `server/src/test/java/vn/edu/ltm/server/device/DeviceProvisioningServiceTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/session/SessionRegistryTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/network/ControlRegistrationIntegrationTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/heartbeat/HeartbeatMonitorTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/network/ReconnectPolicyTest.java`

**Interfaces:**
- Consumes: `ControlEnvelope`, `LengthPrefixedJsonCodec` from Task 1.
- Produces: `DeviceProvisioningService.provision(String deviceId, String displayName, String platform): ProvisionedDevice`; plaintext token is returned once and only its BCrypt hash is stored.
- Produces: `SessionRegistry.register(String deviceId, Socket socket): RegistrationResult`, `touch(String deviceId, Instant now)`, and `removeIfSame(String deviceId, UUID sessionId)`.
- Produces: `ControlClient.start()`, `send(ControlEnvelope)`, `setInboundHandler(Consumer<ControlEnvelope>)`, and `close()`.

- [ ] **Step 1: Write failing database and provisioning tests**

Use a temporary SQLite file. Assert schema initialization is idempotent, provisioning stores a BCrypt hash rather than plaintext, and the returned token verifies against the stored hash.

- [ ] **Step 2: Write failing session and integration tests**

Assert wrong tokens are rejected, an authenticated live duplicate `deviceId` is rejected, stale-session cleanup cannot remove a newer session, heartbeat updates `lastSeen`, and a 15-second timeout marks only the silent device Offline.

- [ ] **Step 3: Run targeted tests and verify they fail**

Run: `./mvnw -pl server,agent -am test -Dtest='*Provisioning*,*SessionRegistry*,*ControlRegistration*,*HeartbeatMonitor*,*ReconnectPolicy*'`

Expected: compilation failure for missing persistence/session classes.

- [ ] **Step 4: Implement the SQLite schema and repositories**

Create `admin_users`, `devices`, `latest_metrics`, `incidents`, `backup_files`, `backup_versions`, `restore_jobs`, and `audit_logs`; enable foreign keys, WAL mode, and a busy timeout for every connection.

- [ ] **Step 5: Implement provisioning and authenticated registration**

Use BCrypt cost 12. Validate nonblank `deviceId`, normalize it to `[A-Za-z0-9._-]{1,64}`, and compare tokens without logging plaintext.

- [ ] **Step 6: Implement session lifecycle, heartbeat timeout, and Agent reconnect policy**

Use reconnect delays of 2, 5, 10, then 30 seconds capped at 30; reset after successful registration.

- [ ] **Step 7: Run targeted tests**

Run: `./mvnw -pl server,agent -am test -Dtest='*Provisioning*,*SessionRegistry*,*ControlRegistration*,*HeartbeatMonitor*,*ReconnectPolicy*'`

Expected: all selected tests PASS.

- [ ] **Step 8: Commit**

Run: `git add server agent && git commit -m "feat: add authenticated agent sessions"`

### Task 3: Real Metrics, Risk Detection, and Local Alerts

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/model/SystemMetrics.java`
- Create: `common/src/main/java/vn/edu/ltm/common/model/RiskType.java`
- Create: `common/src/main/java/vn/edu/ltm/common/model/RiskEvent.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/metrics/MetricsCollector.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/metrics/OshiMetricsCollector.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/risk/RiskThresholds.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/risk/RiskDetector.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/alert/AlertSink.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/alert/JavaFxAlertSink.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/monitoring/MonitoringCoordinator.java`
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/MetricsRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/JdbcMetricsRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/incident/IncidentRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/incident/JdbcIncidentRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/incident/IncidentService.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/risk/RiskDetectorTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/metrics/OshiMetricsCollectorSmokeTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/incident/IncidentServiceTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/network/MetricsControlIntegrationTest.java`

**Interfaces:**
- Produces: `SystemMetrics(Instant collectedAt, double cpuPercent, double ramPercent, double diskPercent, Double temperatureCelsius, Double batteryPercent, boolean charging)`.
- Produces: `MetricsCollector.collect(): SystemMetrics`.
- Produces: `RiskDetector.evaluate(SystemMetrics, Instant): List<RiskEvent>` where each event is `OPEN`, `UPDATE`, or `RESOLVED`.
- Produces: `AlertSink.show(RiskEvent)`.
- Consumes: authenticated `ControlClient` and Server session handler from Task 2.

- [ ] **Step 1: Write failing risk state-machine tests with a fake clock**

Assert CPU opens only after 30 sustained seconds, RAM after 15, disk/temperature/battery immediately, a resolved risk can open again, threshold oscillation does not duplicate `OPEN`, and `temperatureCelsius == null` never opens `OVERHEAT`.

- [ ] **Step 2: Write failing incident lifecycle and control integration tests**

Assert one open incident per `(deviceId, riskType)`, `RISK_RESOLVED` closes it, malformed metrics are rejected, and metrics from an unregistered socket do not persist.

- [ ] **Step 3: Run tests and verify they fail**

Run: `./mvnw -pl common,agent,server -am test -Dtest='*RiskDetector*,*IncidentService*,*MetricsControl*'`

- [ ] **Step 4: Implement OSHI collection behind `MetricsCollector`**

Clamp percentage values to `[0,100]`; represent unavailable battery/temperature as `null`. Keep the smoke test assumption-light: percentages in range and timestamp non-null.

- [ ] **Step 5: Implement risk state tracking, JavaFX alert dispatch, and monitoring scheduling**

Dispatch JavaFX UI work through `Platform.runLater`; keep an in-memory active-risk map so the same active risk produces one popup.

- [ ] **Step 6: Implement Server metrics and incident persistence**

Upsert only latest metrics; persist incident open/update/resolve transitions and send protocol errors for invalid values.

- [ ] **Step 7: Run tests**

Run: `./mvnw -pl common,agent,server -am test -Dtest='*RiskDetector*,*OshiMetricsCollector*,*IncidentService*,*MetricsControl*'`

Expected: selected tests PASS.

- [ ] **Step 8: Commit**

Run: `git add common agent server && git commit -m "feat: monitor resources and report risks"`

### Task 4: Protected Folder Watcher and Durable Agent Backup Queue

**Files:**
- Create: `agent/src/main/resources/db/agent-schema.sql`
- Create: `agent/src/main/java/vn/edu/ltm/agent/persistence/AgentDatabase.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/BackupCandidate.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/BackupQueueItem.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/BackupQueueRepository.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/JdbcBackupQueueRepository.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/BackupEligibility.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/FileStabilityChecker.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/ProtectedFolderWatcher.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/StartupReconciler.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/backup/BackupEligibilityTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/backup/FileStabilityCheckerTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/backup/ProtectedFolderWatcherTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/backup/JdbcBackupQueueRepositoryTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/backup/StartupReconcilerTest.java`

**Interfaces:**
- Produces: `BackupEligibility.evaluate(Path root, Path candidate): EligibilityResult`.
- Produces: `FileStabilityChecker.awaitStable(Path, Duration stableFor, Duration timeout): StableFile`.
- Produces: `BackupQueueRepository.enqueueOrUpdate(BackupCandidate)`, `nextReady(int limit)`, `markUploaded(long id, String sha256)`, and `markRetry(long id, Instant nextAttempt, String error)`.
- Produces: `ProtectedFolderWatcher.start(Consumer<Path> changedFile)` and `close()`.

- [ ] **Step 1: Write failing eligibility/security tests**

Use temporary directories. Assert nested regular files pass; `~$*`, `*.tmp`, `*.swp`, symlinks, paths outside root, unreadable paths, directories, `Recovered/`, and files over 100 MiB are excluded with a reason.

- [ ] **Step 2: Write failing stability/watcher tests**

Assert a file modified during the 3-second window is delayed, recursive subdirectories are registered, newly created subdirectories become watched, and duplicate WatchService events coalesce to one candidate.

- [ ] **Step 3: Write failing durable queue/reconciliation tests**

Assert queue rows survive database reopen, same relative path updates rather than duplicates a pending row, uploaded checksum suppresses unchanged files, and startup scan requeues a changed file exactly once.

- [ ] **Step 4: Run tests and verify they fail**

Run: `./mvnw -pl agent -am test -Dtest='*BackupEligibility*,*FileStability*,*ProtectedFolderWatcher*,*BackupQueue*,*StartupReconciler*'`

- [ ] **Step 5: Implement Agent SQLite state and queue transitions**

Statuses are `PENDING`, `UPLOADING`, and `RETRY`; opening the Agent resets abandoned `UPLOADING` rows to `RETRY`.

- [ ] **Step 6: Implement secure recursive watching and startup reconciliation**

Normalize and resolve all paths against the configured real root before accepting them. Register directories without following symlinks.

- [ ] **Step 7: Run tests**

Run: `./mvnw -pl agent -am test -Dtest='*BackupEligibility*,*FileStability*,*ProtectedFolderWatcher*,*BackupQueue*,*StartupReconciler*'`

Expected: selected tests PASS.

- [ ] **Step 8: Commit**

Run: `git add agent && git commit -m "feat: queue protected folder changes"`

### Task 5: File Upload Protocol and Five-Version Retention

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/transfer/FileTransferCodec.java`
- Create: `common/src/main/java/vn/edu/ltm/common/transfer/TransferResult.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/BackupUploader.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/backup/BackupWorker.java`
- Create: `server/src/main/java/vn/edu/ltm/server/backup/BackupFile.java`
- Create: `server/src/main/java/vn/edu/ltm/server/backup/BackupVersion.java`
- Create: `server/src/main/java/vn/edu/ltm/server/backup/BackupRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/backup/JdbcBackupRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/backup/StoragePathResolver.java`
- Create: `server/src/main/java/vn/edu/ltm/server/backup/BackupService.java`
- Create: `server/src/main/java/vn/edu/ltm/server/network/FileTransferServer.java`
- Create: `server/src/main/java/vn/edu/ltm/server/network/FileUploadHandler.java`
- Test: `common/src/test/java/vn/edu/ltm/common/transfer/FileTransferCodecTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/backup/StoragePathResolverTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/backup/BackupServiceTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/network/FileUploadIntegrationTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/backup/BackupWorkerTest.java`

**Interfaces:**
- Consumes: `FileTransferHeader`, `Hashing`, and Agent queue from Tasks 1 and 4.
- Produces: `FileTransferCodec.writeHeader/readHeader`, `copyExactly(InputStream, OutputStream, long, MessageDigest)`, and `writeResult/readResult`.
- Produces: `BackupService.acceptUpload(FileTransferHeader): UploadReservation`, `complete(UploadReservation, Path tempFile, String actualSha256): TransferResult`, and `abort(UploadReservation)`.
- Produces: `BackupUploader.upload(BackupQueueItem): TransferResult`.

- [ ] **Step 1: Write failing transfer framing tests**

Assert exact-byte copying handles short reads, EOF before declared length fails, trailing bytes remain unread, and invalid/oversized headers are rejected.

- [ ] **Step 2: Write failing storage security and retention tests**

Assert `../`, absolute Windows paths, absolute POSIX paths, separators encoded in device ID, and symlink storage parents are rejected. Assert duplicate SHA returns `UNCHANGED`; the sixth valid version deletes only the oldest after commit.

- [ ] **Step 3: Write failing upload integration tests**

Use an ephemeral file port and temp storage. Cover valid upload, wrong device token, 100 MiB + 1 byte rejection without allocating the payload, disconnect midway, checksum mismatch, source modification causing retry, and Server disk-write failure leaving no published version.

- [ ] **Step 4: Run tests and verify they fail**

Run: `./mvnw -pl common,agent,server -am test -Dtest='*FileTransferCodec*,*StoragePathResolver*,*BackupService*,*FileUpload*,*BackupWorker*'`

- [ ] **Step 5: Implement file protocol and secure atomic storage**

Write to `<storage>/.tmp/<transferId>.part`, fsync/close, verify size and SHA-256, then atomically move where supported. Insert metadata transactionally and delete old version only after the new row/file is valid.

- [ ] **Step 6: Implement Agent uploader and worker retry transitions**

Open one socket per file, authenticate using the device token, stream with a fixed 64 KiB buffer, and use bounded retry backoff without blocking heartbeat threads.

- [ ] **Step 7: Run tests**

Run: `./mvnw -pl common,agent,server -am test -Dtest='*FileTransferCodec*,*StoragePathResolver*,*BackupService*,*FileUpload*,*BackupWorker*'`

Expected: selected tests PASS.

- [ ] **Step 8: Commit**

Run: `git add common agent server && git commit -m "feat: upload and version protected files"`

### Task 6: Administrator Authentication and Safe Restore

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/auth/AdminUserRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/auth/JdbcAdminUserRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/auth/AdminAuthService.java`
- Create: `server/src/main/java/vn/edu/ltm/server/restore/RestoreJob.java`
- Create: `server/src/main/java/vn/edu/ltm/server/restore/RestoreJobRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/restore/JdbcRestoreJobRepository.java`
- Create: `server/src/main/java/vn/edu/ltm/server/restore/RestoreService.java`
- Create: `server/src/main/java/vn/edu/ltm/server/network/FileRestoreHandler.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/restore/RestorePathResolver.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/restore/RestoreHandler.java`
- Test: `server/src/test/java/vn/edu/ltm/server/auth/AdminAuthServiceTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/restore/RestoreServiceTest.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/restore/RestorePathResolverTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/network/RestoreIntegrationTest.java`

**Interfaces:**
- Produces: `AdminAuthService.createInitialAdmin(String username, char[] password)` and `authenticate(String username, char[] password): AuthResult`.
- Produces: `RestoreService.request(long backupVersionId, String targetDeviceId, String adminUsername): RestoreJob`.
- Produces: `RestoreService.claim(String oneTimeToken, String deviceId): ClaimedRestore` and `complete(UUID jobId, TransferResult)`.
- Produces: `RestorePathResolver.resolve(Path protectedRoot, Instant requestedAt, String relativePath): Path`.

- [ ] **Step 1: Write failing authentication tests**

Assert no default password exists, initial admin can be created once, plaintext is never stored, wrong passwords fail, and password `char[]` is cleared by the caller-facing command path.

- [ ] **Step 2: Write failing restore state-machine tests**

Assert unauthenticated calls cannot create jobs, Offline targets remain `PENDING`, Online notification moves to `AVAILABLE`, a one-time token can be claimed exactly once by the matching device before expiry, and restart preserves pending jobs.

- [ ] **Step 3: Write failing Agent path and integration tests**

Assert restore always stays below `Protected/Recovered/<timestamp>`, hostile relative paths fail, an existing destination receives a collision-free name, checksum mismatch deletes the partial file, and successful transfer never overwrites the source file.

- [ ] **Step 4: Run tests and verify they fail**

Run: `./mvnw -pl agent,server -am test -Dtest='*AdminAuth*,*RestoreService*,*RestorePathResolver*,*RestoreIntegration*'`

- [ ] **Step 5: Implement admin authentication and restore job persistence**

Hash with BCrypt cost 12. Generate 256-bit random one-time tokens, store only a token hash, expire after 10 minutes, and write audit log entries for request, claim, completion, and failure.

- [ ] **Step 6: Implement Server download and Agent safe restore**

Agent receives `RESTORE_AVAILABLE`, claims through the file channel, streams to a `.part` file, verifies size/SHA-256, and atomically publishes under `Recovered/`.

- [ ] **Step 7: Run tests**

Run: `./mvnw -pl agent,server -am test -Dtest='*AdminAuth*,*RestoreService*,*RestorePathResolver*,*RestoreIntegration*'`

Expected: selected tests PASS.

- [ ] **Step 8: Commit**

Run: `git add agent server && git commit -m "feat: restore backups through admin approval"`

### Task 7: JavaFX Agent and Server Applications

**Files:**
- Create: `agent/src/main/java/vn/edu/ltm/agent/AgentMain.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/ui/AgentApp.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/ui/AgentViewModel.java`
- Create: `agent/src/main/java/vn/edu/ltm/agent/ui/AgentController.java`
- Create: `agent/src/main/resources/vn/edu/ltm/agent/ui/agent.fxml`
- Create: `agent/src/main/resources/vn/edu/ltm/agent/ui/agent.css`
- Create: `server/src/main/java/vn/edu/ltm/server/ServerMain.java`
- Create: `server/src/main/java/vn/edu/ltm/server/ui/ServerDashboardApp.java`
- Create: `server/src/main/java/vn/edu/ltm/server/ui/LoginController.java`
- Create: `server/src/main/java/vn/edu/ltm/server/ui/DashboardController.java`
- Create: `server/src/main/java/vn/edu/ltm/server/ui/DashboardViewModel.java`
- Create: `server/src/main/resources/vn/edu/ltm/server/ui/login.fxml`
- Create: `server/src/main/resources/vn/edu/ltm/server/ui/dashboard.fxml`
- Create: `server/src/main/resources/vn/edu/ltm/server/ui/dashboard.css`
- Test: `agent/src/test/java/vn/edu/ltm/agent/ui/AgentViewModelTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/ui/DashboardViewModelTest.java`
- Test: `server/src/test/java/vn/edu/ltm/server/ui/LoginControllerTest.java`

**Interfaces:**
- Consumes: all application services from Tasks 2–6.
- Produces: Agent setup/status UI for Server address, device ID/token, protected folder, connection status, latest metrics, queue status, and local alerts.
- Produces: Server login/dashboard UI for device status, latest metrics, incidents, backup versions, provisioning, and restore actions.

- [ ] **Step 1: Write failing view-model/controller tests**

Assert UI state maps `null` temperature to `N/A`, distinguishes Online/Offline/warning states, disables restore without an authenticated admin and selected version, does not expose device tokens after provisioning, and routes background updates through a UI executor abstraction.

- [ ] **Step 2: Run UI unit tests and verify they fail**

Run: `./mvnw -pl agent,server -am test -Dtest='*ViewModel*,*LoginController*'`

- [ ] **Step 3: Implement Agent JavaFX setup and status application**

Persist non-secret configuration in a properties file and restrict token-file permissions where the OS supports POSIX permissions. Validate Server host/ports, unique device ID syntax, and readable protected directory before starting services.

- [ ] **Step 4: Implement Server login and dashboard application**

Use tabs/panels for Devices, Incidents, and Backups. Provisioning shows the token once with a copy action. Restore requires a confirmation dialog naming target device, relative path, and version timestamp.

- [ ] **Step 5: Add orderly startup/shutdown composition**

Application shutdown closes schedulers, watchers, sockets, thread pools, and SQLite resources without calling `System.exit` from domain code.

- [ ] **Step 6: Run unit tests and JavaFX smoke launches**

Run: `./mvnw test`

Run on Linux: `./mvnw -pl server javafx:run` and `./mvnw -pl agent javafx:run`

Expected: unit tests PASS; each application opens and closes without an uncaught exception.

- [ ] **Step 7: Commit**

Run: `git add agent server && git commit -m "feat: add agent and admin JavaFX interfaces"`

### Task 8: End-to-End Verification, Launchers, and Handoff

**Files:**
- Create: `server/src/test/java/vn/edu/ltm/server/e2e/MonitoringBackupRestoreE2ETest.java`
- Create: `scripts/run-server.sh`
- Create: `scripts/run-server.bat`
- Create: `scripts/run-agent.sh`
- Create: `scripts/run-agent.bat`
- Create: `README.md`
- Create: `docs/demo-script.md`
- Modify: `pom.xml`
- Modify: `agent/pom.xml`
- Modify: `server/pom.xml`

**Interfaces:**
- Consumes: complete Agent and Server services.
- Produces: reproducible build, platform launchers, configuration instructions, and one automated golden-path integration test.

- [ ] **Step 1: Write the failing golden-path E2E test**

Start Server services on ephemeral ports and a headless Agent with fake metrics but the real watcher/file clients. Assert registration → heartbeat → sustained RAM alert → saved file upload → second version → authenticated restore → SHA-256-equal file under `Recovered/`.

- [ ] **Step 2: Add resilience cases to the E2E test suite**

Cover Server restart with persistent SQLite/storage, queued upload resuming once, pending restore resuming once, and one broken Agent not blocking a healthy Agent.

- [ ] **Step 3: Run E2E tests and verify they fail before final composition**

Run: `./mvnw -pl server -am test -Dtest='*MonitoringBackupRestoreE2E*'`

- [ ] **Step 4: Complete application composition and build packaging**

Configure executable application artifacts and OS-specific JavaFX profiles. Build Windows artifacts on Windows and Linux artifacts on Linux; do not claim cross-built native installers.

- [ ] **Step 5: Write launchers and README**

Document prerequisites, ports/firewall, Server IP, initial admin creation, device provisioning, Agent configuration, protected-folder rules, backup/restore behavior, known security limits, and exact Windows/Linux commands.

- [ ] **Step 6: Write the classroom demo script**

Demonstrate two Agents, Windows/Linux metrics, a forced threshold alert, two file versions, disconnect/reconnect during backup, admin restore, and proof that the source file was not overwritten.

- [ ] **Step 7: Run the full verification suite**

Run: `./mvnw clean verify`

Expected: all unit/integration tests PASS and all three modules package successfully.

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 8: Perform manual cross-platform acceptance**

On one Windows and one Linux machine, execute TC01–TC17 from the spec and record actual result/evidence in the project report. Temperature may be `N/A`; all other mandatory cases must pass.

- [ ] **Step 9: Commit**

Run: `git add README.md docs scripts pom.xml agent server common && git commit -m "test: verify monitoring backup and restore workflow"`

## Execution Notes

- Tasks 1–6 are headless and testable on the current Linux workspace.
- Task 7 introduces JavaFX only after the network/domain behavior is stable.
- Task 8 requires a real Windows machine for the final Windows acceptance and Windows-specific artifact; the Linux environment cannot prove Windows runtime behavior by itself.
- If schedule slips, preserve registration, heartbeat, CPU/RAM/disk metrics, local alerts, one protected root, SHA-256 backup, five versions, admin restore, and `Recovered/`. Cut battery display, native OS notifications, advanced audit views, and installers before cutting the golden path.
