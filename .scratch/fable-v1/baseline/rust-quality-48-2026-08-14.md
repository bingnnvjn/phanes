# 工单 48：Rust 严格质量基线（2026-08-14）

## 范围

本次只建立固定工具链、逐 crate 本地门禁和规则/基线记录；未修改会话层、渲染器或
Android 业务语义，也未启用 required CI、nightly、cargo-deny 或其他工单 49–54 内容。

三个独立 crate 均有自己的 `Cargo.lock`，门禁通过根目录
`scripts/rust-quality-gate.sh` 逐一调用，不假定 Cargo workspace。

## 固定环境

| 项目 | 结果 |
| --- | --- |
| Rust | `rustc 1.97.1 (8bab26f4f 2026-07-14)` |
| Cargo | `cargo 1.97.1 (c980f4866 2026-06-30)` |
| 配置 | 根目录 `rust-toolchain.toml`，stable `1.97.1`，minimal + `rustfmt`/`clippy` |
| 日期 | 2026-08-14 |

门禁入口还会校验 `rustc` 和 `cargo` 的版本前缀；版本不符时以退出码 2 立即失败。

## 门禁结果

执行命令：

```bash
scripts/rust-quality-gate.sh
```

入口共执行 15 步（每个 crate：fmt、check、clippy、test、rustdoc），最终退出码为
`1`，因为存在历史基线失败；入口仍继续执行后续步骤，未将失败伪装成通过。

| crate | fmt | check | Clippy `-D warnings` | test | rustdoc `-D warnings` |
| --- | --- | --- | --- | --- | --- |
| `fable-boo` | FAIL（4 个文件） | PASS | PASS | PASS | PASS |
| `spike-session` | FAIL（9 个文件） | PASS | FAIL（既存 22 个 error 行；主要是 JNI deprecated、测试/代码风格 lint） | PASS | FAIL（`unresolved link to 0`） |
| `spike-render` | FAIL（27 个文件） | PASS | FAIL（build script 的 `clippy::ptr_arg`） | PASS | FAIL（`unresolved link to i`；另有既存 rustdoc warnings） |

fmt 失败是未格式化的既存代码；本工单不运行 `cargo fmt` 写回，避免把会话/渲染业务
改动混入基线。严格 Clippy 和 rustdoc 失败项交由工单 49（session）及工单 50
（renderer）清零；生产 required CI 由工单 51 在两者清零后启用。

## 规则与例外结论

- 统一规则摘要：[docs/agents/rust-quality.md]($HOME/CODEX/Fable/docs/agents/rust-quality.md)；
  安全边界完整依据仍是 `.scratch/fable-v1/research-Rust严格安全编码规则.md`。
- 默认 warning 在 `-D warnings` 下阻断；不整体启用 `clippy::restriction`、
  `nursery` 或 `cargo`。
- 禁止新增无理由全局 `#[allow]`；局部例外优先 `#[expect(..., reason = "...")]`，
  并保留删除条件。unsafe/FFI 仍需逐块 `SAFETY:`、公共 API `# Safety` 和 Agent
  九项审查，不能由自动门禁替代。
- 每个 crate 仍独立执行锁文件检查；依赖供应链策略留给工单 52。
