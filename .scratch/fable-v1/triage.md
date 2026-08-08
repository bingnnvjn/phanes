# Triage 记录（外部来单 / 用户报告）

> 规则：本文件只收外部来单/用户报告的原始问题，分类 + 状态各一个标签（词汇见 `docs/agents/triage-labels.md`）；不替代 `.scratch/fable-v1/issues/` 的实施工单。维护者评估后自行转工单。

---

## 2026-08-08 — <厂商 ROM> 小窗下终端界面乱跳、扩展键重叠

- **分类:** bug
- **状态:** 待分类
- **来源:** 用户真机反馈（<厂商 ROM>，2026-08-08）
- **影响面:** 主终端界面层（TerminalView 尺寸逻辑 + ExtraKeysView + TermuxActivityRootView），小窗/自由窗口模式

### 症状

1. Fable 或 Termux 进入 <厂商 ROM> 小窗后，终端界面乱跳：Codex CLI（less 分页）下屏幕匀速向上翻到最顶；普通终端界面下上下不停闪动（抽搐）。
2. 扩展键从两行变成视觉四行且重叠，小窗下基本无法使用终端。

### 复现

1. <厂商 ROM> 上把 Fable 切到小窗（完整界面缩小运行）。
2. 终端里跑 `top` 或进入 Codex CLI。
3. 拖动/调整小窗大小，观察终端跳动与扩展键重叠。

### 代码分析（2026-08-08 核实）

- `FableInputTerminalView.updateSize()`：每次尺寸变化（`onSizeChanged`）都会 `mTermSession.updateSize(...)` + `mTopRow = 0` + `scrollTo(0, 0)`。小窗拖动时窗口高度连续微变，行列数在阈值附近震荡 → 反复"PTY resize + 视口回顶"，表现为匀速上翻/抽搐。
- `TermuxActivityRootView.onGlobalLayout`：Gboard 补偿逻辑在窗口尺寸变化时反复改底部 margin，改 margin → 重新布局 → 再触发尺寸变化，形成反馈环，放大症状。
- 扩展键：工具栏高度 = `mTerminalToolbarDefaultHeight(37.5dp) × 行数 × scale`；按钮高度固定 `heightPx = 37.5dp`（`ExtraKeysView.reload`）。小窗下高度不足/margin 振荡压缩扩展键区时，按钮高度超过单元格高度 → 两行互相重叠（视觉四行）。
- 该逻辑基本为 upstream Termux 原样，上游在自由窗口/小窗下同样存在此问题，非本次引入。

### 建议修复方向（供后续转工单参考）

1. 尺寸变化去抖：小窗拖动时合并 resize（延迟几十毫秒再通知 PTY）；resize 不再无条件把视口回顶。
2. 扩展键按钮高度改为按实际单元格高度（`viewHeight / 行数`），不写死 37.5dp。
3. 小窗/自由窗口模式下限制或禁用根视图 margin 补偿循环，切断反馈环。

只动界面层与 TerminalView 尺寸逻辑；会话层 PTY 逻辑不动（PTY resize 为被动接收）。
