# 14 — 渲染器集成 API 补齐（选中文本 / 字号 / 配色板）

**What to build:** 在已验证的 fable-render（工单 08–12 产物：libghostty-vt 核心 + Rust wgpu 渲染器）上补齐主终端集成所需的三个能力：选中区域文本读取（复制用）、字号设置、配色板设置。渲染路径、架构决策、会话层策略一律不动；本工单只加 API 与离屏验收。

**Blocked by:** None

Status: 已完成

## 验收清单

- [ ] 选中文本读取：离屏自检可返回选中区域文本（含跨行、CJK/emoji 边界），与核心格数据一致；主终端可经此 API 取到复制内容
- [ ] 字号设置：设置字号后行高与字形像素尺寸随之变化（离屏像素断言）；未设置时默认字号行为保持现状
- [ ] 配色板设置：push 调色板后前景/背景/选择色的渲染像素随之变化（离屏断言）；未 push 时保持核心解析配色
- [ ] 回归：软件光栅全量断言 ALL PASS；单元测试 PASS；离屏自检 PASS
- [ ] `libfable-render.so` 重编后 APK 构建成功，产物校验通过（badging / apksigner / .so 清单 / TLS 对齐复验）
- [ ] 结论写回：API 签名、实现方式、坑、对工单 15/04 的影响

## Comments

2026-08-07 建单（方向"正式集成主终端" to-tickets；与工单 04 对齐——04 的字号/主题两项经本工单 API 落地）。

2026-08-07 实施完成：

**1. API 签名（JNI = RenderCore.java，Rust = Renderer 句柄）**

- 选中文本：`rendererSelectionText(long handle) -> String`；Rust `Renderer::selection_text() -> String`。返回当前所有 overlay 选中区文本，按行序拼接，跨行 `\n`；宽字符/emoji 以"占用列区间与选区相交"判定，右半边选中也取整字（与核心格数据一致）。
- 字号：`rendererSetFontSize(long handle, float sizePx)`（4..128，默认 24 保持现状）+ `rendererGetCellSize(long handle, int[] out)`（out[0]=格宽 px，out[1]=行高 px）；Rust `set_font_size` / `cell_size`。
- 配色板：`rendererSetPalette(long handle, int fgArgb, int bgArgb, int selectionArgb, int cursorArgb)` + `rendererResetPalette(long handle)`；Rust `set_palette(Palette)` / `reset_palette()`。

**2. 实现方式**

- 三组新命令走既有渲染线程 mailbox；`selection_text` / `cell_size` 用 oneshot 同步查询（mailbox 保序：写先于查），不破坏工单 12 非阻塞语义。
- 选中文本：`selection_text` 先 `ghostty_render_state_update` 再 `collect_snapshot`，从格子文本按列区间取，跨行 join `\n`。
- 字号：`GlyphAtlas` 增加 `pixels_per_em` 字段与 `set_pixels_per_em`——切换字号即清空灰度/彩色图集并重光栅化（revision 递增触发重传）；`cell_size` = 主字体 'M' advance（宽）+ `ascent - descent + line_gap`（高）。默认 24 与旧行为一致。
- 配色板：`Palette`（fg/bg/selection/cursor）存于 `RendererCore`；渲染前覆盖 snapshot 默认前景/背景/光标/选择色。清屏色改用 push bg；显式格背景只在与"实际底"不同时画矩形；SGR 16/256/真彩色单元格色不受 push 影响。坑 H 的近黑转白启发只在未 push（核心解析）时生效。

**3. 验证数据**

- `cargo test`：2 PASS。
- `cargo run --release` 软件光栅：`结果: ALL PASS`（含 scroll/resize/emoji/sprite/下划线）。
- 新增 `cargo run --release --example api_offscreen_check`：`结果: ALL PASS`——mailbox 层（跨行/CJK/emoji 右半边、越界、cell_size 16<24、默认=24、push/reset 后文本正常）+ GPU 像素层（font16 cell=(10,22) text_bbox_h=12，font24 cell=(15,32) text_bbox_h=18；push 配色板背景 (20,80,20)/前景黄 347px/光标深色 420px/选择色混合 (110,62,110)/SGR 红保留；未 push 灰底 (31,31,31) 维持现状）。
- `cargo run --release --example render_offscreen_check`：PASS（white_px=142 cursor=160）；`renderer_thread_check`：200x enqueue 0ms、线程退出干净。
- `libfable-render.so`：PT_TLS p_align=0x40（APK 内复验同样 0x40）；JNI 导出 renderer 16 个（新增 5）。
- APK `fable-app_apt-android-7-debug_arm64-v8a.apk`：`aapt2 dump badging` = `com.gph.fable`（compileSdk 36）；`apksigner` = <签名证书主体>；APK 内 `lib/arm64-v8a/libfable-render.so` 存在（12.2MB），TLS p_align=0x40。

**4. 坑与解法**

- `selection_text` 最初取到空：渲染状态未 `update`，只 `vt_write` 不会刷新 RenderState；读取前必须先 `ghostty_render_state_update`。
- 配色板应用一度误落到 `selection_text` 而不是渲染循环（同一段 `let mut snapshot = current_snapshot()` 上下文撞车），离屏像素自检抓住"push 后背景没变"。
- 单点采样断言 SGR 红不可靠：字形笔画半透明边缘混合出 (187,95,95) 类伪色；改按行区域统计红色像素数（>20）断言。
- 宽字符列宽测试期望要先算清楚：`[0,2)` 只含 1 个宽字符；`[0,4)` 才是"第一"。
- overlay 是逐行独立存续：`set_selection(row, 0, 0)` 即清除该行选区；测试里跨断言需显式清旧行。

**5. 对后续工单影响**

- 工单 15（主终端正式集成）：SurfaceView 尺寸由 `rendererGetCellSize` × (cols, rows) 决定，字号变化后 Kotlin 重排；复制用 `rendererSelectionText`（选择事件后取文本）；主题开关走 `rendererSetPalette` / `rendererResetPalette`。
- 工单 04（v1 最小集 UI）：字号/主题两项直接落地为上述 API；配色板 push 后 SGR 16/256/真彩色语义保持，UI 只提供色值来源不重写渲染通道。
- 遗留：多行选择目前由调用方逐行 `set_selection` 维护 overlay；如主终端需要整矩形选择可再加批量 API（不属本工单）。

2026-08-07 code-review 双轴评审 + 修正轮（Standards 0 硬违规；Spec 2 项已修）：

- 光标深色被坑 H 启发覆盖：`build_overlay_vertices` 的近黑转亮蓝现在按 `palette.is_none()` 门控；离屏新增"push 深色光标原样渲染"断言（dark px=420 PASS）。
- 前景色缺离屏断言：push fg 改黄 (255,240,0) 后第一行黄字像素 347 PASS；背景/选择色断言保持不变。
- "越界行返回空"改为真正越界（row 10 ≥ ROWS=10）再断言。
- 评审整改：`ResetPalette` 独立命令（不再用 `Option` 编码 push/reset）；`apply_palette` 共享函数（渲染循环与离屏自检不重复）；字号范围常量 `MIN/MAX_FONT_SIZE_PX`；签名哈希补 `selection_color`/`palette`（防未来漏重绘）；术语统一为 CONTEXT.md 的"配色板"。
- 已知接受项（评审 judgement call）：`selection_text` 的 ZWJ 多码点按首码点测宽（与渲染器现有限制一致，backlog）；非连续行选择按 `\n` 拼接；`cell_size` 用元组返回（API 文档明确 out[0]/out[1]）。
