# 51 — Rust CI 硬门禁与 Agent 双重审查

**What to build:** 将已经清零的 Rust 质量门禁变为不可绕过的 CI required checks，并建立“实施 Agent + 独立审查 Agent”的 Rust 合并协议、例外台账和失败反馈；不得向非 Fable 所有的远端仓库写入工作流。

**Blocked by:** fable-v1/59、fable-v1/60、fable-v1/61、fable-v1/62
Status: 挂起（ADR-0011 改单仓库拓扑；等 fable-v1/62–65 收口后在新仓库配置门禁）

## 验收清单

- [ ] Fable 拥有的每个 Rust crate 所在远端均有可触发、可复现的 required quality check
- [ ] CI 对 fmt、严格 Clippy、测试、rustdoc 和锁文件策略任一失败均阻断合并
- [ ] Rust 工具链升级、lint 新增、门禁例外和失败基线有受控更新流程
- [ ] 独立 Agent 审查清单覆盖 API 惯用性、错误语义、性能分配、unsafe/FFI、并发和测试强度
- [ ] `#[allow]`/`#[expect]` 例外可检索、具理由与删除条件，且不会在无审查下扩大

## Comments

2026-08-14 建单。当前 `fable-app` remote 指向 Termux 上游、render/session 未配置 remote；本单必须先确认 Fable 自有远端与权限边界，禁止误推或假定可改上游 CI。

2026-08-14 启动核验：`fable-v1/49` 已完成，但 `fable-v1/50` 仍为“待验收”，其 Android
真机 Surface 生命周期验收尚未完成，故依赖未满足。远端边界也已复核：`fable-app` 的
`origin` 为 `https://github.com/termux/termux-app.git`；包含 `fable-boo` 的 Fable 根仓库、
`spike-render` 与 `spike-session` 均未配置 remote。按本单红线，未向 Termux 上游或任何
未知远端新增、提交或推送 CI 工作流，也未把本地脚本伪称为 required check。恢复条件：
完成工单 50 验收，并提供每个需覆盖 Rust crate 的 Fable 自有远端及能设置 required checks
的仓库管理员权限；届时在已确认的目标仓库提交可触发工作流、配置分支保护并以 PR/本地等价
命令验证。

2026-08-15 启动包复核：

1. 验证数据/命令：`fable-v1/49` 与 `fable-v1/50` 均为“已完成”；Fable 根仓库、`spike-render`
   与 `spike-session` 无 remote，`fable-app` 唯一的 `origin` 仍为
   `https://github.com/termux/termux-app.git`。
2. 踩过的坑与解法：工单原状态仍把已完成的 50 写作阻塞条件；已仅收窄状态文本，未在 Termux
   上游或未知远端新增工作流，也未将本地 `rust-quality-gate.sh`/`rust-supply-chain-gate.sh`
   宣称为 required check。
3. 结论写回：依赖工单现已满足；恢复本单只需提供每个 Rust crate 的 Fable 自有远端，以及能将
   已验证 CI job 设为 required check 的仓库管理员权限。取得后再分别提交工作流、配置分支保护，
   并以实际 PR 运行和本地等价命令完成验收。

2026-08-15 拆分写回：Fable private remote 与预演改由工单 58 负责；本单只在 58 完成后接入
Rust CI、required checks、分支保护和独立 Agent review。工单 59 依赖本单完成后才可进行首次
public 发布。

2026-08-16 工单 58 remote handoff：

1. Fable-owned private remotes 已创建并完成精确 `master` ref 推送：
   - `https://github.com/<私有远端1>`
     → `1bfc6cdd41f74b1f1f7702d020b5d1ef60967272`
   - `https://github.com/<私有远端2>`
     → `6ea20f6a9b5fc11afb3efe9e6430c65552e28c3c`
   - `https://github.com/<私有远端3>`
     → `66b4c5d97ee7f894aa06c5c1a238c71a92bdf010`
   三者均为 `bingnnvjn` 所有、`private`、默认分支 `master`，当前权限为
   administrator；没有写入 Termux upstream。

2. CI 接线目标：
   - 目标分支：三个仓库的 `master`。
   - required check 名称：`rust-quality`（fmt、check、严格 Clippy、全目标测试、
     rustdoc，均 `--locked`）和 `rust-supply-chain`（locked metadata +
     cargo-deny 0.20.2 + 供应链台账）。
   - 分支保护目标：禁止 force-push/删除、要求 CODEOWNERS 审查和上述 checks；
     管理员绕过必须禁止或可审计。具体 workflow 与实际 ruleset 由本单实施。

3. 当前阻断：
   - 三个仓库的 `.github/dependabot.yml` 触发了 GitHub 自动 Dependabot dynamic
     workflow，各 1 次成功；`spike-render` 另有 3 个未合并 Dependabot PR/分支，
     未进入 `master`；Fable CI workflow 尚未提交。
   - `GET /repos/<repo>/branches/master/protection` 与 rulesets API 对 private
     仓库均返回 GitHub 计划限制 `403`（需 GitHub Pro 或改 public）；在不违反
     Fable public 红线前，本单不能配置 branch protection，故保持挂起。

2026-08-19 实施与复审：

1. 验证数据/命令：
   - Fable 自有远端三个分支 `ci/rust-quality-gates` 已推送，并创建 PR：
     `fable-boo#1`、`spike-render#4`、`spike-session#1`。
   - 每仓新增 `rust-quality` 与 `rust-supply-chain` workflow。质量链执行固定
     Rust 1.97.1、fmt、`cargo metadata --locked`、严格 Clippy、全目标测试、
     `RUSTDOCFLAGS="-D warnings" cargo doc --locked`。供应链链执行
     `cargo deny --locked --config deny.toml check`、例外台账与 lint 例外校验。
   - 本地等价命令全部通过：`fable-boo` 19 个测试通过；`spike-render` 23 个测试
     通过，且从固定 SHA-256 的 libghostty-vt 与 Apple emoji 输入复现 host CI；
     `spike-session` 13 个测试通过。三个 crate 的 `cargo-deny 0.20.2`、台账、
     Clippy 与 rustdoc 均通过。
   - 已实际触发 GitHub Actions。三个 PR 的 `rust-quality` 与
     `rust-supply-chain` 均已成功完成。

2. 踩过的坑与解法：
   - `spike-render` 独立 clone 缺少 Android 静态库与 emoji 资产。workflow 现在
     下载并 SHA-256 校验固定 libghostty-vt 和 Apple emoji，host-only shim 仅在
     非 Android 目标提供 mmap、window release、PTY 与 SHA 兼容实现；Android 仍
     编译原 TLS/PTY/mmap/SHA shim。
   - `spike-session` 测试原本写死 Termux bash 路径，并且快速退出进程可能令 reader
     抢在启动事件之前投递输出。测试改为注入宿主路径；会话创建改为先同步投递
     `session_created`、`command_started` 再启动 reader，恢复事件顺序保证。
   - 独立 Agent 复审指出安全 lint、`cargo-deny --locked`、lint 例外注释绕过和
     台账一致性缺口，均已整改。renderer 有约百处既有 unsafe 块尚未逐块完成
     `SAFETY:` 改造，因此本单没有虚假开启 `clippy::undocumented_unsafe_blocks`；
     该改造应另立安全清理工单。

3. 结论写回：
   - CI 工作流、供应链台账、PR 双 Agent 审查模板和 Fable 自有远端 PR 已可用。
     `#[allow]`/`#[expect]` 必须带工单号、理由和删除条件，条件属性与注释拆分
     形式也会被检查。
   - 工单核心验收“required checks 不可绕过、独立审查不可绕过”仍未满足。2026-08-19
     再次核验三个 private 仓库的 branch protection 与 rulesets API 都返回 GitHub
     计划限制 403；当前 CODEOWNERS/PR 模板只能记录审查，不能替代强制规则。
     恢复条件：获得 GitHub Pro 或将目标仓库改为 public 后，为 `master` 实际配置并
     用 PR 验证 `rust-quality`、`rust-supply-chain`、CODEOWNERS 审查和管理员绕过
     策略。工单继续挂起，不合并这些 PR。

2026-08-19 公开路线重排：

1. GitHub 当前套餐只能在 public 仓库上提供本单需要的服务器端合并规则。继续要求
   “private 仓库先完成 required checks，再公开”会让本单和工单 59 互相阻塞。
2. 改为 `60 → 61 → 59 → 51`：先补齐 renderer unsafe 合约和公开 CI/ref 审计；
   再把已审计的三个 Rust crate 转 public；最后在 public `master` 配置 required
   checks、CODEOWNERS、独立 Agent 审查记录和管理员绕过策略，并以现有 CI PR 做
   故意失败/成功验收。
3. 现有 private CI PR 不得在无规则的 private `master` 上合并。工单 59 完成 public
   切换后，本单负责在受规则保护的 public `master` 上验收并合并它们。

2026-10-01 拓扑前提变更（工单 62 收尾写回）：

1. ADR-0011 作废 ADR-0010 第 1 条与第 3 条，本单的"每个 Rust crate 一个远端、
   逐仓配置 required checks"不再成立。工单 62 已把五段历史合并到根仓库，
   目录改为 `android/`、`renderer/`、`session/`、`boo/`、`libghostty/`，
   合并后仓库没有任何 remote，也没有向任何远端推送。
2. 三个既有私有远端（`<私有远端1>`、`<私有远端2>`、`<私有远端3>`）
   降级为历史副本，其去留由后续单独决定；它们上面的 CI PR（#1、#4、#1）失去目标
   `master` 的对应关系，不能按原计划合并。
3. 本单的验收内容（fmt、严格 Clippy、测试、rustdoc、锁文件与供应链门禁、独立
   Agent 审查、例外台账）仍然有效，但作用对象要改成合并后的单一仓库，
   workflow 与 required check 的挂载点需要重写。
4. Status 改为挂起并写明原因。恢复条件：fable-v1/62–65 收口、新仓库命名与
   可见性确认之后，重写 workflow 与分支保护目标，再按新的单仓库结构验收。
