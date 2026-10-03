# Phanes（原名 Fable／寓言）

私人 Android 终端 App：Kotlin 壳 + Rust 底层 + libghostty-vt 核心。仓库是公开单仓库，
目录用角色名。装配与构建见 `README.md`；工具链与路线图见 `项目总览与交接.md`。

## 三条硬规则

1. **构建输入不入库。** bootstrap 归档、预编译 `.a`、字体、预编译 `.so`、工具链压缩包都不进版本库；来源、sha256、落地路径在 `docs/build-inputs.md`，新设备要现取。
2. **操作记录不入库。** 真机运行数据、诊断现场、一次性恢复副本、签名材料台账不进仓库；分析结论以报告形式留下。见 ADR-0012。
3. **目录用角色名。** `android/`、`renderer/`、`session/`、`boo/`、`libghostty/`；模块与文档目录同理（`android/core/`、`docs/release/`）。产品名只出现在产品标识上。见 ADR-0011。

## 改名边界

项目叫 Phanes，应用身份冻结在 `com.gph.fable`（`applicationId`、派生的 `$PREFIX`、JNI
符号前缀、签名身份）。这个不一致是刻意的，不要顺手"修"。代码标识符改名见
`docs/rename-inventory.md`。

## Agent skills

### Issue tracker

Issues are tracked as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Category + five-role label vocabulary (Chinese): bug / enhancement + 待分类 / 待补充信息 / 待Agent处理 / 待人工处理 / 不修复. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Workflow

Main engineering flow (MATT + caveman integrated): one-window grill-with-docs → to-spec → to-tickets, then one clean context per ticket via 实施提示词, closing with code-review + caveman. See `docs/agents/workflow.md`.
