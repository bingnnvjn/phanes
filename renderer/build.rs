use std::env;
use std::path::{Path, PathBuf};

/// 编译 vendored FreeType（工单 13：彩色字形通路 = FreeType 光栅 COLRv1）。
///
/// 选 cc crate 手动编（不装 cmake / 不引 freetype-sys）：与既有 tls/pty shim
/// 同一套编译路径，对 aarch64-linux-android 原生生效；只编 COLRv1 需要的模块
/// （base/sfnt/truetype/cff/psaux/pshinter/psnames/smooth/autofit），
/// 并已裁剪 ftmodule.h（去掉 svg/sdf/raster1/bdf/pcf/pfr/type1 等）与
/// ftoption.h（关掉 USE_ZLIB/USE_PNG/USE_BZIP2），静态链进 libfable-render.so。
fn build_freetype(root: &Path) {
    let ft = root.join("third_party/freetype");
    println!("cargo:rerun-if-changed={}", ft.join("include").display());
    println!("cargo:rerun-if-changed={}", ft.join("src").display());
    let mut build = cc::Build::new();
    build
        .include(ft.join("include"))
        .define("FT2_BUILD_LIBRARY", None)
        .flag("-fPIC");
    for rel in [
        "src/base/ftsystem.c",
        "src/base/ftinit.c",
        "src/base/ftdebug.c",
        "src/base/ftbase.c",
        "src/base/ftbbox.c",
        "src/base/ftglyph.c",
        "src/base/ftbitmap.c",
        "src/base/ftmm.c",
        "src/autofit/autofit.c",
        "src/cff/cff.c",
        "src/psaux/psaux.c",
        "src/pshinter/pshinter.c",
        "src/psnames/psnames.c",
        "src/sfnt/sfnt.c",
        "src/smooth/smooth.c",
        "src/truetype/truetype.c",
    ] {
        build.file(ft.join(rel));
    }
    build.compile("freetype");
}

fn main() {
    println!("cargo:rerun-if-changed=src/tls_shim.c");
    println!("cargo:rerun-if-changed=src/pty_shim.c");
    println!("cargo:rerun-if-changed=src/mmap_shim.c");
    println!("cargo:rerun-if-changed=src/sha256_shim.c");
    let Ok(manifest) = env::var("CARGO_MANIFEST_DIR") else {
        eprintln!("CARGO_MANIFEST_DIR is required by renderer build script");
        std::process::exit(1);
    };
    let root = PathBuf::from(&manifest);
    // ADR-0015 宿主矩阵：renderer 的产品目标是 aarch64-linux-android。
    // Android 专用编译/链接选项只在 Android 目标上传，否则非 Android 宿主（CI 的
    // x86_64-unknown-linux-gnu）会在 cc/链接阶段失败（例如 gcc 不认识 -fno-emulated-tls）。
    let on_android = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android");
    let on_aarch64 = env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("aarch64");

    // 工单 13：FreeType 静态接入（必须先于 tls_shim，保证 -fPIC 一致）。
    // FreeType 是便携 C，两个宿主都编（顺带在 CI 上验证 vendored 源码可编）。
    build_freetype(&root);

    if !on_android {
        // ADR-0015：renderer 的产品目标是 aarch64-linux-android。非 Android 宿主只做
        // 源码级检查（fmt/check/clippy/rustdoc）：C 垫片依赖 `android/log.h` 等 bionic
        // 头文件，链接输入是按 ADR-0012 不入库的 arm64 预编译核心。
        println!(
            "cargo:warning=non-Android target: Android-only C shims and native link inputs are skipped"
        );
        return;
    }

    // 工单 08 坑 1 复刻：TLS 对齐占位（ARM64 bionic 要求 PT_TLS p_align=64）。
    // 必须以 -fno-emulated-tls 编译，让占位真正产生 TLS 段。
    let mut tls = cc::Build::new();
    tls.file(root.join("src/tls_shim.c"))
        .flag("-fPIC")
        .flag("-fno-emulated-tls")
        .flag("-ftls-model=global-dynamic");
    let mut pty = cc::Build::new();
    pty.file(root.join("src/pty_shim.c"))
        .flag("-fPIC")
        .flag("-fno-emulated-tls");
    tls.compile("tls_shim");
    pty.compile("pty_shim");
    cc::Build::new()
        .file(root.join("src/mmap_shim.c"))
        .flag("-fPIC")
        .compile("mmap_shim");
    let mut sha = cc::Build::new();
    sha.file(root.join("src/sha256_shim.c")).flag("-fPIC");
    if on_aarch64 {
        sha.flag("-march=armv8-a+crypto");
    }
    sha.compile("sha256_shim");

    // 链接已验证的 expo 预编译 libghostty-vt（ghostty b0947378）与 bionic 系统库。
    println!(
        "cargo:rustc-link-search=native={}",
        root.join("../libghostty/lib/arm64-v8a").display()
    );
    println!("cargo:rustc-link-lib=static=ghostty-vt");
    println!("cargo:rustc-link-lib=m");
    println!("cargo:rustc-link-lib=dylib=log");
    println!("cargo:rustc-link-lib=dylib=android");

    // 强制链接器拉取 tls_shim.o（静态库成员按需提取，未引用会被丢弃），
    // 从而把 PT_TLS p_align 抬到 64。
    println!("cargo:rustc-link-arg=-Wl,--undefined=fable_render_tls_pad");
}
