#!/usr/bin/env bash
set -euo pipefail

# JNI 库由已跟踪的 spike-session Rust 源生成；jniLibs 目录只存 Gradle 打包输入。
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
session_dir="$repo_root/spike-session"
output="$repo_root/fable-app/app/src/main/jniLibs/arm64-v8a/libfable-session.so"

(
    cd "$session_dir"
    cargo build --locked --target aarch64-linux-android --release
)

install -m 700 "$session_dir/target/aarch64-linux-android/release/libfable_session.so" "$output"
