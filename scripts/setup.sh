#!/usr/bin/env bash
# =============================================================================
# setup.sh — Full developer environment setup for this project.
#
# Run via:  npm run setup
#
# What this does:
#   1. Installs Java 17 (required for Android/Gradle builds)
#   2. Downloads and installs Android SDK command-line tools (no Android Studio needed)
#   3. Installs required Android SDK components via sdkmanager
#   4. Writes ANDROID_HOME and PATH to your shell profile
#   5. Validates KVM for emulator hardware acceleration (Linux only)
#   6. Installs ktlint for Kotlin code formatting
#
# Prerequisites (the only things this script cannot install for you):
#   - Node.js 20+  →  install via nvm: https://github.com/nvm-sh/nvm
#                      curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.0/install.sh | bash
#                      nvm install 20 && nvm use 20
# =============================================================================
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_TOOLS_VERSION="13.0"
# See: https://developer.android.com/studio#command-line-tools-only
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
KTLINT_VERSION="1.5.0"

BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()    { echo -e "${BOLD}→ $1${NC}"; }
success() { echo -e "${GREEN}✅ $1${NC}"; }
warn()    { echo -e "${YELLOW}⚠️  $1${NC}"; }
error()   { echo -e "${RED}❌ $1${NC}"; exit 1; }

# ─── 1. Java 17 ───────────────────────────────────────────────────────────────
info "Checking Java 17..."
if java -version 2>&1 | grep -q 'version "17'; then
  success "Java 17 already installed"
else
  info "Installing Java 17..."
  if command -v apt-get &>/dev/null; then
    sudo apt-get update -qq
    sudo apt-get install -y openjdk-17-jdk
  elif command -v brew &>/dev/null; then
    brew install openjdk@17
    sudo ln -sfn "$(brew --prefix openjdk@17)/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-17.jdk 2>/dev/null || true
  else
    error "Cannot install Java automatically. Please install JDK 17 manually:
  Ubuntu/Debian: sudo apt-get install openjdk-17-jdk
  macOS:         brew install openjdk@17
  Windows:       https://adoptium.net/"
  fi
  success "Java 17 installed"
fi

# ─── 2. Android SDK command-line tools ────────────────────────────────────────
info "Setting up Android SDK at $ANDROID_HOME ..."
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  success "Android cmdline-tools already present"
else
  info "Downloading Android command-line tools (~150MB)..."
  TMP_ZIP="/tmp/cmdline-tools.zip"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$TMP_ZIP"
  unzip -q "$TMP_ZIP" -d "/tmp/cmdline-tools-extracted"
  # Google's zip structure requires this specific layout for sdkmanager to work
  mv "/tmp/cmdline-tools-extracted/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$TMP_ZIP" "/tmp/cmdline-tools-extracted"
  success "Android command-line tools installed"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# ─── 3. Accept licenses and install SDK components ────────────────────────────
info "Accepting Android SDK licenses..."
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

info "Installing Android SDK components (platform-tools, build-tools, Android 35)..."
"$SDKMANAGER" \
  "platform-tools" \
  "build-tools;35.0.0" \
  "platforms;android-35"
success "Android SDK components installed"

# ─── 4. Write environment variables to shell profile ─────────────────────────
info "Configuring environment variables..."

# Use a unique marker so we can detect whether setup.sh has already added this block.
# Written to .bashrc (interactive shells), .zshrc (zsh), AND .profile (login shells,
# inherited by all child processes including non-interactive ones).
MARKER="# BEGIN sbb android sdk"
EXPORT_BLOCK="$MARKER
export ANDROID_HOME=\"$ANDROID_HOME\"
# ANDROID_AVD_HOME: the AVD manager respects XDG_CONFIG_HOME but the emulator binary does not
export ANDROID_AVD_HOME=\"\${XDG_CONFIG_HOME:-\$HOME}/.android/avd\"
export PATH=\"\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator\"
# END sbb android sdk"

add_to_profile() {
  local profile="$1"
  [ -f "$profile" ] || return 0
  if grep -q "BEGIN sbb android sdk" "$profile"; then
    warn "Android SDK env already in $profile — skipping"
  else
    echo "" >> "$profile"
    echo "$EXPORT_BLOCK" >> "$profile"
    success "Added Android SDK env to $profile"
  fi
}

add_to_profile "$HOME/.bashrc"
add_to_profile "$HOME/.zshrc"
add_to_profile "$HOME/.profile"

# Make all vars available in the current session immediately
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_AVD_HOME="${XDG_CONFIG_HOME:-$HOME}/.android/avd"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# ─── 5. KVM validation (Linux emulator hardware acceleration) ─────────────────
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
  info "Validating KVM for Android emulator hardware acceleration..."

  # Step 1: check if the CPU supports hardware virtualisation at all
  VT_FLAGS=$(grep -Ec '(vmx|svm)' /proc/cpuinfo 2>/dev/null || echo 0)
  if [ "$VT_FLAGS" -eq 0 ]; then
    warn "CPU does not report VT-x/AMD-V flags."
    warn "Hardware virtualisation must be enabled in BIOS/UEFI before KVM will work."
    warn "The emulator will not start until this is resolved."
  else
    success "CPU supports hardware virtualisation (VT-x/AMD-V)"

    # Step 2: load the KVM kernel module if not already loaded
    if ! lsmod | grep -q '^kvm '; then
      info "Loading KVM kernel module..."
      if grep -q 'vmx' /proc/cpuinfo; then
        sudo modprobe kvm_intel 2>/dev/null || true
      else
        sudo modprobe kvm_amd 2>/dev/null || true
      fi
    fi

    # Step 3: if /dev/kvm still missing, install qemu-kvm as fallback
    if [ ! -e /dev/kvm ]; then
      info "Installing qemu-kvm..."
      sudo apt-get install -y qemu-kvm
    fi

    # Step 4: verify /dev/kvm now exists
    if [ -e /dev/kvm ]; then
      success "KVM available — emulator will run at full speed"

      # Step 5: ensure current user has access (needs kvm group)
      if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
        success "KVM permissions OK"
      else
        info "Granting KVM access to user '$USER'..."
        sudo usermod -aG kvm "$USER"
        warn "Log out and back in (or reboot) for group membership to take effect."
      fi
    else
      warn "KVM device still not available after loading modules."
      warn "VT-x/AMD-V may be disabled in BIOS/UEFI. Reboot, enter BIOS, and enable it."
    fi
  fi
fi

# ─── 6. ktlint ────────────────────────────────────────────────────────────────
info "Checking ktlint..."
if command -v ktlint &>/dev/null && ktlint --version 2>/dev/null | grep -q "$KTLINT_VERSION"; then
  success "ktlint $KTLINT_VERSION already installed"
else
  info "Installing ktlint $KTLINT_VERSION..."
  KTLINT_DIR="$HOME/.local/bin"
  mkdir -p "$KTLINT_DIR"
  curl -fsSL "https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint" -o "$KTLINT_DIR/ktlint"
  chmod +x "$KTLINT_DIR/ktlint"

  # Ensure ~/.local/bin is on PATH in shell profiles
  if ! echo "$PATH" | grep -q "$KTLINT_DIR"; then
    KTLINT_PATH_LINE="export PATH=\"\$PATH:$KTLINT_DIR\""
    for profile in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
      if [ -f "$profile" ] && ! grep -q "$KTLINT_DIR" "$profile"; then
        echo "$KTLINT_PATH_LINE" >> "$profile"
      fi
    done
    export PATH="$PATH:$KTLINT_DIR"
  fi
  success "ktlint $KTLINT_VERSION installed to $KTLINT_DIR/ktlint"
fi

# ─── Done ─────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}✅ Setup complete!${NC}"
echo ""
echo "⚠️  Restart your terminal (or run: source ~/.bashrc) to activate ANDROID_HOME."
echo ""
echo "Next steps:"
echo "  scripts/emulator-create.sh  — Create the SbbAVD emulator"
echo "  ./gradlew assembleDebug     — Build the debug APK"
echo "  npm run check               — Compile, lint, and test"
echo ""
