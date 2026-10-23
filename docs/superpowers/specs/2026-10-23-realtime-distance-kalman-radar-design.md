# Real-Time Client-Server Distance Measurement (Kalman Filter + RSSI Path Loss + Network RTT/TTL) & Radar Visualization Design

**Date:** 2026-10-23
**Status:** Approved

## 1. Overview & Goals
Add real-time physical and network distance estimation between the LAN Backup Server and all connected Clients, built on top of open-source 1D Kalman Filter + Log-Distance Path Loss algorithms (`Silviuh864/BLE-RSSI-Proximity-Filter` & `Shresth1sachiT/RSSI-indoor-localisation`) combined with active Server-side ICMP/TCP RTT, Jitter, and TTL Hop probing.

Key goals:
1. **Pull and integrate open-source Kalman RSSI filter reference (`vendor/BLE-RSSI-Proximity-Filter`)**:
   - Store the cloned GitHub repository in `vendor/BLE-RSSI-Proximity-Filter` and adapt its 1D Kalman Filter (`Q` process noise, `R` measurement noise, state gain `K`) and Log-Distance Path Loss equation into `vn.edu.ltm.common.model.KalmanDistanceEstimator`.
2. **Dual-Source Real-Time Distance Estimation (Works with both new and already-running Agents)**:
   - **Agent-side Wi-Fi Signal Collector (`WifiSignalCollector`)**: Reads real-time RSSI (`dBm`) and signal quality (`%`) on Linux (`/proc/net/wireless` and `iw`) and Windows (`netsh wlan show interfaces`) and attaches `rssiDbm` to the periodic `METRICS` payload.
   - **Server-side Active Network Prober (`RealtimeDistanceTracker`)**: Every 2 seconds, probes active Client IPs via ICMP `ping` (with TCP handshake fallback on port 5050/135/445), extracting real-time **RTT (`ms`)**, **Jitter (`ms`)**, and **TTL / Hop Count**.
   - **Hybrid Kalman Distance Engine**:
     - When direct `rssiDbm` is reported by the Agent, filters `rssiDbm` with a per-device 1D Kalman filter and computes physical distance via Log-Distance Path Loss:
       $$d = 10^{\frac{\text{TxPower} - \text{RSSI}_{\text{Kalman}}}{10 \cdot n}}$$
     - When a connected Client is running an older `agent.jar` without `rssiDbm`, synthesizes an equivalent indoor Wi-Fi RSSI from live wireless RTT + Jitter (`-42 dBm` baseline at `1 ms` degrading logarithmically with wireless airtime delay and jitter), passes it through the per-device 1D Kalman filter, and computes live distance in meters so all connected clients display real-time distance immediately.
3. **Classroom-Ready Presentation UI (3D Spheres + `dxdiag` + Interactive Concentric Radar)**:
   - **Sphere Card Badge**: Shows live distance (`📍 ~X.X m | RTT XX ms | -XX dBm`) on each 3D Client Sphere card.
   - **`dxdiag` Inspector & 3-Tab Dialog**: Includes a dedicated `REALTIME DISTANCE & SIGNAL (Kalman Filter)` section showing `Estimated Distance (m)`, `Proximity Zone`, `Filtered RSSI (dBm)`, `Signal Quality (%)`, `Network RTT (ms)`, `Jitter (ms)`, and `Network Hops (TTL)`.
   - **Concentric Radar Dialog (`🎯 Radar Khoảng cách Realtime`)**: Renders concentric distance rings (`1m`, `3m`, `5m`, `10m`, `15m+`) with the Server/Hotspot at the center and 3D Client Spheres positioned dynamically at their real-time radial distance, auto-refreshing every 2 seconds.

---

## 2. Architecture & Components

### 2.1 `common` Module
- **`DistanceTelemetry` (`vn.edu.ltm.common.model.DistanceTelemetry`)**:
  Record holding:
  - `double rssiRawDbm`
  - `double rssiFilteredDbm`
  - `int signalQualityPercent`
  - `double estimatedMeters`
  - `double rttMs`
  - `double jitterMs`
  - `int ttl`
  - `int hopCount`
  - `String zoneLabel` (`"Rất gần (< 2m)"`, `"Trong phòng (2m - 8m)"`, `"Xa (> 8m)"`)
  - `String method` (`"Wi-Fi RSSI + RTT Kalman"`, `"RTT/Jitter Wireless Kalman"`)
- **`KalmanDistanceEstimator` (`vn.edu.ltm.common.model.KalmanDistanceEstimator`)**:
  - Implements the 1D Kalman Filter ported from `Silviuh864/BLE-RSSI-Proximity-Filter` (`processNoise Q = 0.125`, `measurementNoise R = 2.5`) for RSSI and a second 1D Kalman Filter for RTT.
  - Converts filtered RSSI (`dBm`) + RTT (`ms`) into `DistanceTelemetry`.

### 2.2 `agent` Module
- **`WifiSignalCollector` (`vn.edu.ltm.agent.metrics.WifiSignalCollector`)**:
  - On Linux: parses `/proc/net/wireless` (or `iw dev`) to extract `rssiDbm` (e.g., `-14 dBm` to `-85 dBm`).
  - On Windows: parses `netsh wlan show interfaces` (`Signal : XX%` converted via `dBm = (signalPercent / 2.0) - 100.0`, or direct `dBm` line if present).
- **`MonitoringCoordinator`**:
  - Includes optional `"rssiDbm"` in the `METRICS` JSON payload.

### 2.3 `server` Module
- **`RealtimeDistanceTracker` (`vn.edu.ltm.server.metrics.RealtimeDistanceTracker`)**:
  - Maintains a `ConcurrentHashMap<String, KalmanDistanceEstimator>` per `deviceId` and latest Agent-reported `rssiDbm`.
  - Runs a background virtual-thread probe every 2 seconds for all registered devices with known IP (from `SessionRegistry` or `DxdiagProfile.networkSummary()`), executing `ping -c 1 -W 1 <ip>` (Linux) / `ping -n 1 -w 1000 <ip>` (Windows) to measure `rttMs` and `ttl`, and updating `DistanceTelemetry`.
- **`DashboardDataService` & `DashboardController`**:
  - Attaches `DistanceTelemetry` to `ClientSphereItem`.
  - Renders `📍 ~X.X m | RTT XX ms` on every 3D Sphere card.
  - Appends `REALTIME DISTANCE & SIGNAL (Kalman Filter)` to `dxdiag` text and the `Disk & Network` tab of the `dxdiag` modal.
  - Adds button **`🎯 Radar Khoảng cách Realtime`** to the toolbar, opening a live-updating Radar window (`920x680`, projector-sized fonts) with concentric rings and animated Client spheres positioned by `estimatedMeters`.

---

## 3. Error Handling & Testing
- If a client is `OFFLINE` or unreachable by `ping`, `DistanceTelemetry.offline()` is returned and the sphere is shown at the outer edge of the radar in grey (`OFFLINE`).
- Unit tests:
  - `KalmanDistanceEstimatorTest`: verifies Kalman smoothing reduces RSSI spike variance, calculates accurate distance via Log-Distance Path Loss, and derives hop count from TTL (`64`, `128`, `255`).
  - `RealtimeDistanceTrackerTest`: verifies hybrid estimation from both direct Agent `rssiDbm` and Server RTT/TTL probes.
