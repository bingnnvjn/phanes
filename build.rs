use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=src/tls_shim.c");
    println!("cargo:rerun-if-changed=src/pty_shim.c");
    let manifest = env::var("CARGO_MANIFEST_DIR").unwrap();
    let root = PathBuf::from(&manifest);

    // 工单 08 坑 1 复刻：TLS 对齐占位（ARM64 bionic 要求 PT_TLS p_align=64）。
    // 必须以 -fno-emulated-tls 编译，让占位真正产生 TLS 段。
    cc::Build::new()
        .file(root.join("src/tls_shim.c"))
        .flag("-fPIC")
        .flag("-fno-emulated-tls")
        .flag("-ftls-model=global-dynamic")
        .compile("tls_shim");
    cc::Build::new()
        .file(root.join("src/pty_shim.c"))
        .flag("-fPIC")
        .flag("-fno-emulated-tls")
        .compile("pty_shim");

    // 链接已验证的 expo 预编译 libghostty-vt（ghostty b0947378）。
    println!(
        "cargo:rustc-link-search=native={}",
        root.join("../spike-libghostty/lib/arm64-v8a").display()
    );
    println!("cargo:rustc-link-lib=static=ghostty-vt");
    println!("cargo:rustc-link-lib=m");
    println!("cargo:rustc-link-lib=dylib=log");
    println!("cargo:rustc-link-lib=dylib=android");

    // 强制链接器拉取 tls_shim.o（静态库成员按需提取，未引用会被丢弃），
    // 从而把 PT_TLS p_align 抬到 64。
    println!("cargo:rustc-link-arg=-Wl,--undefined=fable_render_tls_pad");
}
