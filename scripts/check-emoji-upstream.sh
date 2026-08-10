#!/usr/bin/env bash
# 工单 22 更新机制：每周比对 PoomSmart/EmojiFonts 最新 release 与当前字体版本。
# 直接运行即可（或挂 cron）；有新版时输出提示并按 docs/fonts/换字体步骤.md 走。

set -euo pipefail

CURRENT="21.4d3e1"
FABLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d "$FABLE_ROOT/.upstream-tmp.XXXXXX")"
cleanup() {
    if [ -d "$TMP_DIR" ]; then
        find "$TMP_DIR" -type f -delete 2>/dev/null || true
        rmdir "$TMP_DIR" 2>/dev/null || true
    fi
}
trap cleanup EXIT

API="https://api.github.com/repos/PoomSmart/EmojiFonts/releases/latest"
OUT="$TMP_DIR/latest.json"
if ! curl -fsSL -m 60 -o "$OUT" "$API"; then
    # GitHub API 直连不稳时经 ghfast 前缀试一次（仅 GET 公开数据）
    curl -fsSL -m 60 -o "$OUT" "https://ghfast.top/$API" || {
        echo "check-emoji-upstream: 无法访问 GitHub API（网络问题），跳过本轮" >&2
        exit 2
    }
fi

TAG="$(grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' "$OUT" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
NAME="$(grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' "$OUT" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
echo "check-emoji-upstream: 当前字体版本=$CURRENT"
echo "check-emoji-upstream: 上游最新 release tag=$TAG name=$NAME"

case "$TAG" in
    *apple*|*Apple*)
        echo "check-emoji-upstream: 发现 apple 系新 release —— 按 docs/fonts/换字体步骤.md 评估是否升级"
        ;;
    *)
        echo "check-emoji-upstream: 上游无 apple 系新 release（tag=$TAG），无需更新"
        ;;
esac
