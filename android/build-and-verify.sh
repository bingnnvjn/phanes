#!/usr/bin/env bash
# Phanes（原名 Fable）工单 01：构建 + 校验一条命令。
# 用法：./build-and-verify.sh [debug|release]  （默认 debug）
#
# 设备相关输入由环境提供，脚本不写死本机路径：
#   - JDK：JAVA_HOME（未设时用 PATH 上的 java）
#   - Android SDK：ANDROID_HOME / ANDROID_SDK_ROOT，或 android/local.properties 的 sdk.dir
#   - aapt2 / apksigner：优先环境变量 AAPT2 / APKSIGNER，其次 PATH，最后 SDK build-tools
set -euo pipefail

cd "$(dirname "$0")"

BUILD_TYPE="${1:-debug}"

if [ -z "${JAVA_HOME:-}" ]; then
    echo "warning: JAVA_HOME 未设置，Gradle 将使用 PATH 上的 java" >&2
fi

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_dir" ] && [ -f local.properties ]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)"
fi

resolve_tool() {
    tool="$1"
    override="$2"
    if [ -n "$override" ]; then
        printf '%s\n' "$override"
    elif command -v "$tool" >/dev/null 2>&1; then
        # PATH 优先：aarch64 宿主上系统自带的 aapt2 才可执行，SDK 那份是 x86_64
        command -v "$tool"
    elif [ -n "$sdk_dir" ] && [ -x "$sdk_dir/build-tools/36.0.0/$tool" ]; then
        printf '%s\n' "$sdk_dir/build-tools/36.0.0/$tool"
    fi
}

AAPT2="${AAPT2:-$(resolve_tool aapt2 "${AAPT2:-}")}"
APKSIGNER="${APKSIGNER:-$(resolve_tool apksigner "${APKSIGNER:-}")}"
if [ -z "$AAPT2" ] || [ -z "$APKSIGNER" ]; then
    echo "ERROR: 找不到 aapt2/apksigner；设置 AAPT2、APKSIGNER 或 ANDROID_HOME" >&2
    exit 1
fi

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
BADGING=$("$AAPT2" dump badging "$APK")
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
