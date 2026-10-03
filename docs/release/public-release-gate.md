# Phanes 公开发布闸门

本文档和 `scripts/public-release-gate.sh` 是工单 55 建立的可重复本地闸门；
工单 63 按 ADR-0011 把它改写成**单一仓库**形态。闸门是 fail-closed：任何未完成
的审计不会被解释为“安全”或“可公开”。

## 冻结边界（2026-10-03 核实；2026-10-02 的条目沿用，新增条目已按当日事实更新）

- 公开目标是**一个完整仓库**（ADR-0011、ADR-0012）。工单 62 已把五段历史合并
  到五个角色目录：`android/`、`renderer/`、`session/`、`boo/`、`libghostty/`。
- ADR-0010 的“三个 Rust crate 各自独立公开”已作废。随之退役的还有三仓 allow-list
  与私有远端台账（`rust-public-boundary.*`、`rust-private-remotes.tsv`）及其闸门
  `scripts/rust-public-boundary-gate.sh`；它们描述的是不再存在的拓扑。
- 公开本身是独立的、不可逆的外部动作，执行前必须再次取得用户确认
  （ADR-0012 决定 1）。工单 65 先建**私有**仓库 `phanes` 并推送默认分支；按 ADR-0016，
  切换前又同名重建了一次以清掉 GitHub 上残留的旧对象。
- 仓库只有 `origin` = `phanes`（工单 65 建立，ADR-0016 同名重建）。闸门只接受这一个远端，
  地址必须精确等于 `https://github.com/bingnnvjn/phanes[.git]`；出现其它远端集合
  即判失败（“已存在未知远端”是需要重新确认的信号）。公开切换由工单 68 在用户确认后
  执行；切换前已复测旧 SHA 全部 404（实测于 2026-10-03）。
- 应用身份按 ADR-0013 冻结：`applicationId` = `com.gph.fable`、派生的 `$PREFIX`、
  JNI 符号前缀、签名身份与两个 `libfable-*.so` 文件名都不随项目改名变化。
- 签名材料台账与私有远端台账属于操作记录，按 ADR-0012 已从索引移除；仓库内不再
  保留这两个台账。
- 根 `.github/workflows/` 由工单 67 落库三个只读检查（`rust-quality`、
  `rust-supply-chain`、`public-release-gate`）：`permissions: contents: read`、
  第三方 Action 固定到完整 commit SHA、不读 secret、不上传 artifact。闸门校验这三个
  文件存在。

## 入库内容与禁止路径

入库 = 产品 + 设计 + 可复现的结论；操作记录与构建输入不入库（ADR-0012）。
`target/`、Gradle/NDK 产物、日志、APK/AAB、`.env`、`local.properties`、
`signing.properties`、keystore、私钥、APK 与工具链压缩包、bootstrap 归档、
`libghostty/lib/**`、Apple Color Emoji 与 Nerd Font 下载物均不属于发布输入；
构建输入由 `docs/build-inputs.md` 记录的脚本加 sha256 清单提供。

闸门会扫描工作树、索引、refs、reflog 与可达/不可达 Git 对象，且不输出匹配内容。

## 使用

```bash
SECRET_SCANNER_BIN=/path/to/gitleaks scripts/public-release-gate.sh
```

工具固定版本、来源和本机摘要见 `docs/release/public-release-tools.md`：
gitleaks `v8.29.0`、cargo-deny `0.20.2`。闸门会校验 gitleaks 版本，并调用
仓库内的 `scripts/rust-quality-gate.sh` 与 `scripts/rust-supply-chain-gate.sh`；
缺少工具或任一门禁失败都会阻断发布。失败是发布阻断证据，不能通过删掉报告或跳过
命令来“清零”。

gitleaks 使用 `docs/release/gitleaks-public-release.toml`，只排除明确不属于公开
输入的构建缓存；**`.scratch/` 自工单 67 起不再排除**（工程 journal 已脱敏并纳入
扫描）。crate 的禁止路径检查与 Git 对象 marker 扫描仍独立执行。

`cargo-deny` 每次运行都要从 `github.com` 刷新 RustSec advisory DB，本机到 GitHub 的
直连不稳定。设 `FABLE_GITHUB_MIRROR`（例如 `https://ghfast.top/`）可把这一步的 git
传输换成只读镜像；变量默认不设，判据与内容校验不变：

```bash
SECRET_SCANNER_BIN=/path/to/gitleaks \
CARGO_DENY=/path/to/cargo-deny \
FABLE_GITHUB_MIRROR=https://ghfast.top/ \
scripts/public-release-gate.sh
```

`renderer/` 携带的 JetBrains Mono 资产来源固定为 JetBrains Mono `v2.304` tag
commit `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`，并在资产旁提供
`JETBRAINS-MONO-LICENSE.txt` 和 SHA-256。闸门仍会检查该 notice 与
`THIRD_PARTY.md` 是否同步；任何未决标记仍是阻断项。完整第三方来源台账见
`docs/release/third-party-sources.md`。

签名材料按 ADR-0013 冻结在应用身份上，不得进入仓库或构建输入。旧
`<旧签名材料>` 已退役并轮换为仓库外的新 release key；若将来需要重新审计签名
边界，必须先恢复仓库外的可恢复副本，再在副本上评估，不得直接改写本仓库历史。

## 尚需自有远端的验收

工单 58 曾在三仓拓扑下完成自有 private remote 的创建、精确 `master`
refs 推送和私有预演；单仓库形态下这些远端降级为历史副本，其去留另行决定
（ADR-0011 决定 4）。以下 public 动作仍故意不在本地完成：

1. 把 `phanes` 切成 public（工单 68，用户确认后执行）、发布 branch/tag；
2. 配置 required checks、分支保护、CODEOWNERS 的实际审查者和管理员绕过审计；
3. 在 private rehearsal 之外运行故意失败 PR、匿名检查、artifact/日志检查和 fork
   行为检查；
4. 指定安全报告入口、凭据撤销/轮换责任人和泄露公告责任人。

CI 工作流已按 `pull_request`/`push`、`contents: read`、GitHub-hosted runner、
无发布 secret、第三方 Action 固定 commit SHA 落库（工单 67）。工单 68 在切成
public 后把质量、供应链与发布闸门配成 required checks，并让 GitHub 自带秘密扫描与
push protection 生效；当前账户计划对 private 仓库的分支保护 API 返回 403，这是 68
切换后要核对的首项。安全报告入口写在根 `SECURITY.md`
（`https://github.com/bingnnvjn/phanes/security/advisories/new`），明确这是个人项目、
不承诺响应时限，责任人为仓库所有者。

## 泄露响应最小流程

报告入口：根 [`SECURITY.md`](../../SECURITY.md) 指向 GitHub 的私密漏洞报告
（`https://github.com/bingnnvjn/phanes/security/advisories/new`）。**责任人：仓库所有者**；
这是个人项目，不承诺响应时限，也没有漏洞赏金。

1. 立即撤销/轮换受影响的 token、keystore、证书或 Actions secret；
2. 保存不含秘密值的时间线和受影响 refs；
3. 必要时在本地可恢复副本上做历史清理，并验证 fresh clone；
4. 评估公开克隆、artifact、缓存和 fork 的传播范围；
5. 由指定维护者发布公告，记录修复、复核和后续预防措施。

这份流程不能替代 GitHub 账户、密钥保管或法律/合规责任人的人工确认。
