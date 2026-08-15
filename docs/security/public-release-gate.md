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
- 当前三个 Rust 仓库无 remote；这是刻意的冻结状态，不是发布准备完成。

## 允许发布路径

权威 allow-list 已落盘在
`docs/security/rust-public-boundary.allowlist`，由
`scripts/rust-public-boundary-gate.sh` 在工作树索引和 fresh clone 上执行。发布
闸门只允许下列路径族进入三个 crate 的公开源码树：

| 仓库 | 允许路径 |
| --- | --- |
| `fable-boo` | `Cargo.toml`、`Cargo.lock`、`README.md`、`LICENSE`、`SECURITY.md`、`CONTRIBUTING.md`、`.github/CODEOWNERS`、`.github/dependabot.yml`、`build.rs`、`src/**`、`data/frames/**`、`.gitignore` |
| `spike-render` | 上述公共文件，加 `docs/**`、`examples/**`、`assets/JetBrainsMono-Regular.ttf`、`assets/NotoColorEmoji.ttf`、`assets/NOTO-EMOJI-LICENSE.txt`、`third_party/freetype/**` |
| `spike-session` | 上述公共文件，加 `build.rs`、`src/**`、`tests/**`、`vendor/portable-pty/**` |

`target/`、日志、APK/AAB、`.env`、`local.properties`、签名文件、keystore、
私钥和内部 `.scratch`/`.crew` 资料均禁止发布。闸门会同时检查工作树、
索引、refs、reflog 和可达/不可达 Git 对象；不会输出匹配内容。

## 使用

```bash
SECRET_SCANNER_BIN=/path/to/gitleaks scripts/public-release-gate.sh
```

当前环境（2026-08-15）没有安装 `gitleaks` 或 `cargo-deny`，
所以本地闸门应当失败。失败是发布阻断证据，不能通过删掉报告或跳过命令来
“清零”。闸门会调用根仓库现有的 `scripts/rust-quality-gate.sh` 和
`scripts/rust-supply-chain-gate.sh`；它们的失败会直接使发布闸门失败。

`spike-render` 的 JetBrains Mono 资产目前缺少可复核的来源 revision 和许可证
notice；公开闸门会把 `THIRD_PARTY.md` 中的未决标记当作阻断项。

闸门还会在不读取密码的情况下尝试分类
`fable-app/app/<上游测试签名材料>`。无法证明它不是可用私钥时，结果保持
阻断；不得把“当前构建脚本没有引用”当成密钥安全证明。`keystore/<旧签名材料>`
和 `release-missing-credentials.log` 即使被忽略，也始终属于发布范围外的
敏感材料。

## 尚需 Fable 自有远端的验收

以下动作故意不在本地完成：

1. 创建仓库、推送 branch/tag、启用 workflow；
2. 配置 required checks、分支保护、CODEOWNERS 的实际审查者和管理员绕过
   审计；
3. 在临时 private 仓库预演，然后从同一份已审计 refs 创建 public 仓库；
4. 运行故意失败 PR、匿名检查、artifact/日志检查和 fork 行为检查；
5. 指定安全报告入口、凭据撤销/轮换责任人和泄露公告责任人。

取得每个 Fable 自有远端及管理员权限后，才可按工单 51 接入 CI。CI 应使用
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
