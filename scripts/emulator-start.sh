#!/usr/bin/env bash
# =============================================================================
# emulator-start.sh — Start the SbbAVD emulator in foreground.
#
# Run via:  npm run emulator:start
#
# Requires emulator-create.sh to have been run first.
# =============================================================================
set -euo pipefail

AVD_NAME="SbbAVD"

BOLD='\033[1m'
GREEN='\033[0;32m'
NC='\033[0m'

info()    { echo -e "${BOLD}→ $1${NC}"; }
success() { echo -e "${GREEN}✅ $1${NC}"; }

EMULATOR="${ANDROID_HOME}/emulator/emulator"
AVDMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager"

if [ ! -f "$EMULATOR" ]; then
  echo "❌ Android emulator not found. Run: npm run setup"
  exit 1
fi

# Check if AVD exists
if ! "${AVDMANAGER}" list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}"; then
  echo "❌ AVD '${AVD_NAME}' does not exist. Run: scripts/emulator-create.sh"
  exit 1
fi

info "Starting emulator '${AVD_NAME}'..."
exec "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load
