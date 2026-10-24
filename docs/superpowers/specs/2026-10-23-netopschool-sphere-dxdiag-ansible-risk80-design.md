# NetOp School Sphere Dashboard, Cross-Platform `dxdiag` Profile, Multi-Client Broadcast, Ansible Sync, and 80% / Risky-File Alerting Design

**Date:** 2026-10-23
**Status:** Approved

---

## 1. Overview & Goals

This specification extends the LAN Monitoring & Backup system with five capabilities inspired by **NetOp School**:

1. **Sphere Client View & Cross-Platform `dxdiag` Hardware Profile (Windows & Linux):**
   - Represent each client device on the Server Dashboard as an interactive 3D-styled sphere (`Circle` with `RadialGradient` and `DropShadow`).
   - Collect full `dxdiag`-equivalent hardware and network diagnostics on both Windows and Linux via OSHI (`DxdiagProfile`) and display them in a side panel (single-click) and a multi-tab `dxdiag` dialog (`System | Display | Storage & Network` on double-click or button press).
2. **Client Permission Restrictions (NetOp School Access Control):**
   - Per-device permissions configurable by the Administrator on the Server Dashboard:
     - `allowBackup`: whether the client is permitted to upload backup files to the Server.
     - `allowRestore`: whether the client is permitted to receive files/broadcasts from the Server.
     - `isolated`: complete network isolation of the client from the Server.
3. **Copy File from Server to Multiple / All Clients (Broadcast & Multi-Target Transfer):**
   - Allow the Administrator to transfer any selected backup version, device folder, or arbitrary local file on the Server to a single client, a custom selection of clients, or **All Clients** simultaneously.
4. **Ansible Initialization & Synchronized Text File Distribution:**
   - Provide an Ansible inventory (`ansible/inventory.ini`) and playbook (`ansible/init_sync_file.yml`) that initializes a canonical synchronized text document (`THONG_BAO_DONG_BO_LAN.txt`) and distributes it across all clients, with a one-click trigger on the Server Dashboard that executes the playbook and broadcasts the generated text file to every permitted client.
5. **Risky File Detection & 80% System Resource Alerting:**
   - Lower default CPU, RAM, and Disk alert thresholds in `RiskThresholds` to **80%** (`cpuPercent = 80.0`, `ramPercent = 80.0`, `diskPercent = 80.0`).
   - Inspect files in the client's protected directory for risky executable/script extensions (`.exe`, `.bat`, `.cmd`, `.vbs`, `.ps1`, `.scr`, `.msi`, `.sh`, `.com`, `.jar`) and malicious content signatures (`ransomware`, `malware`, `rm -rf /`, `format c:`, `powershell -enc`). Block risky files from backup, alert the local user, and send a `RISK_ALERT` (`RiskType.RISKY_FILE`) to the Server `Incidents` log and turn the client's Sphere red.

---

## 2. Architecture & Data Model Changes

### 2.1 `DxdiagProfile` (`common` & `agent` & `server`)
- New record `vn.edu.ltm.common.model.DxdiagProfile`:
  - `String computerName`
  - `String osNameVersion`
  - `String systemManufacturerModel`
  - `String biosVersion`
  - `String processorSummary` (CPU model, physical cores, logical threads, max frequency)
  - `String memorySummary` (Total physical RAM & available RAM in GiB)
  - `String gpuSummary` (Graphics card name(s) and VRAM)
  - `String diskSummary` (Total & free space across local file stores)
  - `String networkSummary` (Primary IPv4 address, MAC address, interface name)
- Collected by `OshiDxdiagCollector` on the Agent (works on both Windows and Linux via OSHI `SystemInfo`) and sent inside the `REGISTER` (and periodic `METRICS`) payload as JSON field `dxdiag`.
- Persisted in SQLite `devices.dxdiag_json TEXT` on the Server.

### 2.2 Per-Device Permissions (`server`)
- SQLite migration on `devices` table adds:
  - `allow_backup INTEGER NOT NULL DEFAULT 1`
  - `allow_restore INTEGER NOT NULL DEFAULT 1`
- `Device` record updated with `boolean allowBackup` and `boolean allowRestore` (in addition to `boolean isolated`).
- Enforcement points:
  - `FileUploadHandler`: checks `provisioning.isBackupAllowed(deviceId)`. If `false`, rejects the upload with `TransferResult(Status.REJECTED, "Backup disabled by administrator")`.
  - `RestoreService`: checks `devices.findById(targetDeviceId).allowRestore()`. If `false`, skips or rejects file delivery to that client.

### 2.3 Multi-Client File Broadcast & Local Server File Push (`server`)
- `BackupService.importServerLocalFile(Path localFile, List<String> targetDeviceIds)`:
  - Copies an arbitrary file chosen by the Administrator on the Server into each target device's backup storage folder (`~/.ltm-server/backup-storage/<deviceId>/<fileName>`), records the `backup_versions` entry, and returns the created version IDs.
- `RestoreService.requestMultiClient(long backupVersionId, List<String> targetDeviceIds, String adminUsername)`:
  - Iterates over all permitted target devices (`!isolated && allowRestore`) and queues + offers the file transfer to each client.

### 2.4 Ansible Playbook & One-Click Sync (`ansible/` & `server`)
- Files created at repository root (and worktree root):
  - `ansible/inventory.ini`: defines `[lan_server]`, `[linux_clients]`, and `[windows_clients]` host groups.
  - `ansible/init_sync_file.yml`: Ansible playbook that generates the canonical synchronized text file (`~/.ltm-server/ansible-sync/THONG_BAO_DONG_BO_LAN.txt`) with timestamp, system policy, and client synchronization header, and syncs to reachable targets.
- `AnsibleSyncService` on the Server:
  - Runs `ansible-playbook -i ansible/inventory.ini ansible/init_sync_file.yml` (falling back gracefully to direct template generation if `ansible-playbook` binary is not installed on the host OS), then imports `THONG_BAO_DONG_BO_LAN.txt` into `BackupService` and broadcasts it via `RestoreService` to all permitted clients.

### 2.5 Risky File Detection & 80% System Thresholds (`common` & `agent` & `server`)
- `RiskType` enum adds `RISKY_FILE`.
- `RiskThresholds.defaults()` updated to:
  - `cpuPercent = 80.0` (duration `0s` for immediate responsive alerting when crossing 80%)
  - `ramPercent = 80.0` (duration `0s`)
  - `diskPercent = 80.0` (duration `0s`)
  - `temperatureCelsius = 80.0`
  - `batteryPercent = 20.0`
- `BackupEligibility`:
  - Adds risky file inspection:
    - Risky extensions: `.exe`, `.bat`, `.cmd`, `.vbs`, `.ps1`, `.scr`, `.msi`, `.sh`, `.com`, `.pif`
    - Risky content signatures (checked on text/script files up to 64 KiB): `ransomware`, `malware`, `rm -rf /`, `format c:`, `powershell -enc`, `mimikatz`
  - Returns `EligibilityResult` with ` boolean risky` flag and reason `"Risky file blocked: <detail>"`.
- When `AgentApp` / `StartupReconciler` encounters a risky file:
  - Blocks it from entering `backup_queue`.
  - Emits a `RISK_ALERT` (`RiskType.RISKY_FILE`) over `ControlClient` to the Server (recorded as an open incident in `incidents`) and notifies the local Agent UI.

---

## 3. UI Design (Server Dashboard)

1. **Tab `Devices (NetOp School Sphere View)`:**
   - **Left / Center (`FlowPane` inside `ScrollPane`):** Grid of client **Spheres**. Each sphere renders a 3D radial-gradient circle (`radius = 38`) with color coding:
     - **Green (`#2ecc71`):** Online & healthy (`CPU/RAM/Disk < 80%`, no active risky file alert).
     - **Red (`#e74c3c`):** Active alert (`>= 80%` resource usage or `RISKY_FILE` incident).
     - **Amber (`#f39c12`):** Online with restricted permissions (`!allowBackup` or `!allowRestore`).
     - **Gray (`#95a5a6`):** Offline or Isolated (`ISOLATED`).
   - **Right Side Panel (`dxdiag` & Permissions Inspector):**
     - Displays the selected client's `dxdiag` hardware summary (`System`, `Display`, `Storage & Network`), live metrics (`CPU`, `RAM`, `Disk`), and permission checkboxes (`Cho phép Backup`, `Cho phép Nhận file`, `Cô lập máy`) + button **"Xem cửa sổ dxdiag chi tiết"**.
   - **Double-clicking a Sphere (or clicking `"Xem cửa sổ dxdiag chi tiết"`):**
     - Opens a dedicated `dxdiag` modal window with tabs `System`, `Display`, `Disk & Network` styled after Windows `dxdiag` for both Windows and Linux clients.
2. **Tab `Backups & Broadcast (NetOp School File Distribution)`:**
   - Adds target selection options:
     - Specific client (`ComboBox`) OR **"Tất cả Client (Broadcast)"** checkbox/button.
     - Button **"Chọn file từ Server gửi cho nhiều Client"** (`FileChooser`).
     - Button **"Ansible: Khởi tạo & Đồng bộ File văn bản cho tất cả Client"**.

---

## 4. Testing & Verification Plan

1. **Unit Tests:**
   - `RiskDetectorTest`: verify alerts trigger when CPU, RAM, or Disk reaches `80%`.
   - `BackupEligibilityTest`: verify risky file extensions (`.exe`, `.bat`, `.ps1`, `.sh`) and malicious content signatures are flagged `risky = true` and excluded from backup.
   - `OshiDxdiagCollectorTest`: verify non-empty `DxdiagProfile` collection on the current OS.
   - `DevicePermissionsTest`: verify `allowBackup` and `allowRestore` persistence and enforcement in `FileUploadHandler` and `RestoreService`.
   - `AnsibleSyncServiceTest`: verify `ansible/init_sync_file.yml` creates `THONG_BAO_DONG_BO_LAN.txt` and broadcasts it to all permitted clients.
2. **Integration Verification:**
   - Package updated `server` and `agent.jar` executable JARs and verify live Dashboard UI.
