#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$repo_root/scripts/setup-wifi-direct-server.sh"
test_root=$(mktemp -d)
trap 'rm -rf "$test_root"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file=$1 expected=$2
  grep -Fq -- "$expected" "$file" || fail "Expected '$expected' in $file"
}

assert_not_contains() {
  local file=$1 unexpected=$2
  if grep -Fq -- "$unexpected" "$file"; then
    fail "Did not expect '$unexpected' in $file"
  fi
}

fake_bin="$test_root/bin"
dmi_root="$test_root/dmi"
events="$test_root/events.log"
mkdir -p "$fake_bin" "$dmi_root"
: >"$events"
printf '%s\n' 'Acer Incorporated' >"$dmi_root/sys_vendor"
printf '%s\n' 'Aspire Test Model' >"$dmi_root/product_name"
printf '%s\n' 'V1.25' >"$dmi_root/product_version"

make_fake() {
  local name=$1
  shift
  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf '%s\n' "$@"
  } >"$fake_bin/$name"
  chmod +x "$fake_bin/$name"
}

make_fake nmcli '
printf "nmcli" >>"$LTM_TEST_EVENTS"
printf "|%s" "$@" >>"$LTM_TEST_EVENTS"
printf "\n" >>"$LTM_TEST_EVENTS"
case "$*" in
  "-t -f GENERAL.STATE device show wlp0s20f3") echo "GENERAL.STATE:100 (connected)" ;;
  "-t -f GENERAL.CONNECTION device show wlp0s20f3") echo "GENERAL.CONNECTION:Ngan" ;;
  "-t -f IP4.ADDRESS device show wlp0s20f3") echo "IP4.ADDRESS[1]:192.168.1.33/24" ;;
  "-t -f DEVICE,TYPE device status") echo "p2p-dev-wlp0s20f3:wifi-p2p" ;;
  "-t -f NAME connection show")
    if [[ ${LTM_TEST_PROFILE_EXISTS:-0} == 1 ]]; then
      echo "ltm-wifi-direct-distance"
    fi
    ;;
  "-g connection.type,wifi-p2p.peer connection show ltm-wifi-direct-distance")
    printf "%s\n%s\n" "${LTM_TEST_PROFILE_TYPE:-wifi-p2p}" \
      "${LTM_TEST_PROFILE_PEER:-38\\:7A\\:0E\\:A8\\:91\\:A8}"
    ;;
  "connection add "*) echo "Connection added" ;;
  "connection up "*) echo "Connection activated" ;;
esac'

make_fake iw '
printf "iw" >>"$LTM_TEST_EVENTS"
printf "|%s" "$@" >>"$LTM_TEST_EVENTS"
printf "\n" >>"$LTM_TEST_EVENTS"
if [[ ${1:-} == list ]]; then
  echo "         * P2P-GO"
elif [[ ${1:-} == dev && ${2:-} == wlp0s20f3 && ${3:-} == info ]]; then
  printf "Interface wlp0s20f3\n\twiphy 1\n\ttype managed\n"
elif [[ ${1:-} == reg && ${2:-} == get ]]; then
  printf "global\ncountry 00: DFS-UNSET\nphy#0 (self-managed)\ncountry US: DFS-FCC\nphy#1 (self-managed)\ncountry VN: DFS-UNSET\n"
fi'

make_fake busctl '
printf "busctl" >>"$LTM_TEST_EVENTS"
printf "|%s" "$@" >>"$LTM_TEST_EVENTS"
printf "\n" >>"$LTM_TEST_EVENTS"
if [[ "$*" == *GetDeviceByIpIface* ]]; then
  echo "o \"/org/freedesktop/NetworkManager/Devices/10\""
fi'

make_fake wpa_cli '
printf "wpa_cli" >>"$LTM_TEST_EVENTS"
printf "|%s" "$@" >>"$LTM_TEST_EVENTS"
printf "\n" >>"$LTM_TEST_EVENTS"
key=${4:-}
if [[ -n ${LTM_TEST_FAIL_WPA_KEY:-} && $key == "$LTM_TEST_FAIL_WPA_KEY" ]]; then
  echo FAIL
else
  echo OK
fi'

make_fake sudo '
printf "sudo" >>"$LTM_TEST_EVENTS"
printf "|%s" "$@" >>"$LTM_TEST_EVENTS"
printf "\n" >>"$LTM_TEST_EVENTS"
exec "$@"'

make_fake hostnamectl '
if [[ ${1:-} == --static ]]; then echo "lab-server"; fi'
make_fake hostname 'echo "lab-server-fallback"'
make_fake sleep ':'

common_env=(
  "PATH=$fake_bin:$PATH"
  "LTM_TEST_EVENTS=$events"
  "LTM_DMI_ROOT=$dmi_root"
)

apply_output="$test_root/apply.out"
env "${common_env[@]}" "$script" wlp0s20f3 38:7A:0E:A8:91:A8 --apply >"$apply_output" 2>&1

assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|device_name|lab-server'
assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|manufacturer|Acer Incorporated'
assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|model_name|Aspire Test Model'
assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|model_number|V1.25'
assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|device_type|1-0050F204-1'
assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|config_methods|virtual_push_button'
assert_contains "$events" 'wpa_cli|-i|wlp0s20f3|set|country|VN'

wpa_line=$(grep -n '^wpa_cli|-i|wlp0s20f3|set|config_methods|virtual_push_button$' "$events" | cut -d: -f1)
add_line=$(grep -nF 'nmcli|connection|add|type|wifi-p2p' "$events" | cut -d: -f1)
[[ $wpa_line -lt $add_line ]] || fail "WPS identity must be configured before profile creation"

: >"$events"
dry_output="$test_root/dry.out"
env "${common_env[@]}" "$script" wlp0s20f3 38:7A:0E:A8:91:A8 >"$dry_output" 2>&1
assert_contains "$dry_output" 'WPS device name: lab-server'
assert_not_contains "$events" 'wpa_cli|'
assert_not_contains "$events" 'nmcli|connection|add'

: >"$events"
failure_output="$test_root/failure.out"
if env "${common_env[@]}" LTM_TEST_FAIL_WPA_KEY=device_type \
  "$script" wlp0s20f3 38:7A:0E:A8:91:A8 --apply >"$failure_output" 2>&1; then
  fail "Expected a failed WPS setting to abort setup"
fi
assert_contains "$failure_output" 'Failed to configure WPS device_type'
assert_not_contains "$events" 'nmcli|connection|add'

: >"$events"
reconnect_output="$test_root/reconnect.out"
if ! env "${common_env[@]}" LTM_TEST_PROFILE_EXISTS=1 \
  "$script" wlp0s20f3 38:7A:0E:A8:91:A8 --apply >"$reconnect_output" 2>&1; then
  cat "$reconnect_output" >&2
  fail "Expected an existing matching profile to be reused"
fi
assert_contains "$reconnect_output" 'Reusing existing Wi-Fi Direct profile: ltm-wifi-direct-distance'
assert_contains "$events" 'nmcli|connection|up|ltm-wifi-direct-distance'
assert_not_contains "$events" 'nmcli|connection|add|type|wifi-p2p'
assert_not_contains "$events" 'nmcli|connection|delete|ltm-wifi-direct-distance'

: >"$events"
mismatch_output="$test_root/mismatch.out"
if env "${common_env[@]}" LTM_TEST_PROFILE_EXISTS=1 \
  LTM_TEST_PROFILE_PEER=AA:BB:CC:DD:EE:FF \
  "$script" wlp0s20f3 38:7A:0E:A8:91:A8 --apply >"$mismatch_output" 2>&1; then
  fail "Expected a profile for a different peer to be rejected"
fi
assert_contains "$mismatch_output" \
  'existing profile ltm-wifi-direct-distance does not match wifi-p2p peer 38:7A:0E:A8:91:A8'
assert_not_contains "$events" 'nmcli|connection|up|ltm-wifi-direct-distance'

echo "PASS: setup-wifi-direct-server dynamic bootstrap"
