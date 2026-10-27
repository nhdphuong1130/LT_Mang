# Wi-Fi Direct Server–Client Distance Estimation Design

**Date:** 2026-10-24  
**Status:** Validated with the user  
**Scope:** Add a direct Server–Client radio measurement channel while preserving the existing LAN control and file-transfer channel.

## 1. Goal

Estimate the physical distance between the laptop running the Server and each Client. The infrastructure router must not be treated as the physical anchor. The result is explicitly an estimate rather than a precision ranging measurement.

The design must:

- keep the current LAN connection for control, monitoring, backup, restore, and file transfer;
- create a parallel Wi-Fi Direct link in which the laptop Server is the physical radio anchor;
- accept RSSI only when it is proven to belong to that direct link;
- filter RSSI using Apache Commons Math;
- convert filtered RSSI to distance using a measured log-distance model;
- keep network RTT/jitter separate from physical distance;
- expose failures and uncertainty instead of fabricating a distance.

## 2. Decisions and alternatives

### 2.1 Two-channel architecture

The application uses two concurrent links:

```text
Application data:
Client ---- infrastructure LAN/router ---- Server

Physical measurement:
Client ============ Wi-Fi Direct ========== Server
```

The existing LAN sockets remain bound to the LAN address. The Wi-Fi Direct interface must not become the default route for application traffic.

Alternatives rejected:

- Infrastructure Wi-Fi RSSI measures Client-to-router rather than Client-to-Server.
- Converting LAN RTT to metres is invalid at room scale because scheduling and network processing dominate propagation time.
- A Server hotspot could change or disrupt the existing network topology.
- UWB, BLE beacons, and a second USB Wi-Fi adapter require additional hardware.

### 2.2 Wi-Fi Direct lifecycle

The laptop Server should be the Wi-Fi Direct Group Owner. A Windows Client pairs once with user confirmation and stores the resulting profile. Later Agent launches attempt automatic reconnection.

The first implementation begins with a feasibility spike. It must demonstrate that Linux can remain connected to the infrastructure WLAN while operating as the P2P Group Owner, Windows can remain on the LAN while joining the P2P group, and Linux can read the Client station RSSI from the P2P group interface separately from infrastructure RSSI.

### 2.3 Reusing Apache Commons Math

The project will reuse the stable Maven dependency:

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-math3</artifactId>
    <version>3.6.1</version>
</dependency>
```

`org.apache.commons.math3.filter.KalmanFilter` replaces the hand-written RSSI Kalman update. Apache Commons Math supplies the filter implementation only; it does not define RSSI reference power or a distance model.

Cloning the complete Apache repository and copying selected Apache classes were rejected because a Maven dependency is smaller, traceable, licensed, and maintainable.

The existing `vendor/BLE-RSSI-Proximity-Filter` repository is retained only as historical reference. It is not the primary implementation source because it is a small BLE/ESP32 project, uses different parameters, and has no visible license file.

## 3. Components

### Agent

- `DirectLinkManager`: observes pairing, connection, reconnection, peer identity, and adapter capability.
- `DirectLinkIdentityCollector`: reports the Windows Wi-Fi Direct virtual adapter name, state, and normalized local P2P MAC. It does not report infrastructure RSSI.
- Monitoring integration: sends direct-link telemetry through the existing LAN metrics channel.
- Capability/version reporting: lets the Server distinguish an old Agent from a supported Agent whose direct link is temporarily unavailable.

On Windows, a small native/WinRT helper may be required for discovery, first-use pairing, and retaining the Wi-Fi Direct session. RSSI collection does not depend on that helper because Linux measures received power at the Server anchor.

### Server

- `DirectGroupManager`: creates and observes the Linux P2P Group Owner without replacing the normal LAN route.
- `LinuxP2pStationCollector`: reads only P2P group interfaces with `iw dev <interface> station dump` and returns station MAC, signal, interface, and sample time.
- `DirectRssiValidator`: maps the Agent-reported P2P MAC to a station observed on a P2P group interface and verifies sample age.
- Per-Client distance estimator: maintains independent Kalman state for every Client.
- Calibration service: records known-distance measurements and fits shared path-loss parameters.
- Dashboard: presents direct-link state, raw/filtered RSSI, estimated distance, calibration status, and LAN RTT separately.

## 4. Runtime data flow

1. Server starts its existing LAN listener.
2. Server checks P2P hardware/driver support and creates the Wi-Fi Direct group.
3. Agent connects to the Server through the infrastructure LAN.
4. Server shares the expected P2P group identity over the authenticated control channel.
5. On first use, Windows asks the user to confirm pairing. Subsequent runs attempt automatic reconnection.
6. Agent verifies that its P2P virtual adapter is connected and reports its normalized local P2P MAC over LAN.
7. Server polls the Linux P2P group interface approximately once per second with `iw station dump`.
8. Server maps each station MAC to the authenticated Agent identity received over LAN.
9. Server rejects stale, unverified, infrastructure-interface, or unknown-station samples.
10. Server applies the per-Client Apache Kalman filter.
11. Server converts filtered RSSI to an estimated distance using the calibrated log-distance model.
12. Dashboard updates the Client card and detail panel.

Telemetry should include at least:

```text
measurementSource = WIFI_DIRECT
interfaceName
localP2pMac
sampledAt
agentVersion
capabilities
```

The Server enriches this identity with `p2pGroupInterface`, station RSSI, and the station observation time. A top-level RSSI collected from the Client's infrastructure connection is never accepted as physical-distance input.

## 5. Distance model and evidence

The log-distance model is:

```text
RSSI = A - 10 n log10(d) + error
d = 10 ^ ((A - RSSI) / (10 n))
```

`A = -50 dBm` and `n = 3.0` are development placeholders only. They must not be presented as experimentally proven final values.

The final shared values are fitted once from a reproducible calibration dataset rather than calibrated on every normal application run. For representative Clients N, B, and T, collect 100 valid Wi-Fi Direct RSSI samples at each known distance of 0.5 m, 1 m, 2 m, 3 m, and 5 m. Store raw samples in CSV with Client, distance, timestamp, band, interface, and peer BSSID.

Fit `A` and `n` by least-squares regression over all accepted samples. Report sample count, rejected samples, MAE, RMSE, and a true-distance versus estimated-distance plot. Store the fitted shared values and a calibration identifier in configuration:

```properties
distance.reference-rssi=<fitted A>
distance.path-loss-exponent=<fitted n>
distance.calibration-id=<dataset identifier>
```

The Server dashboard includes an **Hiệu chuẩn khoảng cách** workflow to capture labelled samples, fit the model, export CSV/results, and activate the fitted parameters. If only placeholder values exist, the UI must say that the model is not calibrated.

The indoor log-distance model is supported by propagation literature and standards, but the exact reference RSSI depends on the actual radios, antennas, power control, band, and orientation. Therefore the project dataset, rather than a bare literature default, is the evidence for the selected constants.

## 6. Kalman model

Apache Commons Math receives a one-dimensional state representing RSSI. The initial implementation may retain the current development noise assumptions (`Q = 0.08`, `R = 2.2`) only as placeholders. Calibration records stationary RSSI variance to provide evidence for measurement noise `R`; final tuning and held-out error evaluation determine whether `Q` should change.

Kalman filtering stabilizes noisy samples but does not make an incorrectly sourced or incorrectly calibrated RSSI measurement accurate.

RTT, jitter, TTL, and hop count remain network diagnostics. They are not inputs to the distance equation and are never converted to metres.

## 7. States and error handling

LAN state and direct-link state are independent. Supported direct-link states include:

- `DIRECT_CONNECTED`: verified samples and physical estimate available;
- `DIRECT_PAIRING`: waiting for first-time user confirmation;
- `DIRECT_RECONNECTING`: a saved profile exists and Agent is reconnecting;
- `DIRECT_UNAVAILABLE`: hardware, driver, OS, or capability is missing;
- `WRONG_SIGNAL_SOURCE`: a sample belongs to the router, wrong peer, or wrong interface;
- `STALE_SIGNAL`: no fresh valid sample within the configured time window;
- `AGENT_UPDATE_REQUIRED`: Client runs a protocol version without direct-link telemetry;
- `OFFLINE`: the main Agent session is disconnected.

No physical distance is shown unless the latest sample is fresh and verified as Wi-Fi Direct to the Server. Direct-link failure must not stop LAN monitoring, backup, or file transfer.

Example UI:

```text
Khoảng cách ước lượng: 1.8 m
Nguồn đo: Wi-Fi Direct Server <-> Client
RSSI thô / đã lọc: -60 / -58 dBm
Mô hình: classroom-2026-10
LAN RTT: 4 ms
```

When unavailable:

```text
Khoảng cách: Không khả dụng
Wi-Fi Direct: Đang ghép đôi
LAN: Online - RTT 4 ms
```

## 8. Verification and acceptance

### Feasibility gate

Before full integration, prove all of the following on the actual Server and one Windows Client:

- infrastructure LAN stays connected;
- the Server becomes Wi-Fi Direct Group Owner;
- the Windows virtual adapter becomes connected;
- the expected peer identity can be read;
- direct-link RSSI can be read independently;
- RSSI changes plausibly as physical separation changes.

Failure to read independently identified P2P RSSI blocks physical-distance implementation. The system must not substitute infrastructure RSSI.

### Automated tests

- Apache Kalman wrapper produces expected results for deterministic RSSI sequences.
- Calibration regression recovers known synthetic `A` and `n` within tolerance.
- Distance conversion handles bounds and invalid inputs.
- Source validation rejects wrong station MAC/interface/source and stale timestamps.
- Metrics serialization preserves source identity and version/capabilities.
- Per-Client filters never share state.
- UI mapping covers every direct-link status.
- LAN-only operation remains functional when direct ranging is unavailable.

### Field validation

Use separate calibration and evaluation runs. Evaluate at known distances and report MAE/RMSE, variation across Clients, and environmental limitations. Acceptance requires correct signal provenance, reproducible measurements, monotonic aggregate behavior as distance increases, transparent error reporting, and no fabricated distance. It does not claim centimetre-level accuracy.

## 9. References

- Apache Commons Math Kalman filter documentation: https://commons.apache.org/proper/commons-math/userguide/filter.html
- Apache Commons Math repository: https://github.com/apache/commons-math
- ITU-R P.1238-12, indoor propagation data and prediction methods.
- ETSI TR 125 952, indoor path-loss model.
- NIST, *Cooperative Localization Using Received Signal Strength and Least Squares Estimation Methods* (IPIN 2023).
- Microsoft Native Wi-Fi and Wi-Fi Direct documentation.
- Linux `wpa_supplicant` P2P documentation.

