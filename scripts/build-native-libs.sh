#!/usr/bin/env bash
# 工单 66：单一原生库通路。
#
# 用同一组步骤生成四个原生库，放进打包输入目录 android/app/src/main/jniLibs/<abi>/：
#   渲染器      renderer/                            -> libfable-render.so
#   会话层      session/                             -> libfable-session.so
#   bootstrap   android/app/src/main/cpp             -> libtermux-bootstrap.so
#   本地套接字  android/termux-shared/src/main/cpp   -> liblocal-socket.so
# 另把 local-socket 的 STL 运行库 libc++_shared.so 一起放进同一目录。
# 生成后不再依赖 AGP 的 externalNativeBuild / ndkBuild 产物（ADR-0015）。
#
# 宿主矩阵：
#   Linux aarch64（Termux 手机 / aarch64 Linux 电脑）: Termux clang + ndk-sysroot + libc++
#   Linux x86_64 / macOS x64 / macOS arm64 / Windows x64: 官方 NDK 的 clang
# 两条分支走同一组步骤、同一组参数，只有编译器路径不同。
#
# 用法：
#   bash scripts/build-native-libs.sh
#
# 环境变量（都有可用默认值）：
#   FABLE_NATIVE_CC / FABLE_NATIVE_CXX   显式覆盖 C / C++ 编译器
#   FABLE_NDK_VERSION                    非 aarch64 宿主的 NDK 版本，默认 22.1.7171670
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT  非 aarch64 宿主的 NDK 路径
#   ANDROID_HOME / ANDROID_SDK_ROOT      非 aarch64 宿主的 SDK 路径（在其中按版本找 NDK）
#   JITPACK_NDK_VERSION                  兼容既有写法，等价 FABLE_NDK_VERSION

set -euo pipefail

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "找不到 sha256sum / shasum，无法校验构建输入"
    fi
}

size_of() { wc -c < "$1" | tr -d ' '; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# 项目目标设备是 aarch64；产物 ABI 只保留 arm64-v8a（ADR-0015）。
abi="arm64-v8a"
api_app=24     # app 模块的 bootstrapMinSdk（apt-android-7）
api_shared=21  # termux-shared 的 minSdk
rust_target="aarch64-linux-android"

jni_out="$repo_root/android/app/src/main/jniLibs/$abi"
app_cpp="$repo_root/android/app/src/main/cpp"
shared_cpp="$repo_root/android/termux-shared/src/main/cpp"
bootstrap_zip="$app_cpp/bootstrap-aarch64.zip"
# 期望值同时出现在 android/app/build.gradle 的 downloadBootstrap 调用与 docs/build-inputs.md；
# 三处必须一起改。
bootstrap_zip_sha256="d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601"
native_libs_list="$script_dir/native-libs.list"

host_os="$(uname -s)"
host_arch="$(uname -m)"
case "$host_os/$host_arch" in
    Linux/aarch64|Linux/arm64) host_kind="aarch64" ;;
    *) host_kind="ndk" ;;
esac

# ---- 1. 构建输入自检（fail-closed，先于一切编译）----------------------------
step "自检构建输入 bootstrap-aarch64.zip"
if [ ! -f "$bootstrap_zip" ]; then
    die "缺少 $bootstrap_zip
获取指引：cd android && ./gradlew :app:downloadBootstraps
（来源与 sha256 见 docs/build-inputs.md；该归档不入库，ADR-0012）"
fi
actual_sha256="$(sha256_of "$bootstrap_zip")"
if [ "$actual_sha256" != "$bootstrap_zip_sha256" ]; then
    die "bootstrap-aarch64.zip sha256 不符
  期望: $bootstrap_zip_sha256
  实际: $actual_sha256
处理：删除该文件后重跑 cd android && ./gradlew :app:downloadBootstraps
（期望值来自 docs/build-inputs.md，改动前先核对来源表）"
fi
printf 'OK  bootstrap-aarch64.zip %s B sha256=%s\n' "$(size_of "$bootstrap_zip")" "$actual_sha256"

# ---- 2. 解析工具链 ---------------------------------------------------------
step "解析工具链（宿主 $host_kind：$host_os/$host_arch）"
cc="${FABLE_NATIVE_CC:-}"
cxx="${FABLE_NATIVE_CXX:-}"
libcxx_shared=""
cc_cmd=()   # C 编译器命令 + API 级别（app 模块 24）
cxx_cmd=()  # C++ 编译器命令 + API 级别（termux-shared 21）

if [ "$host_kind" = "aarch64" ]; then
    prefix="${PREFIX:-/data/data/com.termux/files/usr}"
    [ -d "$prefix/include/c++/v1" ] || die "缺少 libc++ 头文件：$prefix/include/c++/v1
获取指引：pkg install ndk-sysroot libc++"
    [ -f "$prefix/lib/libc++_shared.so" ] || die "缺少 libc++ 运行库：$prefix/lib/libc++_shared.so
获取指引：pkg install libc++"
    if [ -z "$cc" ]; then cc="$(command -v clang || true)"; fi
    if [ -z "$cxx" ]; then cxx="$(command -v clang++ || true)"; fi
    libcxx_shared="$prefix/lib/libc++_shared.so"
    # Termux clang 的默认目标就是 aarch64-linux-android；这里把 API 级别写成显式参数，
    # 与 NDK 分支的 *-androidNN-clang 包装同义。
    cc_cmd=("$cc" "--target=aarch64-linux-android$api_app")
    cxx_cmd=("$cxx" "--target=aarch64-linux-android$api_shared")
else
    ndk_version="${FABLE_NDK_VERSION:-${JITPACK_NDK_VERSION:-22.1.7171670}}"
    ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    if [ -z "$ndk_root" ]; then
        sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
        if [ -z "$sdk_root" ] && [ -f "$repo_root/android/local.properties" ]; then
            sdk_root="$(sed -n 's/^sdk\.dir=//p' "$repo_root/android/local.properties" | tail -n 1)"
        fi
        [ -n "$sdk_root" ] || die "非 aarch64 宿主需要官方 NDK：设置 ANDROID_NDK_HOME，
或让 ANDROID_HOME / ANDROID_SDK_ROOT 指向包含 ndk/$ndk_version 的 SDK"
        ndk_root="$sdk_root/ndk/$ndk_version"
    fi
    [ -d "$ndk_root" ] || die "NDK 路径不存在：$ndk_root（版本 $ndk_version）"
    case "$host_os" in
        Linux) ndk_host_tag="linux-x86_64" ;;
        Darwin)
            if [ -d "$ndk_root/toolchains/llvm/prebuilt/darwin-arm64" ]; then
                ndk_host_tag="darwin-arm64"
            else
                ndk_host_tag="darwin-x86_64"
            fi
            ;;
        MINGW*|MSYS*|CYGWIN*) ndk_host_tag="windows-x86_64" ;;
        *) die "不支持的宿主：$host_os（宿主矩阵见 docs/adr/0015-native-lib-host-matrix.md）" ;;
    esac
    ndk_bin="$ndk_root/toolchains/llvm/prebuilt/$ndk_host_tag/bin"
    [ -d "$ndk_bin" ] || die "NDK 工具链目录不存在：$ndk_bin"
    libcxx_shared="$ndk_root/toolchains/llvm/prebuilt/$ndk_host_tag/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    if [ -n "$cc" ]; then
        cc_cmd=("$cc" "--target=aarch64-linux-android$api_app")
    else
        cc_cmd=("$ndk_bin/aarch64-linux-android$api_app-clang")
    fi
    if [ -n "$cxx" ]; then
        cxx_cmd=("$cxx" "--target=aarch64-linux-android$api_shared")
    else
        cxx_cmd=("$ndk_bin/aarch64-linux-android$api_shared-clang++")
    fi
    printf 'NDK %s（%s）\n' "$ndk_version" "$ndk_root"
fi

[ -x "${cc_cmd[0]}" ] || die "C 编译器不可用：${cc_cmd[0]}"
[ -x "${cxx_cmd[0]}" ] || die "C++ 编译器不可用：${cxx_cmd[0]}"
[ -f "$libcxx_shared" ] || die "缺少 libc++_shared.so：$libcxx_shared"
[ -f "$native_libs_list" ] || die "缺少库清单：$native_libs_list"
command -v cargo >/dev/null 2>&1 || die "找不到 cargo（Rust 版本见 rust-toolchain.toml）"
printf 'CC  = %s\nCXX = %s\nlibc++_shared = %s\n' "${cc_cmd[*]}" "${cxx_cmd[*]}" "$libcxx_shared"

# Rust crates 内嵌 C（FreeType / TLS / PTY shim）走同一套编译器。
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${cc_cmd[0]}"
export CC_aarch64_linux_android="${cc_cmd[0]}"
export CXX_aarch64_linux_android="${cxx_cmd[0]}"
toolchain_dir="$(dirname "${cc_cmd[0]}")"
if [ -x "$toolchain_dir/llvm-ar" ]; then
    export AR_aarch64_linux_android="$toolchain_dir/llvm-ar"
elif command -v llvm-ar >/dev/null 2>&1; then
    export AR_aarch64_linux_android="$(command -v llvm-ar)"
fi
if [ -x "$toolchain_dir/llvm-ranlib" ]; then
    export RANLIB_aarch64_linux_android="$toolchain_dir/llvm-ranlib"
fi

# ---- 3. 生成四个原生库 -----------------------------------------------------
mkdir -p "$jni_out"

step "渲染器 renderer -> libfable-render.so"
(
    cd "$repo_root/renderer"
    cargo build --locked --release --target "$rust_target"
)
install -m 0644 "$repo_root/renderer/target/$rust_target/release/libfable_render.so" \
    "$jni_out/libfable-render.so"

step "会话层 session -> libfable-session.so"
(
    cd "$repo_root/session"
    cargo build --locked --release --target "$rust_target"
)
install -m 0644 "$repo_root/session/target/$rust_target/release/libfable_session.so" \
    "$jni_out/libfable-session.so"

step "bootstrap -> libtermux-bootstrap.so"
# termux-bootstrap-zip.S 用 .incbin 相对路径引用 bootstrap-aarch64.zip：必须在 cpp 目录内汇编。
(
    cd "$app_cpp"
    "${cc_cmd[@]}" -fPIC -shared \
        -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector -Wl,--gc-sections \
        -o "$jni_out/libtermux-bootstrap.so" \
        termux-bootstrap-zip.S termux-bootstrap.c
)

step "本地套接字 local-socket -> liblocal-socket.so"
(
    cd "$shared_cpp"
    "${cxx_cmd[@]}" -fPIC -shared \
        -std=c++17 -Wall -Wextra -Os \
        -o "$jni_out/liblocal-socket.so" \
        local-socket.cpp -llog
)
install -m 0644 "$libcxx_shared" "$jni_out/libc++_shared.so"

# local-socket 的 STL 依赖必须是运行时 c++_shared（与旧 ndk-build 的 APP_STL := c++_static
# 不同：Termux 不提供静态 libc++，两条宿主分支统一走 c++_shared）。
# 注：aarch64 宿主用 Termux clang 链接时会带一条指向 $PREFIX/lib 的 RUNPATH（clang spec 注入，
# 无法用编译参数去除）。它是设备本地工具的痕迹，.so 不入库；Android 加载时会跳过不存在
# 的路径，不影响加载。
readelf_bin=""
for candidate in "$toolchain_dir/llvm-readelf" "$(command -v readelf || true)" "$(command -v llvm-readelf || true)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then readelf_bin="$candidate"; break; fi
done
if [ -n "$readelf_bin" ]; then
    "$readelf_bin" -d "$jni_out/liblocal-socket.so" | grep -q 'libc++_shared.so' \
        || die "liblocal-socket.so 未记录 libc++_shared.so 依赖：STL 链接方式与预期不符"
    "$readelf_bin" -d "$jni_out/libtermux-bootstrap.so" >/dev/null
fi

# 提醒：留着旧 ABI 目录会让 universal APK 混入非目标 ABI。
for stray in "$repo_root/android/app/src/main/jniLibs"/*/; do
    [ -d "$stray" ] || continue
    if [ "$(basename "$stray")" = "$abi" ]; then continue; fi
    printf 'WARN 发现非目标 ABI 目录（打包前请清掉）：%s\n' "$stray" >&2
done

# ---- 4. 产物自检 -----------------------------------------------------------
step "校验打包输入目录 $jni_out"
required=()
while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    required+=("$line")
done < "$native_libs_list"
[ "${#required[@]}" -gt 0 ] || die "库清单为空：$native_libs_list"
missing=0
for name in "${required[@]}"; do
    target="$jni_out/$name"
    if [ -s "$target" ]; then
        printf 'OK  %-24s %11s B  %s\n' "$name" "$(size_of "$target")" "$(sha256_of "$target")"
    else
        printf 'MISSING %s\n' "$target" >&2
        missing=1
    fi
done
[ "$missing" -eq 0 ] || die "打包输入不完整"

printf '\n完成：%s\n' "$jni_out"
