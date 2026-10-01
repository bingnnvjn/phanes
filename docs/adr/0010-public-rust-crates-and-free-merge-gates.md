# ADR-0010: 公开 Rust crate，以 GitHub Free 规则保护合并

- 状态：已确认（第 1、3 条由 ADR-0011 作废 2026-10-01；第 5 条约束转入 ADR-0012）
- 日期：2026-08-19
- 范围：`fable-boo`、`spike-render`、`spike-session` 的源码公开与 Rust 合并门禁

## 背景

三个 Rust crate 已完成私有远端预演、发布边界、许可证和敏感材料审计。它们现在是
Fable 自有的独立仓库。`fable-app`、`spike-libghostty`、根仓库的 `.scratch/`、
`.crew/`、诊断和签名材料不属于这次公开范围。

私有仓库当前不能配置 GitHub 的 branch protection 或 ruleset。现有
`rust-quality`、`rust-supply-chain` 和 PR 审查模板能发现问题，但不能阻止管理员
直接写入 `master`。

## 决策

1. 公开范围只包括 `fable-boo`、`spike-render`、`spike-session`。Fable App、
   libghostty-vt 集成输入和工程内部资料继续私有。
2. 公开前先完成 renderer 的 `unsafe` 合约清理，并审计将随仓库可见的 branch、tag、
   PR ref 和 Actions 工作流。不能只审计默认分支。
3. 三个 crate 转为 public 后，立即在 `master` 配置 GitHub Free 可用的 PR、required
   checks、CODEOWNERS 审查和管理员绕过规则。`rust-quality`、`rust-supply-chain`
   与独立 Agent 审查记录必须成为合并条件。
4. 不把公开仓库当作 Fable Android App 的发布。APK、签名、私有集成资产和
   `fable-app` 的 Termux upstream 边界不变。
5. 公开切换属于不可逆外部动作。实施工单必须在执行前再次取得用户对仓库名称、
   可见性、保留 refs 和切换方式的确认。

## 执行顺序

`60 renderer unsafe 合约` → `61 公开前 CI/ref 审计` → `59 首次公开与匿名验收`
→ `51 public master 硬门禁` → `55 总闸门收尾`。

## 后果

- 不购买 GitHub Pro，也能让公开仓库使用 GitHub Free 的服务器端合并规则。
- 三个 crate 的代码、历史和 PR 将对外可见，因此发布审计范围扩大到全部保留 refs。
- 在工单 51 完成前，公开仓库仍不能被称为“不可绕过的硬门禁”。
