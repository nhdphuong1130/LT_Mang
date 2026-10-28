#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <physical-wifi-interface> <windows-p2p-mac> [profile-name] [--apply]"
  echo "Without --apply, this command performs checks and prints the planned changes."
}

if [[ $# -lt 2 || $# -gt 4 ]]; then usage; exit 2; fi
physical_iface=$1
peer_mac=${2^^}
if [[ ${3:-} == "--apply" ]]; then
  profile_name="ltm-wifi-direct-distance"
  apply_mode="--apply"
else
  profile_name=${3:-ltm-wifi-direct-distance}
  apply_mode=${4:-}
fi

if [[ ! $physical_iface =~ ^[a-zA-Z0-9_.-]+$ ]]; then echo "Invalid interface name" >&2; exit 2; fi
if [[ ! $peer_mac =~ ^([0-9A-F]{2}:){5}[0-9A-F]{2}$ ]]; then echo "Peer MAC must use AA:BB:CC:DD:EE:FF" >&2; exit 2; fi
if [[ ! $profile_name =~ ^ltm-wifi-direct-[a-zA-Z0-9_.-]+$ ]]; then
  echo "Profile must begin with ltm-wifi-direct- so cleanup remains scoped" >&2; exit 2
fi
command -v nmcli >/dev/null || { echo "nmcli is required" >&2; exit 1; }
command -v iw >/dev/null || { echo "iw is required" >&2; exit 1; }

lan_state=$(nmcli -t -f GENERAL.STATE device show "$physical_iface" | cut -d: -f2- || true)
lan_connection=$(nmcli -t -f GENERAL.CONNECTION device show "$physical_iface" | cut -d: -f2- || true)
lan_ip=$(nmcli -t -f IP4.ADDRESS device show "$physical_iface" | head -n1 | cut -d: -f2- || true)
p2p_device=$(nmcli -t -f DEVICE,TYPE device status | awk -F: '$2=="wifi-p2p" {print $1; exit}')

echo "LAN interface : $physical_iface"
echo "LAN state     : ${lan_state:-unknown}"
echo "LAN profile   : ${lan_connection:-unknown}"
echo "LAN IPv4      : ${lan_ip:-unknown}"
echo "P2P device    : ${p2p_device:-not found}"
echo "Windows peer  : $peer_mac"

[[ $lan_state == *connected* ]] || { echo "Abort: physical Wi-Fi LAN is not connected" >&2; exit 1; }
[[ -n $lan_connection && $lan_connection != -- ]] || { echo "Abort: cannot identify active LAN profile" >&2; exit 1; }
[[ -n $p2p_device ]] || { echo "Abort: NetworkManager exposes no wifi-p2p device" >&2; exit 1; }
iw list | grep 'P2P-GO' >/dev/null || { echo "Abort: adapter does not advertise P2P-GO support" >&2; exit 1; }

echo "Planned command: nmcli connection add type wifi-p2p con-name $profile_name peer $peer_mac"
echo "Planned command: nmcli connection up $profile_name"
if [[ $apply_mode != --apply ]]; then
  echo "Dry run only. Append --apply to create and activate the project P2P profile."
  exit 0
fi

rollback() {
  nmcli connection down "$profile_name" >/dev/null 2>&1 || true
  nmcli connection delete "$profile_name" >/dev/null 2>&1 || true
  nmcli connection up "$lan_connection" >/dev/null 2>&1 || true
}
trap 'echo "Setup failed; rolling back only $profile_name" >&2; rollback' ERR

if nmcli -t -f NAME connection show | grep -Fxq "$profile_name"; then
  echo "Abort: profile already exists: $profile_name" >&2
  exit 1
fi
nmcli connection add type wifi-p2p con-name "$profile_name" peer "$peer_mac" connection.autoconnect no
nmcli connection up "$profile_name"

new_state=$(nmcli -t -f GENERAL.STATE device show "$physical_iface" | cut -d: -f2- || true)
new_ip=$(nmcli -t -f IP4.ADDRESS device show "$physical_iface" | head -n1 | cut -d: -f2- || true)
if [[ $new_state != *connected* || -z $new_ip ]]; then
  echo "Abort: P2P activation disrupted the managed LAN" >&2
  false
fi
trap - ERR
echo "Wi-Fi Direct profile active; managed LAN remains connected at $new_ip"
echo "Verify peer RSSI with: iw dev <p2p-group-interface> station dump"
