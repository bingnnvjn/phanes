fn main() {
    let out = std::process::Command::new("rustc").arg("--version").output();
    if let Ok(out) = out {
        if let Ok(s) = String::from_utf8(out.stdout) {
            println!("cargo:rustc-env=RUSTC_VERSION={}", s.trim());
        }
    }
}
