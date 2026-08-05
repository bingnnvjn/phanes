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
BADGING=$(aapt2 dump badging "$APK")
echo "$BADGING" | sed -n '1,3p'
echo "$BADGING" | grep -q "package: name='com.gph.fable'" || {
    echo "ERROR: badging 包名不是 com.gph.fable" >&2
    exit 1
}

echo "== APK 内容清单（arm64 原生库）=="
SO_LIST=$(unzip -l "$APK" | grep '\.so')
echo "$SO_LIST"
echo "$SO_LIST" | grep -q 'lib/arm64-v8a/libtermux-bootstrap.so' || {
    echo "ERROR: APK 缺少 libtermux-bootstrap.so" >&2
    exit 1
}

echo "== apksigner verify（签名）=="
"$APKSIGNER" verify --print-certs "$APK"

echo "== 校验通过：$APK =="
