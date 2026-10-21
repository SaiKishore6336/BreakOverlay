#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    for candidate in "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
        if [[ -d "$candidate" ]]; then
            export ANDROID_HOME="$candidate"
            break
        fi
    done
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    echo "error: Android SDK not found." >&2
    echo "Set ANDROID_HOME (or ANDROID_SDK_ROOT) to an SDK with platform 34 installed." >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "error: Java 17 is required but 'java' was not found." >&2
    exit 1
fi

echo "Building Break Overlay debug APK..."
./gradlew --console=plain assembleDebug

APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
    echo "error: Gradle finished but the APK was not found at $APK_PATH" >&2
    exit 1
fi

echo
echo "APK: $APK_PATH"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK_PATH"
fi
echo
echo "Install on a connected device with:"
echo "adb install -r \"$APK_PATH\""
