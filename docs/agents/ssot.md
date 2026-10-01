# 单一事实源（SSOT）映射

每个事实只有一个"权威文件"，其余引用它。修改事实 = 改权威文件 + 按需同步引用（见同步规则）。

| 事实 | 唯一来源 |
| --- | --- |
| 架构决策 | `docs/adr/NNN-*.md` |
| 工单状态 / 验收 / 进展 | `.scratch/fable-v1/issues/NN-*.md` |
| 工单实施提示（增量） | `.scratch/fable-v1/实施提示词-工单NN.md` |
| 项目速览 / 工具链 / 红线 | `项目总览与交接.md`（带核实日期） |
| v1 规格 | `.scratch/fable-v1/spec.md` |
| 领域词汇 | `CONTEXT.md` |
| 工单体系规则 | `docs/agents/issue-tracker.md` |
| 核心库资产（.a / 校验和 / 字体） | `libghostty/README.md` |
| 渲染器调研细节 | `docs/adr/0004` + `shellow vs ghostty.html` |

## 同步规则

- 任何 ADR 定案后：更新 `项目总览与交接.md` 的"已定决策"、`CONTEXT.md` 的决策指针，必要时 `spec.md`（标注约束）
- 工单收尾：按 `issue-tracker.md` 完成标准写回 Comments，不另开文档
- 工具链/版本变化：更新 `项目总览与交接.md` 的"工作环境状态"，并带核实日期
- 提示词与工单不重复维护：任务本体在工单文件，提示词只放增量
