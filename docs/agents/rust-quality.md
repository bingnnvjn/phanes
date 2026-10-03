# Phanes Rust 严格质量规则

本文件是三个 Rust crate 的可执行规则摘要；完整安全边界和工具选择依据见
`.scratch/fable-v1/research-Rust严格安全编码规则.md`。工单 48 只建立本地基线和门禁，
不把尚未清零的历史告警标为通过。

## 适用范围

门禁逐一运行以下独立 crate，不把它们隐式合并为 Cargo workspace：

- `boo`
- `session`
- `renderer`

根目录 `rust-toolchain.toml` 固定 stable `1.97.1`，并要求 `rustfmt` 与 `clippy`
组件。升级必须单独提交：更新版本、在干净环境安装同一 profile、运行完整门禁、
记录新增/消失告警和 Android 构建影响，再合并版本变更。

## 本地门禁

从仓库根目录执行：

```bash
scripts/rust-quality-gate.sh
```

入口对每个 crate 依次执行以下五步；任一步失败，入口最终返回非零，并继续执行其余
步骤以保留完整基线：

```bash
cargo fmt --all -- --check
cargo check --all-targets --all-features --locked
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-targets --all-features --locked
RUSTDOCFLAGS="-D warnings" cargo doc --all-features --no-deps --locked
```

`Cargo.lock` 缺失会在该 crate 开始前直接失败。`--locked` 禁止门禁改写锁文件；
门禁不自动安装第三方工具，也不依赖 nightly、CI 或远端仓库。

### 宿主相关的例外：`renderer` 的 test

`renderer` 的产品目标是 `aarch64-linux-android`（ADR-0015）。它的测试二进制要链接
`libghostty/lib/arm64-v8a/libghostty-vt.a`——这是构建输入，按 ADR-0012 不入库，
只在 Android 宿主上现取。因此在非 Android 宿主（例如 CI 的
`x86_64-unknown-linux-gnu`）门禁只跑 `fmt`/`check`/`clippy`/`rustdoc`，**跳过**
`renderer` 的 `test`，并在输出里写明原因；Android 宿主（Termux 开发机）五项全跑。
`renderer/build.rs` 同样只对 Android 目标传 Android 专用编译/链接选项。

这条例外不能当通用豁免：改动 `renderer` 的链接行为、JNI 边界或 `unsafe` 时，
必须在 Android 宿主上跑完整门禁，再交付。

## 每个 crate 的提交要求

本节收敛自三个 crate 原先各自的 `CONTRIBUTING.md`（工单 67 删除；单仓库后由根级
`SECURITY.md` 与本文件统一承担）：

1. 三个 crate 各自独立，**不得**隐式合并成 Cargo workspace；`Cargo.lock` 随提交保留。
2. 改行为要说明原因并配重点测试或示例；提交前跑根级质量门禁与供应链门禁。
3. 涉及 JNI、FreeType、wgpu、原生窗口、PTY、进程或跨线程关闭的改动，必须写出
   安全不变量、ABI 假设、分配/释放与 Drop 顺序、描述符生命周期、线程亲和性、
   panic 行为与确定失败路径，并给聚焦的回归覆盖（见下节清单）。
4. 第三方来源、字体资产与它们的许可证/来源记录必须与改动同步更新。
5. 不提交凭据、签名材料、生成产物或内部 `.scratch`/设备材料。

## Lint 与例外

- 默认 rustc/Clippy warning 在 `-D warnings` 下阻断。
- 不整体启用 `clippy::restriction`、`nursery` 或 `cargo`；争议 lint 先报告，
  逐条结合模块不变量、性能和 ABI 需要评估。
- 生产路径默认不使用 `unwrap`、`expect`、`panic`；初始化不变量必须局部写出理由，
  并由 Agent 审查。
- 禁止新增无理由的全局 `allow`。必须保留例外时，优先使用：

  ```rust
  #[expect(clippy::some_lint, reason = "工单 NN：局部不变量；删除条件")]
  ```

  `#[expect]` 未触发时必须让门禁报错；裸 `#[allow]` 只可在有明确审查记录的测试、
  examples 或第三方兼容边界使用，且范围尽可能小。
- 不对 JNI/FreeType/wgpu crate 全局 `forbid(unsafe_code)`。新增 unsafe block 必须
  邻接可验证的 `// SAFETY:` 前提；公共 `unsafe fn`/trait/impl 必须有 `# Safety`。

## Agent 审查责任

涉及 `unsafe`、JNI、FreeType、wgpu/native window、PTY 或跨线程关闭的改动，自动门禁
通过仍不充分。实施 Agent 和独立审查 Agent 必须逐项确认：

1. 指针有效性、对齐、初始化、边界；
2. 别名规则和 provenance；
3. `repr(C)`/ABI、整数宽度、调用约定；
4. 分配/释放、Java 引用、Drop 顺序；
5. `Send`/`Sync`、JNIEnv 线程亲和性和关闭竞态；
6. panic/unwind 不穿越 FFI；
7. OOM、设备丢失、PTY 退出、重复 destroy 的确定失败行为；
8. unsafe 是否被压缩到最小安全包装；
9. 回归、边界、关闭顺序测试是否能证明不变量。

这份人工清单不能由 Clippy、`cargo test` 或“能运行”替代。生产 required CI 和双重
Agent 审查协议由后续工单 51 在会话层/渲染器基线清零后启用；依赖供应链门禁由工单
52 处理。
