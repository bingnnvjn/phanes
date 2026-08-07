# ADR-0003: 引擎终态 = libghostty-vt 核心 + Kotlin 壳 + Rust 底层

- 状态：已确认（2026-08-06，grilling 会话拍板）
- 日期：2026-08-06
- 范围：Fable 引擎与语言架构的终态方向；v1（工单 04）交付范围不变，但 UI 必须只对着 CoreAdapter 缝写

## 背景

动机：用户核心目标不是"加 AI 功能"，而是让 AI Agent（Codex 等）在终端里**跑得流畅、自然**；多会话并行（2–4 个 Agent 同时跑）是真实使用场景，且已出现过因会话过多导致的崩溃。"要做就做好"，长期地基优先。

调研事实（2026-08-06，一手来源核实）：

- Ghostty 本体用 **Zig** 写；libghostty 是可嵌入的 C/Zig 库。
- 安卓上只有 **libghostty-vt**（终端核心：解析器 + 网格 + 滚动缓冲 + 状态，无渲染）可用——官方 2026-06 合并 lib-vt Android 支持（PR #10925，含 CI 与 NDK 交叉编译）；**完整 libghostty 的渲染/App 层没有安卓运行时**。
- 已有安卓集成先例：expo-libghostty（libghostty-vt + JNI + Canvas/Skia 脏行渲染，MIT）、ghostty_vte（Dart/Flutter 绑定）、Ghostel（Emacs/Termux）、Shellow（Rust 内核 + wgpu + SSH + Codex app-server，V2EX 2026-07）。
- Ghostty 官方参考实现 **Ghostling**（单文件 C，Raylib 演示，MIT）：展示 libghostty C API 的最小接线。
- 会话层 Rust 零件：portable-pty（WezTerm）、alacritty_terminal::tty（Zed 编辑器在用的嵌入模式）、rio-vt（自带 PTY，但安卓未验证）。
- 本机工具链：Rust 1.96 + rust-std-aarch64-linux-android 已安装（rustc 当前损坏，`apt reinstall rust` 可修）；Zig 未安装。

## 决策

1. **核心 = libghostty-vt**（Zig / C API，安卓官方支持）。xterm.js 挂起为未来分支（AI 面板 / WebView 场景），当前不考虑。
2. **终态架构 = Kotlin 壳 + Rust 底层 + libghostty-vt 核心**：界面壳（窗口 / 输入法 / 权限 / 生命周期 / 设置页）用 Kotlin（沿用 ADR-0001 的 XML/经典 View 决策）；壳内**渲染器、会话层、事件流终态全部 Rust**；核心经 C ABI 接入。
3. **渲染器**：Ghostty 官方未提供安卓渲染器 → 渲染层必须自研，但"从零写"不是唯一路径——**借代码起步**（expo-libghostty 安卓渲染器 / Termux terminal-view 绘制代码 / Ghostling 参考）。正式渲染语言与画法（wgpu vs Skia）留待验证切片后决策。
4. **会话层**：阶段一保留 Termux Java 会话层（PTY/进程/环境/生命周期，已跑通、零风险）；终态搬 Rust（借 portable-pty / alacritty tty 零件拼装，非从零写）；搬移时机在验证切片与 Rust 渲染/事件流跑通之后。
5. **验证切片（工单 08）先行**：先以**零渲染**（网格文本 dump）验证 libghostty-vt + Termux 环境 + 字节管道真机可行，再定正式渲染方案。
6. **B（Kotlin 壳 + C/C++ 底层）淘汰**：没有 Rust 的内存安全，也没有 Kotlin 的省事，两者皆非最优。

## 理由（带证据）

- libghostty-vt 是唯一有官方安卓支持的现代可嵌入核心；Rust 候选（alacritty_terminal / termwiz / rio-vt）均无安卓验证证据（可在切片阶段用 `aarch64-linux-android` 试编转正，若需）。
- 渲染层必须存在但不必从零：有 MIT 现成安卓渲染器（expo-libghostty）与官方参考（Ghostling）；Termux terminal-view 的十年画法代码可借。
- 会话层阶段一不动：成熟度最高、用户不可见；AI 事件流不产生于 PTY 层（来自核心解析 / shell 集成），语言对 AI 目标无影响。终态搬 Rust 的动机是**并发安全与语言统一**（多 Agent 并行时编译期防数据竞争、壳内单一 Kotlin ↔ Rust 边界、去掉 Java 第三语言与第三套 JNI），不是性能/内存。
- 终态 Rust 的地基价值：多会话 + 多 Agent 并行时无 GC 抖动、内存紧凑、并发安全；长期维护单一原生语言更省心。

## 冲突标注（与既有 ADR）

- **ADR-0001（部分重开）**：其"TerminalView 继续直接嵌入布局（现状）"的技术前提被本 ADR 推翻——终态换 libghostty-vt。但 ADR-0001 的核心决策（v1 用 XML/经典 View、不引入 Compose）**仍成立**，因为 Kotlin/XML 壳不变；冲突仅限"渲染核心不动"一点。
- **ADR-0002（扩展，非推翻）**："明确不做现代渲染核心"仍适用于 v1 交付范围（v1 不交付 libghostty-vt）；但工单 04 新增约束：UI 只对着 CoreAdapter 缝写，保证后续换核不动 UI。

## 被否选项

- 完整 libghostty（渲染/App 层无安卓运行时）
- alacritty_terminal / termwiz / rio-vt 作为核心（安卓无验证；留档备选，切片时可试编）
- Rio 全包（核心 + PTY 一体，等于换核心；安卓未官宣）
- xterm.js（WebView 路线，推翻原生渲染架构；挂起为未来分支）
- Warp（AGPL、桌面平台、无嵌入库；仅观察名单）
- B（Kotlin 壳 + C/C++ 底层）
- 会话层立即搬 Rust（风险最高的层不应在架构未验证时重写）

## 重开条件

- 工单 08 证明 libghostty-vt 在真机 + Termux 环境下不可行 → 回到核心候选（alacritty_terminal / rio-vt / xterm.js）重新评估。
- 渲染器决策（wgpu vs Skia）在切片结论后单独定案。
- 会话层终态 Rust 的搬移时机由后续工单定案。

## 参考

- ghostty-org/ghostty PR #10925（lib-vt Android 支持，2026-06）
- expo-libghostty（MIT，安卓渲染器参考）：https://github.com/arcboxlabs/expo-libghostty
- Ghostling（官方参考）：https://github.com/ghostty-org/ghostling
- rio-vt / librio 公告（2026-07）：https://rioterm.com/pl/blog/2026/07/27/rio-vt-and-librio
- Shellow（Rust 内核移动终端 + Codex 客户端，V2EX 2026-07）
- portable-pty / alacritty_terminal::tty（会话层零件）
