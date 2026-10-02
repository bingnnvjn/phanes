#!/usr/bin/env bash
# Phanes（原名 Fable）工单 01：构建 + 校验一条命令。
# 用法：./build-and-verify.sh [debug|release]  （默认 debug）
#
# 设备相关输入由环境提供，脚本不写死本机路径：
#   - JDK：JAVA_HOME（未设时用 PATH 上的 java）
#   - Android SDK：ANDROID_HOME / ANDROID_SDK_ROOT，或 android/local.properties 的 sdk.dir
#   - aapt2 / apksigner：优先环境变量 AAPT2 / APKSIGNER，其次 PATH，最后 SDK build-tools
#
# 原生库由 scripts/build-native-libs.sh 生成到 app/src/main/jniLibs/arm64-v8a/；
# 本脚本只校验 APK 里的原生库清单，不负责生成（缺任一库即非零退出，工单 66）。
set -euo pipefail

cd "$(dirname "$0")"

BUILD_TYPE="${1:-debug}"
REPO_ROOT="$(cd .. && pwd)"
NATIVE_LIBS_LIST="$REPO_ROOT/scripts/native-libs.list"
[ -f "$NATIVE_LIBS_LIST" ] || {
    echo "ERROR: 缺少库清单：$NATIVE_LIBS_LIST" >&2
    exit 1
}

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
check_apk_native_libs() {
    apk="$1"
    label="$2"
    echo "--- $label"
    so_list=$(unzip -l "$apk" | grep '\.so' || true)
    echo "$so_list"

    missing_libs=0
    while IFS= read -r lib; do
        case "$lib" in ''|'#'*) continue ;; esac
        if printf '%s\n' "$so_list" | grep -q "lib/arm64-v8a/$lib"; then
            echo "OK  lib/arm64-v8a/$lib"
        else
            echo "MISSING  lib/arm64-v8a/$lib" >&2
            missing_libs=1
        fi
    done < "$NATIVE_LIBS_LIST"
    if [ "$missing_libs" -ne 0 ]; then
        echo "ERROR: $apk 原生库不齐全。生成命令：bash scripts/build-native-libs.sh" >&2
        return 1
    fi

    stray_abis=$(printf '%s\n' "$so_list" | sed -n 's#.*lib/\([^/]*\)/.*#\1#p' | sort -u | grep -v '^arm64-v8a$' || true)
    if [ -n "$stray_abis" ]; then
        echo "ERROR: $apk 含非目标 ABI 目录（清理 app/src/main/jniLibs 下对应目录后重跑）：" >&2
        printf '%s\n' "$stray_abis" >&2
        return 1
    fi
    return 0
}

echo "== ABI 范围（只允许 arm64-v8a）=="
check_apk_native_libs "$APK" "split APK" || exit 1

# universal APK 也要收在同一 ABI 里：jniLibs 里残留的非目标 ABI 目录会混进来。
UNIVERSAL_APK="${APK_BASE}_universal.apk"
if [ -f "$UNIVERSAL_APK" ] && [ "$UNIVERSAL_APK" != "$APK" ]; then
    echo "== ABI 范围（universal APK）=="
    check_apk_native_libs "$UNIVERSAL_APK" "universal APK" || exit 1
fi

echo "== apksigner verify（签名）=="
"$APKSIGNER" verify --print-certs "$APK"

echo "== 校验通过：$APK =="
