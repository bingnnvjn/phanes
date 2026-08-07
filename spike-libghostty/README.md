# spike-libghostty —— libghostty-vt 安卓资产（当前唯一来源）

> 历史：本目录诞生于工单 08 的验证切片，现承载**生产资产**（.a / 头文件 / 字体）。目录名暂不迁移；本 README 是资产清单的权威文件（SSOT，见 `docs/agents/ssot.md`）。

## 资产清单

| 资产 | 路径 | 版本 / 校验 |
| --- | --- | --- |
| libghostty-vt.a（arm64） | `lib/arm64-v8a/libghostty-vt.a` | expo-libghostty 0.8.1 release `storage.libghostty-vt.b0947378349e.r3`；ghostty commit `b0947378`；Zig 0.15.2 / NDK 27.1.12297006 / `-Dsimd=false`；sha256 `b38f032f…`（见 `lib/SHA256SUMS`） |
| 头文件 | `lib/include/ghostty/vt/*.h` | 与 ghostty b0947378 对应 |
| 字体 | `lib/fonts/SymbolsNerdFontMono-Regular.ttf` | Nerd Font Mono v3.4.0（许可见 `NERD-FONTS-LICENSE`） |
| x86_64 .a | `lib/x86_64/` | 同 release（本机验证只用 arm64） |

## 关键坑（跨会话记忆，别重新踩）

- **TLS 对齐**：NDK/Zig 产物 `.tbss` 仅 8 对齐，ARM64 bionic（API 29+）拒绝加载。解法：`-fno-emulated-tls` + 64 对齐 `__thread` 占位（C 探针）；Rust 侧见 `spike-render/build.rs`（`-Wl,--undefined=fable_render_tls_pad`）。
- 外部依赖仅 libm / libc / compiler-rt；Termux clang 可直接链接。
- JNI 桥与探针：`jni/ghostty_spike_jni.c`、`probe/spike_probe.c`（工单 08 产物）。

## 更新策略

升级核心 = 换新 .a + 头文件 + 重跑工单 08 式验证（TLS/显示节流/回归）。当前 **pinned b0947378 不动**（ADR-0004 决策 6；自建/换新 commit 作为独立工单后置）。
