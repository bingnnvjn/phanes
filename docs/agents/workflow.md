# Fable 工程主流程（MATT + Caveman 整合 · 启动协议文件）

本文件是 Fable 工程的**唯一启动依赖**：新会话只读本文件 + 一个方向/工单名，即可自行找素材、按流程执行。词汇与分层见 `CONTEXT.md`；事实源映射见 `docs/agents/ssot.md`。

## 启动协议（一键式）

### 决策上下文（新方向 / 未拆单）

用户说：`读 docs/agents/workflow.md，启动方向：<X>`

→ 在下方"方向映射表"找 X → 按"素材"列读文件（SSOT 路径）→ 按"流程"列执行 → 汇报：**工单清单（含 Blocked by）+ 每张工单的启动包路径**。

### 实施上下文（工单已存在）

用户说：`读 docs/agents/workflow.md，实施工单：<NN>`

→ 读 `.scratch/fable-v1/issues/<NN>-*.md`（工单本体）+ `.scratch/fable-v1/实施提示词-工单<NN>.md`（启动包）→ 按启动包执行 → 收尾（Comments 三样 + Status）。

### 下一步推荐（做完一张后）

用户说：`读 docs/agents/workflow.md，推荐下一步`

→ 读 `项目总览` 全局路线图 + 扫描 `.scratch/fable-v1/issues/` 找 frontier（`Status: 待开工` 且 Blocked by 全完成）→ 汇报可做工单清单 + 方向建议。**用新上下文做，不回旧会话**（旧会话是过期记忆；工单 Comments 的"结论写回"才是最新依据）。

## 方向映射表（2026-08-09 更新）

| 方向 | 素材（SSOT 路径） | 流程 | 产出 |
| --- | --- | --- | --- |
| 正式集成主终端 | 工单 `14/15/04` 启动包、`docs/adr/0001/0003/0004`、`项目总览` 路线图 | 已完成（2026-08-09 真机验收通过） | 无 |
| 会话层搬 Rust | `docs/adr/0003`、工单 `08` Comments（会话层现状）、`项目总览` | 大雾 → grill-with-docs 或 wayfinder → to-spec → to-tickets | 同上 |
| v1 界面重写 | 工单 `04`、`docs/adr/0001/0002`、`spec.md` | 已完成；遗留：选择菜单路线已定（自绘浮条，随 Kotlin 壳重构实现，见工单 04 Comments）、浅色专项 `fable-v1/16`（backlog） | 无（遗留决策见工单 04 Comments） |
| Android 15 权限 | 工单 `05`、`项目总览` | 工单已存在，直接实施 | 完成工单 05 |
| 数据迁移验证 | 工单 `02`、`.scratch/fable-v1/migration/` | 工单已存在；人工部分由用户执行 | 完成工单 02 |
| 渲染器 backlog | 工单 `12` Comments（遗留清单）、`docs/adr/0004` | 已拆为工单 13（emoji 字形方向未定，开工时重调研；ZWJ / 图集增量 / 双线角形并入） | 工单 13 + 启动包 |
| 工具链尾账 | 工单 `11` Comments | 已完成；Groovy 空格赋值清理随工单带；targetSdk 28 评估由工单 05 承接 | — |
| Fable 包仓库（包源） | `docs/adr/0005`、工单 `17/18` 启动包、`项目总览` 路线图 | 工单已存在，按顺序实施（17 → 18） | 完成工单 17/18 |
| fable-boo 动画（观赏+基准） | Ghostty 官方 `+boo` 源码、工单 `21` 启动包、`项目总览` 路线图 | 工单已存在，直接实施 | 完成工单 21 |

新方向出现时，在映射表加一行（决策上下文收尾时顺手维护）。

## 主流程：从想法到落地

### 1. 决策阶段（一个不中断窗口）

- `/grill-with-docs`（工作目录内使用，留痕 CONTEXT/ADR；本仓库已有 CONTEXT.md 与 ADR-0001~0004）
- 有"跑一下才知道"的疑问 → `/handoff` 出去 → `/prototype` 验证 → `/handoff` 回来，把结论引用进思路
- `/to-spec`：更新 `spec.md`（Problem Statement / Solution / User Stories / Implementation Decisions）
- `/to-tickets`：切成垂直切片工单（每片可独立验证、大小适配一个干净上下文），标 `Blocked by`，先向用户 quiz 再发布到 `.scratch/fable-v1/issues/`；**发布时必须同时生成每张工单的 `实施提示词-工单NN.md`（启动包）**——工单"出厂即带提示词"，做完一张，下一张即可直接开跑
- **窗口纪律**：本阶段不 `/compact`、不 `/clear`，直到 `/to-tickets` 完成——决策是实施的一手源，压缩会丢"为什么"

### 2. 实施阶段（每工单一个干净上下文）

- 用户开新会话，按启动协议"实施上下文"一句话启动
- 启动包（实施提示词）含：caveman 会话设置、已定决策、环境快照、红线、完成标准
- `/implement` 语义：
  - **feature 类工单**（如集成主终端、会话层搬 Rust）：在约定缝上先写红测试（`/tdd`），一片红绿一片；全程类型检查 + 单测常跑，收尾全量跑一遍
  - **验证切片类工单**（如渲染数据/GPU 验证）：豁免 TDD，但必须有程序化验收（PASS/FAIL 断言或离屏自检）
- 收尾：`/code-review` 双轴评审（Standards + Spec；大工单用并行子代理）→ 提交（`caveman-commit` 生成提交信息并**实际执行 git commit**，提交到相关仓库当前分支；涉及多仓库时各仓库分别提交，只 add 本工单相关文件）→ 工单 Comments 三样（验证数据 / 坑 / 结论）

### 3. 阶段边界决策树（在实施会话内，按顺序问）

1. 能继续在本会话？→ 继续（成本最低，先排除）
2. 上下文与下一步无关？→ `/clear`（最便宜）
3. 需要便携交接？→ 用仓库内版 handoff（`实施提示词-工单NN.md`），优于 `/handoff` 的临时目录版
4. 可 AFK 子代理？→ 派子代理（如自动评审）
5. 否则 → `/compact`

**红线**：决不在阶段中途 `/compact`；边界处才做选择。

### 工单完成后

- 收尾 Comments 的"结论写回"里写明对后续工单/决策的影响（已在 issue-tracker 完成标准中）
- 下一步怎么做：按启动协议"下一步推荐"执行（读路线图 + 扫 frontier）
- 下一个工单已存在 → 直接新开会话跑它的启动包；是未拆的新方向 → 开决策上下文（映射表登记后按流程走）

## 外环（何时切入）

- `/triage`：**只用于外部来单/用户报告的原始问题**（分类 bug/enhancement + 5 状态，中文标签见 `triage-labels.md`）；`/to-tickets` 产出的工单不 triage
- `/diagnosing-bugs`：真机 bug、间歇性问题——先建立"能复现该 bug 的一条命令"反馈回路，修完加回归测试，定位写回工单 Comments
- `/wayfinder`：大雾项目（方向不明、一次看不清），产决策不产交付；地图清后并入 `/to-spec`
- `/research`：调研类任务交给后台代理，产物是带引用的 md 文件，喂给决策阶段
- `/wizard`：只有人能做的步骤（真机 APK 安装引导、<厂商 ROM> 权限说明、密钥配置）
- `/improve-codebase-architecture`：定期体检（大工单之间），发现深化机会 → 生成想法进主流程
- `/wait-what`：用户表示没听懂时，用 CONTEXT.md 词汇重新表达
- `/teach`：跨会话学习

## 词汇与设计层

- `/domain-modeling`：维护 CONTEXT.md 词汇（术语漂移时用）
- `/codebase-design`：设计模块缝（CoreAdapter 缝、壳与渲染器的边界）

## Caveman 约定（与 MATT 互补）

- 执行回复：caveman full（汇报段可 ultra）；技术词、代码、路径、报错原样保留
- 工单 / Comments / 交接文档：**正常中文**，不压缩
- 收尾：`caveman-review` 评审 diff；`caveman-commit` 生成提交信息并实际执行提交
- 省上下文优先用 `/clear` + 启动包，而不是压缩工单文档

## 与 MATT setup 的关系

本仓库**不重跑** `/setup-matt-pocock-skills`：`docs/agents/*`、`CONTEXT.md`、`.scratch/` 已按同一约定配置（本地 markdown tracker、单上下文）。新词汇/新角色映射直接改 `docs/agents/*.md`。
