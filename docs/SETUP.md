# Developer Setup

Local development environment setup for the SBB Ruby Slippers Android app (`ch.rhosys.sbb`).

## Prerequisites

| Dependency | Version | Notes |
|-----------|---------|-------|
| Node.js | 20+ | For git hooks and deploy script. Install via [nvm](https://github.com/nvm-sh/nvm). |
| Java | 17 | Installed automatically by `scripts/setup.sh` if missing. |
| Android SDK | 35 | Installed automatically by `scripts/setup.sh`. |
| KVM | — | Linux only. Required for emulator hardware acceleration. |

## Initial Setup

Run the setup script from the project root:

```bash
scripts/setup.sh
```

This installs:
- Java 17 (via `apt-get` on Linux, `brew` on macOS)
- Android SDK command-line tools to `$ANDROID_HOME/cmdline-tools/latest/`
- SDK components: `platform-tools`, `build-tools;35.0.0`, `platforms;android-35`
- Accepts all Android SDK licenses
- Writes `ANDROID_HOME` and tool paths to `~/.bashrc`, `~/.zshrc`, `~/.profile`
- Validates KVM availability (Linux)
- Installs ktlint 1.5.0 to `~/.local/bin/`

After setup completes, restart your terminal (or `source ~/.bashrc`) to activate the new environment variables.

Then install Node.js dependencies for git hooks:

```bash
npm ci
```

## Emulator Setup

Create the development AVD (downloads ~1.5 GB system image on first run):

```bash
scripts/emulator-create.sh
```

This creates an AVD named `SbbAVD` using the Pixel 7 device profile with `system-images;android-35;google_apis;x86_64`. If the AVD already exists, the script exits cleanly without recreating it.

Start the emulator in foreground:

```bash
scripts/emulator-start.sh
```

Delete the AVD when no longer needed:

```bash
scripts/emulator-delete.sh
```

## Daily Workflow

1. Start the emulator: `scripts/emulator-start.sh`
2. Build and install the debug APK: `./gradlew assembleDebug`
3. Install on running emulator: `adb install app/build/outputs/apk/debug/app-debug.apk`
4. Make changes, rebuild, reinstall
5. Before pushing, run the quality gate: `scripts/check.sh`

## Commands

| Command | Description |
|---------|-------------|
| `scripts/setup.sh` | Full environment setup (Java, SDK, ktlint, KVM) |
| `scripts/emulator-create.sh` | Create the SbbAVD emulator |
| `scripts/emulator-start.sh` | Start the SbbAVD emulator (foreground) |
| `scripts/emulator-delete.sh` | Delete the SbbAVD emulator |
| `scripts/check.sh` | Pre-push quality gate: compile + lint + test |
| `./gradlew assembleDebug` | Build debug APK → `app/build/outputs/apk/debug/app-debug.apk` |
| `./gradlew bundleRelease` | Build release AAB (unsigned) → `app/build/outputs/bundle/release/app-release.aab` |
| `./gradlew compileDebugKotlin` | Compile only (fast feedback) |
| `./gradlew lintDebug` | Run Android Lint checks |
| `./gradlew testDebugUnitTest` | Run unit tests |

## Pre-Push Quality Gate

Run `scripts/check.sh` before pushing. It executes a single Gradle invocation:

```bash
./gradlew compileDebugKotlin lintDebug testDebugUnitTest
```

This mirrors what CI runs. If any step fails, the script exits non-zero and reports which task failed. Fix all issues before pushing.

## Local Build Commands

### Debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

The debug build uses application ID `ch.rhosys.sbb.debug` so it can be installed alongside the release version.

### Release AAB (unsigned)

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

Without signing properties, this produces an unsigned AAB. Useful for verifying R8 shrinking works correctly.

### Release AAB (signed)

Signed builds are normally produced by CI. To sign locally (requires the decrypted keystore):

```bash
./gradlew bundleRelease \
  -Pandroid.injected.signing.store.file=/path/to/keystore.p12 \
  -Pandroid.injected.signing.store.password="$PASSWORD" \
  -Pandroid.injected.signing.key.alias=sbb \
  -Pandroid.injected.signing.key.password="$PASSWORD"
```

See [docs/SIGNING.md](SIGNING.md) for keystore generation and decryption details.

## Troubleshooting

### `ANDROID_HOME` not found after setup

The setup script writes environment variables to shell profile files. You must restart your terminal or source the profile:

```bash
source ~/.bashrc   # or ~/.zshrc
```

If the variable is still missing, check that the marker block `# BEGIN sbb android sdk` exists in your profile file.

### Emulator fails with KVM error

The Android emulator requires hardware virtualisation (KVM on Linux).

1. Verify your CPU supports it: `grep -Ec '(vmx|svm)' /proc/cpuinfo` should return > 0
2. If it returns 0, enable VT-x/AMD-V in your BIOS/UEFI settings
3. If the CPU supports it but `/dev/kvm` is missing, run: `sudo apt-get install qemu-kvm`
4. Ensure your user is in the `kvm` group: `sudo usermod -aG kvm $USER` (log out and back in)

### Java version mismatch

Gradle requires Java 17. If you see errors about unsupported class file versions:

```bash
java -version   # should show version "17.x.x"
```

If a different version is active, set `JAVA_HOME` explicitly:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # Linux
export JAVA_HOME=$(/usr/libexec/java_home -v 17)      # macOS
```

### Gradle build fails with "SDK not found"

Ensure `ANDROID_HOME` is set and the SDK is installed:

```bash
echo $ANDROID_HOME                    # should print a path
ls $ANDROID_HOME/platforms/android-35 # should exist
```

If missing, re-run `scripts/setup.sh`.

### ktlint not found during commit

The pre-commit hook runs `ktlint --format` on staged `.kt` files. If ktlint is not on PATH:

1. Re-run `scripts/setup.sh` (installs ktlint to `~/.local/bin/`)
2. Ensure `~/.local/bin` is on your PATH
3. Restart your terminal
