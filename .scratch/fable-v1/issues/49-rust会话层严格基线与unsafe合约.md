# 49 — Rust 会话层严格基线与 unsafe 合约

**What to build:** 清零 Rust 会话层的格式、严格 Clippy、测试和文档基线；将 JNI、PTY、事件回调、关闭顺序��跨线程状态的 unsafe/FFI 合约收束并文档化，使会话层可接入不可绕过的质量门禁。

**Blocked by:** fable-v1/48
Status: 已完成

## 验收清单

- [ ] 会话层 crate 在固定工具链下通过 fmt、严格 Clippy、全目标测试和 rustdoc 门禁
- [ ] JNI、PTY fd、Java callback、线程附着和关闭路径的所有权/线程/失败契约可由 `SAFETY:` 与 API 文档审查
- [ ] 生产路径没有未经批准的 panic、unwrap、expect、todo 或静默 lint 例外
- [ ] 现有输出背压、generation 关闭和会话生命周期回归测试不劣化，并补齐修复所需测试
- [ ] 本工单不改变会话层的既定功能语义或输出完整性

## Comments

2026-08-14 建单（依赖 48 的统一规则）。工单 44 的 callback-close 契约是既定前提；发现其实现与严格规则冲突时，必须以测试保持契约，而不是回退到无界队列或旧回调模型。

2026-08-14 实施完成。

- 验证数据：`spike-session` 逐项通过 `cargo fmt --all -- --check`、`cargo check --all-targets --all-features --locked`、`cargo clippy --all-targets --all-features --locked -- -D warnings`、`cargo test --all-targets --all-features --locked`、`RUSTDOCFLAGS="-D warnings" cargo doc --all-features --no-deps --locked`；测试结果 13/13 PASS（2 个单元测试、2 个 PTY 测试、9 个 SessionManager 测试）。新增 crate 级 unsafe/FFI/rustdoc/Clippy 基线，JNI 字符串迁移到非弃用 API，所有本 crate PTY libc unsafe 块补齐 `SAFETY:`。
- 踩坑与解法：严格 Clippy 暴露了测试模块位于导出函数前、`argv[0]` rustdoc 链接、JNI `get_string` 弃用和若干机械 lint；逐项最小修复后保持既有输出、背压、generation/关闭语义。审查还发现读 fd 与 close 的并发窗口及 callback 持锁重入死锁，分别用 read guard + 先 kill 后关 fd、以及复制 callback 槽后锁外调用修复。
- 结论写回：会话层可进入工单 51 的 required CI 接线；callback-close 契约明确为关闭撤销后续投递、已复制的在途回调允许完成。PTY fd 所有权/线程不变量已落到代码与 `SAFETY:`，后续工单仍需覆盖真机 JNI/线程附着和 sanitizer 专项，不改变本单既定功能语义。
