# 60 — renderer unsafe 合约与全目标严格 lint

**What to build:** 清理 `spike-render` 现有 Rust `unsafe`、FFI 与诊断 example 的安全合约，使生产代码和全目标质量门禁都能启用 `unsafe_op_in_unsafe_fn` 与 `clippy::undocumented_unsafe_blocks`；不改变 Android 渲染行为或 ABI。

**Blocked by:** None
Status: 待验收

## 验收清单

- [x] `spike-render` 的每个 `unsafe` block 紧邻具体 `SAFETY:` 前提，不接受“应该安全”一类说明
- [x] 公共 `unsafe fn`、FFI 回调和手写 ABI 的调用责任有 `# Safety` 或等价的可审查契约
- [x] crate、example 和 CI 的全目标检查都启用 `unsafe_op_in_unsafe_fn` 与 `clippy::undocumented_unsafe_blocks`
- [x] 不以 crate 级 `allow`、无删除条件的 lint 例外或跳过 example 来绕过门禁
- [ ] `cargo fmt`、严格 Clippy、全目标测试、rustdoc 与 renderer 的 Android 构建/真机回归不退化
- [x] 独立审查覆盖 JNI、libghostty-vt、FreeType、mmap、native window、GPU 生命周期和 host CI shim 的安全前提

## Comments

2026-08-19 建单。工单 51 的 CI 已证明 renderer 可以在 host CI 上编译和测试，但
renderer 仍有大量既有 unsafe 块未逐一写出可验证的 `SAFETY:` 前提。不能为了让
公开发布计划看起来完整而虚假开启 `clippy::undocumented_unsafe_blocks`。本单完成后，
工单 61 才能把 renderer 作为公开 CI 输入审计。

2026-08-19 开始实施。先以全目标严格 Clippy 建立缺口清单，再按 FFI、字体 mmap、
JNI/native window、渲染线程和诊断 example 边界逐项收紧。

2026-08-19 验证数据：

- `cargo fmt --all -- --check` 通过。
- `cargo clippy --all-targets --all-features -- -D warnings -D unsafe_op_in_unsafe_fn -D clippy::undocumented_unsafe_blocks` 通过。覆盖库、二进制、全部 example 和测试目标。
- `cargo test --all-targets --all-features` 通过：23 个 library tests、全部二进制/example 测试目标均成功。
- `RUSTDOCFLAGS='-D warnings' cargo doc --all-features --no-deps --locked` 通过。
- `./scripts/rust-quality-gate.sh` 15/15 PASS；`spike-render` 的 CI Clippy 显式传入两个 strict lint。
- `cd fable-app && ./gradlew :app:assembleDebug` 未完成：`buildFableSession` 和字体 SHA 校验通过，随后 `:app:downloadBootstraps` 下载既有 `bootstrap-aarch64.zip` 时远端 TLS 握手中断，未进入 APK 打包，更未执行真机回归。

2026-08-19 踩坑与解法：给全局 Rust 门禁直接追加 strict lint 会让不在本工单范围内
的 `fable-boo` 失败。门禁现只对 `spike-render` 追加这两个 lint，其他 crate 保持既有
`-D warnings`。旧诊断 example 与 `src/main.rs` 也会作为独立 target 编译，不能只修
library；已把每个 raw FFI 操作收进有具体前提的 `unsafe` block。

2026-08-19 结论写回：renderer 的 JNI、Ghostty snapshot、FreeType/COLRv1、sbix
mmap、native window/wgpu、renderer 线程与 PTY shim 现在都有可审查的局部前提，且
host CI 不会跳过诊断 example。工单 61 可把 `spike-render` 纳入公开前 CI/ref 审计；
工单 59 前仍须补一次成功的 APK 构建和真机回归，不能把本次 TLS 下载失败视为 Android
验收通过。
