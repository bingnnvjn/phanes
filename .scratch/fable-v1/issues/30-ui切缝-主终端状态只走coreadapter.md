# 30 — UI 切缝（主终端状态只走 CoreAdapter）

**What to build:** 主终端 UI 的标题/铃声/模式/选择/滚动/光标全部改为从 CoreAdapter 读取与触发，不再读取旧模拟器状态；行为保持等价；旧模拟器代码保留但 UI 不再引用其状态 API。

**Blocked by:** fable-v1/29
Status: 已完成

## 验收清单

- [ ] 会话标题显示/通知与旧路径一致（OSC 0/2 生效）
- [ ] bell 触发既有提示行为（按现状实现）
- [ ] alternate screen（vim 等）、mouse tracking（htop/tmux 等）、光标可见/闪烁行为与旧路径一致（JVM 契约测试 + 构建）
- [ ] 长按选择/复制/粘贴/滚动走 CoreAdapter 且行为不回归（JVM 测试）
- [ ] 审计确认主终端 UI 源码不再引用旧模拟器状态 API（删除动作归工单 31）
- [ ] 单测全绿（既有基线规则）+ `:app:assembleDebug` 成功 + APK 校验通过

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 4：先扩缝后删；本单只切 UI，不动删除）。

2026-08-12 实施完成（spike-render `e861328`、fable-app `7d39934`，两仓库已提交；libfable-render.so 已重建进 jniLibs，.so 不入 git）。

**1. 验证数据/命令**

- Rust：`cd spike-render && cargo test --release` → **20/20 PASS**（`ui_state_title_bell_modes` 扩展：DECCKM/DECKPAM/2004 置位与清除、光标闪烁相位切换）。
- 离屏自检：`cargo run --release --bin spike-render` → **结果: ALL PASS**（UI 状态断言新增 DECCKM/DECKPAM/2004 各 6 项）；`cargo run --release --example api_offscreen_check` → **结果: ALL PASS**（mailbox 断言新增 9 项：三模式 on/off + `cursor_blink_phase` 经 rendererInfo 可查）。
- JVM 缝契约：`:terminal-emulator:testDebugUnitTest --rerun-tasks` → **162/162 PASS**（新增 8 项：`fakeCoreCursorKeysKeypadBracketedAndBlinkPhase`、`legacyAdapterReportsDecCkmDeckpamModes`、`TerminalSessionUiStateTest` 5 项——getTitle 委托、title/bell 轮询投递与旧回调门控、旧路径回调保留、paste 的 bracketed 模式来自缝、旧路径 paste 回退）。
- `:app:testDebugUnitTest --rerun-tasks` → 34 测 33 绿 1 败 = 既有基线 `FileReceiverActivityTest.testIsSharedTextAnUrl`（Robolectric 4.8.1 + JDK 25 NoClassDefFoundError，工单 15/28/29 已记录，非本次引入）。
- `:app:assembleDebug` → **BUILD SUCCESSFUL**（JDK 25 / Gradle 9.7.0 / AGP 9.3.0）；APK `fable-app_apt-android-7-debug_arm64-v8a.apk` 校验：包名 `com.gph.fable`、versionCode 1022、5 个 arm64 .so（libfable-render.so 8,121,992B）、apksigner Fable 证书 SHA-256 `<证书指纹>…`。
- .so JNI 导出 **37 个**（新增 4 个：`rendererGetModeCursorKeysApplication` / `rendererGetModeKeypadApplication` / `rendererGetModeBracketedPaste` / `rendererSetCursorBlinkState`，APK 内 .so 复验一致）。

**2. 踩过的坑与解法**

- **缝扩展（决策记录，需用户/后续决策窗口知悉）**：验收 5「主终端 UI 源码不再引用旧模拟器状态 API」用工单 29 的 API 无法完全满足——审计发现 Fn 键编码要读 DECCKM/DECKPAM（`isCursorKeysApplicationMode/isKeypadApplicationMode`）、粘贴要读 bracketed paste（2004）、光标闪烁相位无推送通道。本单按工单 29 的既有模式补齐 4 个默认方法（3 个 mode 查询 + `setCursorBlinkState` 相位 feed），属于完成验收所必需的缝扩展而非重开决策；已在 commit body 标注 `Refs fable-v1/30`。若后续决策窗口认为应改为单独工单，可回退这 4 个方法并把剩余读取记录为残项。
- **onColorsChanged 不加门控（评审回退）**：颜色不在本单能力清单；初版把新路径 OSC 10/11 回调门掉，评审指出是越界行为变化，已回退为既有行为（新路径下 `updateBackgroundColor` 走 `FableTerminalPalette`，本就不读旧模拟器状态）。
- **光标闪烁边角（评审修复）**：渲染层 `DATA_CURSOR_VISIBLE && cursor_blink_phase` 无条件 AND；若闪烁线程停止时相位恰为隐藏，光标会永久消失（旧路径 `shouldCursorBeVisible()` 是 `enabled && (blinkingEnabled ? phase : true)`）。修复：`stopTerminalCursorBlinker()` 停止即恢复可见相位。
- **api_offscreen_check 相位断言竞态**：`set_cursor_blink_state` 走 mailbox 异步，直接读 `renderer.info()` 可能读到旧 stats；加同步查询作 mailbox 屏障后再读。
- **paste 测试后端未初始化**：`TerminalSession.paste` 经 `mFableSession.write` 落地，测试要先 `updateSize` 建后端，否则字节被吞。
- **词边界/URL 取词/滚动钳制残项（工单 31 输入）**：长按词边界展开、URL 点击取词、fling/滚轴 transcript 行数仍读旧模拟器内容/几何（CoreAdapter 暂无逐格文本、word-at-location、scrollback 行数 API）。已在代码注释标注 `工单 31 残项`；滚动触发本身走 `adapter.scroll`（验收 4 的"滚动走 CoreAdapter"满足），钳制只影响视图本地 `mTopRow`（渲染器内部自行钳制）。词边界双模型是工单 22 Comments 已记录的遗留。

**3. 结论写回（对工单 31/后续的输入）**

- **切缝完成**：主终端 UI 的 title/bell/模式（alt/mouse/光标可见+闪烁/DECCKM/DECKPAM/2004）/选择/滚动/光标全部走 CoreAdapter；title/bell 用"UI 轮询消费标记"（200ms，`TerminalSession.pollUiEvents` + `TermuxTerminalSessionActivityClient` Handler），旧模拟器回调在缝激活时门控，不再双投递。
- **审计结论**：新路径已无旧模拟器状态 API 直读（模式/标题/铃声/光标），剩余直读均为内容/几何类（词边界、URL 取词、transcript 行数、accessibility getText），已在代码注释标记为工单 31 残项；旧路径（adapter 为 null）回退分支保留原行为。
- **对工单 31（删旧模拟器/旧渲染器）**：删除时需补 3 个缝能力——逐格/词边界文本查询（`TextSelectionCursorController.setInitialTextSelectionPosition` + `TermuxTerminalViewClient.onSingleTapUp` URL 取词）、scrollback 行数查询（`TerminalView` 滚动钳制/滚轴）、可考虑 bracketed paste 已在缝上（`getModeBracketedPaste` 已具备）；旧路径回退分支（`mCoreAdapter == null`）一并删除。
- **行为差异提示**：title/bell 提示经轮询投递，前台感知延迟 ≤200ms（与 toast/提示可感知性匹配）；光标闪烁停止时相位强制恢复可见；`TerminalSession` 新增 5 个模式查询方法（`isMouseTrackingActive` 等）作为 UI 统一入口，后续 Kotlin 迁移可直接复用。
- 真机回归归工单 40（本单验证 = JVM 契约测试 + 构建 + APK 校验，与工单 29 口径一致）。
