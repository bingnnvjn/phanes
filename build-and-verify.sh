#!/data/data/com.termux/files/usr/bin/bash
# Fable（寓言）工单 01：构建 + 校验一条命令。
# 用法：./build-and-verify.sh [debug|release]  （默认 debug）
set -euo pipefail

cd "$(dirname "$0")"

BUILD_TYPE="${1:-debug}"
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
APKSIGNER=<仓库外 android-sdk>/build-tools/36.0.0/apksigner

./gradlew ":app:assemble${BUILD_TYPE^}"

APK_BASE="app/build/outputs/apk/${BUILD_TYPE}/fable-app_apt-android-7-${BUILD_TYPE}"
if [ -f "${APK_BASE}_arm64-v8a.apk" ]; then
    APK="${APK_BASE}_arm64-v8a.apk"
else
    APK="${APK_BASE}_universal.apk"
fi
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found: $APK" >&2
    exit 1
fi

echo "== aapt2 badging（包名/版本）=="
aapt2 dump badging "$APK" | head -3

echo "== APK 内容清单（arm64 原生库）=="
unzip -l "$APK" | grep '\.so'

echo "== apksigner verify（签名）=="
"$APKSIGNER" verify --print-certs "$APK"

echo "== 校验通过：$APK =="
