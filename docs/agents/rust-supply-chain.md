# Phanes Rust 供应链门禁

工单 52 为 `boo`、`session`、`renderer` 建立一个供应链策略入口（包名自工单 63 起用角色名）：

```bash
CARGO_DENY=/absolute/path/to/cargo-deny scripts/rust-supply-chain-gate.sh
```

`cargo-deny` 会从 `github.com` 刷新 RustSec advisory DB。直连 GitHub 不可达时设
`FABLE_GITHUB_MIRROR`（例如 `https://ghfast.top/`）把该 git 传输换成只读镜像；
默认不设，判据不变。

门禁固定 `cargo-deny 0.20.2`，逐 crate 以 `--locked --all-features` 运行 RustSec
公告、许可证、来源与重复版本检查。三个 crate 不是 Cargo workspace，故绝不把它们
合并解析，也不允许门禁改写任一 `Cargo.lock`。入口会在每次 `cargo metadata` 与
`cargo-deny` 调用前后分别比较锁文件 SHA-256（2026-08-15 核实）。

## 策略

- RustSec：拒绝已知漏洞、无维护、unsound 与 yanked 依赖。当前仅有两项无维护例外
  （2026-10-02 复核）：
  `RUSTSEC-2026-0192`（`ttf-parser 0.25.1`）与 `RUSTSEC-2026-0206`
  （`rustybuzz 0.20.1`）。两条公告都记 `patched = []`，且 crates.io 上
  `ttf-parser 0.25.1`、`fontdue 0.9.4`、`rustybuzz 0.20.1` 仍是各自最新版，
  所以**没有任何依赖升级能清掉它们**；这是有意识的例外，不是安全通过。
  它们在 `supply-chain-exceptions.toml` 记录影响、所有者、理由及 **2026-11-30**
  到期复核日。退出条件写在理由里：`rustybuzz` → `harfrust 0.13.3`；
  `ttf-parser` → 让渲染器字体栈不再经 `fontdue` 解析（FreeType 已在链接图里）。
  因为三个 crate 独立检查，入口只在受影响的 render 图上把“例外不再命中”
  升格为失败；新增或延长公告例外必须在同一变更中更新台账与工单。
- 许可证：允许 `0BSD`、`Apache-2.0`、`BSD-2-Clause`、`ISC`、`MIT`、
  `Unicode-3.0` 与 `Zlib`。这些是当前三份锁文件解析出的许可表达式所需的最小集合
  （2026-08-15 核实）；
  许可例外默认拒绝。每个允许 SPDX ID 的理由、所有者、最近复核日
  **2026-08-15** 与下次复核日 **2026-11-15** 均在
  `supply-chain-exceptions.toml`；新增许可证必须在同一变更中说明用途、所有者和
  复核日期。
- 来源：只允许 crates.io registry；未知 registry 与所有 git 依赖一律失败。直接
  path/git 来源必须逐项登记在 `supply-chain-exceptions.toml`，并由脚本验证。
- 重复版本：默认失败。`deny.toml` 中的八条 `skip` 仅是当前锁图中无法在不升级依赖的
  情况下合并的版本（2026-08-15 核实）；每条的依赖链理由、所有者和 **2026-11-15**
  复核日均由台账逐条记录并由脚本核对。因为同一策略分别运行在三个独立图上，未出现在
  某一图的 skip 会由入口静默，避免无关重复噪音；台账与配置不一致仍会失败。删除或升级
  相应依赖时必须删除已无效的例外。

## 本地 patch / path / git 台账

| 来源 | 用途与上游 | 所有者 | 最近复核 | 下次复核 |
| --- | --- | --- | --- | --- |
| `session/vendor/portable-pty` | crates.io `portable-pty 0.9.0`（WezTerm revision `f8921727…`）的 MIT 本地副本；只为精确保留登录 shell 的 `argv[0]` 语义而补 `CommandBuilder::argv0`。完整树 SHA-256 与路径由 `supply-chain-exceptions.toml` 和门禁校验。 | Phanes Rust maintainers | 2026-08-15 | 2026-11-15 |

`portable-pty` 不是“自动放行”的第三方代码：更改副本、来源、许可、哈希、所有者或
复核日期必须同时更新台账，并重新运行完整供应链门禁。当前没有 git 来源或许可证例外
（2026-08-15 核实）；两项 RustSec 无维护例外见上文与台账。

## 随提交同步的资产与来源

本节收敛自三个 crate 原先各自的 `CONTRIBUTING.md`（工单 67 删除）：

- 改动 `renderer/` 携带的字体或其他第三方资产时，必须同步更新资产旁的
  license/provenance 文件、SHA-256 与 `docs/release/third-party-sources.md`；
- 改动 `session/vendor/portable-pty` 时，必须同步 `supply-chain-exceptions.toml`
  里的来源 revision、许可与整树 SHA-256；
- 任何来源、许可、哈希、所有者或复核日期的变化都要求重跑完整供应链门禁。

## 依赖更新

原 crate 级 `.github/dependabot.yml` 指定每周 Cargo 更新提醒，已随工单 64 删除：
单仓库形态下 `.github/` 只在仓库根目录生效（ADR-0011）。根 `.github/workflows/`
已由工单 67 落库三个只读检查；要不要再加根级 dependabot 仍属独立工程决策。

依赖更新 PR 必须保留并审查 `Cargo.lock` diff，然后按下列顺序验证：

```bash
CARGO_DENY=/absolute/path/to/cargo-deny scripts/rust-supply-chain-gate.sh
scripts/rust-quality-gate.sh
bash scripts/build-native-libs.sh
(cd android && ./build-and-verify.sh debug)
```

当工单 51 的恢复条件满足（自有远端、分支保护与管理员权限）后，CI 必须把前两项
设为 required checks，并在依赖更新 PR 上执行 Android 构建验证。此策略报告仅涵盖
**已知公告与依赖策略**；JNI/FFI 安全仍必须按 `docs/agents/rust-quality.md` 的人工审查
清单独立确认。
