#!/usr/bin/env bash
set -eu

if ! command -v iw >/dev/null 2>&1; then
    echo "iw is required but was not found" >&2
    exit 1
fi

echo "Wi-Fi interfaces"
iw dev

echo "Supported P2P modes and interface combinations"
iw list | awk '
    /Supported interface modes:/ { printing=1 }
    printing { print }
    /valid interface combinations:/ { combinations=1 }
    combinations && /Device supports/ { exit }
' | sed -n '1,180p'

echo "Current routes"
ip route

echo "NetworkManager devices"
if command -v nmcli >/dev/null 2>&1; then
    nmcli -t -f GENERAL.DEVICE,GENERAL.TYPE,GENERAL.STATE,GENERAL.CONNECTION device show
else
    echo "nmcli is not installed"
fi

echo "P2P group station measurements"
found_group=0
for interface_name in $(iw dev | awk '$1 == "Interface" { print $2 }'); do
    case "$interface_name" in
        p2p-*-*)
            found_group=1
            echo "Interface: $interface_name"
            iw dev "$interface_name" station dump
            ;;
    esac
done

if [ "$found_group" -eq 0 ]; then
    echo "No active p2p group interface; capability may still be available."
fi
