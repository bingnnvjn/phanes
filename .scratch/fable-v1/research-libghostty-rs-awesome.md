# 调研：`libghostty-rs` 与 `awesome-libghostty` 对 Fable 的价值

- 调研日期：2026-08-17
- 范围：只评估用途、成熟度、许可证、API/集成方式、对 Fable 当前 Rust 会话层/Kotlin 壳/渲染架构的帮助与风险。
- 方法：一级来源（GitHub 仓库源码、README、Cargo manifest、GitHub issue/API、官方 Ghostty/libghostty 文档）+ Fable 本地架构文档。未修改代码或工单。

## 结论先行

### `Uzaaft/libghostty-rs`

**有用，但现在不适合直接替换 Fable 已验收的生产 C FFI。**

它最适合做一个被 Fable Rust 层隐藏的候选适配层：减少手写 `ghostty/vt.h` FFI，提供 `Terminal`、`RenderState`、脏行/行格迭代器、输入编码、选择和 Kitty Graphics 等安全 Rust API。它不提供 PTY、进程生命周期、环境组装或 GPU/wgpu 渲染器，因此不能替代 Fable 的 `portable-pty` 会话层，也不能替代现有 `spike-render` 画法层。

阻塞生产引入的风险有三类：

1. 上游明确声明 API 仍在开发、不稳定、可能发生破坏性变化；所有核心类型均为 `!Send + !Sync`，不能直接套入 Fable 当前跨线程持有 Ghostty 指针的模型。
2. Android 只有构建脚本中的 `aarch64-linux-android`/`x86_64-linux-android` target 映射；仓库 CI 没有 Android job，不能把“能映射 target”当作 Fable 真机可用证据。
3. 当前有公开且未关闭的 soundness 报告：`graphemes_buf` 安全函数向 C 侧不传容量，可能越界写（#70）；剪贴板回调对 `(null, 0)` 和任意二进制数据存在 UB 风险（#75）。在上游修复或 Fable 明确避开相关 API 前，不应把它作为无条件安全底座。

**建议：**只开独立 spike，固定 Git SHA，在单一 owner thread 内拥有 `Terminal`/`RenderState`，向 Fable mailbox 输出自有 owned snapshot；通过 Android 静态构建、TLS/16KB page、行为、性能和内存回归后，再决定是否替换手写 FFI。不要在 spike 中改 Kotlin API 或 `portable-pty`。

### `Uzaaft/awesome-libghostty`

**有用，但只是生态雷达/案例索引，不是依赖，也不是质量背书。**

它收集 Core/Libraries、终端应用、Web/Embedded、AI/Agent、系统工具和资源等项目。清单仓库自身是 MIT，但其贡献规则只要求与 libghostty 相关、分类/字母序和 CI 通过，没有成熟度、安全性或许可证门槛；每个被列项目必须单独审核。

对 Fable 最有价值的是：

- `expo-libghostty`：Android JNI、脏行快照、Ghostty 输入编码、IME/选择/宽字符/emoji 的近邻参考；Fable 现有 `spike-libghostty`/`spike-render` 已使用其固定的 `libghostty-vt` Android 资产。
- `Chuchu`：真实 Android SSH 客户端，可参考移动端 IME、mouse/focus、Kitty image、选择和批量 `ByteBuffer` 快照。
- `elias8/libghostty`：Flutter/Dart 侧的 API 与测试覆盖，可用来核对 Fable `CoreAdapter` 能力清单。
- `Restty`：WASM + WebGPU 的渲染分层、字形/整形和 benchmark 思路；只做概念参考。
- `Godotty`：真实 Rust wrapper 消费者，可用来交叉检查生命周期和渲染状态使用方式。

这些项目的渲染框架、壳层或平台大多与 Fable 不同，不能直接搬入依赖树。`flutter_ghostty`（README 仅支持 macOS、无 LICENSE）、`Umbra`（Android JNI 仍为 TODO/dummy stub）和失效的 `sshotty-term` 条目不应作为 Fable 实现依据。

## 1. `libghostty-rs` 事实

### 1.1 定位与 API

仓库是一个 Rust workspace：

- `libghostty-vt-sys`：从 Ghostty `ghostty/vt.h` 生成的裸 FFI。
- `libghostty-vt`：安全 Rust 包装，入口包括 `Terminal`、`RenderState`、`RowIterator`、`CellIterator`、Key/Mouse encoder、Selection、OSC、Paste、Kitty Graphics、Snapshot 等。
- `example/ghostling_rs`：使用该 wrapper 的最小终端示例；它仍自行负责 PTY、窗口和绘制。

README 的示例链路是：创建 `Terminal` → `vt_write` 喂 VT 字节 → `RenderState::update` → 行/格迭代器读取快照。官方 libghostty 文档也把 Render State 定义为“供自定义 renderer 使用的增量状态”，并列出 terminal、formatter、snapshot、key/mouse 等 API。

来源：

- [仓库 README](https://github.com/Uzaaft/libghostty-rs/blob/master/README.md)
- [`libghostty-vt` crate 文档/线程模型](https://github.com/Uzaaft/libghostty-rs/blob/master/crates/libghostty-vt/src/lib.rs)
- [`Terminal` API](https://github.com/Uzaaft/libghostty-rs/blob/master/crates/libghostty-vt/src/terminal.rs)
- [`RenderState`、dirty row、两阶段 update](https://github.com/Uzaaft/libghostty-rs/blob/master/crates/libghostty-vt/src/render.rs)
- [官方 libghostty-vt API 首页](https://libghostty.tip.ghostty.org/)
- [官方 `vt.h`](https://github.com/ghostty-org/ghostty/blob/main/include/ghostty/vt.h)

### 1.2 线程与生命周期

上游 wrapper 明确说明：所有 `libghostty-vt` 类型默认 `!Send`、`!Sync`，因为底层 C API 可能使用线程局部状态且没有足够的线程安全保证；建议把终端模拟放在专用 OS 线程/任务中，通过 channel 与业务/渲染线程通信。`RenderState` 的文档说明更新阶段只需短暂访问 Terminal，结束后可在不持有 Terminal 锁的情况下读取快照。

这与 Fable 的正确结合方式是：

```text
PTY reader / session owner thread
        │  vt_write + effects + RenderState begin/end
        ▼
Fable owned snapshot / mailbox（只传值，不传 Ghostty 指针）
        ▼
Rust wgpu renderer → Kotlin Surface 壳
```

不能把 `Terminal` 或 `RenderState` 直接塞进当前跨线程 renderer registry，也不能把 wrapper 类型暴露给 Kotlin/JNI。

来源：

- [`lib.rs` 的 Thread safety / channel 建议](https://github.com/Uzaaft/libghostty-rs/blob/master/crates/libghostty-vt/src/lib.rs)
- [`render.rs` 的 begin/end update 说明](https://github.com/Uzaaft/libghostty-rs/blob/master/crates/libghostty-vt/src/render.rs)

### 1.3 构建、版本与成熟度

- 仓库创建于 2026-03-21；当前 master 在 2026-08-14 的最新提交为 `a28e4ad00d6ba79b98b8cac651ed8976d8500903`。
- `Cargo.toml` 当前 workspace 版本为 `0.2.1`，edition 2024，MSRV Rust 1.90，默认 vendored。
- GitHub 目前可见正式 release 为 `v0.2.0`（2026-06-16）；master 的工作树版本已高于该 release，不能把 `0.2.1` 当作经过同等发布验证的稳定版。
- vendored 构建要求 Zig 0.16.x，默认在 build time 拉取固定 Ghostty commit `22d13172cde98a0a4dda05d3d6a3fcb0dd8ed018`；可以用 `GHOSTTY_SOURCE_DIR` 指向本地 Ghostty，也可以用 `pkg-config`/`link-dynamic` 改变链接方式。
- build script 明确映射 `aarch64-linux-android` 与 `x86_64-linux-android`，但 CI 只覆盖 Linux x86_64/aarch64、macOS aarch64、Windows 以及 iOS 检查，没有 Android job。

来源：

- [最新 master commit](https://github.com/Uzaaft/libghostty-rs/commit/a28e4ad00d6ba79b98b8cac651ed8976d8500903)
- [workspace manifest](https://github.com/Uzaaft/libghostty-rs/blob/master/Cargo.toml)
- [v0.2.0 release](https://github.com/Uzaaft/libghostty-rs/releases/tag/v0.2.0)
- [vendored build script、Ghostty pin、Android target 映射](https://github.com/Uzaaft/libghostty-rs/blob/master/crates/libghostty-vt-sys/build.rs)
- [CI matrix](https://github.com/Uzaaft/libghostty-rs/blob/master/.github/workflows/ci.yml)
- [官方 Android/lib-vt 支持 PR #10925](https://github.com/ghostty-org/ghostty/pull/10925)

### 1.4 许可证与安全

`Cargo.toml` 声明 `MIT OR Apache-2.0`，但仓库当前可见的 `LICENSE` 文件是 MIT 文本，没有单独的 Apache-2.0 LICENSE/NOTICE 文件。对 Fable 采用代码/依赖的 MIT 路径没有明显障碍；若要按 Apache-2.0 归档，应先向上游确认许可证声明与文件的一致性。

截至调研日有两个公开未关闭的 soundness 报告：

- [Issue #70](https://github.com/Uzaaft/libghostty-rs/issues/70)：安全的 `RenderStateRowCells::graphemes_buf(&mut [char])` 向 C 侧只传指针、不传容量，过小 slice 可能导致越界写；issue 还指出 `graphemes()` 的长度读取/填充存在 TOCTOU 风险。
- [Issue #75](https://github.com/Uzaaft/libghostty-rs/issues/75)：剪贴板写回对空 OSC 52 数据 `(null, 0)` 构造 slice，以及把任意二进制当作 unchecked UTF-8，存在 UB 风险。

因此在 Fable spike 中应避开这两条 API，并对所有 `unsafe`/回调边界做本地审查；不能仅因为 crate 名称含有 “safe API” 就跳过 FFI 安全闸门。

来源：

- [Cargo license metadata](https://github.com/Uzaaft/libghostty-rs/blob/master/Cargo.toml)
- [仓库 LICENSE](https://github.com/Uzaaft/libghostty-rs/blob/master/LICENSE)
- [Issue #70](https://github.com/Uzaaft/libghostty-rs/issues/70)
- [Issue #75](https://github.com/Uzaaft/libghostty-rs/issues/75)

## 2. 对 Fable 各层的适配判断

| Fable 层 | 价值 | 当前判定 | 原因 |
| --- | --- | --- | --- |
| Rust 会话层（`portable-pty`） | 低/间接 | 不替换 | `libghostty-rs` 不负责 PTY、spawn、读写、resize、进程回收、环境快照或六事件流；继续使用现有 `spike-session`/生产 `libfable-session`。 |
| libghostty 核心接线 | 中/高 | 先 spike | 可以把手写 C ABI 声明换成生成 FFI + Rust wrapper，但会同时切换 Ghostty pin、Zig 构建和 ABI，不能视作纯 API 重构。 |
| 快照层/dirty rows | 高 | 最值得验证 | `RenderState`、两阶段 update、RowIterator/CellIterator 与 Fable 工单 45 的 dirty mailbox/按需解码方向直接相合。 |
| Rust wgpu 画法层 | 中/间接 | 只借数据契约 | wrapper 提供结构化终端状态，不提供字形图集、FreeType/COLRv1/sbix、shader、wgpu Surface 或 SurfaceView。 |
| Kotlin 壳/CoreAdapter | 间接 | 不暴露上游类型 | Kotlin 只看 Fable 自有 handle/DTO；上游 0.x API 变化不能穿过 JNI 缝。 |
| 多线程/多会话 | 有条件 | 固定 owner thread | 上游类型 `!Send/!Sync` 强制把核心对象固定在线程，快照值跨 mailbox；这能强化安全边界，但需要改变当前指针/线程假设。 |

Fable 当前架构依据：

- [CONTEXT.md](../../CONTEXT.md)
- [ADR-0003：libghostty-vt + Kotlin 壳 + Rust 底层](../../docs/adr/0003-libghostty-vt-core-and-rust-endstate.md)
- [ADR-0004：Rust + wgpu 渲染器](../../docs/adr/0004-renderer-wgpu-shellow-skeleton.md)
- [ADR-0008：Rust 会话层/portable-pty](../../docs/adr/0008-session-layer-rust.md)
- [工单 08：当前 C API Android 真机切片已通过](issues/08-libghostty-vt验证切片.md)
- [工单 25：生产会话层与 JNI 事件流](issues/25-fable-session生产实现-事件流-jni.md)
- [工单 45：渲染 mailbox/快照增量化 backlog](issues/45-渲染mailbox与快照增量化.md)

## 3. `awesome-libghostty` 事实与参考价值

### 3.1 清单本身

README 分为 Core & Libraries、Terminal Apps & Clients、Web & Embedded Terminals、AI Tools & Agent Orchestration、System Integrations & Utilities、Resources 六类；当前约 126 个条目。仓库创建于 2026-02-07，最近一次 push 为 2026-08-12；截至调研时 GitHub API 显示 776 stars、51 forks，未归档。

清单自身是 MIT。贡献规则要求条目与 libghostty 相关、放入合适分类、按字母序、CI 通过；没有活跃度、许可证、安全性、真机可运行性门槛。因此“在清单中”只能说明被收录，不能说明项目成熟或适合复制。

来源：

- [README / 完整清单](https://github.com/Uzaaft/awesome-libghostty/blob/master/README.md)
- [仓库元数据](https://github.com/Uzaaft/awesome-libghostty)
- [CONTRIBUTING.md](https://github.com/Uzaaft/awesome-libghostty/blob/master/CONTRIBUTING.md)
- [清单仓库 LICENSE](https://github.com/Uzaaft/awesome-libghostty/blob/master/LICENSE)

### 3.2 直接贴近 Fable 的条目

#### `expo-libghostty`：Android 行为基线（最高优先级）

这是 Fable 现有静态 `libghostty-vt` Android 资产的来源/近邻参考。其 Android 路线是 pinned libghostty-vt + JNI + Kotlin Canvas/Skia，使用扁平/批量快照而非逐 cell JNI，并覆盖 Ghostty key encoder、IME、滚动、选择、安全粘贴、宽字符/emoji、主题、bell/title/pwd 等。

可借鉴的是 Android C/JNI 接线、快照 DTO、输入/选择测试点和固定资产 manifest；不可直接借的是 Expo/React Native 壳、Canvas/Skia 画法和其单 handle/无锁线程模型。Fable 终态仍是 XML/Kotlin 壳 + Rust/wgpu。

来源：

- [expo-libghostty README](https://github.com/arcboxlabs/expo-libghostty/blob/master/README.md)
- [Android JNI](https://github.com/arcboxlabs/expo-libghostty/blob/master/android/src/main/cpp/ghostty_jni.cpp)
- [Android TerminalView](https://github.com/arcboxlabs/expo-libghostty/blob/master/android/src/main/java/expo/modules/libghostty/GhosttyTerminalView.kt)
- [固定资产 manifest](https://github.com/arcboxlabs/expo-libghostty/blob/master/vendor-manifest.json)
- Fable 本地接线：[spike-render/build.rs](../../spike-render/build.rs)、[工单 08](issues/08-libghostty-vt验证切片.md)

#### `Chuchu`：移动端交互与批量桥（高优先级）

Chuchu 是实际使用 libghostty 的 Android SSH 客户端。应重点看其 IME mirror/suppression、key/mouse/focus 编码、Kitty image snapshot、grapheme extras、选择跟随 viewport、批量 `ByteBuffer` 快照和测试边界。它的 Compose + Canvas + SSH/Mosh 产品形态与 Fable 的 XML + 本地 Rust PTY + wgpu 不同，只提取协议/交互和测试点。

来源：

- [Chuchu README/元数据](https://github.com/jossephus/chuchu)
- [Ghostty bridge](https://github.com/jossephus/chuchu/blob/main/android/app/src/main/java/com/jossephus/chuchu/service/terminal/GhosttyBridge.kt)
- [TerminalSnapshot](https://github.com/jossephus/chuchu/blob/main/android/app/src/main/java/com/jossephus/chuchu/service/terminal/TerminalSnapshot.kt)
- [TerminalInputView](https://github.com/jossephus/chuchu/blob/main/android/app/src/main/java/com/jossephus/chuchu/ui/terminal/TerminalInputView.kt)

#### `elias8/libghostty`、`Restty`、`Godotty`：能力/渲染交叉检查

- [elias8/libghostty](https://github.com/elias8/libghostty)：Flutter/Dart wrapper，可核对 Terminal、RenderState、tracked grid refs、selection gesture、Kitty graphics 的 API 能力和测试组织；不能把 Dart/Flutter renderer 搬进 Fable。
- [Restty](https://github.com/wiedymi/restty)：MIT，libghostty-vt WASM + WebGPU，适合观察 GPU glyph pipeline、text shaping、fallback、headless/surface 分层和 benchmark；浏览器 WASM/TypeScript 不是 Android native wgpu 依赖。
- [Godotty](https://github.com/ingur/godotty)：MIT，真实 Rust wrapper 消费者，适合检查 `libghostty-rs` 生命周期、RenderState 和选择整合；Godot 画法与 Fable wgpu 无关。

### 3.3 明确不作为 Fable 依据的条目

- [flutter_ghostty](https://github.com/jiahaog/flutter_ghostty)：README 明示只支持 macOS，仓库无 LICENSE；不能用于 Fable Android，也不应复制代码。
- [Umbra](https://github.com/charliesbot/umbra)：仓库描述虽称 GPU Android terminal，但其 JNI/架构源码仍是 TODO/dummy/hello-world stub，不能当成熟实现。
- [sshotty-term](https://github.com/sshotty/sshotty-term)：清单链接当前失效（404），提醒我们必须逐项核验，不可盲信索引。

## 4. 对 Fable 的行动建议（不改变现有生产路径）

### P0：当前不切换

- 不把 `libghostty-rs` 直接加入生产 `spike-render` 或会话 crate。
- 不替换已经在 Android 真机通过的 C ABI/预编译核心，不改变 `portable-pty` 和 Kotlin `SessionHandle/CoreAdapter` API。
- 不把 `awesome-libghostty` 当依赖、供应链来源或成熟度认证。

### P1：单独做 `libghostty-rs` spike

固定精确 Git SHA（至少固定到调研时的 `a28e4ad00d6ba79b98b8cac651ed8976d8500903`，不要浮动 master/0.x），只验证：

1. `aarch64-linux-android` 静态构建、Gradle/JNI 打包、Android loader；
2. TLS 对齐、Android 15+ 16KB page、NDK/Termux 两条构建线；
3. `Terminal::vt_write`、effects/write-back、resize/scrollback；
4. `RenderState` begin/end、global/row dirty、RowIterator/CellIterator；
5. Fable owned snapshot 与现有手写 C FFI 的行为、分配、JNI 次数和帧构建耗时对比；
6. Issue #70/#75 相关 API 的禁用/本地修复策略，以及 Miri/ASan/真机回归。

spike 的输出应只进入 Fable 自有 DTO/mailbox；通过所有门槛后，才讨论是否替换 `spike-render/src/ffi.rs` 中的手写声明。

### P1：持续扫 `awesome-libghostty`

按月重新检查仓库和重点条目的 push、release、license、Android 真机证据；优先跟踪 `expo-libghostty`、`Chuchu`、`elias8/libghostty`、`Restty`、`Godotty`。任何代码借用前单独读目标仓库 LICENSE、NOTICE、依赖清单和平台构建脚本。

### 结论等级

| 项目 | 结论 |
| --- | --- |
| `libghostty-rs` | **值得做隔离 spike；暂不生产依赖** |
| `awesome-libghostty` | **值得保留并定期扫描；永不作为依赖/质量背书** |
| Fable 当前生产路线 | **继续 C `libghostty-vt` + Rust `portable-pty`/wgpu + Kotlin 壳** |
