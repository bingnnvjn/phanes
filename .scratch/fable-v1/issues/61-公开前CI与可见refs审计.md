# 61 — 公开前 CI 与可见 refs 审计

**What to build:** 审计并收紧三个 Rust crate 在公开后可见的 branch、tag、PR ref 和 GitHub Actions 工作流，保证公开 CI 不依赖秘密、第三方 Action 与下载输入可复现，Apple emoji 测试路径有明确的公开边界。

**Blocked by:** fable-v1/60
Status: 待开工

## 验收清单

- [ ] 列出公开切换后会暴露的 branch、tag、PR ref、Actions workflow、artifact 和日志；未通过审计的 ref 不进入 public 仓库
- [ ] 三个 crate 的公开 workflow 使用最小权限、`pull_request`、无 PR 可读的发布 secret，第三方 Action 固定到完整 commit SHA
- [ ] `rust-quality`、`rust-supply-chain` 的工具链、外部下载和锁文件策略可复现，并有 SHA-256 或等价的版本固定
- [ ] Apple emoji 测试字体的下载、缓存、日志和许可边界已记录；若不能作为公开 CI 输入，改为不依赖该资产的公开测试策略
- [ ] `fable-boo`、`spike-render`、`spike-session` 的公开候选 refs 通过 secret scanner、公开路径 allow-list、许可证/供应链和 Git 历史检查
- [ ] 不改变仓库可见性、不创建 public remote、不发布 tag 或 release

## Comments

2026-08-19 建单。仓库一旦转 public，默认分支以外的保留 branch、PR head 和 Actions
日志也会成为公开输入。现有 CI PR 已在 private remote 通过，仍需在公开前审计其
workflow Action pin、下载资产和可见 ref。完成后交给工单 59 执行不可逆的可见性切换。

