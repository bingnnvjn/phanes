# 24 — Rust 会话层验证切片（portable-pty 试编 + 真机探针）

**What to build:** 验证"portable-pty 0.9.0（或 nix 兜底）在 aarch64-linux-android + Termux clang 构建线下可用"：新 Rust crate 试编 portable-pty，薄 JNI 桥暴露 ptySpawn/Read/Write/Resize/Close；独立探针 APK（"Fable Session Probe" launcher 图标，不碰主终端）：spawn bash（com.gph.fable 环境注入：PREFIX/HOME/PATH/TERM/TMPDIR，环境由 Kotlin 传入）、读写交互、resize（40x10/80x24）、4 会话并行、seq 长输出性能粗测。若 portable-pty 链接/运行遇坑，切 nix 自拼（posix_openpt 序列）路径并记录证据。

**Blocked by:** None（与 fable-v1/23 并行）
Status: 待开工

## 验收清单

- [ ] portable-pty 0.9.0（或 nix 兜底）在 aarch64-linux-android 编译并链接成 .so，进 APK（jniLibs 直供），aapt2 badging / apksigner 校验通过
- [ ] 探针：spawn bash 出现提示符；`echo $PREFIX` = /data/data/com.gph.fable/files/usr；`pwd` = files/home
- [ ] 读写 + resize：40x10 / 80x24 行列正确、文本重排
- [ ] 4 会话并行互不崩、输出各自独立
- [ ] seq 200 流式输出不卡死（性能粗测）
- [ ] 环境注入正确（HOME/PREFIX/PATH/TERM/TMPDIR 与预期一致）
- [ ] 结论写回 Comments：portable-pty vs nix 判定、构建参数、坑（含 NDK/CI 线 min API ≥ 23 的风险说明）、对 fable-v1/25 的输入

## Comments

2026-08-10 建单（决策窗口 8：会话层搬 Rust，ADR-0008；调研依据 `.scratch/fable-v1/research-会话层Rust零件.md`）。
