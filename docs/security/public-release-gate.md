# Fable 公开发布闸门

本文档和 `scripts/public-release-gate.sh` 是工单 55 的可重复本地闸门。
闸门是 fail-closed：任何未完成的审计不会被解释为“安全”或“可公开”。

## 冻结边界（2026-08-15 核实）

- 公开 Rust 源码目标是三个独立仓库：`fable-boo`、`spike-render`、
  `spike-session`。
- `fable-bootstrap` 继续作为包源/bootstrap 仓库，不承载 Rust 源码。
- Fable 根仓库保存 `CONTEXT.md`、ADR、`.scratch/`、`.crew/`、诊断和
  设备验收资料，不能整体公开。
- `fable-app` 的 `origin` 仍是 `https://github.com/termux/termux-app.git`；
  在另建 Fable 自有 fork、清理历史和确认发布边界前，不得向它写入 Fable
  workflow 或推送 Fable 分支。
- 2026-08-15 的预远端冻结状态是三个 Rust 仓库无 remote；这是当时刻意的冻结状态，
  不是发布准备完成。
- 2026-08-16 工单 58 已创建三个 Fable-owned private remote 并推送已审计 `master`
  refs；它们仍不是 public 发布。CI、分支保护和 public release 继续由工单 51/59
  处理，不能把 private rehearsal 解释为公开就绪。
- 本地 remote 名称与 fetch/push URL 固定在
  `docs/security/rust-private-remotes.tsv`；该台账仅验证本地配置，不能替代
  GitHub 所有权、权限或可见性的人工复核。

## 允许发布路径

权威 allow-list 已落盘在
`docs/security/rust-public-boundary.allowlist`，由
`scripts/rust-public-boundary-gate.sh` 在工作树索引和 fresh clone 上执行。发布
闸门只允许下列路径族进入三个 crate 的公开源码树：

| 仓库 | 允许路径 |
| --- | --- |
| `fable-boo` | `Cargo.toml`、`Cargo.lock`、`README.md`、`LICENSE`、`SECURITY.md`、`CONTRIBUTING.md`、`THIRD_PARTY.md`、`.github/CODEOWNERS`、`.github/dependabot.yml`、`build.rs`、`src/**`、`data/frames/**`、`.gitignore` |
| `spike-render` | 上述公共文件，加 `docs/**`、`examples/**`、`assets/JetBrainsMono-Regular.ttf`、`assets/JETBRAINS-MONO-LICENSE.txt`、`assets/NotoColorEmoji.ttf`、`assets/NOTO-EMOJI-LICENSE.txt`、`third_party/freetype/**` |
| `spike-session` | 上述公共文件，加 `build.rs`、`src/**`、`tests/**`、`vendor/portable-pty/**` |

`target/`、日志、APK/AAB、`.env`、`local.properties`、签名文件、keystore、
私钥和内部 `.scratch`/`.crew` 资料均禁止发布。闸门会同时检查工作树、
索引、refs、reflog 和可达/不可达 Git 对象；不会输出匹配内容。

## 使用

```bash
SECRET_SCANNER_BIN=/path/to/gitleaks scripts/public-release-gate.sh
```

工具固定版本、来源和本机摘要见 `docs/security/public-release-tools.md`：
gitleaks `v8.29.0`、cargo-deny `0.20.2`。闸门会校验 gitleaks 版本，并调用
根仓库现有的 `scripts/rust-quality-gate.sh` 和
`scripts/rust-supply-chain-gate.sh`；缺少工具或任一门禁失败都会阻断发布。
失败是发布阻断证据，不能通过删掉报告或跳过命令来“清零”。

gitleaks 使用 `docs/security/gitleaks-public-release.toml`，仅排除明确不属于
公开发布输入的内部资料和构建缓存；它会扫描工作树、可达 refs、reflog 与
不可达 blob。crate 的 Git allow-list、禁止路径以及 Git 对象 marker 扫描仍独立执行。

`spike-render` 的 JetBrains Mono 资产来源现已固定为 JetBrains Mono `v2.304`
tag commit `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`，并在资产旁提供
`JETBRAINS-MONO-LICENSE.txt` 和 SHA-256。公开闸门仍会检查该 notice 与
`THIRD_PARTY.md` 是否同步；任何未决标记仍是阻断项。

完整第三方来源台账见 `docs/security/third-party-sources.md`。
签名材料的路径、条目类型、隔离位置和历史策略见
`docs/security/signing-material-inventory.md`；该台账不含任何秘密值。

闸门还会在不读取密码的情况下尝试分类
`fable-app/app/<上游测试签名材料>`。该文件已确认是 Termux 上游共享测试 key，
不是 Fable release key。`fable-app` 的 `origin` 受 Termux upstream 保护，且整个
App 工作树不属于三个 Rust crate 的公开输入；因此该已知 test key 和旧
`keystore/<旧签名材料>` 的 upstream 历史不会阻断 Rust 发布闸门。

当前 `<旧签名材料>` 已退役并轮换为仓库外的新 Fable release key；旧 key 保留在受限
备份和受保护的本地 upstream 历史中。若将来创建或公开 Fable App fork，必须先以
可恢复副本为基础审计并清理该 fork 的签名材料历史。删除工作树文件不等于历史清理。

## 尚需 Fable 自有远端的验收

工单 58 已于 2026-08-16 完成三个 Fable-owned private remote 的创建、精确
`master` refs 推送和私有预演；以下 public/CI 动作仍故意不在本地完成：

1. 从同一份已审计 refs 创建 public 仓库、发布 branch/tag、启用 public workflow；
2. 配置 required checks、分支保护、CODEOWNERS 的实际审查者和管理员绕过
   审计；
3. 在 private rehearsal 之外运行故意失败 PR、匿名检查、artifact/日志检查和 fork
   行为检查；
4. 指定安全报告入口、凭据撤销/轮换责任人和泄露公告责任人。

已取得每个 Fable 自有远端及管理员权限，但当前账户计划对 private 仓库的分支保护
API 返回 403；工单 51 仍需解决计划/权限后再接入 CI。CI 应使用
`pull_request`、`contents: read`、GitHub-hosted runner、无 PR 发布 secret，
并将第三方 Action 固定到完整 commit SHA；质量、rustdoc、锁文件和供应链
门禁必须成为 required checks。

## 泄露响应最小流程

1. 立即撤销/轮换受影响的 token、keystore、证书或 Actions secret；
2. 保存不含秘密值的时间线和受影响 refs；
3. 必要时在本地可恢复副本上做历史清理，并验证 fresh clone；
4. 评估公开克隆、artifact、缓存和 fork 的传播范围；
5. 由指定维护者发布公告，记录修复、复核和后续预防措施。

这份流程不能替代 GitHub 账户、密钥保管或法律/合规责任人的人工确认。
