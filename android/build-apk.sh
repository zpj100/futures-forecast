#!/bin/bash
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:/opt/gradle-8.2.1/bin:$PATH"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$ROOT/android/app/keystore.jks" ]; then
  keytool -genkeypair -v \
    -keystore "$ROOT/android/app/keystore.jks" \
    -alias futures -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass futures2026 -keypass futures2026 \
    -dname "CN=Futures Forecast, OU=Personal, O=zpj100, L=China, ST=China, C=CN"
fi

cd "$ROOT/android"
gradle assembleRelease --no-daemon --stacktrace

APK=$(find "$ROOT/android/app/build/outputs/apk" -name '*.apk' | head -n 1)
mkdir -p "$ROOT/releases"
cp -f "$APK" "$ROOT/releases/futures-forecast-v1.0.0.apk"
ls -la "$ROOT/releases/futures-forecast-v1.0.0.apk"
