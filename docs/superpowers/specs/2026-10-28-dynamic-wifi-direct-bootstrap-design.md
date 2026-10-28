# Dynamic Wi-Fi Direct Bootstrap Design

## Intent

Make the Linux Wi-Fi Direct setup command repeatable after an interface, driver, or `wpa_supplicant` restart. The operator supplies only the physical Wi-Fi interface and the discovered Windows P2P MAC; machine identity and regulatory settings are derived at runtime.

## Goals

- Configure WPS identity before NetworkManager activates the P2P profile.
- Derive device name, manufacturer, model, version, and country from the local machine.
- Advertise push-button pairing so the Windows helper can confirm without a PIN.
- Fail before creating a NetworkManager profile when required WPS configuration fails.
- Preserve the infrastructure LAN connection and the existing scoped rollback behavior.
- Provide hardware-free regression tests using fake command executables.

## Non-goals

- Do not embed a peer MAC, Linux interface, hostname, manufacturer, model, or country.
- Do not claim the acoustic/distance field target is validated.
- Do not automate Windows-side execution of the helper.

## Approaches

1. Persist values in a global `wpa_supplicant.conf`. Rejected because NetworkManager owns the live supplicant and distribution paths differ.
2. Apply runtime values through `wpa_cli` immediately before discovery and activation. Selected because it matches the proven manual recovery sequence without taking ownership away from NetworkManager.
3. Add fixed values directly to the shell script. Rejected because it would only work on the current laptops and violates the no-hardcode requirement.

## Runtime behavior

The script reads hostname information and DMI fields, normalizes them to safe bounded WPS strings, and reads the active regulatory country from `iw reg get`. It applies standard WPS computer device type and virtual push-button configuration through `wpa_cli`. Environment overrides are supported only as explicit operator/test inputs; defaults always come from the running machine.

In apply mode, missing `wpa_cli`, unavailable privilege escalation, an empty identity, or any non-`OK` WPS setting aborts before profile creation. Dry-run mode reports derived values and planned actions without invoking privileged or mutating commands.

## Validation

Shell regression tests fake `nmcli`, `iw`, `busctl`, `wpa_cli`, and privilege escalation. They verify dynamic values, push-button configuration, dry-run non-mutation, early failure, and preservation of the existing activation flow. Final verification includes shell syntax, the shell regression suite, all Maven tests, all .NET helper tests, and `git diff --check`.
