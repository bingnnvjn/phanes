# 58 — Fable 私有远端与 private 预演

**What to build:** 在工单 56、57 完成后，为三个 Rust crate 创建或确认 Fable 自有 private remote，推送已审计 refs，完成发布输入、CI 前置条件、日志、artifact 和失败路径的私有预演；不得创建 public 仓库。
**Blocked by:** fable-v1/56、fable-v1/57
Status: 已完成

## 验收清单

- [x] 每个 crate 的 private remote 所有权、URL、默认分支和管理员权限已人工确认
- [x] private remote 不属于 Termux upstream 或未知组织
- [x] 只推送工单 56、57 产出的已审计 refs；没有 `git add .` 全量上传
- [x] private remote 的源码树、Git 历史、artifact 和 Actions 日志通过发布清单检查
- [x] 运行本地公开发布闸门和 Rust 质量/供应链门禁
- [x] CI 接线前置条件、required check 名称和 branch protection 目标已记录，交给工单 51
- [x] 私有预演不创建 public 仓库，不发布 release，不使用 release signing/GPG secret
- [x] 预演失败有可复现命令和修复记录
- [x] 三个 private remote 的首次创建与首次写入顺序例外已由人工接受

## Comments

2026-08-19 验收复核开始：工单 57 已于同日完成签名材料轮换收尾；本轮仅复核三个 Fable-owned private remote、精确发布 refs 和本地闸门，不创建 public 仓库、不推送新 refs、不启用 workflow 或 release。

2026-08-15 建单。工单 55 的本地闸门已明确 remote 冻结；本单是第一个允许触碰 Fable 自有 GitHub private remote 的切片，但必须等待本地发布输入清零。

2026-08-16 预演前置核验（未创建远端、未推送）：

1. 验证数据/命令：
   - GitHub CLI 身份核实为 `bingnnvjn`；候选仓库 `fable-boo`、`spike-render`、
     `spike-session` 当前均不存在，三个本地 crate 均为干净 `master`，且没有
     configured remote。
   - 当前已审计 refs：`fable-boo` `1bfc6cdd41f74b1f1f7702d020b5d1ef60967272`、
     `spike-render` `6ea20f6a9b5fc11afb3efe9e6430c65552e28c3c`、
     `spike-session` `66b4c5d97ee7f894aa06c5c1a238c71a92bdf010`。
   - `bash scripts/rust-public-boundary-gate.sh`：`36 checks, 0 failures`。
   - `scripts/rust-quality-gate.sh`：`15 steps, 0 failed`；`cargo-deny 0.20.2`
     的 `scripts/rust-supply-chain-gate.sh`：`7 steps, 0 failed`。
   - 固定 `gitleaks v8.29.0` 扫描工作树、索引和 Git 对象通过。带固定工具运行
     `scripts/public-release-gate.sh`：`65 checks, 3 failures`；唯一失败是三个
     crate 尚无 CI workflow，属于工单 51 的后置接线，不是本次本地发布输入失败。

2. 踩过的坑与解法：
   - 工单 57 仍为“挂起（签名材料轮换/历史策略待人工确认）”。虽然其材料已从
     当前 Rust 发布树隔离，仍不能把该工单依赖默认为完成。
   - GitHub 账号、仓库名称、可见性和默认分支属于外部状态；在没有人工确认前，
     不把推测的 URL 当成目标，不运行 `gh repo create`、`git remote add` 或
     `git push`。

3. 结论写回：
   - 本次只完成了私有预演的本地前置核验；没有创建 public/private remote、
     没有推送 refs、没有启用 workflow、没有发布 release，也没有触碰
     `fable-app` 的 Termux upstream。
   - 恢复条件：先完成工单 57 的签名材料用途/轮换/历史策略人工确认，并确认三个
     Fable-owned private remote 的确切名称/URL、所有权、可见性、默认分支和管理员
     权限；随后才能按上述固定 refs 创建或确认远端、推送并继续工单 51 的 CI 接线。

2026-08-16 人工确认：

- 已确认目标为 `<私有远端1>`、`<私有远端2>`、
  `<私有远端3>`；三者均创建为 private，默认分支目标为 `master`。
- 允许按已审计 refs 逐仓库创建/推送；仍不创建 public 仓库、不发布 release、不启用
  workflow，不修改 `fable-app` 的 Termux upstream。

2026-08-16 private remote 创建与预演收尾（仍待工单 57/51 的后续人工闸门）：

1. 验证数据/命令：
   - 三个 remote 均由 `bingnnvjn` 所有，`private=true`、管理员权限、默认分支
     `master`，URL 分别为：
     `https://github.com/<私有远端1>`、
     `https://github.com/<私有远端2>`、
     `https://github.com/<私有远端3>`。
   - 只执行了逐仓库 `git remote add origin` 和
     `git push origin refs/heads/master:refs/heads/master`；远端 HEAD 与已审计 refs
     完全一致：`1bfc6cdd41f74b1f1f7702d020b5d1ef60967272`、
     `6ea20f6a9b5fc11afb3efe9e6430c65552e28c3c`、
     `66b4c5d97ee7f894aa06c5c1a238c71a92bdf010`。
   - GitHub fresh clone 校验通过：当前源码树 allow-list、禁止产物、gitleaks 工作树
     与 reachable Git history、无 tag、`git diff --check` 均通过。`spike-render` 历史
     仍保留工单 56 选择的历史提交中已删除的
     `assets/NotoColorEmoji-CBDT-2017.ttf`（由 `0fbac48` 引入、`5383888` 删除）；
     它不在当前发布树，属于已记录的历史保留策略，不是 secret 或当前 artifact。
   - 每个 remote 的 Actions API 显示无 Fable CI workflow、无 artifact、无 release、
     无 tag。由于三个 crate 都携带 `.github/dependabot.yml`，GitHub 自动生成的
     Dependabot dynamic workflow 各运行 1 次，三次均 `success`；没有发布或构建 artifact。
     `spike-render` 另自动生成 3 个未合并 Dependabot 分支/PR（`cc`、`pollster`、
     `sha2`），它们不是本次 push 的 refs，未合并、未纳入 master 发布输入，交由工单
     52/51 后续审查。
   - `gh run view --log` 取得三次 Dependabot 日志并做 secret-like marker 扫描，三次均
     PASS；日志不含已知 token/private-key 标记。
   - 分支保护/rulesets API 对三个 private 仓库均返回 GitHub 计划限制
     `403 Upgrade to GitHub Pro or make this repository public`；未擅自改为 public。

2. 踩过的坑与解法：
   - 初版历史路径检查器把 `git rev-list --objects` 的 tree/commit OID 当成文件路径，
     产生假失败；修正为只检查 reachable blob 后，三个远端的源码树/历史审计通过。
   - 创建 private 仓库会因 Dependabot 配置自动触发一次 dynamic workflow；这不是
     Fable CI，已单独记录为成功的前置日志，且 artifact/release 均为空。
   - 当前 GitHub 账户计划不能为 private 仓库启用 branch protection；这属于工单 51
     的权限/计划阻断，不通过改可见性绕过。

3. 结论写回：
   - 工单 58 的 private remote、精确 refs 推送和私有预演已完成；没有创建 public
     仓库、没有发布 release、没有使用 release signing/GPG secret，也没有修改
     `fable-app` upstream。
   - required checks 目标（`rust-quality`、`rust-supply-chain`，目标分支 `master`）
     与分支保护阻断已写入工单 51；CI workflow、required checks 和 branch protection
     仍由工单 51 处理。工单 57 的签名材料用途/轮换/历史策略仍未清零，因此本工单
     保持 `待验收`，不得解释为可公开发布。

2026-08-19 验收复核收尾：

1. 验证数据/命令：
   - 三个 GitHub private remote 均复核为 `bingnnvjn` 所有、`private=true`、默认分支
     `master`、当前执行身份为 `ADMIN`；GitHub API 的 `master` ref 分别仍为
     `1bfc6cdd41f74b1f1f7702d020b5d1ef60967272`、
     `6ea20f6a9b5fc11afb3efe9e6430c65552e28c3c`、
     `66b4c5d97ee7f894aa06c5c1a238c71a92bdf010`，与本地审计台账一致。
   - 每仓库均无 tag、release、artifact；仅各有一次成功的 Dependabot dynamic
     workflow。三份 Actions 日志均可读取，所扫描的 private-key/GitHub token/AWS
     marker 均未命中。
   - `scripts/rust-public-boundary-gate.sh`：`36 checks, 0 failures`；
     `scripts/rust-quality-gate.sh`：`15 steps, 0 failed`；
     使用固定 `cargo-deny 0.20.2` 重试
     `scripts/rust-supply-chain-gate.sh`：`7 steps, 0 failed`。
   - 使用固定 `gitleaks v8.29.0` 与 `cargo-deny 0.20.2` 运行
     `scripts/public-release-gate.sh`：`67 checks, 4 failures`。三个失败是三个
     crate 尚无 CI workflow（工单 51）；第四个是内嵌 RustSec advisory-db HTTPS
     刷新超时。独立供应链门禁的随即重试完整通过，确认该项不是依赖、锁文件或台账
     发现。
   - 三个 `master` branch-protection API 均仍返回
     `403 Upgrade to GitHub Pro or make this repository public`；未以改为 public
     绕过。required checks 目标仍为 `rust-quality`、`rust-supply-chain`，目标分支
     `master`，已交给工单 51。

2. 踩过的坑与解法：
   - 直接 `git ls-remote` 对 `spike-render`、`spike-session` 出现 GitHub HTTPS
     连接超时；改以已认证 GitHub API 精确查询 remote `master` ref，并与本地
     remote 台账和已审计 commit 三方比对，未重推 refs。
   - cargo-deny 第一次仅在 `fable-boo` 的 RustSec 更新阶段超时；本机已固定
     Git HTTP/1.1，重试后七步全过。总闸门会把该临时网络错误计为失败，因此保留
     独立门禁的成功输出和精确失败命令，不能把网络失败伪称为供应链 finding。

3. 结论写回：
   - 工单 57 已于 2026-08-19 完成签名材料轮换收尾，本工单的原有验收阻塞解除；
     本轮复核确认三个 Fable-owned private remote、精确发布输入、日志/artifact
     审计与失败路径均符合验收清单，工单 58 现已完成。
   - 本轮没有创建 public 仓库、推送新 ref、创建 release、启用 workflow、使用
     release signing/GPG secret，或修改 `fable-app` 的 Termux upstream。公开发布
     仍被工单 51 的 CI/branch-protection 阻断；后续按工单 51 处理，不得把本单
     完成解释为可公开发布。

2026-08-19 评审修正：收尾评审指出 2026-08-16 的首次创建/push 早于工单 57 在
2026-08-19 的完成记录，不能仅靠事后复核宣称满足“工单 56、57 完成后推送”的顺序。
在三仓库 `master` 均已与台账精确一致的前提下，现仅允许对同一已审计 ref 进行非强制、
幂等 push；不得新增或改写 ref。完成该合规重演后再更新状态。

2026-08-19 合规重演收尾：

1. 验证数据/命令：
   - 在工单 57 完成后，逐仓库执行
     `git push origin refs/heads/master:refs/heads/master`，无 `--force`、无 tag、
     无其他 ref。`spike-render`、`spike-session` 第一次均返回 `Everything up-to-date`；
     `fable-boo` 第一次 HTTPS 连接超时后，以
     `git -c http.version=HTTP/1.1 push origin refs/heads/master:refs/heads/master`
     重试，也返回 `Everything up-to-date`。
   - GitHub API 随后复核三个 remote `master` 仍分别为
     `1bfc6cdd41f74b1f1f7702d020b5d1ef60967272`、
     `6ea20f6a9b5fc11afb3efe9e6430c65552e28c3c`、
     `66b4c5d97ee7f894aa06c5c1a238c71a92bdf010`；没有新 ref 或历史改写。

2. 踩过的坑与解法：
   - GitHub HTTPS 在 `fable-boo` 的首次精确 ref push 再次短暂超时；同一命令以
     已固定的 HTTP/1.1 配置重试成功。因为目标 ref 已存在，三次操作均为可验证的
     no-op，而非向 private remote 增加发布输入。

3. 结论写回：
   - 工单 56、57 都完成后，三个 crate 的已审计 `master` ref 已按本单规则完成
     非强制、幂等的 private 推送重演；但这不能回溯改变 2026-08-16 首次创建 remote
     和首次写入发生在工单 57 完成前的事实。
   - 因此工单 58 挂起，等待人工在两条路径中选择：接受并记录这次 private-only
     顺序例外，或明确授权删除并重建三个 private remote 后完整重演。后一条路径会
     删除 remote 上现有的 Dependabot 日志，属于破坏性外部操作，不能自行执行。
     工单 51 仍负责 CI workflow、required checks 与 branch protection；本工单没有
     创建 public 仓库或 release，不能作为公开发布批准。

2026-08-19 人工例外决定：

- 用户确认接受本次顺序例外：三个 private remote 在工单 57 完成前已创建并首次写入，
  原因是前期流程不熟练。
- 不删除或重建 remote；保留已审计 refs、private 可见性、Git 历史和 Dependabot 日志。
  工单 58 的 private 预演验收完成。此例外不解除工单 51 的 CI/branch-protection
  阻断，也不构成公开发布批准。
