#!/usr/bin/env bash
# One-shot setup + build for Linux (Ubuntu, Arch/CachyOS, Fedora...).
#  - finds a JDK between 17 and 23 (what Gradle 8.11 + Android Gradle Plugin 8.7 need)
#  - installs the Android SDK command-line tools, platform 35 and build-tools if they are missing
#  - writes local.properties and runs ./gradlew assembleDebug
# Usage: ./scripts/setup-and-build.sh [gradle tasks to run before assembleDebug, e.g. clean]
set -euo pipefail

cd "$(dirname "$0")/.."
PROJECT_DIR="$(pwd)"
LOG="$PROJECT_DIR/build.log"
: > "$LOG"
say() { echo "==> $*" | tee -a "$LOG"; }
die() { echo "ERROR: $*" | tee -a "$LOG" >&2; exit 1; }

# ---------------------------------------------------------------- JDK
java_major() {
    "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1") ? v[2] : v[1]; exit}'
}
pick_jdk() {
    local best="" best_major=0 dir major
    for dir in "${JAVA_HOME:-}" /usr/lib/jvm/* /opt/android-studio/jbr "$HOME"/android-studio/jbr "$HOME"/.jdks/*; do
        [ -n "$dir" ] && [ -x "$dir/bin/javac" ] || continue
        major="$(java_major "$dir" || true)"
        case "$major" in
            21) echo "$dir"; return 0 ;;
            17|18|19|20|22|23) if [ "$best_major" -eq 0 ] || [ "$major" -eq 17 ]; then best="$dir"; best_major="$major"; fi ;;
        esac
    done
    [ -n "$best" ] && echo "$best"
}
say "Looking for a JDK (17 to 23)"
ls -d /usr/lib/jvm/* 2>/dev/null | tee -a "$LOG" || true
JDK="$(pick_jdk || true)"
if [ -z "$JDK" ]; then
    die "No JDK 17-23 found. Install one, then re-run:
    Ubuntu/Debian : sudo apt install openjdk-17-jdk
    Arch/CachyOS  : sudo pacman -S jdk17-openjdk
    Fedora        : sudo dnf install java-17-openjdk-devel"
fi
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"
say "Using JDK $(java_major "$JDK") at $JDK"

# ---------------------------------------------------------------- Android SDK
SDK=""
for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk; do
    if [ -n "$dir" ] && [ -d "$dir" ] && [ -w "$dir" ]; then SDK="$dir"; break; fi
done
[ -n "$SDK" ] || SDK="$HOME/Android/Sdk"
mkdir -p "$SDK"
say "Android SDK directory: $SDK"
ls "$SDK" "$SDK/platforms" "$SDK/build-tools" 2>/dev/null | tee -a "$LOG" || true

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -d "$SDK/platforms/android-35" ] || [ ! -d "$SDK/build-tools/34.0.0" ] || [ ! -d "$SDK/platform-tools" ]; then
    if [ ! -x "$SDKMANAGER" ]; then
        say "Downloading Android command-line tools"
        command -v unzip >/dev/null || die "unzip is required (sudo apt install unzip / sudo pacman -S unzip)"
        TMP="$(mktemp -d)"
        curl -fL --progress-bar -o "$TMP/tools.zip" \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        unzip -q "$TMP/tools.zip" -d "$TMP"
        mkdir -p "$SDK/cmdline-tools"
        rm -rf "$SDK/cmdline-tools/latest"
        mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"
        rm -rf "$TMP"
    fi
    say "Accepting SDK licences and installing platform 35, build-tools 34.0.0, platform-tools"
    yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >>"$LOG" 2>&1 || true
    "$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;34.0.0" 2>&1 | tee -a "$LOG"
fi

echo "sdk.dir=$SDK" > "$PROJECT_DIR/local.properties"
export ANDROID_HOME="$SDK"

# ---------------------------------------------------------------- Build
say "Running ./gradlew $* assembleDebug"
set +e
./gradlew --console=plain -Dorg.gradle.java.home="$JAVA_HOME" "$@" assembleDebug 2>&1 | tee -a "$LOG"
STATUS=${PIPESTATUS[0]}
set -e
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ "$STATUS" -eq 0 ] && [ -f "$APK" ]; then
    say "BUILD OK: $APK ($(du -h "$APK" | cut -f1))"
    say "Install on a phone with USB debugging enabled: $SDK/platform-tools/adb install -r \"$APK\""
else
    say "BUILD FAILED (exit $STATUS). Full output is in $LOG"
    exit 1
fi
