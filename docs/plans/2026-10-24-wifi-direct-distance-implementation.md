# Wi-Fi Direct Distance Estimation Implementation Plan

**Goal:** Measure Server–Client Wi-Fi Direct RSSI at the Linux Server, filter it with Apache Commons Math, fit evidence-backed path-loss parameters, and expose honest distance/link states without changing existing LAN traffic.

**Architecture:** The infrastructure LAN remains the control and file-transfer channel. A parallel Wi-Fi Direct group provides radio frames; Linux reads each connected station's RSSI with `iw`, maps the station MAC to an Agent identity reported over LAN, and maintains one Apache Kalman filter per Client. Calibration fits a single shared `A` and `n` from labelled samples and persists the resulting model.

**Tech Stack:** Java 21, Maven, Apache Commons Math 3.6.1, Jackson, JavaFX, JUnit 6, Linux `iw`/NetworkManager/wpa_supplicant, Windows Wi-Fi Direct APIs.

**Status (updated 2026-10-28):** Automated Java/.NET tests, packaging, Windows helper publishing, and shell regression/syntax checks pass. Real Windows pairing now reaches `CONNECTED`; Linux and Windows retain the infrastructure LAN while joining the P2P subnet, Linux reads the authorized Windows station RSSI, and bidirectional P2P traffic has been smoke-tested. Dynamic WPS bootstrap removes the previously required machine-specific manual `wpa_cli set ...` sequence. RSSI/acoustic calibration, held-out two-laptop distance validation, and the broader TC01–TC17 manual acceptance pass remain pending; no >90% field-accuracy claim is made.

---

### Task 1: Align the design and add non-destructive capability diagnostics

**Files:**
- Modify: `docs/plans/2026-10-24-wifi-direct-distance-design.md`
- Create: `scripts/check-wifi-direct-linux.sh`
- Create: `scripts/check-wifi-direct-windows.ps1`

**Step 1: Update the design source of truth**

Replace Client-side RSSI collection with Server-side station RSSI collection. Document that the Agent reports its P2P adapter MAC while Linux reads `iw dev <p2p-group-interface> station dump`. Keep Client-side RSSI as explicitly rejected fallback.

**Step 2: Add the Linux diagnostic**

The POSIX script must use only read-only commands, print the managed interface, P2P device, valid interface combinations, current LAN route, P2P group interfaces, and station RSSI when present. It must never create/disconnect an interface.

**Step 3: Add the Windows diagnostic**

The PowerShell script must list physical Wi-Fi and hidden Microsoft Wi-Fi Direct adapters, normalize MAC addresses, and emit JSON with `supported`, `adapterName`, `status`, and `macAddress`. It must never pair or change an adapter.

**Step 4: Verify diagnostics**

Run:

```bash
bash -n scripts/check-wifi-direct-linux.sh
scripts/check-wifi-direct-linux.sh
```

Expected: exit 0; output includes `P2P-device` and the existing LAN remains connected.

**Step 5: Commit**

```bash
git add docs/plans/2026-10-24-wifi-direct-distance-design.md scripts/check-wifi-direct-linux.sh scripts/check-wifi-direct-windows.ps1
git commit -m "docs: align direct ranging with server RSSI"
```

### Task 2: Reuse Apache Commons Math for RSSI Kalman filtering

**Files:**
- Modify: `pom.xml`
- Modify: `common/pom.xml`
- Create: `common/src/main/java/vn/edu/ltm/common/model/ApacheRssiKalmanFilter.java`
- Create: `common/src/test/java/vn/edu/ltm/common/model/ApacheRssiKalmanFilterTest.java`
- Modify: `common/src/main/java/vn/edu/ltm/common/model/KalmanDistanceEstimator.java`
- Modify: `common/src/test/java/vn/edu/ltm/common/model/KalmanDistanceEstimatorTest.java`

**Step 1: Write failing dependency/behavior tests**

Tests must instantiate `ApacheRssiKalmanFilter(0.08, 2.2)`, assert the first sample initializes to the measurement, assert a single `-80 dBm` spike between `-60 dBm` samples is damped, and assert a non-finite measurement is rejected.

Add an estimator test asserting its method label includes `Apache Commons Math`.

**Step 2: Run RED**

```bash
./mvnw -pl common -Dtest=ApacheRssiKalmanFilterTest,KalmanDistanceEstimatorTest test
```

Expected: test compilation fails because `ApacheRssiKalmanFilter` does not exist.

**Step 3: Add Maven dependency**

Add `commons-math.version=3.6.1` to the parent, manage `org.apache.commons:commons-math3`, and add that dependency to `common/pom.xml`.

**Step 4: Implement the wrapper**

Use one-dimensional matrices `A=[1]`, `H=[1]`, `Q=[processNoise]`, `R=[measurementNoise]`, `P0=[1]`. Initialize state with the first finite sample. Each later update calls `predict()`, `correct(new double[]{measurement})`, and returns `getStateEstimation()[0]`.

**Step 5: Replace only the RSSI filter**

`KalmanDistanceEstimator` delegates RSSI updates to `ApacheRssiKalmanFilter`; RTT filtering stays separate. Preserve the current public constructors and distance behavior. Change method labels to identify Apache Commons Math.

**Step 6: Run GREEN**

```bash
./mvnw -pl common -Dtest=ApacheRssiKalmanFilterTest,KalmanDistanceEstimatorTest test
```

Expected: all selected tests pass.

**Step 7: Commit**

```bash
git add pom.xml common/pom.xml common/src/main common/src/test
git commit -m "feat: filter RSSI with Apache Commons Math"
```

### Task 3: Define source-labelled direct-link telemetry

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/model/DirectLinkStatus.java`
- Create: `common/src/main/java/vn/edu/ltm/common/model/DirectLinkIdentity.java`
- Create: `common/src/test/java/vn/edu/ltm/common/model/DirectLinkIdentityTest.java`
- Modify: `common/src/main/java/vn/edu/ltm/common/model/DistanceTelemetry.java`
- Modify: `common/src/test/java/vn/edu/ltm/common/model/KalmanDistanceEstimatorTest.java`

**Step 1: Write failing validation tests**

Cover normalized uppercase colon-delimited MAC addresses, rejection of malformed MACs, `CONNECTED` requiring interface and MAC, and `UNAVAILABLE` allowing absent identity.

**Step 2: Run RED**

```bash
./mvnw -pl common -Dtest=DirectLinkIdentityTest test
```

Expected: compilation failure because the model is absent.

**Step 3: Implement the model**

Statuses are `CONNECTED`, `PAIRING`, `RECONNECTING`, `UNAVAILABLE`, `WRONG_SIGNAL_SOURCE`, `STALE_SIGNAL`, and `AGENT_UPDATE_REQUIRED`. `DirectLinkIdentity` contains status, interface name, local P2P MAC, sample time, Agent version, and capability set. Its factory normalizes MAC addresses and validates connected identities.

**Step 4: Enrich distance output compatibly**

Append source/status/calibration metadata to `DistanceTelemetry`, while retaining an overload matching the existing ten-argument constructor. `hasPhysicalDistance()` must require both non-negative metres and a verified direct source. Existing waiting/offline factories receive explicit statuses.

**Step 5: Run GREEN and common regression tests**

```bash
./mvnw -pl common test
```

Expected: 0 failures.

**Step 6: Commit**

```bash
git add common/src/main common/src/test
git commit -m "feat: model verified Wi-Fi Direct telemetry"
```

### Task 4: Report the Windows Wi-Fi Direct adapter identity from Agent

**Files:**
- Create: `agent/src/main/java/vn/edu/ltm/agent/metrics/DirectLinkIdentityCollector.java`
- Create: `agent/src/test/java/vn/edu/ltm/agent/metrics/DirectLinkIdentityCollectorTest.java`
- Modify: `agent/src/main/java/vn/edu/ltm/agent/monitoring/MonitoringCoordinator.java`
- Modify: `agent/src/test/java/vn/edu/ltm/agent/monitoring/MonitoringCoordinatorTest.java`
- Deprecate behavior in: `agent/src/main/java/vn/edu/ltm/agent/metrics/WifiSignalCollector.java`

**Step 1: Write parser tests**

Use representative PowerShell JSON for connected/disconnected Microsoft Wi-Fi Direct Virtual Adapters. Assert correct status/MAC/interface and `UNAVAILABLE` for missing support.

**Step 2: Run RED**

```bash
./mvnw -pl agent -am -Dtest=DirectLinkIdentityCollectorTest test
```

Expected: compilation failure for the absent collector.

**Step 3: Implement collection and metrics serialization**

On Windows, execute the read-only PowerShell adapter query with a two-second timeout. On non-Windows platforms report `UNAVAILABLE`. Serialize a nested `directLink` object plus `agentVersion` and capability `WIFI_DIRECT_IDENTITY_V1`. Stop serializing infrastructure `rssiDbm` as a physical measurement.

**Step 4: Run GREEN**

```bash
./mvnw -pl agent -am -Dtest=DirectLinkIdentityCollectorTest,MonitoringCoordinatorTest test
```

Expected: selected tests pass.

**Step 5: Commit**

```bash
git add agent/src/main agent/src/test
git commit -m "feat: report Client Wi-Fi Direct identity"
```

### Task 5: Collect and validate station RSSI at Linux Server

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/LinuxP2pStationCollector.java`
- Create: `server/src/test/java/vn/edu/ltm/server/metrics/LinuxP2pStationCollectorTest.java`
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/DirectLinkRegistry.java`
- Create: `server/src/test/java/vn/edu/ltm/server/metrics/DirectLinkRegistryTest.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/metrics/MetricsMessageHandler.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/metrics/RealtimeDistanceTracker.java`
- Modify: `server/src/test/java/vn/edu/ltm/server/metrics/RealtimeDistanceTrackerTest.java`

**Step 1: Write failing parser and provenance tests**

Parse multiple `iw station dump` blocks and prefer `signal avg`. Assert case-insensitive MAC matching, rejection of infrastructure interface names, rejection after ten seconds, and one independent estimator per device.

**Step 2: Run RED**

```bash
./mvnw -pl server -am -Dtest=LinuxP2pStationCollectorTest,DirectLinkRegistryTest,RealtimeDistanceTrackerTest test
```

Expected: compilation failure for absent classes.

**Step 3: Implement collection**

Discover only group interfaces matching `p2p-*` or explicitly configured names. Run `iw dev <interface> station dump` with a one-second timeout. Return immutable station samples containing interface, station MAC, RSSI, and timestamp.

**Step 4: Integrate metrics identity and tracker**

`MetricsMessageHandler` validates nested `directLink`, updates the registry, and never accepts legacy top-level `rssiDbm` for physical distance. `RealtimeDistanceTracker` looks up the registered MAC in current P2P station samples before updating Apache Kalman state. RTT remains independent.

**Step 5: Run GREEN and server tests**

```bash
./mvnw -pl server -am test
```

Expected: 0 failures.

**Step 6: Commit**

```bash
git add server/src/main server/src/test
git commit -m "feat: measure verified P2P RSSI at Server"
```

### Task 6: Fit and persist an evidence-backed shared distance model

**Files:**
- Create: `common/src/main/java/vn/edu/ltm/common/model/DistanceCalibrationPoint.java`
- Create: `common/src/main/java/vn/edu/ltm/common/model/DistanceCalibrationModel.java`
- Create: `common/src/test/java/vn/edu/ltm/common/model/DistanceCalibrationModelTest.java`
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/DistanceCalibrationStore.java`
- Create: `server/src/test/java/vn/edu/ltm/server/metrics/DistanceCalibrationStoreTest.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/metrics/RealtimeDistanceTracker.java`

**Step 1: Write RED tests with synthetic data**

Generate exact data from `A=-52.4`, `n=2.83` at 1/2/3/5 m. Assert fitted values within `0.01`, zero RMSE for exact data, rejection of invalid distances/RSSI, and round-trip persistence.

**Step 2: Run RED**

```bash
./mvnw -pl server -am -Dtest=DistanceCalibrationModelTest,DistanceCalibrationStoreTest test
```

Expected: compilation failure for absent calibration types.

**Step 3: Implement regression and metrics**

Use Apache `SimpleRegression` with `x=log10(distance)` and `y=RSSI`; fitted intercept is `A`, fitted slope gives `n=-slope/10`. Calculate MAE and RMSE in metres. Persist model ID, timestamp, `A`, `n`, sample count, MAE, and RMSE atomically under the Server data directory.

**Step 4: Inject model into new per-Client estimators**

`RealtimeDistanceTracker` creates estimators from the active model. Existing tracks reset only when the active model changes.

**Step 5: Run GREEN**

```bash
./mvnw -pl server -am test
```

Expected: 0 failures.

**Step 6: Commit**

```bash
git add common/src server/src
git commit -m "feat: fit and persist RSSI distance calibration"
```

### Task 7: Add calibration workflow and truthful dashboard states

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/DistanceCalibrationSession.java`
- Create: `server/src/test/java/vn/edu/ltm/server/metrics/DistanceCalibrationSessionTest.java`
- Modify: `server/src/main/resources/vn/edu/ltm/server/ui/dashboard.fxml`
- Modify: `server/src/main/resources/vn/edu/ltm/server/ui/dashboard.css`
- Modify: `server/src/main/java/vn/edu/ltm/server/ui/DashboardController.java`
- Modify: `server/src/test/java/vn/edu/ltm/server/ui/DashboardControllerSelectionTest.java`

**Step 1: Write session/UI formatting tests**

Test 100 accepted samples per labelled distance, wrong-device/wrong-source rejection, CSV escaping/export, model activation, and status text for pairing/unavailable/wrong-source/stale/uncalibrated states. Add a regression test for the literal `%` in the dxdiag dialog format string.

**Step 2: Run RED**

```bash
./mvnw -pl server -am -Dtest=DistanceCalibrationSessionTest,DashboardControllerSelectionTest test
```

Expected: failures for missing workflow and the current unescaped `%` formatting bug.

**Step 3: Implement UI workflow**

Add **Hiệu chuẩn khoảng cách**. The dialog selects a Client and labelled distance, displays accepted count toward 100, records only verified fresh P2P samples, exports CSV, fits the model after all four distances, and shows fitted `A`, `n`, MAE, and RMSE before activation.

**Step 4: Update display and fix formatter**

Show source, link state, calibration ID/default warning, raw/filtered RSSI, distance, and LAN RTT separately. Escape the dxdiag text-block percent as `%%`.

**Step 5: Run GREEN**

```bash
./mvnw -pl server -am test
```

Expected: 0 failures.

**Step 6: Commit**

```bash
git add server/src
git commit -m "feat: add distance calibration workflow"
```

### Task 8: Provide Wi-Fi Direct pairing/group utilities

**Files:**
- Create: `tools/wifi-direct-windows/README.md`
- Create: `tools/wifi-direct-windows/WifiDirectAgentHelper.csproj`
- Create: `tools/wifi-direct-windows/Program.cs`
- Create: `scripts/setup-wifi-direct-server.sh`
- Create: `scripts/stop-wifi-direct-server.sh`

**Step 1: Implement Windows helper tests around pure parsing/state code**

The helper discovers the configured Linux peer with `Windows.Devices.WiFiDirect`, requests pairing on first use, retains the session, and writes line-delimited JSON status. Separate pure state formatting so it can be tested without radio hardware.

**Step 2: Build the Windows helper**

```bash
dotnet test tools/wifi-direct-windows
dotnet publish tools/wifi-direct-windows/WifiDirectAgentHelper.csproj -c Release -f net10.0-windows10.0.19041.0 -r win-x64 --self-contained true -p:PublishSingleFile=true
```

Expected: tests pass and a Windows executable is produced.

**Step 3: Add guarded Linux group scripts**

Scripts first print current LAN state, require an explicit physical interface and peer, use NetworkManager's `wifi-p2p` profile or the existing supplicant control path, and abort if the managed LAN would be disconnected. The stop script removes only the named project P2P group/profile.

**Step 4: Perform the real feasibility gate**

On the Windows Client, start the helper and confirm pairing. On Linux, confirm both the original LAN and a P2P group interface, then run:

```bash
iw dev <p2p-group-interface> station dump
```

Expected: the Client P2P MAC and signal are visible while `192.168.1.33:5050` remains reachable.

**Step 5: Commit**

```bash
git add tools/wifi-direct-windows scripts
git commit -m "feat: add Wi-Fi Direct pairing utilities"
```

### Task 9: Package, verify, and deploy

**Files:**
- Modify: `README.md`
- Modify: `docs/demo-script.md`

**Step 1: Document setup and limitations**

Document one-time pairing, LAN/P2P separation, calibration procedure, model provenance, permissions, troubleshooting statuses, and the fact that RSSI distance is approximate.

**Step 2: Run full verification**

```bash
./mvnw clean verify
dotnet test tools/wifi-direct-windows
bash -n scripts/check-wifi-direct-linux.sh scripts/setup-wifi-direct-server.sh scripts/stop-wifi-direct-server.sh
```

Expected: all Java/.NET tests pass and all scripts parse.

**Step 3: Build distributables**

```bash
./mvnw package -DskipTests
sha256sum agent/target/agent-1.0-SNAPSHOT-executable.jar server/target/server-1.0-SNAPSHOT-executable.jar
```

Copy the new Agent artifact to the workspace distribution location only after preserving the previous file as a dated backup.

**Step 4: Runtime smoke test**

Start Server and local Agent, verify LAN metrics/dxdiag/backup remain operational, verify unpaired clients show an explicit direct-link state instead of a distance, then pair one real Windows Client and verify direct RSSI/distance.

**Step 5: Commit documentation**

```bash
git add README.md docs/demo-script.md
git commit -m "docs: explain Wi-Fi Direct ranging setup"
```

