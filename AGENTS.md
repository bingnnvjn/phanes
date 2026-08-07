# Fable（寓言）

## Agent skills

### Issue tracker

Issues are tracked as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Category + five-role label vocabulary (Chinese): bug / enhancement + 待分类 / 待补充信息 / 待Agent处理 / 待人工处理 / 不修复. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Workflow

Main engineering flow (MATT + caveman integrated): one-window grill-with-docs → to-spec → to-tickets, then one clean context per ticket via 实施提示词, closing with code-review + caveman. See `docs/agents/workflow.md`.
