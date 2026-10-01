# 52 — Rust 依赖供应链门禁

**What to build:** 为三个 Rust crate 建立统一、可审计的依赖漏洞、许可证、来源、重复版本与本地 patch 策略，并接入 Cargo 依赖更新提醒；依赖策略失败必须阻断合并或有明确、可追踪例外。

**Blocked by:** fable-v1/48
Status: 待验收

## 验收清单

- [x] RustSec 公告、许可证、来源和依赖策略有程序化检查
- [x] 本地 patch、path/git 来源、允许许可证和例外均有理由、所有者和复核日期
- [x] 三个独立锁文件均被检查，CI 不会隐式改写依赖解析
- [ ] Cargo 依赖更新会产生可审查变更，并经过 Rust 门禁与 Android 构建验证
- [x] 工具报告区分“已知公告/策略”与“本项目 JNI/FFI 安全”，不作错误安全承诺

## Comments

2026-08-14 建单。最终采用统一供应链门禁而非重叠的重复告警；`portable-pty` 本地 patch 必须作为显式审查对象保留。

2026-08-15 启动核验：工单 48 已完成；三个 crate 仍为独立仓库/锁文件，`spike-session`
存在唯一的本地 `portable-pty 0.9.0` path patch。`fable-app` 当前仍指向 Termux 上游，
而三个 Rust 仓库均未配置 Fable 自有远端；本单不会向该上游写入 Dependabot 或 CI。

2026-08-15 实施完成，待验收。

- 验证数据：新增 `deny.toml`、`supply-chain-exceptions.toml` 与
  `scripts/rust-supply-chain-gate.sh`。以 `cargo-deny 0.20.2` 运行入口，三个独立
  crate 共 7 步均 PASS；每个 `cargo metadata` 与 `cargo-deny` 调用均使用
  `--locked --all-features`，且单独比对调用前后锁文件 SHA-256。`bash
  scripts/build-session-lib.sh && (cd fable-app && ./build-and-verify.sh debug)` 通过：
  arm64 APK 包含 `libfable-session.so`，`com.gph.fable` badging 与 apksigner 均通过。
  完整 Rust 质量入口跑得 15 步、2 失败：`fable-boo` 既有 fmt 漂移，以及
  `spike-session` 的 `portable_pty_roundtrip` 在当前 80×24 终端环境断言 40×10 失败；
  本单未修改这两处业务/测试基线。
- 踩坑与解法：三个独立依赖图不能把某一 crate 专属的例外当作其他 crate 的错误；
  统一 `deny.toml` 对非本图的 skip 静默，但脚本要求许可证、重复版本、公告与来源台账和
  策略配置完全一致，并在受影响图上把“公告例外不再命中”升格为失败。RustSec 发现
  `ttf-parser 0.25.1`（RUSTSEC-2026-0192）与 `rustybuzz 0.20.1`
  （RUSTSEC-2026-0206）无维护且无安全升级路径，均已登记所有者、理由和
  2026-09-15 复核日；不是安全通过。`cargo-deny` 不在默认 PATH，入口允许显式
  `CARGO_DENY` 指向固定版本工具。
- 结论写回：三个 crate 各有每周 Dependabot Cargo 配置；只有推送至 Fable 自有远端才会
  生效。当前不得修改仍指向 Termux 上游的 `fable-app`。工单 51 恢复时应把供应链入口和
  Rust 质量入口设为 required checks，并让 Dependabot PR 运行 Android 构建；在
  `fable-boo` 格式与 session PTY 环境测试基线解决前，该更新流不能宣称“完整 Rust
  门禁全绿”。本门禁只报告已知公告/依赖策略，JNI/FFI 安全仍由
  `docs/agents/rust-quality.md` 的独立审查负责。

2026-10-02 复核并续期两项公告例外（工单 63 移交）：

1. **触发**：工单 63 复跑公开发布闸门得到 `48 checks, 1 failure`，唯一失败是本单
   2026-08-15 设定的 `review-by = 2026-09-15` 已过期（1 个月窗口到点）。
2. **复核数据（2026-10-02）**：
   - 本地 RustSec advisory DB（`~/.cargo/advisory-dbs/advisory-db-3157b0e258782691`）：
     `RUSTSEC-2026-0206`（rustybuzz，2026-07-11 登记，`informational = "unmaintained"`）
     与 `RUSTSEC-2026-0192`（ttf-parser，2026-06-28 登记，同为 `unmaintained`）。
     **两条都记 `patched = []`**，即不存在修复版本。
   - crates.io 现状：`rustybuzz` 最新 = `0.20.1`（就是当前锁定值）；`fontdue` 最新 =
     `0.9.4`（当前值）；`ttf-parser` 最新 = `0.25.1`（当前值）。**不存在可升级路径。**
   - `cargo tree -i ttf-parser`：由 `fontdue 0.9.4` 与 `rustybuzz 0.20.1` 各引入一次，
     两者都只在 `renderer` 图里。
   - 公告给出的替代：rustybuzz → `harfrust`（现 `0.13.3`，Harfbuzz 项目维护）；
     ttf-parser → `skrifa`（Google fontations）。
3. **处置**：续期到 **2026-11-30**（`reviewed-on = 2026-10-02`），理由字段写入本次复核
   事实与退出条件。台账（`supply-chain-exceptions.toml`）与
   `docs/agents/rust-supply-chain.md` 在本单同一变更中同步——本单对“新增或延长公告
   例外”的要求。
4. **退出条件（真正修法，不在本单范围）**：
   - `rustybuzz` → `harfrust`：同源 fork，改动面小，但必须重跑渲染器的整形与
     emoji/ZWJ 回归（工单 13/22 的断言集）。
   - `fontdue` → 脱离 `ttf-parser`：渲染器已经链接 FreeType（彩色字形 + 光栅），灰度
     正文可评估改走 FreeType 或 `swash`；这是字体栈迁移，需要独立工单 + 真机回归。
   迁移完成前，本例外继续按 2026-11-30 到期。
5. **复跑**：`CARGO_DENY=… scripts/rust-supply-chain-gate.sh` → **7 steps, 0 failed**；
   `SECRET_SCANNER_BIN=… scripts/public-release-gate.sh` → **48 checks, 0 failures**。
