# Dynamic Wi-Fi Direct Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Linux Wi-Fi Direct setup configure a dynamic, push-button WPS identity before activating NetworkManager's P2P profile.

**Architecture:** Keep NetworkManager responsible for connection lifecycle. Add small shell functions that derive and normalize local identity, then call the live supplicant through `wpa_cli` before discovery and profile creation.

**Tech Stack:** Bash, NetworkManager/nmcli, wpa_supplicant/wpa_cli, iw, busctl, .NET helper tests, Maven.

**Spec:** `docs/superpowers/specs/2026-10-28-dynamic-wifi-direct-bootstrap-design.md`

## Global Constraints

- No machine-specific interface, MAC, hostname, DMI value, or country is embedded in code.
- Dry-run must not invoke mutating commands.
- A WPS setup failure must happen before a NetworkManager profile is created.
- The infrastructure LAN and scoped rollback behavior remain unchanged.

## Review Focus

- Missing DMI fields use safe runtime fallbacks.
- Spaces/control characters cannot split or corrupt `wpa_cli` commands.
- A missing regulatory country does not set an invalid country.
- Non-root execution uses privilege escalation only in apply mode.
- Failed WPS configuration does not create or delete a connection profile.

---

### Task 1: Shell regression harness and dynamic WPS bootstrap

**Files:**
- Create: `scripts/tests/setup-wifi-direct-server-test.sh`
- Modify: `scripts/setup-wifi-direct-server.sh`

**Interfaces:**
- Consumes: physical interface and peer MAC command arguments; runtime hostname, DMI, and `iw reg get` data.
- Produces: configured WPS identity on the live supplicant before NetworkManager P2P activation.

- [ ] Write end-to-end shell tests with fake commands for dry-run, dynamic apply, and WPS failure.
- [ ] Run the shell test and verify it fails because no WPS calls are made.
- [ ] Add derivation, normalization, privileged `wpa_cli` execution, and fail-fast WPS setup.
- [ ] Run shell syntax and regression tests and verify they pass.

### Task 2: Documentation and integration status

**Files:**
- Modify: `README.md`
- Modify: `docs/plans/2026-10-24-wifi-direct-distance-implementation.md`
- Modify: `docs/acoustic-ranging-setup.md`

**Interfaces:**
- Consumes: Task 1 command behavior and the confirmed dual-laptop P2P connection.
- Produces: accurate setup instructions that distinguish connected transport from unverified field accuracy.

- [ ] Document prerequisites, dynamic WPS bootstrap, and Windows listener ordering.
- [ ] Mark real pairing/RSSI/runtime transport checks complete while leaving calibration and >90% field validation pending.
- [ ] Run documentation consistency searches and `git diff --check`.

### Task 3: Full verification and main integration

**Files:**
- Verify: all branch changes.

**Interfaces:**
- Consumes: Tasks 1-2 and existing Windows helper fixes.
- Produces: one verified branch ready to fast-forward or merge into `main`.

- [ ] Run shell regression and syntax checks.
- [ ] Run `dotnet test` for the Windows helper.
- [ ] Run `./mvnw clean verify`.
- [ ] Inspect diff and worktree status, commit the bootstrap changes, then integrate into `main` without touching unrelated untracked files.
