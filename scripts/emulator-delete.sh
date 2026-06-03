#!/usr/bin/env bash
# emulator-delete.sh — Delete the development AVD.
# Run via: npm run emulator:delete
set -euo pipefail

AVD_NAME="SbbAVD"
AVDMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager"

if [ ! -f "$AVDMANAGER" ]; then
  echo "❌ Android SDK not found. Run: npm run setup"
  exit 1
fi

"$AVDMANAGER" delete avd --name "$AVD_NAME" && echo "✅ Deleted '${AVD_NAME}'"
