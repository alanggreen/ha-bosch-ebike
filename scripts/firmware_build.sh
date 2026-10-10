#!/usr/bin/env bash
# Compile an ESPHome config from this repo in a scratch folder (keeps build output out of the repo).
#   scripts/firmware_build.sh [esphome/ha-bosch-ebike-home.yaml]
# Needs esphome (pip install --user esphome) and esphome/secrets.yaml (copy secrets.yaml.example; it is gitignored).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
YAML="${1:-esphome/ha-bosch-ebike-home.yaml}"
NAME="$(basename "$YAML")"
BUILD="${EBIKE_BUILD_DIR:-/tmp/ebike-build}"
export PATH="$HOME/.local/bin:$PATH"

[ -f "$REPO/esphome/secrets.yaml" ] || { echo "esphome/secrets.yaml is missing (copy secrets.yaml.example)"; exit 2; }
rm -rf "$BUILD" && mkdir -p "$BUILD"
cp -r "$REPO/esphome/components" "$BUILD/"
find "$BUILD/components" -name __pycache__ -prune -exec rm -rf {} +
cp "$REPO/$YAML" "$REPO/esphome/secrets.yaml" "$BUILD/"
cd "$BUILD"
esphome compile "$NAME" > build.log 2>&1 && status=0 || status=$?
tr '\r' '\n' < build.log | sed 's/\x1b\[[0-9;]*[A-Za-z]//g' | grep -E 'error:|undefined reference|Error' | head -20 || true
if [ "$status" = 0 ]; then
  echo "BUILD OK: $(find .esphome/build -name firmware.factory.bin | head -1)"
else
  echo "BUILD FAILED (exit $status); full log: $BUILD/build.log"
  exit "$status"
fi
