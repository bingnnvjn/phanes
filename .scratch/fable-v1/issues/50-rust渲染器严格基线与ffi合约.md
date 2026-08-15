# 50 — Rust 渲染器严格基线与 FFI 合约

**What to build:** 清零 Rust 渲染器的格式、严格 Clippy、测试和文档基线；将 JNI、libghostty-vt、FreeType、字体映射、wgpu/native window、renderer token 与跨线程 renderer 的 unsafe/FFI 合约收束并文档化。

**Blocked by:** fable-v1/48
Status: 已完成

## 验收清单

- [x] 渲染器 crate 在固定工具链下通过 fmt、严格 Clippy、全目标测试和 rustdoc 门禁
- [x] 每个必要 unsafe 边界都有可验证的有效性、ABI、所有权、线程和释放说明
- [x] JNI 导出、FFI 布局、unwind/panic、防重复销毁和 native window 生命周期有对应自动化回归
- [x] 生产路径没有未经批准的 panic、unwrap、expect、todo 或静默 lint 例外
- [x] 字形、emoji、选择、滚动、GPU 离屏和 renderer 句柄测试不劣化

## Comments

2026-08-14 建单（依赖 48 的统一规则）。工单 43 的 token 注册表与 Surface 所有权协议是既定前提；本单不重开渲染器架构或字体路线。

2026-08-14 开始实施：按启动包先取得 `spike-render` 严格门禁红基线；测试缝限定为 crate
质量门禁、JNI/FFI 合约、renderer token、防重复销毁与 native window 生命周期，以及既有
渲染回归。FFI 实现改动前先写 Validity、ABI、Ownership、Threading、Release、Unwind 不变量。

2026-08-14 实施完成，待 Android 真机验收。

1. 验证数据/命令：
   - `cargo fmt --manifest-path Cargo.toml --all -- --check`：PASS。
   - `cargo clippy --manifest-path Cargo.toml --all-targets --all-features --locked -- -D warnings`：PASS。
   - `cargo test --manifest-path Cargo.toml --all-targets --all-features --locked`：PASS，23 个库测试
     全部通过，全部 binary/example test target 无失败。
   - `RUSTDOCFLAGS="-D warnings" cargo doc --manifest-path Cargo.toml --all-features --no-deps --locked`：PASS。
   - 生产库启用 `unsafe_op_in_unsafe_fn`、FFI、rustdoc、todo/unimplemented/dbg 与
     `unwrap/expect/panic`（非测试）门禁；新增 `spike-render/docs/ffi-contracts.md`，
     收束 JNI token、Ghostty、FreeType/mmap、wgpu/native window、线程和 panic/unwind 合约。
   - `spike-render` 提交：`b19288b`（质量基线）+ `6cc990e`（JNI unwind/FFI 安全边界）。
   - 尚未运行 Android instrumentation/真机 Surface attach-detach-reset 压力；当前环境
     `adb devices` 无设备，因此状态保留为“待验收”。
2. 踩过的坑与解法：
   - Rust 1.97 的 Clippy 将 `build.rs` 的 `&PathBuf`、JNI 过时数组 API、诊断 examples
     和多个无效转换一起阻断；逐项修复并保留必要 FFI 名称/布局，不用全局 `allow`。
   - `cargo fmt --check` 覆盖 29 个既有渲染/诊断文件；执行格式化只改变布局，不改输出或
     生命周期语义。vendored FreeType 的 C `unused variable memory` 仍是编译器 warning，
     不属于 Rust crate lint，未改第三方源码。
   - `unsafe_op_in_unsafe_fn` 历史 Ghostty 读取集中在具体适配函数的有理由 `expect`；
     GPU/window/thread 边界未继承模块级例外，并补齐 `Send/Sync` 安全说明。
   - JNI 环境入口、renderer attach 和 PTY spawn/read/write/resize/close 统一经过
     `catch_unwind`；native window 与 PTY shim unsafe 调用补充邻接 `SAFETY:` 前提。
3. 结论写回：
   - 渲染器本地自动门禁已从工单 48 的历史基线清零为全绿；字形、emoji、选择、滚动、
     GPU 离屏和 renderer token 既有测试保持通过。
   - 工单 43 的 token 注册表与 Surface 所有权协议未回退；工单 44–47 可直接依赖
     `docs/ffi-contracts.md` 的失效 token、重复 destroy、window release 和 renderer 线程不变量。
   - 真机验收需复用工单 43 的 instrumentation 与 Surface 生命周期场景，确认 attach/detach/
     reset/Activity 重建无双重释放、无迟到回调；通过后可将 Status 改为 `已完成`，并由工单 51
     接管 required CI。

2026-08-15 真机验收通过（设备 `<测试机型>`，ADB `192.168.0.2:41007`）。

1. 验证数据/命令：
   - 确认前台为 Termux 后，仅通过 instrumentation 执行；未发送任何坐标点击或输入事件。
   - `adb shell am instrument -w -r -e class
     com.gph.fable.app.terminal.adapter.FableRenderCoreAdapterInstrumentedTest
     com.gph.fable.test/androidx.test.runner.AndroidJUnitRunner`：`OK (8 tests)`，耗时
     `14.989s`。
   - 验收修正提交：`spike-render/a68c2f3`（release 导出 `rendererCreate`）与
     `fable-app/2e8db87`（清除残留 selection overlay）。
   - 覆盖 token、重复 destroy、Activity-like Context 重建、并发 reset/destroy、
     SurfaceTexture attach/detach、字体与配色、写入/resize/scroll/selection、scrollback、
     title/bell/modes，以及创建失败清理。
   - 应用 APK 于 2026-08-15 重新构建；其 native 库导出 `rendererCreate`，上述真机用例已实际
     走通该 JNI 路径。
2. 踩过的坑与解法：
   - 初始 APK 仍打入 2026-08-12 的旧 native 库；重新复制并打包当前 `aarch64-linux-android`
     release 库后再验收。
   - `rendererCreate` 误留 `#[cfg(test)]`，使 release 库遗漏 JNI create 导出；移除该属性，
     同时删去因此失效的 dead-code lint 例外。
   - selection overlay 按行独立保存；第二次断言前显式清除 `row2`，避免旧选择残留影响结果。
3. 结论写回：
   - 工单 50 的本地质量门禁与真机 JNI/Surface 生命周期回归均通过，状态改为“已完成”。
   - 后续工单可依赖 release JNI create 导出和逐行 selection overlay 的当前行为；工单 51 继续
     承接 CI required check。
