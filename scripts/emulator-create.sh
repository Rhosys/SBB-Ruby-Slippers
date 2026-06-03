#!/usr/bin/env bash
# =============================================================================
# emulator-create.sh — Create an Android Virtual Device (emulator) for development.
#
# Run via:  npm run emulator:create
#
# Requires setup.sh to have been run first (Android SDK must be installed).
# Downloads a ~1.5GB system image on first run.
# =============================================================================
set -euo pipefail

AVD_NAME="SbbAVD"
SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
DEVICE_PROFILE="pixel_7"

BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()    { echo -e "${BOLD}→ $1${NC}"; }
success() { echo -e "${GREEN}✅ $1${NC}"; }
warn()    { echo -e "${YELLOW}⚠️  $1${NC}"; }

SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager"

if [ ! -f "$SDKMANAGER" ]; then
  echo "❌ Android SDK not found. Run: npm run setup"
  exit 1
fi

# Check if AVD already exists
if "${AVDMANAGER}" list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}"; then
  warn "Emulator '${AVD_NAME}' already exists."
  echo "  To start it: npm run emulator:start"
  echo "  To delete and recreate: npm run emulator:delete && npm run emulator:create"
  exit 0
fi

info "Installing Android 35 system image (~1.5GB download)..."
# yes exits with SIGPIPE when sdkmanager closes stdin; suppress that so set -e doesn't abort
yes 2>/dev/null | "$SDKMANAGER" "$SYSTEM_IMAGE" || true

info "Creating emulator '${AVD_NAME}'..."
echo "no" | "$AVDMANAGER" create avd \
  --name "$AVD_NAME" \
  --package "$SYSTEM_IMAGE" \
  --device "$DEVICE_PROFILE" \
  --force

success "Emulator '${AVD_NAME}' created."
echo ""
echo "Start it with:  npm run emulator:start"
