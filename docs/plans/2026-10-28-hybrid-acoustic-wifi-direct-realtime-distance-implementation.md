# Hybrid Acoustic + Wi-Fi Direct Realtime Distance Implementation Plan

> Execute task-by-task with TDD. Do not claim the >90% field target until the acceptance dataset proves it.

**Goal:** Add configurable, privacy-preserving two-way ultrasonic ranging between Server and Agent laptops, with Wi-Fi fallback and explicit confidence/status reporting.

**Architecture:** `common` owns validated configuration, protocol DTOs, signal processing, ranging math, and confidence scoring. Each endpoint wraps Java Sound behind testable interfaces. The Agent handles correlated requests; the Server serializes per-client sessions, derives accepted samples, and feeds the existing realtime tracker. Raw audio never crosses the network or reaches storage.

**Tech Stack:** Java 21, Java Sound, Jackson, Apache Commons Math, Maven, JUnit 6, existing control TCP protocol and JavaFX dashboard.

---

### Task 1: Validated external ranging configuration

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/AcousticRangingConfig.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/AcousticRangingConfigLoader.java`
- Create: `common/src/main/resources/acoustic-ranging-defaults.properties`
- Test: `common/src/test/java/vn/edu/ltm/common/ranging/AcousticRangingConfigTest.java`
- Test: `common/src/test/java/vn/edu/ltm/common/ranging/AcousticRangingConfigLoaderTest.java`

Write failing tests for loading, override precedence, malformed values, and cross-field validation. Implement one immutable config and a classpath-default plus optional external-file loader. Run focused tests, then commit.

### Task 2: Deterministic signal and distance core

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/LinearChirpGenerator.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/ChirpDetection.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/MatchedFilterDetector.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/TwoWayDistanceCalculator.java`
- Test: matching files under `common/src/test/java/vn/edu/ltm/common/ranging/`

Test waveform bounds, known sample delays with noise, multipath rejection, and round-trip-to-centimetre conversion. Implement windowed chirps, normalized matched filtering, diagnostics, and configurable speed/latency corrections. Run focused tests, then commit.

### Task 3: Typed protocol and confidence/status model

**Files:**
- Modify: `common/src/main/java/vn/edu/ltm/common/protocol/MessageType.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/RangingCapability.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/RangingRequest.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/RangingResult.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/RangingStatus.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/RangingConfidenceScorer.java`
- Test: protocol JSON round-trip and confidence boundary tests.

Assert that payloads contain no audio, add four ranging message types, and implement configurable scoring with typed failure states. Run all common tests, then commit.

### Task 4: Java Sound endpoint and hardware gate

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/AudioEndpoint.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/JavaSoundAudioEndpoint.java`
- Create: `common/src/main/java/vn/edu/ltm/common/ranging/AudioHardwareGate.java`
- Test: format/device selection, PCM conversion, cleanup, and rejection paths.

Use bounded in-memory buffers. Reject devices that fail configured sample-rate, ultrasonic-band, SNR, or leakage gates. Run common tests, then commit.

### Task 5: Agent ranging responder

**Files:**
- Create: `agent/src/main/java/vn/edu/ltm/agent/ranging/AgentRangingService.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/ranging/AgentRangingServiceTest.java`
- Modify: `agent/src/main/java/vn/edu/ltm/agent/ui/AgentApp.java`

Test capability publication, correlation, configured response delay, timeout/abort, send failure, and shutdown. Route ranging messages alongside restore messages on a dedicated serial executor. Run agent tests, then commit.

### Task 6: Server session coordinator

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/ranging/RangingCoordinator.java`
- Create: `server/src/main/java/vn/edu/ltm/server/ranging/RangingSample.java`
- Test: `server/src/test/java/vn/edu/ltm/server/ranging/RangingCoordinatorTest.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/network/ControlClientHandler.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/ui/ServerDashboardApp.java`

Test one in-flight request per device, correlation, retry, timeout, fairness, reconnect cleanup, and capability failure. Schedule non-overlapping configured slots. Run server tests, then commit.

### Task 7: Confidence-gated fusion and dashboard

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/ranging/HybridDistanceFusion.java`
- Test: `server/src/test/java/vn/edu/ltm/server/ranging/HybridDistanceFusionTest.java`
- Modify: `common/src/main/java/vn/edu/ltm/common/model/DistanceTelemetry.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/metrics/RealtimeDistanceTracker.java`
- Modify: dashboard view-model/FXML files.

Test threshold gating, rolling stability, centimetre formatting, staleness, and labelled fallback. Acoustic is primary; Wi-Fi supplies only fallback/range plausibility and is never silently averaged. Run common/server tests, then commit.

### Task 8: Acceptance harness, operations docs, and verification

**Files:**
- Create: `server/src/test/java/vn/edu/ltm/server/ranging/RangingAcceptanceDatasetTest.java`
- Create: `docs/acoustic-ranging-setup.md`
- Modify: `README.md`
- Update the DOCX proposal only after code validation through the document-editing workflow.

Add a held-out CSV evaluator reporting hit rate within configured tolerance at 0.5/1/2/3/5 m, with calibration and validation sessions separated. Document mixer discovery, external configuration, calibration, privacy, and fallbacks. Run `./mvnw clean verify`, smoke startup, and `git diff --check`. The target passes only when held-out hit rate is at least 90%; otherwise keep field validation explicitly incomplete.
