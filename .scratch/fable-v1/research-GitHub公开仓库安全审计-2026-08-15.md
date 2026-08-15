# Fable 公开 GitHub 仓库发布前安全审计

日期：2026-08-15
范围：将 Fable 源码或 Rust crate 推送到 `bingnnvjn` 账号下的公开 GitHub 仓库。
审计类型：发布前安全、供应链、CI、权限、许可证和隐私审计。
审计原则：公开推送前不创建远端、不推送、不启用工作流；只使用可复核的本地证据和一手资料。

## 结论

当前不允许把工作区整体推送到公开仓库。

公开发布可行，但必须先完成一张独立工单：**公开仓库发布前安全审计与发布闸门**。最安全的目标形态是：

1. `fable-boo`、`spike-render`、`spike-session` 分别作为公开 Rust 源码仓库，或经过明确清理后放入一个公开源码仓库。2026-08-15 复核发现 `fable-boo` 当前没有自己的 `.git`，`git -C fable-boo` 解析到根仓库；因此“独立仓库”仍是目标形态，不是当前事实。
2. 根仓库的 `.scratch/`、`.crew/`、内部诊断、实施提示和设备验收记录继续留在本地或私有仓库。
3. `fable-app` 不直接改写 Termux 上游；如果以后公开 Fable App，必须另建 Fable 自有 fork，并单独清理其 Git 历史。
4. 公开仓库首次推送必须从“允许发布文件清单”产生，禁止对当前工作区执行 `git add .`。
5. 首次公开前先使用同一份发布清单在临时 private 仓库预演；预演通过后再创建空的 public 仓库并推送同一份已审计 refs。

当前最高风险不是 Rust 代码本身，而是**发布边界和历史材料**：

- `fable-app/app/<上游测试签名材料>` 是 `fable-app` Git 历史中的已跟踪二进制文件。该文件出现在历史提交 `e634d8f`（2025-05-23，`v0.119.0-beta.3`）。当前没有发现它被 `app/build.gradle` 或签名脚本引用，因此它可能是上游公开测试材料；在确认用途和不可用于正式签名前，不能把它当作安全材料。
- `fable-app/keystore/<旧签名材料>` 当前被 `.gitignore` 忽略，但仍必须当作敏感材料处理。
- 根仓库跟踪了大量 `.scratch/` 规划、工单、诊断和迁移资料；这些内容含设备标识、局域网地址、Termux 本地路径和内部决策细节。
- 根仓库 `.gitignore` 排除了 `fable-app/`、`spike-render/`、`spike-session/`；根仓库的 Git 历史不是三个 Rust crate 的完整源码历史。
- 2026-08-15 复核显示 `fable-boo` 没有自己的 `.git`，因此它当前仍属于根仓库工作树；这与已定“Rust crate 三个独立仓库”的目标决策不一致，发布闸门必须阻断。
- 当前没有 Rust CI workflow；三个 Rust crate 有锁文件和本地门禁脚本，但公开发布前仍需把 CI 权限、Action 固定、required checks 和审查流程落到 Fable 自有远端。

## 审计对象和仓库边界

| 对象 | 当前状态 | 公开发布判断 |
| --- | --- | --- |
| Fable 根仓库 | `master`，无 remote；跟踪 `fable-boo`、文档、`.scratch` 和本地门禁 | 不可整体公开；需做源码/规划分离 |
| `fable-boo` | 当前目录没有独立 `.git`，由 Fable 根仓库跟踪；有 `LICENSE`、`Cargo.lock`、Dependabot 配置 | 必须先从根仓库拆出独立历史/发布 refs，或明确改用单一公开源码仓库；在此之前不可按独立仓库发布 |
| `spike-render` | 独立 Git 仓库；无 remote；含 JNI、FreeType、wgpu/native window | 可公开，但必须做 FFI、第三方许可证和 CI 安全审计 |
| `spike-session` | 独立 Git 仓库；无 remote；含 JNI、PTY、vendor patch | 可公开，但必须做 FFI、PTY、供应链和 CI 安全审计 |
| `fable-app` | `origin` 是 `https://github.com/termux/termux-app.git` | 不得写入当前 upstream；公开必须使用 Fable 自有 fork 和清理后的历史 |
| `bingnnvjn/fable-bootstrap` | 已有公开仓库；内容是 Fable 包源和 bootstrap 构建 | 继续作为包仓库，不与 Rust 源码远端混用 |

核实日期：2026-08-15。

## 本地证据

### 工作区没有“可直接公开”的干净发布状态

根仓库当前有已修改文件：

- `.scratch/fable-v1/spec.md`
- `CONTEXT.md`
- `docs/agents/workflow.md`
- 项目总览及实施提示模板

当前还有未跟踪内容：

- `.crew/`
- `.scratch/fable-v1/diagnostics/`
- 多张新工单和实施提示
- 新研究文件
- 新 ADR 和代码审查记录

这些内容没有进入一次受控发布清单。不能用 `git add .` 作为发布动作。

### 签名材料

本地发现：

| 文件 | Git 状态 | 风险 |
| --- | --- | --- |
| `fable-app/app/<上游测试签名材料>` | `fable-app` 已跟踪；历史可达 | 高（条件性）：先确认是否含可用私钥；若可签名则轮换并清理历史 |
| `fable-app/keystore/<旧签名材料>` | 当前被 `keystore/` 忽略 | 高：即使未跟踪，也不能复制进公开仓库或 artifact |
| `fable-app/release-missing-credentials.log` | 当前被 `*.log` 忽略 | 中：可能暴露构建路径、凭据名称或环境信息 |

根仓库、`spike-render`、`spike-session`、`fable-boo` 的常见私钥和 GitHub token 标记扫描未发现命中：

- `BEGIN ... PRIVATE KEY`：0
- `ghp_...`：0
- `github_pat_...`：0
- `AKIA...`：0

这不是完整证明。当前环境没有安装 `gitleaks`、`trufflehog` 或 `detect-secrets`，发布闸门必须补上专门扫描器，并扫描全部 Git 历史。

### 隐私和内部信息

根仓库已跟踪的 `.scratch/` 资料命中以下类别：

- Termux 本地绝对路径；
- ADB 命令和设备验收记录；
- 局域网地址；
- 真机设备标识；
- 内部工单、决策、失败反馈和实施提示。

这些内容不一定是秘密，但不应默认公开。尤其是真机设备标识、网络地址和本地路径没有进入公开源码的必要。

### Git 历史和身份

- 根仓库只有 `master` 分支，没有 tag；Rust 子仓库也只有本地 `master`。
- 根仓库和 Rust 子仓库主要使用 `fable.local` 作为提交邮箱域。
- `fable-app` 历史含至少一个非 `fable.local` 的作者邮箱域。
- Git 历史一旦被公开克隆，后续普通删除不能让历史副本消失。

发布时必须明确是否保留历史。若要保留历史，必须先从历史中删除任何可用签名材料；若不需要保留历史，使用干净导出或新的 orphan 历史更安全。`<上游测试签名材料>` 在完成用途分类前不得带入新的公开历史。

### 许可证和来源

- `fable-boo` 有 `LICENSE`。
- `spike-render` 有 vendored FreeType 及其许可证文件，但 crate 本身缺少明确的顶层公开许可证文件。
- `spike-session` 有 vendor portable-pty 的许可证文件，但 crate 本身缺少明确的顶层公开许可证文件。
- 根仓库没有统一的 `SECURITY.md`、`CODEOWNERS` 或公开贡献策略。
- Apple emoji、FreeType、portable-pty、libghostty 和 Termux 来源必须按各自许可证和来源说明处理；不能把内部资产或未获授权的字体二进制推入公开仓库。

本审计不是法律意见。公开前必须逐项记录第三方来源、许可证、NOTICE 要求和可发布资产范围。

### 当前 CI 和 Action 风险

当前 GitHub Actions 只存在于 `fable-app`；三个 Rust crate 没有 Rust workflow。

现有 `fable-app` workflow 使用可变 Action 标签，例如 `actions/checkout@v4`、`actions/upload-artifact@v4` 和 `gradle/wrapper-validation-action@v3`。如果未来把它们带入公开 Fable fork，应固定到完整 commit SHA，并重新审查触发器、权限和 artifact 内容。

当前本地 Rust 门禁脚本已经覆盖：

```text
fmt
check --locked
clippy -D warnings
test --locked
rustdoc -D warnings
```

供应链脚本还检查 `cargo-deny`、锁文件、来源台账、许可证和 advisory 例外。但当前 shell 没有安装 `cargo-deny` 或 `cargo-audit`；“脚本存在”不等于“公开远端已有 required check”。

## 威胁模型

### 机密性

攻击者或普通浏览者获得：

- keystore、签名材料或凭据；
- 内部路径、设备编号、网络地址；
- 未发布的架构、迁移和失败资料；
- CI secret 名称、发布流程和包仓库操作细节。

### 完整性

攻击者通过恶意 PR、依赖更新、Action 更新或管理员账号入侵，改变：

- Rust 代码、JNI/FFI 边界或供应链配置；
- CI workflow；
- release artifact；
- 包仓库发布流程。

### 可用性

公开仓库的 PR 和 workflow 可能消耗 CI 配额，制造大量失败、依赖更新或 artifact。

### 供应链

风险来源包括：

- mutable Action tag；
- Cargo registry/git/path 依赖；
- 许可证和 advisory 例外扩大；
- 恶意或被接管的依赖更新；
- 公开 release artifact 被误信。

### 人员和账户

GitHub 账号、PAT、SSH key、Actions secret、GPG 私钥和 Android release keystore 任一泄露，都可能改变仓库、CI 或包仓库。

## 风险清单

| ID | 风险 | 等级 | 当前证据 | 发布前要求 |
| --- | --- | --- | --- | --- |
| PUB-01 | `fable-app` 历史包含 `app/<上游测试签名材料>` | P1（条件性 P0） | 历史提交 `e634d8f` 可达；当前构建脚本未引用 | 完成用途/私钥可用性分类；若可用于签名，立即轮换并清理历史 |
| PUB-02 | Fable release keystore 或日志误入公开仓库 | P0 | `keystore/<旧签名材料>`、`release-missing-credentials.log` 存在于工作树 | 删除/移出发布上下文；加入忽略规则；扫描工作树、索引和历史 |
| PUB-03 | 根仓库发布边界不清 | P1 | `.gitignore` 排除三个独立仓库；`.scratch` 已跟踪 | 采用允许发布文件清单；优先拆分三个公开 Rust 仓库 |
| PUB-04 | 内部资料和设备信息公开 | P1 | `.scratch`、诊断、ADB、设备标识和局域网地址 | 私有化规划资料；公开前做隐私 scrub |
| PUB-05 | 公开 PR 执行不受信任代码 | P1 | 未来需要新增 Rust workflow；现有 workflow 使用 mutable tag | `pull_request`、只读权限、无发布 secret、GitHub-hosted runner、Action SHA pin |
| PUB-06 | required checks 不存在或可被绕过 | P1 | Rust crate 当前无 workflow/远端 | 配置 protected branch、required checks、独立 Agent review、禁止管理员绕过 |
| PUB-07 | 依赖和许可证例外不可审计 | P1 | `deny.toml` 和台账存在，但工具未安装；crate 顶层许可证不完整 | 安装并固定 cargo-deny/audit；例外有 owner、理由、期限、删除条件 |
| PUB-08 | 第三方来源或字体资产误发布 | P1 | FreeType/vendor/emoji/libghostty/Termux 多来源 | 来源清单、许可证和资产白名单；不上传未授权二进制 |
| PUB-09 | GitHub 账号或 token 被接管 | P1 | GitHub CLI 使用高权限账号 `bingnnvjn` | MFA/passkey、细粒度 token、短期 token、最小 repo 权限、secret 不进仓库 |
| PUB-10 | 历史信息永久可见 | P2 | 多年提交和内部记录已存在 | 决定是否保留历史；公开前做历史扫描；敏感历史采用新历史 |
| PUB-11 | 公开仓库缺少安全响应入口 | P2 | 没有 `SECURITY.md`、CODEOWNERS | 添加安全报告方式、维护者、代码审查和发布责任 |

## 规避方案

### A. 发布边界

推荐拆成三个公开源码远端：

```text
<私有远端1>
<私有远端2>
<私有远端3>
```

根仓库继续保存工程上下文、`.scratch/`、ADR 和跨仓库路线图，先不公开。

不要把 `fable-bootstrap` 改造成 Rust 源码仓库；它是 Fable 包源和 bootstrap 发布仓库。

### B. 历史和密钥

1. 对每个准备公开的 Git 仓库执行完整历史扫描。
2. `fable-app` 若未来公开，先确认 `app/<上游测试签名材料>` 是否为可用私钥；若是，从所有 refs 中移除并轮换对应签名材料。若只是无效上游测试材料，也要在发布清单中明确记录其用途。
3. `fable-app/keystore/<旧签名材料>` 即使当前被忽略，也不得复制到发布目录。
4. 对 release keystore、GPG 私钥、GitHub token、Actions secret、签名证书执行独立轮换和保管。
5. 发布只使用白名单文件和已审计 commit，不使用工作树全量添加。

### C. CI 安全基线

每个公开 Rust crate 的 workflow 必须：

```yaml
on:
  pull_request:
  workflow_dispatch:

permissions:
  contents: read
```

门禁必须逐一执行：

```text
cargo fmt --all -- --check
cargo check --all-targets --all-features --locked
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-targets --all-features --locked
RUSTDOCFLAGS="-D warnings" cargo doc --all-features --no-deps --locked
cargo deny check
```

要求：

- 不使用 `pull_request_target` 检出并执行 PR 代码；
- PR job 不读取发布、GPG、Android signing 或包仓库 secret；
- 第三方 Action 固定完整 commit SHA；
- 使用 GitHub-hosted runner，不使用连接内部网络的 self-hosted runner；
- workflow 文件变更本身必须触发审查；
- toolchain、cargo-deny、audit 数据库和例外台账有受控升级流程；
- artifact 只保存测试结果，不保存 keystore、环境变量 dump 或完整工作目录。

### D. GitHub 仓库设置

每个公开 Rust 仓库至少配置：

- default branch protection/ruleset；
- required status checks；
- required independent Agent review；
- stale approval dismiss；
- latest push requires re-review；
- CODEOWNERS 审查 workflow、Cargo.lock、deny.toml、FFI 模块；
- 禁止管理员绕过，或至少把绕过记录到例外台账；
- secret scanning 和 push protection；
- Dependabot Cargo 更新；
- `SECURITY.md`；
- release tag 和 artifact 权限限制。

### E. 许可证和公开资料

发布前建立两张清单：

1. `发布文件清单`：源码、Cargo manifest、锁文件、许可证、必要文档和 CI。
2. `禁止发布清单`：`.scratch`、`.crew`、设备日志、keystore、GPG 私钥、内部路径、下载缓存、APK、字体二进制和未授权第三方资产。

每个 crate 顶层补充：

- 明确许可证；
- README 的构建、平台和安全边界；
- vendor/third_party 的来源和许可证；
- `SECURITY.md`；
- `CODEOWNERS`；
- 公开 issue/PR 的贡献规则。

## 详细实施流程

### 阶段 0：冻结

- 不创建公开远端。
- 不推送任何 branch/tag。
- 不把 `fable-app` 的 upstream remote 改名或覆盖。
- 记录当前 refs、HEAD、工作树状态和允许发布范围。

### 阶段 1：清点

- 为三个 Rust crate 生成文件清单、Git refs 清单、Cargo.lock 清单和第三方来源清单。
- 对 `fable-app` 单独标记签名材料和历史。
- 明确哪些资料属于工程上下文，哪些属于公开源码。

### 阶段 2：密钥和历史

- 安装并固定 secret scanner 版本。
- 扫描工作树、索引、所有 branch/tag 和 Git 对象。
- 对发现的真实密钥执行撤销/轮换，不把“删除文件”当作修复。
- 需要保留历史时，先做历史重写并在本地 clone 验证；不需要保留历史时，使用干净导出。

### 阶段 3：公开源码导出

- 从每个独立 Rust 仓库制作干净发布分支或新仓库。
- 只复制允许发布清单。
- 保留 Cargo.lock、许可证、vendor provenance 和安全文档。
- 运行 `git diff --check`、secret scanner、许可证检查和依赖来源检查。

### 阶段 4：CI 和分支保护

- 新增安全 workflow。
- 用本地等价脚本验证每个 job 的失败路径。
- 先创建临时 private 仓库，推送已审计 refs，运行完整 CI 和故意失败 PR。
- 检查 Actions 日志、artifact、Release、依赖图、secret scanning 和 fork 行为。
- 预演通过后创建空的 public 仓库；不要把未审计的工作树直接从 private 仓库转换为 public。
- 运行一个故意失败的 PR，证明 required check 会阻止合并。
- 运行一个故意修改 workflow 的 PR，证明 CODEOWNERS/独立审查会介入。

### 阶段 5：公开前验收

- 验证仓库首页、默认分支、历史、release、Actions 日志和 artifact 不含敏感内容。
- 验证匿名访问看到的内容与发布清单一致。
- 验证 fork/PR workflow 不会读 secret 或访问内部网络。
- 验证安全报告入口和维护者责任明确。

### 阶段 6：公开后监测

- 开启 secret scanning、push protection、Dependabot。
- 每周审查依赖、Action SHA、供应链例外和外部 PR。
- 每季度复核仓库成员、PAT、Actions secret、branch ruleset 和 CODEOWNERS。
- 发生泄露时先撤销/轮换，再处理 Git 历史和公告。

## 验收标准

以下任一项失败，禁止公开推送：

- [ ] 三个 Rust crate 的公开边界已确认，根 `.scratch` 未误入。
- [ ] `fable-app` 的签名材料已从发布范围和公开历史排除。
- [ ] 工作树、索引、全部 refs 和 Git 对象通过 secret scanner。
- [ ] 所有第三方许可证、vendor 来源和字体资产均有记录。
- [ ] 每个 crate 有顶层许可证、README、`SECURITY.md` 和 `CODEOWNERS`。
- [ ] CI workflow 使用 `pull_request`、最小权限、无 PR secret、固定 Action SHA。
- [ ] fmt、Clippy、test、rustdoc、锁文件和 cargo-deny 均为 required checks。
- [ ] 独立 Agent review 覆盖 unsafe/FFI、API、错误、性能、并发和测试强度。
- [ ] 分支保护禁止或审计管理员绕过。
- [ ] 故意失败 PR 已证明门禁会阻断合并。
- [ ] 已在临时 private 仓库完成一次完整预演，再从同一份已审计 refs 创建 public 仓库。
- [ ] 公开仓库匿名检查通过。
- [ ] 泄露响应流程和责任人已记录。

## 一手资料

- GitHub Docs：Setting repository visibility
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility>
- GitHub Docs：About permissions and visibility of forks
  <https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/about-permissions-and-visibility-of-forks>
- GitHub Docs：About secret scanning
  <https://docs.github.com/en/code-security/secret-scanning/introduction/about-secret-scanning>
- GitHub Docs：About push protection
  <https://docs.github.com/en/code-security/secret-scanning/introduction/about-push-protection>
- GitHub Docs：Removing sensitive data from a repository
  <https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository>
- Git documentation：gitignore patterns
  <https://git-scm.com/docs/gitignore>
- GitHub Docs：About protected branches
  <https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches>
- GitHub Docs：About rulesets
  <https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets>
- GitHub Docs：Security hardening for GitHub Actions
  <https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions>
- GitHub Docs：Using secrets in GitHub Actions
  <https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions>
- GitHub Docs：Secure use reference
  <https://docs.github.com/en/actions/reference/security/secure-use>
- GitHub Docs：Hardening deployments and OIDC
  <https://docs.github.com/en/actions/deployment/security-hardening-your-deployments>
- GitHub Docs：About CODEOWNERS
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners>
- GitHub Docs：Dependency review
  <https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review>
- GitHub Docs：About Dependabot alerts
  <https://docs.github.com/en/code-security/dependabot/dependabot-alerts/about-dependabot-alerts>
- GitHub Docs：Artifact attestations
  <https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations>
- GitHub Docs：Licensing a repository
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/understanding-connections-between-repositories/licensing-a-repository>
- GitHub Docs：Adding a security policy
  <https://docs.github.com/en/code-security/getting-started/adding-a-security-policy-to-your-repository>
- GitHub Docs：Dependabot options reference
  <https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-dependency-updates>
- Cargo Reference：license and license-file fields
  <https://doc.rust-lang.org/cargo/reference/manifest.html#the-license-and-license-file-fields>
- Cargo Guide：Cargo.lock
  <https://doc.rust-lang.org/cargo/guide/cargo-toml-vs-cargo-lock.html>
- Git filter-repo 官方仓库
  <https://github.com/newren/git-filter-repo>
- Fable 本地 Rust 安全规则
  `.scratch/fable-v1/research-Rust严格安全编码规则.md`
- Fable 本地供应链策略
  `deny.toml`、`supply-chain-exceptions.toml`、`scripts/rust-supply-chain-gate.sh`

## 审计结论写回

公开发布不是当前工单 51 的简单“创建仓库并推送”步骤。它是独立的发布安全边界，需要在任何远端创建、历史推送和 CI required check 配置前完成本报告的验收清单。
