# ADR-0008: 会话层搬 Rust（终态落地 · 独立先行）

- 状态：已确认（2026-08-10，决策窗口 8）
- 日期：2026-08-10
- 范围：ADR-0003 决策 4（会话层终态 Rust）的落地路径；v1 已交付功能不回退

## 背景

ADR-0003 已定终态 = Kotlin 壳 + Rust 底层（渲染/会话/事件流）+ libghostty-vt 核心；会话层阶段一保留 Termux Java、终态搬 Rust，**搬移时机由后续工单定案**。本窗口填补该空白：

- 驱动：多 Agent 并行（2–4 个 Agent 同时跑）的并发安全与稳定性第一；语言统一（壳内单一 Kotlin↔Rust 边界、去 Java 第三套 JNI）与事件流为综合收益一并覆盖。
- 事实（2026-08-10 调研，见 `.scratch/fable-v1/research-会话层Rust零件.md`）：
  - **portable-pty 0.9.0**（MIT）Unix 实现依赖 `libc::openpty()`；Bionic 自 API 23 起提供 openpty（bionic pty.h），本项目 minSdk 24；Termux clang 实测 openpty 编译、链接、运行全部通过。已知 Android 链接失败案例（distant PR #270）根因是 Rust 安卓目标默认 API 21 + NDK 链接器按 min API 拒绝符号——本仓库 Termux clang 构建线无此检查，仅 NDK/GitHub Actions 备用线需显式 min API ≥ 23。
  - **nix 0.31.3**（MIT）pty 模块 posix_openpt/grantpt/unlockpt/ptsname_r 官方测试覆盖 `linux_android`（Google vendored nix）；工单 08 C 探针（posix_openpt + fork + exec）已真机验收——兜底路径零风险。
  - **rio-vt 0.5.19** PTY（teletypewriter）仅声明 macOS/Linux/Windows；**alacritty_terminal::tty** 无 Android 证据（Fressh 移动端只用其解析）——均不选。

## 决策

1. **范围**：PTY 生命周期（spawn/close/resize）、进程管理、环境注入、字节 I/O 全进 Rust；Java 壳只保留 UI、设置、系统集成（通知/前台服务/Intent）。
2. **驱动优先级**：多 Agent 并行并发安全第一；语言统一与事件流为综合收益一并覆盖。
3. **零件**：portable-pty 0.9.0（MIT）主选；nix 自拼（posix_openpt 序列）兜底。NDK/CI 备用构建线若启用，需显式 min API ≥ 23。
4. **排期**：独立先行、验证切片先行（工单 24），不并入 Kotlin 壳重构；工单 23（Java 基线采集）与 24 并行。
5. **过渡**：双实现并行 + 切换开关（构建期默认 Rust；设置页 debug 项可切回 Java）；Java 会话层原样保留，至验收对比（工单 27）后再评估去留。
6. **并发指标**：2–4 会话硬指标、8 并发设计余量。
7. **事件流（头脑风暴 §3.3 转正）**：第一版最小集六事件 = `command_started` / `output_chunk` / `command_finished` / `exit_code` / `session_created` / `session_closed`；schema 含 session_id、时间戳，留扩展 metadata；Rust 侧产出，经 JNI 事件回调暴露 Kotlin；Kotlin 第一版只接诊断/日志订阅。
8. **环境注入边界**：环境快照由 Kotlin 计算后传入 Rust（不重实现 AndroidShellEnvironment / termux-shared 的环境组装逻辑），第一版不搬环境构造。

## 理由

- openpty 事实验证：本机 Termux clang 直接编译运行 `openpty()` 通过；libc crate 0.2.189 对 android 也声明 openpty（`src/unix/linux_like/mod.rs`）。
- portable-pty 提供成熟 PtyPair/Child/CommandBuilder API，覆盖 PTY + 进程 + 环境，避免从零写 fork/exec/setsid/ioctl 的边界坑；nix 兜底已有真机证据。
- 独立先行：渲染已 Rust 化（工单 09–13、22），事件流本次定案——ADR-0003 的搬移前置条件已满足；独立切片先暴露最大未知数（安卓 PTY 零件），复刻工单 08 的验证节奏。
- 双实现并行：会话层是风险最高层，保留回退路径；工单 08 已证明薄 JNI 桥 + PTY 接线成本可控。
- 事件流最小集：覆盖多 Agent 场景可观测性（会话开始/输出/结束/退出码），扩展点兜未来 AI 功能。

## 冲突标注

- **ADR-0003（扩展，非推翻）**：决策 4 的"搬移时机由后续工单定案"由本 ADR 填充；其余不变。
- **ADR-0002（兼容）**：会话恢复（方案 B，最近会话记录）属壳层 UI 数据，继续由 Java 持有；Rust 会话层只负责进程生命周期。
- **工单 04（约束延续）**：UI 只对着 CoreAdapter 缝写不变；本窗口新增"会话层抽象缝"（工单 26 在 Java 侧引入 SessionFactory 接口，Java/Rust 双实现）。

## 重开条件

- 工单 24 证明 portable-pty 在 aarch64-linux-android（Termux clang 构建线）无法链接/运行，且 nix 兜底也不可行 → 回到零件重新评估。
- 工单 27 验收对比显示 Rust 会话层并发/稳定性不优于 Java 基线 → 暂停 Java 下线，保留双实现并评估原因。

## 参考

- 调研文件：`.scratch/fable-v1/research-会话层Rust零件.md`
- portable-pty 0.9.0：https://docs.rs/crate/portable-pty/0.9.0
- distant PR #270（openpty × Android Bionic）：https://github.com/chipsenkbeil/distant/pull/270
- bionic pty.h（openpty since API 23）：https://github.com/aosp-mirror/platform_bionic/blob/master/libc/include/pty.h
- nix 0.31.3 pty 模块：https://docs.rs/nix/latest/nix/pty/index.html
- rio-vt / librio 公告：https://rioterm.com/blog/2026/07/27/rio-vt-and-librio
- Fressh（alacritty_terminal 移动端解析先例）：https://github.com/EthanShoeDev/fressh
- 工单 08（posix_openpt 真机验收）：`.scratch/fable-v1/issues/08-libghostty-vt验证切片.md`
