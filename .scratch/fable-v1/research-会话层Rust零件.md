# 调研：会话层 Rust 零件在 Android 的可用性（2026-08-10）

> 用途：决策会话"会话层搬 Rust"的零件选型事实依据（SSOT 补丁：`docs/adr/0003` 决策 4 的候选零件现状）。
> 方法：一手来源（官方仓库源码 / docs.rs / bionic 源码 / 已合并 PR），结论均带 URL；本地可验证项在真机环境实测。

## 结论速览

| 候选 | 版本 | Android 支持现状 | 本环境实测 | 判定 |
| --- | --- | --- | --- | --- |
| portable-pty（WezTerm） | 0.9.0（MIT） | 非官方支持；Unix 实现用 `libc::openpty()`，Android Bionic 自 API 23 起提供该符号 | ✅ Termux clang 下 `openpty()` 编译链接运行通过 | **主选**（先试编验证） |
| nix::pty（自拼 posix_openpt 序列） | 0.31.3（MIT） | 官方测试覆盖 `linux_android`（posix_openpt/grantpt/unlockpt/ptsname_r） | 工单 08 的 C 探针（posix_openpt + fork + exec）已在真机跑通 | **备选兜底** |
| rio-vt（含 PTY 驱动 teletypewriter） | 0.5.19 | 官方仅声明 macOS/Linux/Windows；无 Android 证据 | 未测 | 不选 |
| alacritty_terminal::tty | 最新（未核实精确版本） | 无 Android 支持证据；Fressh 在移动端只用其 Term 解析、不用 tty | 未测 | 不选 |

## 证据明细

### 1. portable-pty 0.9.0

- 版本与许可证：0.9.0，MIT（docs.rs crate 页 / Cargo.toml `license = "MIT"`）。
- Unix 实现（src/unix.rs）直接调用 `libc::openpty()` 建 PTY，再用 `ioctl(TIOCSWINSZ/TIOCGWINSZ)`、`setsid()`、`TIOCSCTTY`、`ttyname_r`、`tcgetpgrp`、`nix::sys::termios::tcgetattr`；spawn 用 `std::process::Command` + `pre_exec`（setsid + 可选 controlling tty + umask）。
  - 来源：https://docs.rs/crate/portable-pty/0.9.0/source/src/unix.rs
- Android 已知坑：distant PR #270（2026-03 已合并）报告 `openpty()` 在 Android Bionic 链接失败——根因是 Rust 安卓目标默认 API 21（低于 openpty 的 introduced=23），NDK 链接器按 min API 拒绝符号。
  - 来源：https://github.com/chipsenkbeil/distant/pull/270
- 本环境实测（2026-08-10，Termux clang / aarch64 真机环境）：
  - `openpty()` C 程序编译、链接、运行全部通过（openpty OK m=3 s=4，exit=0）。
  - libc crate 0.2.189 对 android 也声明 `openpty`（`src/unix/linux_like/mod.rs` 的 `cfg_if! not(target_env="uclibc")` 块包含 android）。
  - 结论：本仓库的 Termux clang 构建线（无 NDK 的 introduced-API 检查）下，portable-pty 大概率可直接编译；风险点转为"若未来切回 NDK/GitHub Actions 构建，需显式配置 min API ≥ 23"。

### 2. nix::pty（备选）

- nix 0.31.3，MIT；`pty` 模块提供 `posix_openpt` / `grantpt` / `unlockpt` / `ptsname_r`（标注 `linux_android`）/ `openpty` / `forkpty`。
  - 来源：https://docs.rs/nix/latest/nix/pty/index.html
- Google 官方 vendored nix 测试含 `[cfg(linux_android)]` 的 posix_openpt 用例（android-crates-io 镜像），说明该路径在 Android 受支持。
  - 来源：https://android.git.googlesource.com/platform/external/rust/android-crates-io/+/20faca8ad6dd4fcf5e014c415faedbad4c5f66a2/crates/nix/test/test_pty.rs
- 工单 08 的 C 探针（`posix_openpt + fork + exec bash --login`，环境变量由 C 侧设置）已在真机验收通过——同一语义的 Rust 实现风险极低。
  - 来源：`.scratch/fable-v1/issues/08-libghostty-vt验证切片.md` Comments

### 3. rio-vt 0.5.19

- 含 PTY 驱动（teletypewriter）与 VT 状态机；官方公告与 docs.rs 均只声明 macOS/Linux/Windows 平台支持，无 Android 证据。
  - 来源：https://rioterm.com/blog/2026/07/27/rio-vt-and-librio 、https://docs.rs/crate/rio-vt/latest
- 本项目已用 libghostty-vt 作核心，rio-vt 的 VT 部分是重复资产；其 PTY 又无 Android 证据 → 不选。

### 4. alacritty_terminal::tty

- alacritty_terminal 含 `tty` 模块与 `event_loop`（PTY I/O），但官方无 Android 支持声明。
- 移动端先例 Fressh（2026-06）在 iOS/Android 用 alacritty_terminal 仅做 SSH 字节解析（Term 状态），不涉及本地 PTY。
  - 来源：https://github.com/EthanShoeDev/fressh 、alacritty/alacritty issue #7121（Android 移植诉求无官方结论）

## 风险与待验证项

1. **试编验证**（首张切片的必做项）：portable-pty 0.9.0 在 `aarch64-linux-android`（Termux clang）下 `cargo build` + 链接成 .so，跑通最小探针（spawn bash + 读写 + resize）。
2. **NDK/CI 构建线**：GitHub Actions 备用线若启用，需显式 min API ≥ 23（`cargo-ndk -p 24` 或等价配置），否则重现 distant PR #270 的链接错误。
3. **fork 语义**：Android 上 fork + exec 是 Termux 既有路径（libtermux.so、工单 08 探针均如此），无新风险；但 portable-pty 的 `close_random_fds()` 依赖 `/dev/fd`（不存在时为 no-op，安全）。

## 引用

- portable-pty 0.9.0 crate 页 / 源码 / Cargo.toml：https://docs.rs/crate/portable-pty/0.9.0
- distant PR #270（Android Bionic openpty 链接失败证据）：https://github.com/chipsenkbeil/distant/pull/270
- nix 0.31.3 pty 模块：https://docs.rs/nix/latest/nix/pty/index.html
- Android vendored nix PTY 测试（linux_android）：https://android.git.googlesource.com/platform/external/rust/android-crates-io/+/20faca8ad6dd4fcf5e014c415faedbad4c5f66a2/crates/nix/test/test_pty.rs
- bionic pty.h（openpty available since API 23）：https://github.com/aosp-mirror/platform_bionic/blob/master/libc/include/pty.h
- rio-vt / librio 公告：https://rioterm.com/blog/2026/07/27/rio-vt-and-librio
- Fressh（alacritty_terminal 移动端解析先例）：https://github.com/EthanShoeDev/fressh
- 工单 08 真机验收（posix_openpt 路径）：`.scratch/fable-v1/issues/08-libghostty-vt验证切片.md`
