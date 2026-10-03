# 29 — CoreAdapter 补 UI 状态 API

**What to build:** 在 fable-render（spike-render）与 CoreAdapter 缝上补齐 UI 所需状态能力：标题读取与变更通知（title_changed）、bell 通知、模式查询（alternate screen / mouse tracking / cursor visible / blink）；已有 cursor/selection/scroll 能力保持不动。契约测试覆盖新能力。

**Blocked by:** fable-v1/28
Status: 已完成

## 验收清单

- [ ] Rust 侧新增 libghostty-vt 绑定与 JNI 导出（title / bell / mode），`cargo test` 全绿
- [ ] 离屏自检（软件光栅 / api_offscreen_check 等）PASS；`rendererInfo` 能报告 title/mode 状态
- [ ] CoreAdapter 新增能力走默认方法，既有实现（若仍在）不破坏编译
- [ ] JVM 缝契约测试：fake 核心实现覆盖 title / bell / mode / selection / scroll
- [ ] APK 构建 + 校验通过（本单不改 UI 行为）

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 4；事实：libghostty-vt 原生 API 已具备，见 terminal.h；JNI 现状见 RenderCore.java / jni.rs）。

2026-08-12 实施完成（spike-render `01418fe`、fable-app `7ec4a52`，两仓库已提交；libfable-render.so 已重建进 jniLibs，.so 不入 git）。

**1. 验证数据/命令**

- Rust：`cd spike-render && cargo test --release` → **20/20 PASS**（新增 `ui_state_title_bell_modes`：真实核心 effect 回调 + 查询端到端）。
- 离屏自检：`cargo run --release --bin spike-render` → **结果: ALL PASS**（新增 18 项 UI 状态断言：OSC 0/2 标题、bell、1049/1000/25/12 模式置位与清除）；`cargo run --release --example api_offscreen_check` → **结果: ALL PASS**（新增 16 项 mailbox 断言 + `info: rendererInfo 报告 title/mode 状态`）。
- JVM 缝契约：`:terminal-emulator:testDebugUnitTest --rerun-tasks` → **155/155 PASS**（新增 3 项：`newCapabilitiesDefaultToSafeValues`、`fakeCoreTitleBellModes`、`fakeCoreSelectionAndScroll`；fake 核心覆盖 title/bell/mode/selection/scroll）。
- `:app:testDebugUnitTest --rerun-tasks` → 34 测 33 绿 1 败 = 既有基线 `FileReceiverActivityTest.testIsSharedTextAnUrl`（Robolectric + JDK 25 NoClassDefFoundError，工单 15/28 已记录，非本次引入）。
- `:app:assembleDebug` → **BUILD SUCCESSFUL**（JDK 25 / Gradle 9.7.0 / AGP 9.3.0）；APK `fable-app_apt-android-7-debug_arm64-v8a.apk` 校验：包名 `com.gph.fable`、versionCode 1022、5 个 arm64 .so（libfable-render.so 8,116,616B）、apksigner Fable 证书 SHA-256 `<证书指纹>`。
- .so JNI 导出 33 个（新增 7 个：`rendererGetTitle` / `rendererConsumeTitleChanged` / `rendererConsumeBell` / `rendererGetModeAltScreen` / `rendererGetModeMouseTracking` / `rendererGetModeCursorVisible` / `rendererGetModeCursorBlink`）。
- code-review 双轴：Standards 无硬违规（4 项判断类 smells，已采纳删未用 ffi 常量/类型别名）；Spec 3 项全部修复（见坑 3/4/5）。

**2. 踩过的坑与解法**

- **title_changed 回调内读标题无效**：terminal.h 约定新标题在回调返回后才可读 → 回调只置 `title_changed` 标记，`vt_write` 返回后 `sync_ui_events()` 再 `ghostty_terminal_get(DATA_TITLE)`。
- **effect 回调 userdata 地址稳定**：userdata 指向 `Box<TerminalEvents>` 堆地址，Box 随结构体移动但堆分配地址不变；Drop 顺序 = 先 `ghostty_terminal_free` 再落 Box，无悬垂。
- **fake 核心行坐标模型**：外部行 0 = 屏幕顶行、transcript 为负（与 TerminalBuffer 一致）；初版用 0 基列表直索引 + `Math.max(-(lines-rows),0)` 钳制写反 → 改为 `lineIndex = externalRow + (lines-rows)`、`minTop = Math.min(0, -(lines-rows))`。
- **X10 鼠标追踪缺口**（Spec 评审）：fake 只认 1000/1002/1003，漏 `?9`；已改为四模式分存 + OR（与核心 `TERMINAL_DATA_MOUSE_TRACKING` any-mode 语义一致）。
- **越界改动回退**（Spec 评审）：给旧路径加 `getTitle` 覆写超出"默认方法扩展"已定决策 → 已移除，旧路径仅保证编译不破坏。
- `cargo run` 需 `--bin spike-render`（多 bin 未配 default-run）。

**3. 结论写回（对工单 30 的输入）**

- **API 形态**：CoreAdapter 新增 7 个默认方法 `getTitle` / `consumeTitleChanged` / `consumeBell` / `getModeAlternateScreen` / `getModeMouseTracking` / `getModeCursorVisible` / `getModeCursorBlink`；JNI/RenderCore 同名 `renderer*`；title/bell 用"消费标记"语义（查询即清除），UI 轮询不丢事件。
- **模式语义定案**：alt screen = mode_get(1047)‖mode_get(1049)；mouse tracking = `TERMINAL_DATA_MOUSE_TRACKING`（X10/1000/1002/1003 任一）；cursor visible = mode_get(25)；blink = mode_get(12)。
- **对工单 30**：UI 切缝（主终端状态只走 CoreAdapter）可直接消费上述方法实现标题栏 / bell 提示 / alt-screen 行为，无需再动 Rust/JNI；`rendererInfo` 已含 `title/title_changed/bell/mode_*` 字段供真机诊断。
- 本单未改 UI 行为；真机回归归工单 40。
