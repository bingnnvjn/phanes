# 48 — Rust 严格质量基线与本地门禁

**What to build:** 为 Fable 的三个 Rust crate 固定可复现工具链，建立统一的本地质量门禁与严格规则基线；门禁必须逐 crate 执行格式、检查、严格 Clippy、测试和文档构建，记录当前失败基线与例外政策，但在会话层/渲染器基线清零前不把未解决历史问题伪装成通过。

**Blocked by:** None
Status: 已完成

## 验收清单

- [x] 固定 Rust stable/nightly 工具链及升级流程；同一提交在干净环境可重复得到相同质量结果
- [x] 一个本地入口逐一覆盖三个 crate 的 fmt、check、Clippy、test、rustdoc，使用锁文件且失败码可靠
- [x] 严格规则、生产路径例外、测试/examples 例外、`#[allow]`/`#[expect]` 纪律和 Agent 审查责任形成单一规则文档
- [x] 记录三个 crate 的格式与严格 Clippy 基线，明确由后续工单清零的项目，不新增全局静默 allow
- [x] 本地入口、现有 Android 构建关系和独立 crate 边界有程序化或脚本化验证

## Comments

2026-08-14 建单（Rust 严格安全编码门禁方向）。本单只建立工具链、入口和基线，不借机修改渲染/会话业务语义；生产级 required CI 要等 49/50 清零后由 51 启用。

2026-08-14 实施完成。

- 验证数据：新增根目录 `rust-toolchain.toml`（stable 1.97.1，minimal + rustfmt/clippy）、
  `scripts/rust-quality-gate.sh` 和统一规则摘要 `docs/agents/rust-quality.md`；入口逐一执行
  三 crate 的 fmt、check、严格 Clippy、test、rustdoc，共 15 步。复跑结果稳定为
  `check=3/3 PASS`、`test=3/3 PASS`、`fmt=3/3 FAIL`、`Clippy=1/3 PASS`、
  `rustdoc=1/3 PASS`，入口退出码 `1`；详见
  `.scratch/fable-v1/baseline/rust-quality-48-2026-08-14.md`。
- 踩坑与解法：三个 crate 不是 workspace 且 session/render 是独立子仓库，入口使用固定
  manifest 路径和各自锁文件逐一执行；Termux `/tmp` 不可写，验证采用直接捕获输出，没有
  生成额外日志。未运行 `cargo fmt` 写回，也未新增全局 `allow`，避免把历史问题伪装成通过。
- 结论写回：fable-boo 已达到严格 Clippy、测试和 rustdoc；session 的 JNI/代码风格 lint
  与文档链接、render 的 build-script lint 与文档链接/既存 rustdoc warnings 留给工单 49/50
  清零；required CI 和双重 Agent 审查仍由工单 51 承接，依赖供应链由工单 52 承接。本单
  未开始工单 49–54 的实现。

2026-08-14 收尾：代码审查完成；实现文件已提交为 `d9aaeba`（`chore(rust): add local quality baseline`）。
工单 48 验收项全部完成，Status 更新为 `已完成`。
