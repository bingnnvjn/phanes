#!/usr/bin/env bash
#
# Fable Rust local quality gate (工单 48).
#
# The crates are intentionally invoked one at a time: they are independent
# repositories/manifests and must not accidentally become a workspace.
#
# Every check runs even after a failure so a baseline run reports all known
# failures. The final status is non-zero if any check failed.

set -u

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

CRATES=(
    "boo"
    "session"
    "renderer"
)

failed=0
step_count=0
failed_steps=0

toolchain_file="$REPO_ROOT/rust-toolchain.toml"
if [[ ! -f "$toolchain_file" ]]; then
    printf 'ERROR: missing toolchain file: %s\n' "$toolchain_file" >&2
    exit 2
fi
expected_toolchain="$(
    sed -n 's/^channel = "\([^"]*\)"/\1/p' "$toolchain_file" | head -n 1
)"
if [[ -z "$expected_toolchain" ]]; then
    printf 'ERROR: could not read channel from %s\n' "$toolchain_file" >&2
    exit 2
fi
rustc_version="$(rustc --version 2>/dev/null || true)"
cargo_version="$(cargo --version 2>/dev/null || true)"
if [[ "$rustc_version" != "rustc $expected_toolchain "* ]] ||
    [[ "$cargo_version" != "cargo $expected_toolchain "* ]]; then
    printf 'ERROR: expected Rust toolchain %s, got %s / %s\n' \
        "$expected_toolchain" "${rustc_version:-<rustc unavailable>}" \
        "${cargo_version:-<cargo unavailable>}" >&2
    exit 2
fi
printf 'Toolchain: %s (%s / %s)\n' "$expected_toolchain" "$rustc_version" "$cargo_version"

# ADR-0015 宿主矩阵：renderer 的测试二进制要链接 aarch64-linux-android 的预编译核心
# （按 ADR-0012 属构建输入，不入库）。只有 Android 宿主能链接并运行它；
# 非 Android 宿主仍跑 fmt/check/clippy/rustdoc，但跳过 renderer 的 test，并打印原因。
host_triple="$(rustc -vV 2>/dev/null | sed -n 's/^host: //p')"
android_host=0
case "$host_triple" in
    *android*) android_host=1 ;;
esac
printf 'Host: %s (renderer tests: %s)\n' \
    "${host_triple:-<unknown>}" \
    "$([[ "$android_host" -eq 1 ]] && printf 'run' || printf 'skip')"

run_step() {
    local crate="$1"
    local label="$2"
    shift 2
    step_count=$((step_count + 1))
    printf '\n[%02d] %s :: %s\n' "$step_count" "$crate" "$label"
    if "$@"; then
        printf 'PASS: %s :: %s\n' "$crate" "$label"
    else
        local status=$?
        printf 'FAIL(%d): %s :: %s\n' "$status" "$crate" "$label"
        failed=1
        failed_steps=$((failed_steps + 1))
    fi
}

for crate in "${CRATES[@]}"; do
    manifest="$REPO_ROOT/$crate/Cargo.toml"
    lockfile="$REPO_ROOT/$crate/Cargo.lock"

    if [[ ! -f "$manifest" ]]; then
        printf 'ERROR: missing manifest: %s\n' "$manifest" >&2
        failed=1
        failed_steps=$((failed_steps + 1))
        continue
    fi
    if [[ ! -f "$lockfile" ]]; then
        printf 'ERROR: missing lockfile: %s\n' "$lockfile" >&2
        failed=1
        failed_steps=$((failed_steps + 1))
        continue
    fi

    run_step "$crate" "fmt" \
        cargo fmt --manifest-path "$manifest" --all -- --check
    run_step "$crate" "check" \
        cargo check --manifest-path "$manifest" --all-targets --all-features --locked
    if [[ "$crate" == "renderer" ]]; then
        run_step "$crate" "clippy" \
            cargo clippy --manifest-path "$manifest" --all-targets --all-features --locked -- \
                -D warnings \
                -D unsafe_op_in_unsafe_fn \
                -D clippy::undocumented_unsafe_blocks
    else
        run_step "$crate" "clippy" \
            cargo clippy --manifest-path "$manifest" --all-targets --all-features --locked -- -D warnings
    fi
    if [[ "$crate" == "renderer" && "$android_host" -eq 0 ]]; then
        printf '\n[--] renderer :: test SKIPPED (host %s is not Android; the test binary links the\n' "$host_triple"
        printf '     aarch64-linux-android prebuilt core, which is a build input and not in the repo)\n'
    else
        run_step "$crate" "test" \
            cargo test --manifest-path "$manifest" --all-targets --all-features --locked
    fi
    run_step "$crate" "rustdoc" \
        env RUSTDOCFLAGS="-D warnings" \
        cargo doc --manifest-path "$manifest" --all-features --no-deps --locked
done

printf '\nRust quality gate: %d steps, %d failed\n' "$step_count" "$failed_steps"
if [[ "$failed" -ne 0 ]]; then
    exit 1
fi
