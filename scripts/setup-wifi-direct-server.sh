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

normalize_wps_value() {
  local max_length=$1
  tr -d '\000-\010\013\014\016-\037\177' |
    tr '\r\n\t' '   ' |
    sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//' |
    cut -c1-"$max_length"
}

read_dmi_value() {
  local field=$1 fallback=$2 dmi_root=${LTM_DMI_ROOT:-/sys/class/dmi/id} value=""
  if [[ -r "$dmi_root/$field" ]]; then
    value=$(normalize_wps_value 64 <"$dmi_root/$field")
  fi
  printf '%s' "${value:-$fallback}"
}

device_name=$(hostnamectl --static 2>/dev/null || hostname 2>/dev/null || true)
device_name=$(printf '%s' "$device_name" | normalize_wps_value 32)
[[ -n $device_name ]] || device_name="linux-p2p-device"
manufacturer=$(read_dmi_value sys_vendor Linux | normalize_wps_value 64)
model_name=$(read_dmi_value product_name "$device_name" | normalize_wps_value 32)
model_number=$(read_dmi_value product_version unknown | normalize_wps_value 32)
country=$(iw reg get 2>/dev/null | awk '
  /^phy#/ { in_phy = 1 }
  $1 == "country" {
    gsub(":", "", $2)
    if ($2 ~ /^[A-Z][A-Z]$/ && $2 != "00" && fallback == "") fallback = $2
    if (in_phy && $2 ~ /^[A-Z][A-Z]$/ && $2 != "00") { print $2; found = 1; exit }
  }
  END { if (!found && fallback != "") print fallback }
')

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
echo "WPS device name: $device_name"
echo "WPS vendor    : $manufacturer"
echo "WPS model     : $model_name ($model_number)"
echo "WPS country   : ${country:-not available}"

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

command -v wpa_cli >/dev/null || { echo "wpa_cli is required in apply mode" >&2; exit 1; }
if (( EUID != 0 )); then
  command -v sudo >/dev/null || { echo "sudo is required to configure wpa_supplicant" >&2; exit 1; }
fi

run_wpa_cli() {
  if (( EUID == 0 )); then
    wpa_cli -i "$physical_iface" "$@"
  else
    sudo wpa_cli -i "$physical_iface" "$@"
  fi
}

configure_wps_value() {
  local key=$1 value=$2 response
  if ! response=$(run_wpa_cli set "$key" "$value" 2>&1) || ! grep -Fxq OK <<<"$response"; then
    echo "Failed to configure WPS $key: ${response:-no response}" >&2
    return 1
  fi
}

echo "Configuring dynamic WPS identity on $physical_iface..."
configure_wps_value device_name "$device_name"
configure_wps_value manufacturer "$manufacturer"
configure_wps_value model_name "$model_name"
configure_wps_value model_number "$model_number"
configure_wps_value device_type "1-0050F204-1"
configure_wps_value config_methods "virtual_push_button"
if [[ -n $country ]]; then
  configure_wps_value country "$country"
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

p2p_path=$(busctl call org.freedesktop.NetworkManager /org/freedesktop/NetworkManager org.freedesktop.NetworkManager GetDeviceByIpIface "s" "$p2p_device" 2>/dev/null | awk '{print $2}' | tr -d '"' || true)
if [[ -n "$p2p_path" && "$p2p_path" != "/" ]]; then
  echo "Triggering Wi-Fi Direct discovery on $p2p_device..."
  busctl call org.freedesktop.NetworkManager "$p2p_path" org.freedesktop.NetworkManager.Device.WifiP2P StartFind "a{sv}" 0 >/dev/null 2>&1 || true
  sleep 3
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
