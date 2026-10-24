# Real-Time Client-Server Distance Measurement & Radar Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pull the open-source Kalman RSSI proximity filter repository (`Silviuh864/BLE-RSSI-Proximity-Filter`), implement real-time hybrid Wi-Fi RSSI + Network RTT/Jitter/TTL distance estimation (`KalmanDistanceEstimator`, `WifiSignalCollector`, `RealtimeDistanceTracker`), and display live distance on 3D Client Spheres, `dxdiag` panels, and a Concentric Radar window.

**Architecture:** Clone `Silviuh864/BLE-RSSI-Proximity-Filter` into `vendor/BLE-RSSI-Proximity-Filter`; add `DistanceTelemetry` and `KalmanDistanceEstimator` in `common`; add cross-platform `WifiSignalCollector` in `agent` and attach `rssiDbm` to `METRICS`; add `RealtimeDistanceTracker` in `server` that combines Agent `rssiDbm` with active 2-second Server-to-Client ICMP/TCP RTT + TTL probes; surface live distance in `DashboardDataService`, `DashboardController`, and `dashboard.fxml` including a Concentric Radar modal.

**Tech Stack:** Java 21, JavaFX 21, OSHI, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-23-realtime-distance-kalman-radar-design.md`

## Global Constraints
- Physical metres require the updated Agent's real RSSI. Older clients expose RTT/Jitter/TTL only and show `Đang chờ RSSI`; LAN latency must never be presented as physical distance.
- Must use large projector-friendly typography (`16px–24px bold`) suitable for a 50-person classroom.

---

### Task 1: Pull Open-Source GitHub Repo & Implement `DistanceTelemetry` + `KalmanDistanceEstimator` (`common`)

**Files:**
- Create: `vendor/BLE-RSSI-Proximity-Filter` (cloned from `https://github.com/Silviuh864/BLE-RSSI-Proximity-Filter.git`)
- Create: `common/src/main/java/vn/edu/ltm/common/model/DistanceTelemetry.java`
- Create: `common/src/main/java/vn/edu/ltm/common/model/KalmanDistanceEstimator.java`
- Test: `common/src/test/java/vn/edu/ltm/common/model/KalmanDistanceEstimatorTest.java`

- [x] **Step 1: Clone `Silviuh864/BLE-RSSI-Proximity-Filter` into `vendor/BLE-RSSI-Proximity-Filter`**
- [x] **Step 2: Write failing test `KalmanDistanceEstimatorTest`**
- [x] **Step 3: Implement `DistanceTelemetry` and `KalmanDistanceEstimator`**
- [x] **Step 4: Run `KalmanDistanceEstimatorTest` and verify it passes**

---

### Task 2: Agent Wi-Fi Signal Collector & `METRICS` Payload Extension (`agent`)

**Files:**
- Create: `agent/src/main/java/vn/edu/ltm/agent/metrics/WifiSignalCollector.java`
- Modify: `agent/src/main/java/vn/edu/ltm/agent/monitoring/MonitoringCoordinator.java`
- Test: `agent/src/test/java/vn/edu/ltm/agent/metrics/WifiSignalCollectorTest.java`

- [x] **Step 1: Write `WifiSignalCollectorTest` verifying Linux `/proc/net/wireless` and Windows `netsh wlan show interfaces` parsing**
- [x] **Step 2: Implement `WifiSignalCollector`; physical-distance telemetry was later superseded by verified Server-side Wi-Fi Direct RSSI**
- [x] **Step 3: Run `WifiSignalCollectorTest` and `MonitoringCoordinatorTest`**

---

### Task 3: Server `RealtimeDistanceTracker` & `MetricsMessageHandler` Integration (`server`)

**Files:**
- Create: `server/src/main/java/vn/edu/ltm/server/metrics/RealtimeDistanceTracker.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/metrics/MetricsMessageHandler.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/ui/DashboardDataService.java`
- Modify: `server/src/main/java/vn/edu/ltm/server/ui/ServerDashboardApp.java`
- Test: `server/src/test/java/vn/edu/ltm/server/metrics/RealtimeDistanceTrackerTest.java`

- [x] **Step 1: Write `RealtimeDistanceTrackerTest`**
- [x] **Step 2: Implement `RealtimeDistanceTracker` with active 2-second ping/TCP RTT + TTL probing; physical metres now require verified Server-side Wi-Fi Direct RSSI**
- [x] **Step 3: Wire `RealtimeDistanceTracker` into `MetricsMessageHandler`, `DashboardDataService`, and `ServerDashboardApp`**
- [x] **Step 4: Run `RealtimeDistanceTrackerTest` and `DashboardDataServiceTest`**

---

### Task 4: Server Dashboard UI — Sphere Distance Badges, `dxdiag` Distance Section & Concentric Radar Dialog (`server`)

**Files:**
- Modify: `server/src/main/resources/vn/edu/ltm/server/ui/dashboard.fxml`
- Modify: `server/src/main/java/vn/edu/ltm/server/ui/DashboardController.java`

- [x] **Step 1: Add distance badge (`📍 ~X.X m | RTT XX ms | -XX dBm`) to 3D Sphere cards and `REALTIME DISTANCE & SIGNAL (Kalman Filter)` section to `dxdiag` view & modal**
- [x] **Step 2: Add `🎯 Radar Khoảng cách Realtime` button and interactive Concentric Radar modal (`openRadarDialog`) that refreshes live every 2 seconds**
- [ ] **Step 3: Restart running services and verify live Wi-Fi Direct distance metrics with a real Windows Client**
