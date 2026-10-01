#!/usr/bin/env bash
# 工单 22：Apple Color Emoji + Noto COLRv1 字体资产获取/剥离/校验。
#
# 幂等：目标文件 sha256 正确即跳过网络/剥离；任何一步失败即整体失败
# （APK 构建挂 preBuild，拉不到即构建失败）。
#
# 用法：scripts/fetch-emoji-fonts.sh [输出目录]
# 默认输出 android/app/src/main/assets/fonts/。

set -euo pipefail

FABLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="${1:-$FABLE_ROOT/android/app/src/main/assets/fonts}"
mkdir -p "$ASSETS_DIR"

APPLE_URL="https://ghfast.top/https://github.com/PoomSmart/EmojiFonts/releases/download/17.0.0-apple/AppleColorEmoji-160px.ttc"
APPLE_TTC_SHA256="76c37f95960d82a37a2c93c929dce7eb830a641707786a9e6a8ee17d911714dc"
APPLE_TTF_SHA256="6f6ad8b9751356c5707ab9e2645cddc3521d116a6b387d7b4b1437456d3784a3"
NOTO_SHA256="0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2"

TMP_DIR="$(mktemp -d "$FABLE_ROOT/.font-tmp.XXXXXX")"
cleanup() {
    if [ -d "$TMP_DIR" ]; then
        find "$TMP_DIR" -type f -delete 2>/dev/null || true
        rmdir "$TMP_DIR" 2>/dev/null || true
    fi
}
trap cleanup EXIT

sha256_ok() { # file expected_hex
    [ -f "$1" ] || return 1
    local got
    got="$(sha256sum "$1" | awk '{print $1}')"
    [ "$got" = "$2" ]
}

step_apple() {
    if sha256_ok "$ASSETS_DIR/AppleColorEmoji.ttf" "$APPLE_TTF_SHA256"; then
        echo "fetch-emoji-fonts: AppleColorEmoji.ttf 已就绪（sha256 校验通过）"
        return
    fi
    echo "fetch-emoji-fonts: 下载原始 ttc..."
    local ttc="$TMP_DIR/AppleColorEmoji-160px.ttc"
    curl -fL --retry 3 -m 300 -o "$ttc" "$APPLE_URL"
    if ! sha256_ok "$ttc" "$APPLE_TTC_SHA256"; then
        echo "fetch-emoji-fonts: ERROR 原始 ttc sha256 不匹配" >&2
        exit 1
    fi
    echo "fetch-emoji-fonts: 剥离（face 0 + 仅 160 档）..."
    local out="$TMP_DIR/AppleColorEmoji.ttf"
    (cd "$FABLE_ROOT/renderer" && cargo run --release --quiet --example strip_apple_emoji -- "$ttc" "$out")
    if ! sha256_ok "$out" "$APPLE_TTF_SHA256"; then
        echo "fetch-emoji-fonts: ERROR 剥离产物 sha256 不匹配" >&2
        exit 1
    fi
    mv "$out" "$ASSETS_DIR/AppleColorEmoji.ttf"
    echo "fetch-emoji-fonts: AppleColorEmoji.ttf -> $ASSETS_DIR"
}

step_noto() {
    if sha256_ok "$ASSETS_DIR/NotoColorEmoji.ttf" "$NOTO_SHA256"; then
        echo "fetch-emoji-fonts: NotoColorEmoji.ttf 已就绪（sha256 校验通过）"
        return
    fi
    local src="$FABLE_ROOT/renderer/assets/NotoColorEmoji.ttf"
    local out="$TMP_DIR/NotoColorEmoji.ttf"
    if sha256_ok "$src" "$NOTO_SHA256"; then
        cp "$src" "$out"
    else
        echo "fetch-emoji-fonts: renderer 内 Noto 缺失/不匹配，尝试下载..."
        curl -fL --retry 3 -m 300 -o "$out" \
            "https://ghfast.top/https://github.com/googlefonts/noto-emoji/raw/main/fonts/Noto-COLRv1.ttf"
    fi
    if ! sha256_ok "$out" "$NOTO_SHA256"; then
        echo "fetch-emoji-fonts: ERROR Noto COLRv1 sha256 不匹配" >&2
        exit 1
    fi
    mv "$out" "$ASSETS_DIR/NotoColorEmoji.ttf"
    echo "fetch-emoji-fonts: NotoColorEmoji.ttf -> $ASSETS_DIR"
}

step_apple
step_noto
echo "fetch-emoji-fonts: OK"
