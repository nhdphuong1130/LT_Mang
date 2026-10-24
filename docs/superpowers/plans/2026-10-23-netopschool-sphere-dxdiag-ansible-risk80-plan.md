# NetOp School Sphere Dashboard, `dxdiag` Profile, Multi-Client Broadcast, Ansible Sync, and 80% / Risky-File Alerting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 5 NetOp School-inspired features: 80% resource alerting + risky file blocking, cross-platform `dxdiag` hardware profile (`DxdiagProfile`), client permission controls (`allowBackup`, `allowRestore`, `isolated`), multi-client file broadcast + Ansible playbook sync, and the 3D Sphere + `dxdiag` Server Dashboard UI.

**Architecture:** Extend `common` with `DxdiagProfile` and `RiskType.RISKY_FILE`; update `agent` (`RiskThresholds`, `BackupEligibility`, `OshiDxdiagCollector`, `MonitoringCoordinator`, `AgentApp`) to enforce 80% thresholds, block/report risky files, and send `dxdiag` profiles; update `server` (`Database`, `Device`, `JdbcDeviceRepository`, `DeviceProvisioningService`, `FileUploadHandler`, `BackupService`, `RestoreService`, `AnsibleSyncService`, `DashboardDataService`, `DashboardController`, `dashboard.fxml`) with permission enforcement, multi-client broadcast, Ansible sync, and Sphere + `dxdiag` UI.

**Tech Stack:** Java 21, JavaFX 21, OSHI 7.6.1, SQLite JDBC, Jackson, JUnit Jupiter, Ansible.

**Spec:** `docs/superpowers/specs/2026-10-23-netopschool-sphere-dxdiag-ansible-risk80-design.md`

---

### Task 1: 80% System Thresholds & Risky File Detection (`common` & `agent`)
- [x] Update `RiskType.java` to add `RISKY_FILE`.
- [x] Update `RiskThresholds.java` defaults to `80%` for CPU, RAM, and Disk.
- [x] Update `BackupEligibility.java` to detect risky extensions (`.exe`, `.bat`, `.cmd`, `.vbs`, `.ps1`, `.scr`, `.msi`, `.sh`, `.com`, `.pif`) and risky content signatures (`ransomware`, `malware`, `rm -rf /`, `format c:`, `powershell -enc`, `mimikatz`), returning `EligibilityResult` with `risky() == true`.
- [x] Update `AgentApp.java` and `MonitoringCoordinator.java` to send `RISK_ALERT` (`RISKY_FILE`) to Server when a risky file is detected.
- [x] Verify with `RiskDetectorTest` and `BackupEligibilityTest`.

### Task 2: Cross-Platform `dxdiag` Hardware Profile (`common` & `agent` & `server`)
- [x] Create `vn.edu.ltm.common.model.DxdiagProfile` record.
- [x] Create `vn.edu.ltm.agent.metrics.OshiDxdiagCollector` to collect System, Display, Disk, and Network `dxdiag` fields on both Windows and Linux.
- [x] Include `dxdiag` JSON in `MonitoringCoordinator` `METRICS` payload and store `dxdiag_json` on `devices` in `server`.
- [x] Verify with `OshiMetricsCollectorSmokeTest` and `OshiDxdiagCollectorTest`.

### Task 3: Per-Client Permissions (`allowBackup`, `allowRestore`, `isolated`) (`server`)
- [x] Add `allow_backup`, `allow_restore`, and `dxdiag_json` columns to `devices` table in `Database.java`.
- [x] Update `Device.java`, `DeviceRepository.java`, `JdbcDeviceRepository.java`, and `DeviceProvisioningService.java` to read/update permissions and `dxdiag`.
- [x] Enforce `allowBackup` in `FileUploadHandler.java` and `allowRestore` in `RestoreService.java`.
- [x] Verify with `DeviceProvisioningServiceTest` and `RestoreServiceTest`.

### Task 4: Multi-Client File Broadcast & Ansible Initialization (`ansible/` & `server`)
- [x] Create `ansible/inventory.ini` and `ansible/init_sync_file.yml`.
- [x] Add Server-local file import and multi-client broadcast operations to `BackupService` / `RestoreService`.
- [x] Create `vn.edu.ltm.server.ansible.AnsibleSyncService` to execute `ansible-playbook` (with native fallback) to generate `THONG_BAO_DONG_BO_LAN.txt` and broadcast it to all permitted clients.
- [x] Verify the native fallback and permitted-client filtering with `AnsibleSyncServiceTest`.

### Task 5: NetOp School Sphere Dashboard UI + `dxdiag` Inspector & Modal (`server`)
- [x] Update `DashboardDataService.java` to expose structured client sphere data (with `status`, `isolated`, `allowBackup`, `allowRestore`, resource percentages, active-alert state, and `dxdiagProfile`).
- [x] Update `dashboard.fxml`, `dashboard.css`, and `DashboardController.java` to render:
  - Interactive 3D **Sphere Grid (`FlowPane`)** for all clients (Green = Normal, Red = Alert >=80% or Risky File, Amber = Restricted Permissions, Gray = Offline/Isolated).
  - Right-hand **`dxdiag` & Permissions Inspector** (single-click) + **Dedicated `dxdiag` Multi-Tab Dialog** (`System | Display | Storage & Network` on double-click or button click).
  - Multi-client broadcast buttons (**"Gửi file đã chọn cho TẤT CẢ Client"**, **"Chọn file từ Server gửi cho nhiều Client"**, **"Ansible: Khởi tạo & Đồng bộ File văn bản cho tất cả Client"**).
- [x] Run the full automated test suite and package `agent.jar` and `server.jar`.
- [ ] Restart the Server and complete the live dashboard smoke test.
