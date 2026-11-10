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

# 签名口令只从（未跟踪、被 .gitignore 忽略的）android/local.properties 读取，绝不硬编码。
LOCAL_PROPS="$ROOT/android/local.properties"
if [ ! -f "$LOCAL_PROPS" ]; then
  echo "缺少 $LOCAL_PROPS：请填写 keystore.file / keystore.storePassword / keystore.keyPassword / keystore.alias（含口令，禁止提交 git）" >&2
  exit 1
fi

# 精确按键名取值（仅剥离首个 '=' 之前的部分，值里含 '=' 也安全），不经过会触发 SIGPIPE 的 head 管道。
get_prop() {
  local key="$1" line
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "$key"=*) printf '%s' "${line#*=}"; return 0 ;;
    esac
  done < "$LOCAL_PROPS"
  return 0
}
KS_FILE="$(get_prop keystore.file)"
KS_STOREPASS="$(get_prop keystore.storePassword)"
KS_KEYPASS="$(get_prop keystore.keyPassword)"
KS_ALIAS="$(get_prop keystore.alias)"
if [ -z "$KS_FILE" ] || [ -z "$KS_STOREPASS" ] || [ -z "$KS_KEYPASS" ] || [ -z "$KS_ALIAS" ]; then
  echo "$LOCAL_PROPS 缺少 keystore 配置项" >&2
  exit 1
fi
# keystore.file 相对仓库根目录（与 app/build.gradle 约定一致）
if [[ "$KS_FILE" = /* ]]; then KEYSTORE="$KS_FILE"; else KEYSTORE="$ROOT/$KS_FILE"; fi

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_STOREPASS" -keypass "$KS_KEYPASS" \
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
