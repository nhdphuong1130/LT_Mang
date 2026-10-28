# Windows Wi-Fi Direct Helper Diagnostics Design

**Date:** 2026-10-28
**Status:** Approved by the user

## Problem

Linux reliably discovers `DESKTOP-K5PKS87` and starts a real P2P Group Owner negotiation, but `wpa_supplicant` receives `P2P-GO-NEG-FAILURE status=4`. The Windows helper currently prints `LISTENING` immediately after calling `WiFiDirectAdvertisementPublisher.Start()`, ignores publisher failures in list mode, ignores the result of `PairAsync`, and does not expose whether `ConnectionRequested` or `FromIdAsync` was reached. Consequently the console output cannot identify the failing WinRT boundary.

## Selected approach

Add evidence-only diagnostics without changing Group Owner intent, WPS policy, or connection topology. Subscribe to publisher status before starting it and emit `LISTENING` only after WinRT reports `Started`; emit a typed failure when it reports `Aborted`. Emit line-delimited JSON when a connection request arrives, when pairing begins, when pairing succeeds/fails, and when opening the Wi-Fi Direct device begins/fails.

State formatting remains platform-neutral and unit-testable on Linux. WinRT event wiring remains inside `#if WINDOWS`. Pairing failures stop that request instead of continuing into `FromIdAsync` with an invalid state. Exception messages are included because the helper handles no credentials or application data.

## Non-goals

- Do not change `GroupOwnerIntent = 0` yet.
- Do not force PBC/PIN or alter NetworkManager settings.
- Do not claim the radio interoperability problem is fixed until a real Windows/Linux run reaches `CONNECTED`.

## Acceptance

- `LISTENING` can only represent publisher status `Started`.
- Publisher abort reason is visible.
- Every connection request and pairing result is visible as JSON.
- A failed pairing never proceeds to device opening.
- Existing JSON fields remain compatible and .NET tests/build pass.
