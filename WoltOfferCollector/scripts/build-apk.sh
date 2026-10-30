#!/usr/bin/env bash
# Builds the debug APK, runs the JVM unit tests and copies the APK to dist/.
# Usage: scripts/build-apk.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# JDK 17+: use JAVA_HOME if set, else Homebrew openjdk@17, else Android Studio's bundled JBR.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for c in /opt/homebrew/opt/openjdk@17 "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [[ -x "$c/bin/java" ]] && export JAVA_HOME="$c" && break
  done
fi
[[ -n "${JAVA_HOME:-}" ]] || { echo "JDK 17+ not found. Set JAVA_HOME." >&2; exit 1; }

# Android SDK: local.properties wins; otherwise ANDROID_HOME / common locations.
if [[ ! -f local.properties ]]; then
  for c in "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools; do
    [[ -n "$c" && -d "$c/platforms" ]] && echo "sdk.dir=$c" > local.properties && break
  done
fi
[[ -f local.properties ]] || { echo "Android SDK not found. Create local.properties with sdk.dir=..." >&2; exit 1; }

./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug
version=$(grep -E 'versionName *=' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk "dist/WoltOfferCollector-${version}-debug.apk"
echo "APK: dist/WoltOfferCollector-${version}-debug.apk"
