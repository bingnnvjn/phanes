# ADR-0004: 渲染器定案 = Rust + wgpu（安卓 Vulkan），以 Shellow 为骨架、按官方架构补齐

- 状态：已确认（2026-08-07，研究系列拍板）
- 日期：2026-08-07
- 范围：Fable 渲染器（画法层）方向定案；核心（libghostty-vt）与 Kotlin 壳不变；正式渲染器语言与画法决策（ADR-0003 遗留项）在此落定。

## 背景

调研事实（2026-08-07，一手来源核实，附源码级证据）：

- 工单 08 已验证：libghostty-vt 核心 + Termux 环境 + 字节管道真机可行（零渲染，文本 dump 验收通过）。渲染状态 C API 头文件已在 `spike-libghostty/lib/include/ghostty/vt/`（含两阶段更新、全局/行级脏标记、行/格迭代器、RowCells、配色板、光标样式）。
- 官方渲染器 2026-05 大重构后为"一个通用渲染器 + Metal/OpenGL 抽象层"（`src/renderer/generic.zig` 143KB）：渲染线程模型（mailbox + demand lock + 120FPS/cursor 定时器）、多通道 shader（bg/text/image/fullscreen）、实例化一次 draw call、字形双图集（灰度+彩色）+ bin-packing 增量同步、sprite face 自绘符号、字体 collection + 逐字回退 + Nerd Font 约束。官方无安卓渲染器。
- Shellow（ZingLix/Shellow，Apache-2.0，Google Play beta）：与 Fable 终态架构同构（Kotlin/Compose 壳 + Rust 核心 + libghostty-vt + wgpu）。源码精读结论（`crates/shellow-core/src/renderer.rs` 4091 行 + `ghostty_adapter.rs` 1310 行）：
  - 已实现约 60% 官方核心技术：字形图集（RGBA 单图集）、单 pipeline + 一次 draw call（36B 顶点，mode 区分文字/矩形）、rustybuzz 整形 + fontdue 光栅、脏行跟踪、光标/选择/搜索 overlay、viewport 滚动缓冲、安卓 SurfaceView/ANativeWindow + wgpu Vulkan 实机验证。
  - 40% 为简化/占位：正文走逐格 `grid_ref().graphemes()`（非官方 RowIterator/CellIterator）；图集 revision 变化整张重传（非增量）；无彩色 emoji 通道；procedural 符号是噪点占位（非 sprite face）；单 shader 单通道（非多 pass）；无颜色空间管理；无独立渲染线程（Kotlin 内容签名触发同步渲染）。
  - 补充参照：betamax-core（joshka，MIT/Apache-2.0）示范了 RenderState RowIterator/CellIterator + cosmic-text 软件光栅的正确用法；libghostty-rs（uzaaft，MIT/Apache-2.0）提供安全 Rust API（Terminal/RenderState/迭代器，pin ghostty ab0b9da9，需 Zig 0.16）。
- 工具链现状：rustc 损坏（重装修复，版本 1.96 满足 libghostty-rs MSRV 1.90）；Zig 0.16.0 在 Termux 源可用；cargo-ndk 需 `cargo install`；Gradle 7.2 + AGP 4.2.2 旧但已验证可用（升级列为 backlog 工单 11，稳定后再做）。

## 决策

1. **渲染器 = Rust + wgpu**，安卓走 Vulkan（`wgpu::Backends::VULKAN`，与 Shellow 一致）；不写裸 Vulkan，不用 Kotlin Canvas/Skia 作为终态。
2. **以 Shellow 为骨架**（Apache-2.0，可借代码），**按官方架构补齐 40% 缺口**：正文用 RenderState 行/格迭代器、灰度+彩色双图集 + 增量上传、sprite face 自绘符号、脏行增量顶点更新、多通道按需升级。
3. **六模块架构**（借谁的/改什么/补什么见工单 09/10 与调研 HTML）：
   - 快照层（核心 → 渲染器数据）：借 Shellow 快照结构；正文改走 RenderState 迭代器
   - 字形管线：借 Shellow（字体路径/rustybuzz/fontdue）；补双图集 + 增量 + sprite face
   - 顶点构建：借 Shellow SurfaceFrameBuilder；改脏行增量重建
   - GPU 层：借 Shellow wgpu 整段（SurfaceView/ANativeWindow + 单 draw call + WGSL）
   - 渲染驱动 + Kotlin 壳：借 Shellow 的"内容签名触发渲染"模式（先不做独立渲染线程）
   - 构建管线：先用 expo 预编译库（b0947378，工单 08 已验证）；自建核心后置
4. **验证路径分两步**：
   - 工单 09（渲染切片一）：快照层 + 软件光栅（借 betamax 思路），纯 Termux 内跑通"核心 → 渲染状态 → 像素帧（PNG）"，验证数据正确性。
   - 工单 10（渲染切片二）：wgpu + SurfaceView + Vulkan 接入 fable-app（借 Shellow 整段），真机出画面。
5. **渲染驱动先简后繁**：采用 Shellow 的"Kotlin 检测内容签名变化 → 同步渲染"；官方"独立渲染线程 + demand lock"作为多会话并行稳定后的优化项，不预支复杂度。
6. **核心版本策略**：继续使用已验证的 expo 预编译（ghostty b0947378）；切换到更新的 ghostty commit（自建，Zig 0.16）作为独立工单后置，重走工单 08 的 TLS/对齐验证。

## 理由（带证据）

- GPU 渲染必选（海量字形 CPU 画不动）；Vulkan 是安卓现代选择；wgpu 提供安全、跨平台抽象，Shellow 已证明 wgpu+Vulkan 在安卓实机可用，性能接近裸 Vulkan 且工作量大幅降低。
- Shellow 与 Fable 架构同构（Kotlin 壳 + Rust 渲染 + ghostty 核心），源码级验证过"官方三件套（图集/一次 draw call/脏行）"的安卓落地路径；其缺口恰好都有官方答案可补。
- 正文数据通道选官方迭代器而非 Shellow 逐格读取：O(列×行) 次调用在"多会话 + 大输出"场景是隐患，官方/betamax 均示范了批量迭代；代价是快照层稍复杂，值得。
- 软件光栅先行的理由：Termux 命令行无窗口，Vulkan 无法直出；软件光栅零 GPU 依赖、可立刻验证数据层正确；快照层与 GPU 版完全共用，后续只换"画"那一半。
- 工具链按需升级：修 rustc、装 Zig 0.16（与 libghostty-rs 精确匹配）、cargo-ndk（GPU 切片交叉编译）；Gradle/AGP/JDK 不动（构建已验证可用，升级是独立工单）。

## 冲突标注（与既有 ADR）

- **ADR-0003（收口）**："渲染器决策（wgpu vs Skia）留待切片后定案"由本 ADR 定案为 **wgpu**；"渲染器终态 Rust"、"核心 = libghostty-vt"不变。
- **ADR-0001（不冲突）**：Kotlin/XML 壳不变；本 ADR 只作用于壳内渲染器。

## 被否选项

- 裸 Vulkan（安全/工作量/跨平台权衡不划算；wgpu 抽象开销对终端负载可忽略）
- Kotlin Canvas/Skia 渲染器作为终态（expo-libghostty 路线；不符合"渲染器终态 Rust"，仅作参考与短期验证）
- macroquad（ghostling_rs）/ raylib（ghostling）路线（演示级，逐格绘制调用多，性能天花板低）
- Flutter 路线（ghostty_vte_flutter；等于换壳，推翻 Kotlin 壳决策）
- 软件渲染作为终态（betamax 路线；只用于验证切片，实时性能不足）
- 独立渲染线程 + demand lock 现在就做（复杂度预支；先 Shellow 式驱动，稳定后评估）
- 现在就自建 ghostty / 升级核心版本（当前预编译稳定且已验证；更新作为独立工单）

## 重开条件

- 工单 10 证明 wgpu+Vulkan 在目标真机不可行 → 回退评估 Skia（skia-safe）或裸 Vulkan。
- 核心更换（libghostty-vt → 其他）→ 渲染状态接口变化，本 ADR 需重新评估。
- 多会话并行实测暴露渲染驱动瓶颈 → 重开"独立渲染线程 + demand lock"议题。

## 参考

- Ghostty 官方渲染器：github.com/ghostty-org/ghostty `src/renderer/`（MIT，2026-05 重构）
- Shellow：github.com/ZingLix/Shellow（Apache-2.0；renderer.rs / ghostty_adapter.rs / libghostty-vt-sys）
- betamax-core：github.com/joshka/betamax（MIT/Apache-2.0；RowIterator 正确用法 + cosmic-text 软件光栅）
- libghostty-rs：github.com/uzaaft/libghostty-rs（MIT/Apache-2.0；安全 Rust API + ghostling_rs 示例）
- expo-libghostty：github.com/arcboxlabs/expo-libghostty（MIT；Kotlin Canvas 渲染器 + 预编译库来源）
- 调研报告：`shellow vs ghostty.html`（2026-08-07，本仓库根目录 + 内部存储 Download）
