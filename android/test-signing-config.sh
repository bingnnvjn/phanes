#!/usr/bin/env bash
# 工单 41：签名配置程序化验收。
set -euo pipefail

cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
    echo "warning: JAVA_HOME 未设置，Gradle 将使用 PATH 上的 java" >&2
fi
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

echo "== secret/keystore 扫描 =="
if git grep -nE "storePassword[[:space:]]+['\"][^'\"]+['\"]|keyPassword[[:space:]]+['\"][^'\"]+['\"]" -- \
    ':!*.md' ':!README.md'; then
    echo "ERROR: signing secret found in tracked source" >&2
    exit 1
fi
unexpected_keystores=$(git ls-files '*.jks' | grep -v '^app/<上游测试签名材料>\.jks$' || true)
if [ -n "$unexpected_keystores" ]; then
    echo "ERROR: tracked keystore found:" >&2
    echo "$unexpected_keystores" >&2
    exit 1
fi
echo "PASS: no signing secret or private keystore tracked"

gradle_args=(
    ":app:printSigningConfiguration"
    "-q"
    "--no-daemon"
    "-x"
    "fetchEmojiFonts"
)
missing_signing_args=(
    "-PFABLE_RELEASE_KEYSTORE="
    "-PFABLE_RELEASE_KEY_ALIAS="
    "-PFABLE_RELEASE_STORE_PASSWORD="
    "-PFABLE_RELEASE_KEY_PASSWORD="
)

echo "== debug 默认身份 =="
debug_output=$(
    env -u FABLE_DEBUG_USE_RELEASE_SIGNING \
        ./gradlew "${missing_signing_args[@]}" "${gradle_args[@]}"
)
echo "$debug_output"
grep -q "debug=default-debug-keystore" <<<"$debug_output"
grep -q "release=missing" <<<"$debug_output"

echo "== release 缺少凭据时明确失败 =="
if env -u FABLE_RELEASE_KEYSTORE -u FABLE_RELEASE_KEY_ALIAS \
    -u FABLE_RELEASE_STORE_PASSWORD -u FABLE_RELEASE_KEY_PASSWORD \
    ./gradlew "${missing_signing_args[@]}" ":app:verifyReleaseSigning" --no-daemon -x fetchEmojiFonts \
    >"$temp_dir/fable-release-signing-missing.log" 2>&1; then
    echo "ERROR: assembleRelease unexpectedly succeeded without signing credentials" >&2
    exit 1
fi
grep -q "Release signing requires" "$temp_dir/fable-release-signing-missing.log"

echo "== 显式 debug 覆盖 =="
override_output=$(
    FABLE_DEBUG_USE_RELEASE_SIGNING=1 \
    FABLE_RELEASE_KEYSTORE=/tmp/fable-release.jks \
    FABLE_RELEASE_KEY_ALIAS=fable \
    FABLE_RELEASE_STORE_PASSWORD=not-a-secret \
    FABLE_RELEASE_KEY_PASSWORD=not-a-secret \
    ./gradlew "${gradle_args[@]}"
)
echo "$override_output"
grep -q "debug=release-override" <<<"$override_output"
grep -q "release=configured" <<<"$override_output"

echo "PASS: signing configuration checks"
