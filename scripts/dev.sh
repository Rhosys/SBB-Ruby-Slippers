#!/usr/bin/env bash
# =============================================================================
# dev.sh — Single command for all local development.
#
# Run via:  npm run start            (debug variant)
#           npm run start:release    (minified release variant — catches R8 crashes)
#
# Runs setup if the Android SDK is not present, creates the AVD if it doesn't
# exist (~1.5GB download on first run), starts the emulator if not already
# running, then does an incremental Gradle build + install + launch.
# Fast on repeated runs when nothing changed.
#
# The release path installs the R8-minified, ProGuard-processed APK signed with
# the shared debug key (see app/build.gradle.kts). It is the local mirror of the
# instrumented release tests (testBuildType = "release") and surfaces stripping /
# keep-rule crashes that never appear in the debug build.
# =============================================================================
set -euo pipefail

# ── Build variant (debug | release) ────────────────────────────────────────────
VARIANT="${1:-debug}"
case "$VARIANT" in
  debug)
    INSTALL_TASK="installDebug"
    APP_ID="ch.rhosys.sbb.debug"    # debug applies applicationIdSuffix = ".debug"
    ;;
  release)
    INSTALL_TASK="installRelease"
    APP_ID="ch.rhosys.sbb"
    ;;
  *)
    echo "❌ Unknown variant '$VARIANT' — use 'debug' or 'release'." >&2
    exit 1
    ;;
esac

AVD_NAME="WorkspaceAVD"
SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"
DEVICE_PROFILE="pixel_7"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${XDG_CONFIG_HOME:-$HOME}/.android/avd}"
export ANDROID_HOME ANDROID_AVD_HOME

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
GRADLEW="$(dirname "$0")/../gradlew"

echo "→ Variant: $VARIANT  (install task: $INSTALL_TASK, package: $APP_ID)"

# ── Run setup if Android SDK is not installed ──────────────────────────────────
if [ ! -f "$SDKMANAGER" ]; then
  echo "→ Android SDK not found — running setup (first time only)..."
  bash "$(dirname "$0")/setup.sh"
  # Re-export PATH additions that setup.sh wrote to the profile but can't
  # propagate back to this process (subprocess exports don't bubble up).
  export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
fi

# ── Create AVD if missing ──────────────────────────────────────────────────────
if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
  echo "→ AVD '$AVD_NAME' not found — creating it (downloads ~1.5GB on first run)..."
  yes 2>/dev/null | "$SDKMANAGER" "$SYSTEM_IMAGE" || true
  echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "$DEVICE_PROFILE" \
    --force
  echo "✅ AVD created."
  echo ""
fi

# ── Start emulator if not running ─────────────────────────────────────────────
if ! "$ADB" devices 2>/dev/null | grep -q "^emulator"; then
  echo "→ Starting emulator '$AVD_NAME'..."
  # ANDROID_EMULATOR_USE_SYSTEM_LIBS=1: use system Vulkan loader so the NVIDIA
  # ICD is discovered instead of falling back to bundled Lavapipe (software GPU).
  # -no-audio: Ubuntu's PipeWire breaks the emulator's PulseAudio driver and can
  # cause long hangs during boot.
  ANDROID_EMULATOR_USE_SYSTEM_LIBS=1 \
    setsid "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load -no-audio &>/dev/null &

  # Raise the emulator window to the foreground once it appears
  (
    for _ in $(seq 1 30); do
      sleep 2
      if wmctrl -a "$AVD_NAME" 2>/dev/null; then
        break
      fi
    done
  ) &
else
  echo "  Emulator already running — skipping launch"
fi

# ── Wait for emulator to be online and fully booted ───────────────────────────
echo "→ Waiting for emulator to come online..."
until "$ADB" devices 2>/dev/null | grep -E "^emulator.*[[:space:]]device$" > /dev/null; do
  sleep 2
done
echo "→ Waiting for boot to complete..."
until "$ADB" shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do
  sleep 2
done
echo "→ Waiting for package manager to be ready..."
until "$ADB" shell pm list packages 2>/dev/null | grep -q "package:"; do
  sleep 2
done
echo "  Emulator ready."

# ── Build + install ────────────────────────────────────────────────────────────
# Gradle's incremental build means this only does real work when something
# changed. installRelease runs R8/ProGuard, so it is slower than installDebug.
echo "→ Building and installing ($INSTALL_TASK)..."
"$GRADLEW" ":app:$INSTALL_TASK"

# ── Launch ──────────────────────────────────────────────────────────────────────
echo "→ Launching $APP_ID..."
"$ADB" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "✅ $VARIANT build installed and launched on '$AVD_NAME'."
echo "   Streaming logs (Ctrl-C to stop) — crashes will appear here:"
echo ""
# Surface crashes immediately. AndroidRuntime = uncaught exceptions (the typical
# R8/keep-rule failure mode); the app's own tag follows its logging.
exec "$ADB" logcat -v color AndroidRuntime:E "$APP_ID":V "*:S"
