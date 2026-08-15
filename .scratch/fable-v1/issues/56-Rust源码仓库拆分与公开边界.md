# 56 — Rust 源码仓库拆分与公开边界

**What to build:** 将三个 Rust crate 的本地发布边界变成可独立验收的 Git 仓库输入；拆出仍位于 Fable 根仓库的 `fable-boo`，并为三个 crate 固定公开路径 allow-list、禁止路径和历史保留策略。
**Blocked by:** None
Status: 已完成

## 验收清单

- [x] `fable-boo` 具有独立 Git 顶层；其历史或干净导出策略已记录
- [x] `spike-render`、`spike-session` 的独立 Git 顶层和当前 refs 已记录
- [x] 三个 crate 的公开路径 allow-list 已落盘并通过脚本检查
- [x] 根仓库 `.scratch/`、`.crew/`、诊断、设备记录和内部实施资料不在 crate 发布输入中
- [x] `target/`、构建缓存、日志、APK/AAB、环境文件和签名材料不在发布输入中
- [x] 每个 crate 的发布输入可从干净 checkout 重建
- [x] 本单不创建 GitHub remote、不推送、不新增 workflow

## Comments

2026-08-15 建单。工单 55 的闸门发现 `fable-boo` 当前属于 Fable 根仓库，不能直接作为独立 public crate 发布；本单先解决本地 Git 拓扑和发布边界，不处理 GitHub 远端权限。

2026-08-15 实施收尾：

1. 验证数据/命令：
   - 先保存可恢复副本：`.scratch/fable-v1/backups/工单56-2026-08-15/fable-root-history.bundle`（SHA-256 `51c9bdac80ee6e6207bbd2aad92bd8aee66a087d76cafffb1a03eb10808ced8d`）和 `fable-boo-source-HEAD.tar`（SHA-256 `cc2caa1ce266024c5becca996923f51f3a4d8011e589fa6fdc04448a2c79a88a`）。
   - `fable-boo` 从根仓库 `b00fbde4ed8e3f05ac0b0bbb4c3a091c6c065b1c` 以 `git subtree split --prefix=fable-boo --annotate='fable-boo: '` 生成独立历史，当前 `master` 为 `fae5df476879f5b9a478f859087235ed5916f477`；`git -C fable-boo rev-parse --show-toplevel` 已返回 `Fable/fable-boo`。
   - `spike-render` 当前 `master` 为 `3859f86afd895d56ab93e5831b6aae73164d8f00`，`spike-session` 当前 `master` 为 `66b4c5d97ee7f894aa06c5c1a238c71a92bdf010`；三者均无 remote。
   - 新增 `docs/security/rust-public-boundary.allowlist`、`docs/security/rust-public-boundary.refs`、`docs/security/rust-public-boundary.md` 和 `scripts/rust-public-boundary-gate.sh`。边界门禁结果：`Rust public-boundary gate: 36 checks, 0 failures`；工作树索引与 fresh clone 均通过固定 `master` ref、allow-list、禁止产物和 `git diff --check`。
   - 根仓库增加 `/fable-boo/` 忽略规则并显式停止跟踪 `fable-boo` 源码；未使用 `git add .`，未修改 `fable-app` upstream，未创建 remote、推送或启用 workflow。

2. 踩过的坑与解法：
   - 新仓库初始化后，Git 会拒绝用历史 checkout 覆盖现有未跟踪源码；先在已备份的独立仓库内做临时 seed commit，再 `reset --hard` 到 subtree split head，最终历史只保留 crate 相关提交。
   - `spike-render` 工作树有被 `.gitignore` 排除的 `out.png` 与 `coverage_report.txt`。发布输入应检查 Git 索引而不是把本地诊断输出当成可发布文件；边界脚本因此同时验证索引和 fresh clone，秘密扫描仍单独覆盖工作树。

3. 结论写回：
   - 本工单的本地 Git 拓扑、历史保留策略、公开路径 allow-list、禁止边界和 fresh checkout 重建路径已落盘并通过程序化检查；可进入后续工单 57/58。
   - 公开发布总闸门仍会因工单 51/57 的 workflow、secret scanner、cargo-deny、字体来源和签名材料等前置项失败；这些不是本工单的完成条件，不能据此宣称已可公开。
