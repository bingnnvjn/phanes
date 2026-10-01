# 调研：Rust 安全审查与代码质量工具（2026-08-14）

> 用途：为 Fable 的 Rust 底层建立可执行的安全与代码合格性门槛。范围是 `spike-render`（渲染器，JNI + FreeType + wgpu/Vulkan）、`spike-session`（会话层，JNI + PTY）与 `fable-boo`（宿主侧基准/工具）。
>
> 方法：只使用 Rust 官方文档、各工具的官方文档或官方仓库。工具按“能发现什么”分类；它们互补，任何单一工具都不能证明 Rust、JNI 或 Android 原生代码绝对安全。
>
> 仓库现状（2026-08-14）：三个 crate 各自有 `Cargo.toml` 与 `Cargo.lock`，但不是一个 Cargo workspace；`spike-render`、`spike-session` 产出 `cdylib`，并跨越 JNI/FFI；现有 GitHub Actions 在 `fable-app/.github/workflows/`，尚未运行独立 Rust 质量检查。`fable-app/.github/dependabot.yml` 目前只更新 GitHub Actions。

## 结论速览

用户提到的“严格模式”通常指下面这条 Clippy 命令：

```bash
cargo clippy --all-targets --all-features -- -D warnings
```

其中 `-D warnings` 的含义是：把 Rust 编译器和 Clippy 已报告的警告升级为构建失败。它是很好的**代码合格性**门槛，但不是内存安全证明，也不会验证 JNI ABI、Java 生命周期、FreeType/wgpu 等外部库调用。

对 Fable 的优先级如下：

| 层级 | 工具 / 做法 | 对 Fable 的结论 |
| --- | --- | --- |
| PR 必过 | rustfmt、Clippy `-D warnings`、`cargo test`、依赖漏洞/许可证策略（`cargo-deny` 或 `cargo-audit` 二选一） | 应尽快建立。成本低，能先阻断格式漂移、常见 Rust 错误、测试回归和已知易受攻击依赖。 |
| PR 规则 | `unsafe` 边界政策：最小化、写安全不变量、启用针对 `unsafe` 的 lint | JNI、FreeType、Android native window、跨线程 renderer registry 都在这里；这是人工审查可落地的核心。 |
| 定时 / 合并队列 | Miri、`cargo fuzz`、Loom | 只对可脱离 Android 的纯 Rust 状态机、解析器、缓冲区和并发模型运行。 |
| 专用 Android 真机 | HWASan/ASan（优先 HWASan）、Android 集成/压力测试 | 对 JNI/FFI 内存错误最接近真实路径；构建与设备成本高，不应作为每个普通 PR 的唯一门槛。 |
| 评估后引入 | Kani、cargo-geiger、cargo-vet、Dependabot | Kani 适合很小的关键状态机；其余主要治理依赖或量化风险，不直接证明运行时正确性。 |

## 工具对比

### 1. rustfmt / `cargo fmt`：格式一致性

```bash
cargo fmt --manifest-path spike-render/Cargo.toml -- --check
```

- **检查内容**：只统一 Rust 源码格式；`--check` 只检查，不改文件，适合 CI。
- **稳定性**：Rust 官方 stable 工具，低成本。
- **CI 适合度**：每个 PR 必跑。
- **Android / JNI / FFI 适合度**：通用，但只能提高 diff 可读性，不能发现所有权、指针、ABI 或线程问题。
- **Fable 判断**：必须有，但它不是安全扫描。
- **官方来源**：<https://doc.rust-lang.org/cargo/commands/cargo-fmt.html>

### 2. Clippy：代码合格性与常见错误（“严格模式”的主体）

```bash
cargo clippy --manifest-path spike-session/Cargo.toml \
  --all-targets --all-features -- -D warnings
```

- **检查内容**：Clippy lint 覆盖可疑代码、常见 bug、惯用法、部分性能问题；`-D warnings` 同时会把 rustc warning 和 Clippy warning 变成失败。
- **稳定性**：随 stable Rust 发布；官方给出在 CI 中使用 `-D warnings` 的方式。新版本可能新增 lint，所以 CI 应固定 Rust 工具链版本，并以受控升级的方式更新。
- **CI 适合度**：每个 PR 必跑，低到中等成本。
- **Android / JNI / FFI 适合度**：能检查 JNI Rust 壳和普通 Rust 代码，但不能证明 Java 对象生命周期、JNI 签名、C ABI、外部库内存所有权或 GPU 驱动行为。
- **`pedantic` / `restriction`**：先不要整体阻断。官方说明 `clippy::pedantic` 有较强主观性；`clippy::restriction` 不建议整体启用。可先以报告模式试跑，逐条选择真正有价值的 lint。
- **Fable 判断**：这应是第一条“代码合格”硬门槛。先清理现有 warning，再开启阻断。
- **官方来源**：<https://doc.rust-lang.org/stable/clippy/usage.html>、<https://doc.rust-lang.org/stable/clippy/continuous_integration/index.html>、<https://doc.rust-lang.org/stable/clippy/lints.html>

### 3. `cargo test`：行为回归

```bash
cargo test --manifest-path spike-render/Cargo.toml --all-targets --all-features
```

- **检查内容**：编译并执行单元测试、集成测试和文档测试；`--all-targets` 会选择更多 target，适合检查 examples / 测试辅助程序是否仍可编译。
- **稳定性**：Rust 官方 stable 工具。
- **CI 适合度**：每个 PR 必跑；耗时取决于 native/GPU 依赖。
- **Android / JNI / FFI 适合度**：适合验证可抽出的纯 Rust 逻辑，例如 renderer 句柄状态机、mailbox 合并规则、会话输出批处理、文本/escape-sequence 解析。不能代替真机 JNI 回调、Vulkan 或 PTY 验收。
- **Fable 判断**：已有测试基础，应把新 bug 的可复现路径优先沉淀成 Rust 回归测试；真机测试仍作为另一层。
- **官方来源**：<https://doc.rust-lang.org/cargo/commands/cargo-test.html>

### 4. 编译器 / Clippy 的 `unsafe` 政策：审查 FFI 的第一道人工规则

这不是一个独立扫描器，但对 Fable 最重要。Rust 的 `unsafe` 只表示编译器把某些责任交给代码作者；它不保证没有未定义行为。

建议在每个 Rust crate 的根模块逐步采用：

```rust
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::missing_safety_doc)]
```

并在每个 `unsafe` block、`unsafe fn`、`unsafe impl Send/Sync` 和 JNI/FFI 导出边界写出：

1. 外部输入或指针的有效范围、对齐、NUL 终止和所有权；
2. Java / Kotlin、Rust、C 库中谁负责释放；
3. 线程亲和性和销毁顺序；
4. 失败、重复销毁、Java callback 已失效时的行为；
5. 哪个安全封装负责维持这些条件。

- **检查内容**：`unsafe_op_in_unsafe_fn` 阻止在 `unsafe fn` 内无标记地继续执行 `unsafe` 操作；`unsafe_code` lint 可禁止 crate 内出现 `unsafe` 相关结构。后者不能直接用于整个 Fable JNI crate，但可用于不需要 FFI 的模块或测试 crate。
- **稳定性 / CI**：编译器 lint 和 Clippy lint 都可在 stable CI 运行。
- **Android / JNI / FFI 适合度**：最高；它把“必须人工确认的安全条件”紧贴到 JNI、FreeType、Vulkan 和跨线程边界。
- **Fable 判断**：不要对 `spike-render`、`spike-session` 粗暴使用 `#![deny(unsafe_code)]`，否则会把必要的 FFI 一并禁止。应将 unsafe 收束到很小的模块，其他模块可 `deny(unsafe_code)`。
- **官方来源**：<https://doc.rust-lang.org/reference/behavior-considered-undefined.html>、<https://doc.rust-lang.org/stable/nightly-rustc/rustc_lint/builtin/static.UNSAFE_CODE.html>、<https://doc.rust-lang.org/stable/clippy/lints.html>

### 5. `cargo audit`：已知漏洞依赖扫描

```bash
cargo install cargo-audit
cd spike-session && cargo audit
```

- **检查内容**：使用 RustSec Advisory Database 审计 `Cargo.lock` 中依赖的已公开安全公告；也会报告 yanked crate 等锁定依赖状态。
- **稳定性**：RustSec 社区维护的独立 CLI，不是 Rust 工具链内置组件。
- **CI 适合度**：低成本，适合 PR 或每日 CI；需要网络/缓存的 advisory database。
- **Android / JNI / FFI 适合度**：能覆盖 `jni`、`portable-pty`、`wgpu` 等 Rust 依赖的已知公告；不能发现本项目 JNI 或 native 代码的内存错误。
- **限制**：只会发现数据库中已有的公告；“通过”不等于依赖没有漏洞。
- **Fable 判断**：可快速引入；三个 crate 有独立锁文件，须在每个 crate 分别运行。
- **官方来源**：<https://github.com/rustsec/rustsec/tree/main/cargo-audit>、<https://github.com/RustSec/advisory-db>

### 6. `cargo-deny`：依赖漏洞、来源、许可证和版本策略

```bash
cargo install cargo-deny
cd spike-render && cargo deny check
```

- **检查内容**：`cargo-deny check` 可执行 advisories、bans、licenses、sources 四类策略；advisories 使用 RustSec 数据。
- **稳定性**：Embark 维护的独立社区工具，不属于 Rust stable 工具链保证。
- **CI 适合度**：低到中等成本，适合 PR 或每日 CI；需要先评审并提交 `deny.toml` 中的许可证、git 源、重复版本例外。
- **Android / JNI / FFI 适合度**：与 `cargo audit` 相同地覆盖依赖供应链，不检查运行时 JNI/FFI 正确性。
- **与 `cargo audit` 的关系**：两者的漏洞数据库层面重叠。初期可先选 `cargo audit`；若需要许可证、依赖来源、重复依赖禁令，改用 `cargo-deny` 作为统一门禁，避免让两个工具制造重复告警。
- **Fable 判断**：最终更推荐 `cargo-deny`，因为 Fable 有本地 patch 的 `portable-pty`，需要显式记录允许的 git/path/source 规则和许可证决策。
- **官方来源**：<https://github.com/EmbarkStudios/cargo-deny>、<https://embarkstudios.github.io/cargo-deny/>

### 7. `cargo-geiger`：`unsafe` 使用量与依赖暴露面报告

```bash
cargo install cargo-geiger
cargo geiger --manifest-path spike-render/Cargo.toml
```

- **检查内容**：统计直接/传递依赖中的 `unsafe` 使用情况，帮助定位审查量大的 crate 和依赖路径。
- **稳定性**：独立社区工具；报告能力，不是正确性证明。
- **CI 适合度**：低成本报告或阈值告警，适合定时任务；不建议一开始将“unsafe 数量”作为硬失败条件。
- **Android / JNI / FFI 适合度**：对 `spike-render` 和 `spike-session` 很有价值，因为它们天然需要少量 unsafe；可用于观察改动是否把 unsafe 扩散到不应出现的模块。
- **限制**：unsafe 数量少不代表安全；依赖中 unsafe 多也不表示漏洞。
- **Fable 判断**：作为安全审查仪表板而不是门禁。优先人工审查 Fable 自己写的 JNI / FreeType / native-window unsafe 边界。
- **官方来源**：<https://github.com/geiger-rs/cargo-geiger>

### 8. Miri：解释执行，寻找纯 Rust 未定义行为

```bash
rustup +nightly component add miri
cargo +nightly miri test --manifest-path spike-session/Cargo.toml
```

- **检查内容**：以解释执行方式运行测试，可发现许多 Rust 未定义行为，例如越界访问、悬垂/无效引用、未初始化读取、错误对齐、违反别名规则，以及部分数据竞争。
- **稳定性**：nightly；运行慢，且 Miri 支持的语言/平台/API 范围有限。
- **CI 适合度**：适合 nightly 或合并队列；不建议以全量 Miri 阻塞每个 Android PR。
- **Android / JNI / FFI 适合度**：低到中等。Miri 不能替代实际 Android/JNI/FreeType/Vulkan 测试；应将纯 Rust unsafe 封装、token/句柄状态机、缓冲区转换提取为不调用系统 FFI 的测试，再交给 Miri。真实 FFI 测试必要时按 Miri 配置跳过。
- **Fable 判断**：值得做，但在 43/45 的 renderer registry 与 mailbox 逻辑能独立测试后再接入。
- **官方来源**：<https://github.com/rust-lang/miri>、<https://rust-lang.github.io/miri/>

### 9. Rust Sanitizers：真实运行时内存错误与竞态

```bash
RUSTFLAGS='-Zsanitizer=hwaddress' \
  cargo +nightly build --target aarch64-linux-android
```

- **检查内容**：Rust 编译器可接入 AddressSanitizer、Hardware-assisted AddressSanitizer、LeakSanitizer、MemorySanitizer、ThreadSanitizer 等。不同 sanitizer 检测的错误不同；例如 Address/HWAddressSanitizer 面向内存访问错误，ThreadSanitizer 面向数据竞争。
- **稳定性**：`-Zsanitizer` 是 nightly/不稳定编译器选项，且需要目标、链接器与运行时配合。
- **CI 适合度**：高成本；适合单独的 nightly Android arm64 job、合并队列或发布前真机压力测试。
- **Android / JNI / FFI 适合度**：高。官方支持列表包含 `aarch64-linux-android` 的 HWASan，因此它是 Fable 真机 native/JNI 内存错误检查的首选候选。第三方预编译库未插桩时覆盖会降低；TSan 的支持目标和同步库限制也要单独验证，不能先承诺 Android 可用。
- **Fable 重点场景**：renderer 销毁/重建、Java callback 关闭竞争、跨线程 mailbox、FreeType 字体缓冲、字形图集上传、连续 resize 与会话退出。
- **官方来源**：<https://doc.rust-lang.org/beta/unstable-book/compiler-flags/sanitizer.html>

### 10. `cargo fuzz` / libFuzzer：不可信字节和边界输入

```bash
cargo install cargo-fuzz
cargo +nightly fuzz run terminal_bytes
```

- **检查内容**：覆盖率引导的模糊测试；将随机变异输入持续喂给 target，寻找崩溃、panic 和 sanitizer 报告，并保留可复现的 crash 输入。
- **稳定性**：Rust Fuzz Book 要求 nightly；运行时间没有固定上限。
- **CI 适合度**：不适合普通 PR 同步全量运行；适合 nightly 定时长跑、人工安全测试或 merge queue 的短预算任务。应保存 corpus，并把已发现的崩溃输入转为普通回归测试。
- **Android / JNI / FFI 适合度**：中等。适合在宿主机 fuzz 纯 Rust 输入处理，例如 terminal escape sequence、UTF-8/Unicode、颜色/样式解析、快照解码和 JNI 输入转换的安全包装；不适合直接代替 Android UI 或 GPU 生命周期测试。
- **Fable 判断**：会话层的终端字节边界与渲染快照转换是最先值得建 target 的位置。
- **官方来源**：<https://rust-fuzz.github.io/book/cargo-fuzz.html>、<https://github.com/rust-fuzz/cargo-fuzz>

### 11. Kani：对小型关键函数做有界形式验证

```bash
cargo install --locked kani-verifier
cargo kani setup
cargo kani
```

- **检查内容**：Kani 用 model checking 验证带 proof harness 的 Rust 函数在给定边界内是否违反断言、panic 或不变量。它适合穷举有限状态，而不是替代整体 App 测试。
- **稳定性**：独立项目；对 Rust 版本、标准库和可建模 API 有约束，接入成本高于 test/Clippy。
- **CI 适合度**：中到高成本，适合少量关键 harness 的定时或 PR job；不适合直接验证 wgpu、Android 平台 API 或完整 JNI 运行时。
- **Android / JNI / FFI 适合度**：低到中等。适用于抽出来的纯 Rust 状态机：renderer token 的 create/reset/destroy 规则、mailbox 合并与容量上限、session closed 后 callback 的禁止投递规则。
- **Fable 判断**：暂不作为基础门禁。等 43–45 的状态机稳定、可无 Android 依赖运行时，选 1–2 个严重后果路径建立 proof harness。
- **官方来源**：<https://github.com/model-checking/kani>、<https://model-checking.github.io/kani/>

### 12. Loom：并发交错的确定性模型测试

- **检查内容**：Loom 用受控调度探索线程、原子变量和同步原语的可能交错，以发现普通测试不稳定复现的竞态/死锁。
- **稳定性**：Tokio 社区项目；需要用 Loom 提供的同步类型或通过抽象层替换 `std::sync`，不是“对现有程序直接扫描”。
- **CI 适合度**：状态空间会指数增长；只适合小模型、严格设置规模上限的 PR/定时测试。
- **Android / JNI / FFI 适合度**：中等偏低。不能直接建模 Java 线程、Android Looper、GPU 驱动或 JNI；可模型化 Rust 内的 renderer registry、mailbox、关闭/投递竞态。
- **Fable 判断**：45 的有界 mailbox 或 43 的 registry 若出现并发类 bug，再引入 Loom；不要为了“有工具”而全局改造。
- **官方来源**：<https://github.com/tokio-rs/loom>

### 13. Dependabot：已知依赖更新的自动化提醒

- **检查内容**：GitHub Dependabot 可按 `cargo` package ecosystem 监视 `Cargo.toml` / lockfile 更新并提出更新 PR；它是依赖维护机制，不是 Rust 代码静态分析器。
- **稳定性 / CI**：GitHub 官方服务，低维护成本；适合持续运行。
- **Android / JNI / FFI 适合度**：能让 `jni`、`wgpu`、`portable-pty` 等依赖及时进入审查流程，但升级仍必须跑 Android 构建和真机测试。
- **Fable 现状与建议**：`fable-app/.github/dependabot.yml` 只含 `github-actions`。三个 Rust crate 是独立 Git 工作树时，应在各自远端仓库根目录配置 `package-ecosystem: cargo`；若日后合并到一个远端，再为 `/spike-render`、`/spike-session`、`/fable-boo` 分别添加条目。
- **官方来源**：<https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference>

### 14. `cargo-vet`：依赖代码审查证明（可选）

- **检查内容**：为依赖版本维护审查记录/策略，可复用社区 audit-as-crates-io 记录；这是“是否审阅过供应链代码”的治理工具。
- **稳定性**：独立社区项目；需要建立审查策略和例外流程。
- **CI 适合度**：中等成本，适合依赖变化较频繁或高风险项目；对小型应用仓库初期可能过重。
- **Android / JNI / FFI 适合度**：可为高风险 native 依赖升级增加审查留痕，但不测试运行时内存安全。
- **Fable 判断**：在 `cargo-deny`、Dependabot、Android 回归稳定后再评估；目前不应阻塞 43–47 的工作。
- **官方来源**：<https://github.com/mozilla/cargo-vet>、<https://mozilla.github.io/cargo-vet/>

## Fable 的分阶段最小 CI 方案

### 阶段 A：先建立可重复的基础门槛

目标：让每个 Rust 改动至少经过格式、静态 lint 与行为测试。

1. 在 CI 固定 stable Rust 版本（例如提交 `rust-toolchain.toml` 或在 workflow 显式选择版本），避免新工具链悄然新增 lint 使历史 PR 不可预测地失败。
2. 对三个独立 crate 分别运行：

   ```bash
   cargo fmt --manifest-path fable-boo/Cargo.toml -- --check
   cargo clippy --manifest-path fable-boo/Cargo.toml --all-targets --all-features -- -D warnings
   cargo test --manifest-path fable-boo/Cargo.toml --all-targets --all-features
   ```

   对 `spike-render/Cargo.toml` 与 `spike-session/Cargo.toml` 重复同样的三条命令。因为当前不是 workspace，不应使用根目录的 `--workspace` 命令假设。

3. 同一 PR 仍必须跑现有 Gradle/APK 构建；Rust host test 通过不代表 Android `cdylib` 可链接或可被 Kotlin 壳正确调用。
4. 基线尚未清理时，先让 Clippy job 以报告形式出现；在修完所有已有 warning 后再加 `-D warnings` 设为 required check。不要通过全局 `allow` 掩盖既有告警。

### 阶段 B：加依赖与 `unsafe` 审查

1. 先在三个 crate 分别启用 `cargo audit`；需要许可证、来源和重复版本治理时，改为一个带 `deny.toml` 的 `cargo-deny` job。
2. 在每个 unsafe 边界补充 `SAFETY:` 说明和测试；只对纯 Rust 模块使用 `deny(unsafe_code)`，不要破坏必要 JNI/FFI 封装。
3. 每周生成 `cargo-geiger` 报告，观察本项目而非仅依赖的 unsafe 范围是否扩大。
4. 为持有各 crate 的远端仓库启用 Dependabot 的 `cargo` 更新；每个更新 PR 都通过阶段 A 与 Android APK/真机回归。

### 阶段 C：针对真实内存与并发风险

1. nightly 定时任务：为无系统 FFI 的状态机、字节转换和缓冲区运行 Miri。
2. nightly/定时长跑：为 terminal bytes、快照转换、Unicode/样式转换建立 `cargo fuzz` target；保存 corpus 和失败输入。
3. Android arm64 专用 job 或人工发布前流程：运行 HWASan 构建与真机压力路径，重点覆盖 renderer 销毁、mailbox、callback 关闭、字体与图集。
4. 仅当并发状态机够小且有明确不变量时，用 Loom 或 Kani；二者是增强审查，而不是替代测试和真机验证。

## 不应作出的错误承诺

| 错误说法 | 正确理解 |
| --- | --- |
| “Clippy 严格模式通过，所以没有内存安全问题。” | 错。它主要是 lint；需要 unsafe 审查、Miri、sanitizer 和真机路径。 |
| “Miri 能验收 JNI/FreeType/wgpu。” | 错。Miri 对许多系统 API 与 FFI 不适用；它应运行纯 Rust 测试。 |
| “cargo audit 通过就说明依赖安全。” | 错。它只能匹配已公开、已录入数据库的公告。 |
| “cargo-geiger 的 unsafe 数量低就安全。” | 错。它只量化暴露面，不能验证安全不变量。 |
| “Sanitizer 通过就能替代 Android 回归。” | 错。未插桩库、覆盖路径和设备差异都会留下盲区。 |

## 推荐决策

现在应立一个“小而硬”的 Rust 质量工单，而不是直接把所有高级工具塞进 CI：

1. 为三个 crate 建立 **fmt + Clippy `-D warnings` + test** 的 PR required checks；
2. 选定 **`cargo-deny` 或 `cargo audit`** 的单一依赖漏洞门禁（推荐最终选 `cargo-deny`）；
3. 制定并执行 **JNI/FFI/`unsafe` 安全说明** 的审查规则；
4. 在 45 的有界 mailbox 与 43 的 renderer 生命周期补齐后，以定时任务引入 Miri / fuzz，再用 Android HWASan 测真实 native 路径。

这条路线先处理现有、低成本且可阻断的问题，再把高成本工具投到 Fable 的真正风险边界：会话层、渲染器、JNI callback、原生字体和 GPU 路径。
