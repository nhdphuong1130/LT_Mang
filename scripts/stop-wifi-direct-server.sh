#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <ltm-wifi-direct-profile-name>" >&2
  exit 2
fi
profile_name=$1
if [[ ! $profile_name =~ ^ltm-wifi-direct-[a-zA-Z0-9_.-]+$ ]]; then
  echo "Refusing to touch a profile outside the ltm-wifi-direct-* namespace" >&2
  exit 2
fi
if ! nmcli -t -f NAME connection show | grep -Fxq "$profile_name"; then
  echo "Profile does not exist: $profile_name"
  exit 0
fi
nmcli connection down "$profile_name" >/dev/null 2>&1 || true
nmcli connection delete "$profile_name"
echo "Removed project Wi-Fi Direct profile: $profile_name"
