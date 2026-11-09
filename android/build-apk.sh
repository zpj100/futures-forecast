#!/bin/bash
# 一键构建 APK。需要 JDK 17+ 与 Gradle 8.2+，Android SDK 34。
# 可用环境变量覆盖：JAVA_HOME、GRADLE_BIN、ANDROID_HOME。
set -euo pipefail

pick_jdk() {
  for d in "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk-amd64 \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    if [ -n "$d" ] && [ -x "$d/bin/javac" ]; then echo "$d"; return 0; fi
  done
  return 1
}
pick_gradle() {
  for g in "${GRADLE_BIN:-}" /opt/gradle-8.9/bin/gradle /opt/gradle-8.2.1/bin/gradle; do
    if [ -n "$g" ] && [ -x "$g" ]; then echo "$g"; return 0; fi
  done
  command -v gradle || return 1
}

JDK="$(pick_jdk)" || { echo "未找到 JDK 17+"; exit 1; }
export JAVA_HOME="$JDK"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE_BIN="$(pick_gradle)" || { echo "未找到 Gradle 8.2+"; exit 1; }
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$(dirname "$GRADLE_BIN"):$PATH"

echo "==> JDK     : $JAVA_HOME"
echo "==> Gradle  : $GRADLE_BIN"
echo "==> SDK     : $ANDROID_HOME"

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
