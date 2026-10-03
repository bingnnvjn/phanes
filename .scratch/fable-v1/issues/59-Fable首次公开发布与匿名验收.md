# 59 — Fable 首次公开发布与匿名验收

**What to build:** 在工单 58、60、61 完成后，将三份已审计 Rust crate 公开到用户确认的 GitHub public 目标，完成首次公开内容检查、匿名访问检查、fork/PR 行为检查和泄露响应记录；不发布 Android App。
**Blocked by:** fable-v1/58、fable-v1/60、fable-v1/61、fable-v1/62
Status: 挂起（被 fable-v1/68 取代：单仓库首次公开）

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

2026-10-01 拓扑前提变更（工单 62 收尾写回）：

1. ADR-0011 作废 ADR-0010 第 1 条与第 3 条。本单的公开对象"三份已审计 Rust
   crate"已不存在独立身份：工单 62 把它们合并进单仓库的角色目录
   `renderer/`、`session/`、`boo/`，根仓库与该合并结果都没有 remote。
2. 工单 58 的预演结论（三仓各自 private 远端、精确 `master` ref 推送）保留为
   历史记录，但那三个远端现在只是异地副本，不再是公开发布的候选目标。
3. 本单的验收动作（首次 public refs、匿名访问检查、artifact/fork 行为检查、
   泄露响应记录）仍然适用，但要针对合并后的单仓库重写：对象变成一次 clone、
   一棵树、一条默认分支。
4. Status 改为挂起并写明原因。恢复条件：新的公开决策窗口确认目标仓库名与
   可见性，工单 62–65 收口后重写本单的验收清单，再执行不可逆的公开动作。

2026-10-03 取代记录：首次公开改由 fable-v1/68 执行——单仓库 `bingnnvjn/phanes`，
只公开 `master`，不打 tag、不发 release。本单的匿名访问检查、泄露响应记录等条目
转由 68 承接，本单保留作历史记录。
