#!/bin/bash
# 一键构建 APK。环境：WSL Debian + JDK 21 + Gradle 8.9 + Android SDK 34。
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE_BIN="${GRADLE_BIN:-/opt/gradle-8.9/bin/gradle}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$(dirname "$GRADLE_BIN"):$PATH"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$ROOT/android/app/keystore.jks" ]; then
  keytool -genkeypair -v \
    -keystore "$ROOT/android/app/keystore.jks" \
    -alias futures -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass futures2026 -keypass futures2026 \
    -dname "CN=Futures Forecast, OU=Personal, O=zpj100, L=China, ST=China, C=CN"
fi

VERSION="$(grep -m1 "versionName" "$ROOT/android/app/build.gradle" | sed -E "s/.*versionName[[:space:]]*'([^']+)'.*/\1/")"
echo "==> building v$VERSION"

cd "$ROOT/android"
"$GRADLE_BIN" assembleRelease --no-daemon --stacktrace

APK="$(find "$ROOT/android/app/build/outputs/apk/release" -name '*.apk' | head -n 1)"
mkdir -p "$ROOT/releases"
cp -f "$APK" "$ROOT/releases/futures-forecast-v$VERSION.apk"
ls -la "$ROOT/releases/futures-forecast-v$VERSION.apk"
