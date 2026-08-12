# 43 — Renderer 句柄所有权与销毁协议

**What to build:** 用 Rust 句柄注册表或等价所有权模型替换 JNI 裸指针；统一 Renderer 调用、reset、detach、destroy 和在途请求的生命周期；修复 renderer 创建失败清理和会话级 Activity Context 持有。

**Blocked by:** None
Status: 待验收

## 验收清单

- [ ] JNI 不再把任意 `jlong` 直接转换为可释放裸指针
- [ ] destroy 与 write/query/attach/reset 并发时不会 use-after-free、双重释放或永久阻塞
- [ ] 句柄失效、重复 destroy、创建失败和 Activity 重建有自动化压力测试
- [ ] 会话级对象不持有 Activity Context
- [ ] Renderer 创建失败后的 session remove/cleanup 不崩溃
- [ ] Rust/Android 测试及 APK 构建通过

## Comments

2026-08-12 建单（完整仓库代码审查 P1）。本单先定所有权协议，再改 wrapper；禁止只在 Java 侧扩大 synchronized 范围来掩盖 Rust 裸指针问题。

2026-08-12 实施完成，待真机验收。

1. 验证数据/命令：
   - `cargo test --manifest-path spike-render/Cargo.toml --lib`：23 passed。
   - `cd fable-app && ./gradlew :app:compileDebugAndroidTestJavaWithJavac :app:assembleDebug :app:assembleDebugAndroidTest`：BUILD SUCCESSFUL，debug APK 与 androidTest APK 均已生成。
   - 新增 instrumentation 覆盖失效 token、重复 destroy、创建失败、write/query/attach/reset/destroy 并发、销毁后不复活及 application Context 持有；当前 `adb devices` 无连接设备，尚未实际执行真机测试。
   - 全量 `:app:testDebugUnitTest` 到 34 项时，既有 `FileReceiverActivityTest#testIsSharedTextAnUrl` 因 Robolectric/ASM `ClassReader` 类加载失败；与本单改动无调用关系。
2. 踩过的坑与解法：
   - 仅以 Java 读取 `mHandle` 后再调 JNI 仍会与 destroy 竞态；Rust 改为不透明递增 token → 注册表 `Arc<Renderer>`，destroy 只 remove，已在途调用持有 Arc 到结束。
   - `ANativeWindow_fromSurface` 的引用必须随 attach 命令明确转移；同 Surface、attach 失败、detach 与 Renderer drop 各自严格归还一次。
   - reset 创建新 renderer 与 destroy 交错时不能发布/初始化新句柄；仅在仍拥有旧代句柄时，于 adapter 锁内发布并恢复状态，否则销毁新句柄。
3. 结论写回：
   - 工单 44–46 可以把 JNI handle 当作可失效 token：所有迟到调用必须容忍安全默认返回，不能缓存或解引用其数值。
   - 会话级 FableRenderCoreAdapter 只保留 application Context，Activity 重建不会由渲染器适配器持有旧 Activity。
