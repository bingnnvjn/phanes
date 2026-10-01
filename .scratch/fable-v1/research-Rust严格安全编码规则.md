# Rust 严格安全编码规则（Fable 调研草案）

日期：2026-08-14  
范围：`spike-render`（JNI + FreeType + wgpu/Vulkan）、`spike-session`（JNI + PTY）、`fable-boo`（纯 Rust 工具/基准）。

## 结论先行

Fable 可以把 Rust 代码下限显著提高，但不存在一个工具能证明“写法绝对最好”。可执行的最高强度方案是分层门禁：

1. **每个 PR 必须通过**：固定工具链、rustfmt、rustc/Clippy 严格 lint、全目标测试、文档构建、锁文件和依赖策略。
2. **每个涉及 unsafe/FFI 的 PR 必须人工/独立 Agent 通过**：按 Rust Reference、Rustonomicon、Unsafe Code Guidelines、API Guidelines 逐项审查不变量。
3. **定期专项**：Miri、`cargo fuzz`、突变测试、Loom、Android arm64 HWASan/ASan。

“能编译、能运行”不是通过条件；通过条件是：语言未定义行为（UB）被排除、FFI 合约明确、错误和资源生命周期可证明、测试能杀死错误变体、例外有理由且可追踪。

## 一手资料与交叉验证

### Rust 语言与未定义行为

- Rust Reference：行为被视为未定义  
  <https://doc.rust-lang.org/reference/behavior-considered-undefined.html>
- Rust Reference：不安全操作与 `unsafe`  
  <https://doc.rust-lang.org/reference/unsafety.html>
- Rust Reference：类型布局、`repr(C)`、`repr(transparent)`  
  <https://doc.rust-lang.org/reference/type-layout.html>
- Rust Reference：外部块、ABI 与 FFI  
  <https://doc.rust-lang.org/reference/items/external-blocks.html>
- Rust Reference：函数 ABI、展开（unwind）  
  <https://doc.rust-lang.org/reference/items/functions.html#abi>
- Rustonomicon（官方不安全 Rust 手册）：Working with Unsafe、Ownership、Send/Sync、FFI、Unwinding  
  <https://doc.rust-lang.org/nomicon/>
  <https://doc.rust-lang.org/nomicon/working-with-unsafe.html>
  <https://doc.rust-lang.org/nomicon/ffi.html>
- Unsafe Code Guidelines 项目（Rust 社区 WG，部分内容仍属讨论性参考，不替代 Reference）  
  <https://rust-lang.github.io/unsafe-code-guidelines/>
  <https://github.com/rust-lang/unsafe-code-guidelines>

交叉结论：Reference 是语言边界；Nomicon 将边界转成不变量和封装方法；UCG 提供别名、有效性、指针来源等讨论材料。审查文档必须优先引用 Reference/Nomicon，并标明 UCG 尚未全部定稿。

### Rust 2024 不安全规则

- `unsafe fn` 中的每个不安全操作必须位于显式 `unsafe {}`  
  <https://doc.rust-lang.org/edition-guide/rust-2024/unsafe-op-in-unsafe-fn.html>
- `extern` 块必须标记为 `unsafe`，可逐项声明安全级别  
  <https://doc.rust-lang.org/edition-guide/rust-2024/unsafe-extern.html>
- `no_mangle`、`export_name`、`link_section` 等不安全属性必须显式标记  
  <https://doc.rust-lang.org/edition-guide/rust-2024/unsafe-attributes.html>

规则：若依赖和 Android 工具链兼容，迁移至 edition 2024；至少启用 `unsafe_op_in_unsafe_fn`，并审查所有 unsafe extern 项和导出属性。

### Lint、格式与 API 规范

- Clippy CI 官方建议  
  <https://doc.rust-lang.org/clippy/continuous_integration/index.html>
- Clippy lint 清单与分组  
  <https://doc.rust-lang.org/clippy/lints.html>
  <https://rust-lang.github.io/rust-clippy/master/index.html#restriction>
- Clippy 配置  
  <https://doc.rust-lang.org/clippy/lint_configuration.html>
- rustc lint level 与覆盖规则  
  <https://doc.rust-lang.org/rustc/lints/levels.html>
  <https://doc.rust-lang.org/reference/attributes/diagnostics.html>
- Rust Style Guide / rustfmt  
  <https://doc.rust-lang.org/style-guide/>
  <https://doc.rust-lang.org/cargo/commands/cargo-fmt.html>
- Rust API Guidelines（Rust 项目维护的社区规范清单）  
  <https://rust-lang.github.io/api-guidelines/>
- rustdoc lint 与文档手册  
  <https://doc.rust-lang.org/rustdoc/lints.html>
  <https://doc.rust-lang.org/rustdoc/>
- Rust Design Patterns（社区维护，作为 Agent 评审参考，不是编译器规范）  
  <https://rust-unofficial.github.io/patterns/>

交叉结论：`-D warnings` 只会把已经触发的 lint 变成错误；它不是“最佳设计证明”。Clippy 官方明确不建议整体启用 `restriction`，因为该组包含主观、互相冲突或不适合所有项目的规则。应启用默认规则并逐条选择高价值限制项。

### Cargo、可复现构建与测试

- `cargo test`（测试目标、文档测试、`--all-targets`）  
  <https://doc.rust-lang.org/cargo/commands/cargo-test.html>
- `cargo check`  
  <https://doc.rust-lang.org/cargo/commands/cargo-check.html>
- `cargo doc`  
  <https://doc.rust-lang.org/cargo/commands/cargo-doc.html>
- 依赖与 `Cargo.lock`  
  <https://doc.rust-lang.org/cargo/guide/cargo-toml-vs-cargo-lock.html>
- Cargo 的 `--locked`、`--frozen`、`--offline`  
  <https://doc.rust-lang.org/cargo/commands/cargo.html>

规则：CI 使用 `--locked`，禁止自动改写锁文件；发布/审计 job 可使用 `--frozen` 验证不访问网络的可复现构建。三个独立 crate 必须逐一运行检查。

### 依赖、供应链与漏洞

- RustSec Advisory Database  
  <https://rustsec.org/>
  <https://github.com/RustSec/advisory-db>
- `cargo-audit`（RustSec 官方项目工具）  
  <https://github.com/RustSec/cargo-audit>
- `cargo-deny`（Embark Studios，一手项目文档）  
  <https://github.com/EmbarkStudios/cargo-deny>
  <https://embarkstudios.github.io/cargo-deny/>
- `cargo-geiger`（unsafe 暴露面报告）  
  <https://github.com/geiger-rs/cargo-geiger>
- `cargo-vet`（依赖审查证明）  
  <https://github.com/mozilla/cargo-vet>
- GitHub Dependabot Cargo 配置  
  <https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference>

交叉结论：`cargo-audit` 主要查 RustSec 已知公告；`cargo-deny` 还能统一许可证、来源和重复依赖策略；二者都不能发现 Fable 自己的 JNI/FFI UB。Fable 最终选 `cargo-deny` 作为统一策略门禁，同时保留 RustSec 数据源；Dependabot 负责提出更新 PR，不能代替审查。

### 动态、并发与输入安全工具

- Miri  
  <https://github.com/rust-lang/miri>
  <https://rust-lang.github.io/miri/>
- Rust Sanitizers（ASan、HWASan、LSan、MSan、TSan）  
  <https://doc.rust-lang.org/unstable-book/compiler-flags/sanitizer.html>
- Rust Fuzz Book / `cargo-fuzz`  
  <https://rust-fuzz.github.io/book/cargo-fuzz.html>
  <https://github.com/rust-fuzz/cargo-fuzz>
- Kani model checker  
  <https://github.com/model-checking/kani>
  <https://model-checking.github.io/kani/>
- Loom 并发模型测试（Tokio 社区）  
  <https://github.com/tokio-rs/loom>
- `cargo-mutants` 突变测试  
  <https://mutants.rs/>
  <https://github.com/sourcefrog/cargo-mutants>

适用边界：Miri/Kani/Loom 只应接入可脱离 Android/JNI/GPU 的纯 Rust 模型；fuzz 重点放在终端字节、Unicode、快照和协议解析；HWASan/ASan 用真机覆盖 JNI、FreeType、native window、线程关闭和图集缓冲区。

### 成熟项目实践（用于 Agent 评审而非照搬）

- Rust 编译器贡献与诊断开发资料：<https://rustc-dev-guide.rust-lang.org/diagnostics.html>
- Firefox `cargo-vet` 供应链审查：<https://github.com/mozilla/cargo-vet>
- Tokio Loom 并发模型：<https://github.com/tokio-rs/loom>
- Rust FFI Omnibus（字符串、回调、opaque pointer、panic 边界示例；社区实践，非规范）：<https://jakegoulding.com/rust-ffi-omnibus/>

这些项目共同体现：unsafe 必须缩小并写出不变量；依赖要有审查留痕；并发正确性要用模型或压力测试；公共 API 要以文档和可预测错误为契约。

## Fable 的分层规则

### A. PR 自动阻断（每个 crate）

固定 `rust-toolchain.toml`（版本由仓库维护者升级并单独评估），然后执行：

```bash
cargo fmt --all -- --check
cargo check --all-targets --all-features --locked
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-targets --all-features --locked
RUSTDOCFLAGS="-D warnings" cargo doc --all-features --no-deps --locked
cargo deny check
```

建议在 crate 根部逐步采用：

```rust
#![deny(unsafe_op_in_unsafe_fn)]
#![deny(clippy::undocumented_unsafe_blocks)]
#![deny(clippy::missing_safety_doc)]
#![deny(rustdoc::broken_intra_doc_links)]
#![deny(rustdoc::private_intra_doc_links)]
#![deny(improper_ctypes)]
#![deny(improper_ctypes_definitions)]
#![deny(ffi_unwind_calls)]
#![deny(clippy::todo, clippy::unimplemented, clippy::dbg_macro)]
```

生产路径默认禁止 `unwrap`、`expect`、`panic`；允许的初始化不变量必须局部写理由并经 Agent 审查。测试代码可通过目录级配置显式放宽，但不能无理由全局 `allow`。

lint 例外政策：

- 禁止裸 `#[allow(...)]`。
- 优先使用 `#[expect(lint, reason = "工单号：原因；删除条件")]`；期望未触发时必须让 CI 报错，防止死代码例外。
- `forbid` 只用于真正全局且不需要例外的规则；不要对含 JNI/FreeType/wgpu 的 crate 全局 `forbid(unsafe_code)`。
- `clippy::restriction`、`clippy::nursery`、`clippy::cargo` 不整组强制；先报告，逐条升级。

### B. 每个 unsafe/FFI 改动的 Agent 阻断审查

审查者必须逐个回答：

1. **Validity**：指针是否非空、对齐、初始化、满足大小和有效范围？切片长度是否来自可信边界？
2. **Aliasing / provenance**：是否同时存在违反别名规则的可变引用？裸指针来源和使用期间是否仍有效？
3. **Layout / ABI**：跨 Kotlin/JNI/C/FreeType/wgpu 的结构是否有正确 `repr(C)` 或 `repr(transparent)`？整数宽度、布尔值、枚举表示和调用约定是否明确？
4. **Ownership**：谁分配、谁释放、是否重复释放、Java 全局/弱引用何时删除、Drop 是否可能晚于 VM/窗口？
5. **Threading**：`Send`/`Sync` 是否有可证明的不变量？JNI `JNIEnv` 和 Java 对象是否只在线程允许的上下文使用？关闭、回调、mailbox 是否存在竞态？
6. **Panic / unwind**：Rust panic 是否可能穿过 `extern "C"`/JNI 边界？是否转换为错误、状态码或捕获后记录？
7. **Failure behavior**：内存不足、字体解码失败、GPU device lost、PTY 退出、重复 destroy 是否有确定结果？
8. **Minimality**：unsafe 是否被压缩到最小块，并由安全 API 包装？是否有更简单的安全 Rust 写法？
9. **Tests**：是否有能失败的回归测试、边界测试、关闭顺序测试；测试是否会被突变测试杀死？

每个 unsafe block 相邻必须有 `// SAFETY:`，内容是可验证前提，不得写“这里应该安全”。公共 `unsafe fn`/trait/impl 的文档必须有 `# Safety`，说明调用者责任。

### C. 定期专项（不作为普通 PR 唯一阻断）

- Miri：renderer token、mailbox、session closed 状态机、缓冲区/文本解析的纯 Rust 测试。
- `cargo fuzz`：PTY 字节流、ANSI/Unicode、快照解码、字体元数据；crash corpus 转普通回归测试。
- Loom：只对 mailbox/registry 的最小并发模型建模，限制状态空间。
- `cargo-mutants`：每周或合并队列预算运行；突变存活即说明测试不足。
- Android arm64 HWASan/ASan：真机跑 renderer reset/destroy、JNI callback、FreeType、native window、GPU buffer 和四会话压力；第三方未插桩库的��盖盲区必须记录。
- `cargo-geiger`：报告本项目及传递依赖 unsafe 暴露面，不以 unsafe 行数作为单独失败条件。
- `cargo-vet`：依赖升级时维护审查证据；与 `cargo-deny` 的许可证/来源策略互补。

## Clippy 严格配置：采纳与拒绝

### 采纳（默认阻断）

`-D warnings` 下的默认 Clippy；另外逐条阻断：

- `undocumented_unsafe_blocks`、`missing_safety_doc`、`unsafe_op_in_unsafe_fn`
- `todo`、`unimplemented`、`dbg_macro`
- 生产路径的 `unwrap_used`、`expect_used`、`panic`
- 与 FFI 直接相关的 `improper_ctypes`、`improper_ctypes_definitions`、`ffi_unwind_calls`
- 文档链接和公开 API 文档 lint

### 先报告再升级

`clippy::pedantic`、`clippy::nursery`、`clippy::cargo`，以及 restriction 中的 `indexing_slicing`、`as_conversions`、`cast_possible_truncation`、`arithmetic_side_effects`、`string_slice`。每项必须结合模块不变量决定：

- 渲染热路径可使用受控整数转换，但必须先检查范围并写理由；
- JNI/FreeType ABI 转换可使用显式 `as`，但要求目标宽度、符号和溢出策略；
- 解析器可以用索引，但索引必须由边界检查或迭代器保证；
- 不应为消灭 lint 引入过度复杂的包装层或不透明宏。

拒绝“全开 restriction”：官方文档指出该组主观且可能冲突；这会逼出反惯用代码，降低而不是提高安全下限。

## Fable 专项映射

| 边界 | 必须证明的内容 | 重点工具/规则 |
|---|---|---|
| JNI 导出、`JNIEnv`、Java callback | VM/线程亲和性、引用生命周期、签名、异常清理、关闭后不回调 | Rust Reference FFI、Nomicon FFI、unsafe lint、HWASan、Agent checklist |
| renderer token/registry | token 唯一性、Arc 所有权、重复 destroy、reset 与 write 的竞态 | Miri、Loom/Kani、突变测试、43 回归测试 |
| FreeType/font bytes | buffer 生命周期、对齐、长度、face 销毁顺序、恶意字体输入 | SAFETY 注释、fuzz、HWASan、资源 Drop review |
| wgpu/Vulkan/native window | handle 所有权、线程限制、surface 生命周期、device lost | 真机测试、HWASan/ASan、Agent review；不能只靠 Clippy |
| PTY/session | 读取边界、关闭顺序、callback 背压、阻塞线程和错误传播 | fuzz、压力测试、Loom（抽象模型）、44 回归 |
| mailbox/输出批处理 | 有界内存、合并语义、不会丢终态/重复投递 | property tests、Loom/Kani、cargo-mutants |

## “更好的写法”如何变成可执行阻断

机器工具只能看到已编码的规则，不能判断所有架构选择。因此每个 Rust PR 还要附一份 Agent review：

- 先读 `CONTEXT.md`、相关 ADR/工单和本文件；
- 对照 API Guidelines：命名、标准 trait、错误类型、可预测 panic、资源 RAII、公共 API 文档；
- 对照 Design Patterns：优先迭代器/借用/类型状态/新类型，避免不必要 clone、字符串和布尔参数；
- 对照 Nomicon/Reference：unsafe 的每个前提都要落到代码或测试；
- 对照 Fable 边界表：JNI/FreeType/wgpu/PTY 的所有权和线程模型不能凭“经验”猜；
- 发现更惯用或更简单方案时阻断，并要求说明性能、兼容性或 ABI 理由；若保留复杂写法，必须有 ADR/工单记录。

## 分阶段落地

### 阶段 0：基线（先完成）

固定 nightly/stable 工具链；清零现有 `fmt` 和 `clippy -D warnings`；补齐 `cargo test/doc --locked`；建立 CI required checks。不要在基线未清零时同时引入几十条争议 lint。

### 阶段 1：安全边界

迁移 edition 2024（若 Android NDK/依赖兼容）；启用 unsafe、FFI、rustdoc lint；把 JNI、FreeType、native window 的 unsafe 集中到少数模块；每个 block 补 `SAFETY:`，每个公共 unsafe API 补 `# Safety`。

### 阶段 2：供应链

提交 `deny.toml`：RustSec advisories、允许许可证、禁止未知 git/path 来源、重复依赖策略；启用 Dependabot Cargo；依赖升级必须跑三个 crate 的完整门禁和 Android 构建。

### 阶段 3：专项验证

抽出纯 Rust 状态机后接 Miri/Loom/Kani；为终端输入和快照建立 fuzz targets；加入突变测试评估测试有效性；每周或发布前跑 Android arm64 HWASan/ASan 压力矩阵。

### 阶段 4：持续治理

每季度复核 Clippy 例外、`cargo-deny` allowlist、unsafe 暴露面和 Agent review 规则；工具链升级单独 PR，记录新增 lint、依赖公告和基准变化。

## 明确拒绝的过度规则

1. **不整体启用 `clippy::restriction`**：官方明确不建议；会产生主观冲突和反惯用代码。
2. **不全 crate `forbid(unsafe_code)`**：JNI/FreeType/wgpu/native window 必须有少量 unsafe；正确目标是集中、最小化和可证明。
3. **不把 unsafe 行数或依赖 unsafe 数量当安全证明**：`cargo-geiger` 只能量化暴露面。
4. **不把 Miri、fuzz、HWASan 当作互相替代**：它们覆盖不同错误域，且 Android 外部库有盲区。
5. **不无条件禁止所有 `unwrap`、索引、显式转换或算术**：在已证明不变量、ABI 转换和热路径中可能合理；必须局部理由、测试和 Agent 审查。
6. **不把 `cargo audit` 通过解释为“无漏洞”**：它只覆盖 RustSec 已知公告。
7. **不让 Agent 只看“能运行”**：必须检查 API 设计、错误语义、资源生命周期、并发模型和安全不变量。

## 推荐的合并判定

任何一项失败都不能合并：

- 自动门禁失败；
- 新增 unsafe 没有逐块 `SAFETY:` 或公共 unsafe API 没有 `# Safety`；
- FFI ABI/所有权/线程/展开策略没有书面合约；
- 生产错误被 `unwrap`/`expect`/`panic` 吞掉且无例外理由；
- 测试无法证明修复，或突变测试显示关键错误可存活；
- 依赖策略、许可证、来源或 RustSec 公告未处理；
- Agent 发现存在更简单、更惯用且兼容的 Rust 写法，而 PR 没有记录理由。

该方案将“超级严格”落实为可重复的 compiler/lint/test 门禁，加上对 JNI、FreeType、wgpu、PTY 和并发生命周期的独立 Agent 证明；它提高代码下限，同时避免以互相冲突的规则逼出更差实现。
