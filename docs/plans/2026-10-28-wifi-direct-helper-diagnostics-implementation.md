# Windows Wi-Fi Direct Helper Diagnostics Implementation Plan

**Goal:** Make every Windows WinRT publisher, incoming-request, pairing, and device-open boundary observable without changing negotiation policy.

**Architecture:** Add platform-neutral diagnostic event factories covered by xUnit, then wire WinRT callbacks to those factories under `#if WINDOWS`. Treat publisher and pairing results as state transitions, and stop a connection request immediately when pairing fails.

**Tech Stack:** .NET 10, C#, WinRT `Windows.Devices.WiFiDirect`, xUnit, line-delimited JSON.

---

### Task 1: Define stable diagnostic states with tests

**Files:**
- Modify: `tools/wifi-direct-windows/WifiDirectAgentHelper.Tests/DirectLinkStateTests.cs`
- Modify: `tools/wifi-direct-windows/Program.cs`

1. Add failing tests asserting JSON for publisher starting/aborted, connection requested, pairing started/succeeded/failed, and device opening.
2. Run `dotnet test tools/wifi-direct-windows/WifiDirectAgentHelper.Tests/WifiDirectAgentHelper.Tests.csproj --configuration Release` and confirm compilation/test failure for missing factories.
3. Add the minimal `DirectLinkState` factory methods and re-run the focused suite.
4. Commit `feat: model Wi-Fi Direct helper diagnostics`.

### Task 2: Wire diagnostics to WinRT lifecycle

**Files:**
- Modify: `tools/wifi-direct-windows/Program.cs`
- Modify: `tools/wifi-direct-windows/README.md`

1. Add publisher `StatusChanged` handling before `Start()` in listener and list modes. Remove the unconditional `LISTENING` output and report start exceptions.
2. Emit connection-request and pairing lifecycle states. Check `DevicePairingResult.Status`; on failure, emit `PAIRING_FAILED` and return before `FromIdAsync`.
3. Emit device-opening state and preserve the existing `CONNECTED`/`RECONNECTING` lifecycle.
4. Document the new JSON states and the single-helper-process requirement.
5. Run tests, build both target frameworks with `dotnet build tools/wifi-direct-windows/WifiDirectAgentHelper.csproj --configuration Release`, and run `git diff --check`.
6. Commit `fix: expose Windows Wi-Fi Direct negotiation failures`.
