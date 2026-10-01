# 59 — Fable 首次公开发布与匿名验收

**What to build:** 在工单 58、60、61 完成后，将三份已审计 Rust crate 公开到用户确认的 GitHub public 目标，完成首次公开内容检查、匿名访问检查、fork/PR 行为检查和泄露响应记录；不发布 Android App。
**Blocked by:** fable-v1/58、fable-v1/60、fable-v1/61
Status: 挂起（等待 fable-v1/60、fable-v1/61）

## 验收清单

- [ ] public 仓库名称、可见性、默认分支和所有权已人工确认
- [ ] public 仓库只包含工单 56、57、58 验收的 refs 和允许发布路径
- [ ] 匿名访问看不到 keystore、token、内部路径、设备信息、私有日志或未授权资产
- [ ] Actions 日志、artifact、Release 和 fork 行为均通过检查
- [ ] 首次 public refs、构建产物和来源清单记录 SHA-256
- [ ] 泄露时的撤销、轮换、历史清理、公告和负责人已记录
- [ ] 公开完成后将 public `master` 的 required checks、CODEOWNERS 和独立 Agent
      审查验收交给工单 51；本单不把临时流程伪称为硬门禁

## Comments

2026-08-15 建单。该切片包含不可逆的 public 可见性和首次 push，只能在工单 51、58 完成并由用户确认具体仓库后执行。

2026-08-19 路线调整：

1. GitHub 当前套餐不能在 private 仓库配置 required checks。公开是工单 51 的前置，
   不是后置，因此本单改为先于工单 51 执行。
2. 工单 60 与 61 分别保证 renderer 的 strict unsafe 基线和公开可见 ref/CI 输入；
   工单 58 已完成 private 预演。
3. 执行前仍必须由用户确认目标仓库名称、是否转换现有 private 仓库、保留哪些 refs
   以及可见性切换时间。完成公开后立即启动工单 51，不在无保护的 public `master`
   上合并现有 CI PR。
