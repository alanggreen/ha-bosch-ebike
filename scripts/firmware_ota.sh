#!/usr/bin/env bash
# Push the firmware built by firmware_build.sh to the ESP32 over WiFi.
#   scripts/firmware_ota.sh [esphome/ha-bosch-ebike-home.yaml] [192.168.1.20]
# Only the firmware agent flashes. Retries while the link is jittery (the ESP32 sits behind weak WiFi).
set -uo pipefail
YAML="${1:-esphome/ha-bosch-ebike-home.yaml}"
HOST="${2:-192.168.1.20}"
NAME="$(basename "$YAML")"
BUILD="${EBIKE_BUILD_DIR:-/tmp/ebike-build}"
export PATH="$HOME/.local/bin:$PATH"
cd "$BUILD" || { echo "run scripts/firmware_build.sh first"; exit 2; }

for attempt in 1 2 3 4; do
  for _ in $(seq 1 12); do      # wait for a clean link: worst of 10 pings under 300 ms
    max=$(ping -c10 -i0.3 -W1 "$HOST" 2>/dev/null | tail -1 | sed -E 's#.*= [0-9.]+/[0-9.]+/([0-9.]+)/.*#\1#')
    [[ "$max" =~ ^[0-9.]+$ ]] && awk "BEGIN{exit !($max < 300)}" && break
    sleep 10
  done
  echo "attempt $attempt (worst ping ${max:-none} ms)"
  if esphome upload "$NAME" --device "$HOST" 2>&1 | tr '\r' '\n' | sed 's/\x1b\[[0-9;]*[A-Za-z]//g' | tee /tmp/ota_try.log | tail -3 | grep -q "Successfully"; then
    echo "OTA OK"; exit 0
  fi
  tail -2 /tmp/ota_try.log
done
echo "OTA FAILED"; exit 1
