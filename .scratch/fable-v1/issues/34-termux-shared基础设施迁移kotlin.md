# 34 — termux-shared 基础设施 Java→Kotlin

**What to build:** 把 termux-shared 的基础设施包（设置/属性/主题/日志/通知/文件/数据/工具）迁移为 Kotlin；行为等价；对外 API 形态保持（Java 调用方无需改动或按工单 33 后的缝收口调整）。

**Blocked by:** fable-v1/33
Status: 已完成

## 验收清单

- [ ] 目标包 100% Kotlin（对应 .java 删除）
- [ ] 行为等价：相关 JVM 单测全绿（属性/主题/日志/文件等可测逻辑）
- [ ] 与 app / terminal-view / fable-core 的互调编译通过
- [ ] 单测基线不劣化（既有基线规则）；APK 构建 + 校验通过

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 1/6：分模块迁移）。

2026-08-12 实施完成（重新审计既有未提交迁移后收口）。

**1. 验证数据/命令**

- 迁移覆盖：`termux-shared` 本单基础设施范围的 68 个 `.java` 已逐一替换为 68 个 `.kt`；activity/android/crash/data/errors/file/interact/logger/markdown/models/net uri+url/notification/reflection/settings/theme/view 范围内 Java 残留为 0。剩余 53 个 Java 文件只在后续工单边界：环境/bootstrap、shell/local socket、terminal 交互和 Report/TextIO Activity。
- Kotlin/Java 互操作测试：新增 Java 调用方回归 5 项，`./gradlew :termux-shared:testDebugUnitTest --rerun-tasks` → 5/5 PASS。覆盖原静态工具 API（文件权限/类型/时间）、`null` 文件路径的 `IOException` 契约，以及 `Error` 接收不可变 `List<Throwable>` 的构造/更新路径。
- 跨模块与 APK：`./gradlew :app:assembleDebug :fable-core:testDebugUnitTest :termux-shared:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL；fable-core 18/18 PASS；app、terminal-view、fable-core 均完成对 Kotlin 共享层的编译调用。
- 最终全量命令 `./gradlew :app:assembleDebug :app:testDebugUnitTest :fable-core:testDebugUnitTest :termux-shared:testDebugUnitTest --rerun-tasks`：APK 打包成功；app 单测 34 项中 33 PASS、1 个既有失败 `FileReceiverActivityTest.testIsSharedTextAnUrl`（Robolectric 4.8.1 + JDK 25 的 `NoClassDefFoundError`，工单 15/28–33 已记录，非本单引入）；fable-core 18/18、termux-shared 5/5 PASS。
- APK `app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`（187,502,745B，sha256 `d5e1eb04…`）：包名 `com.gph.fable`、versionCode 1022、Fable 证书 SHA-256 `<证书指纹>`；arm64 含 fable-render / fable-session / local-socket / termux-bootstrap 四个 `.so`，Apple 与 Noto emoji assets 均存在。
- ABI 审计：用工单 33 基线 `1d5e7c6` 编译 AAR 并与本单 AAR 做 `javap -public` 对照；既有 Java 调用点及新增 Java 测试均可编译。Kotlin 对工具类产生的 `final`/`INSTANCE`/`Companion` 是预期语言产物，静态入口用 `@JvmStatic` 保持。

**2. 踩过的坑与解法**

- 既有转换未能首次编译：`FileAttributes.get(null, …)` 向 `NativeDispatcher.stat/lstat` 传可空路径，但 Kotlin 参数误写成非空 `String`。将 Dispatcher 参数保留为 `String?`，先做与旧 Java 相同的受检路径验证，再将验证后的非空路径传给 Android `Os` API；新增测试固定 `IOException("The path is null or empty")` 契约。
- `Error.kt` 把原来的 `List<Throwable>` 强转为 `MutableList<Throwable>`；Java 的 `Collections.singletonList()` 会在运行时触发 `ClassCastException`。字段改回只读 `List<Throwable>`，并添加 Java 回归测试验证构造与 `setStateFailed` 都可接受不可变列表。
- 初次共享层没有 JVM 单测，`testDebugUnitTest` 会显示 `NO-SOURCE`，不能作为等价性证据；补最小 Java API 边界测试，既验证行为也验证 Kotlin 对现有 Java 调用方的静态 API 兼容性。
- 按流程启动的双轴 `code-review` 并行代理在本地模型通道返回 503，未产出评审结论；主会话据同一 Standards/Spec 清单完成手工审计并修复上述两项发现。

**3. 结论写回（对后续工单/决策的输入）**

- termux-shared 基础设施层已是 Kotlin，AGP 9 内置 Kotlin 配置仅在本模块启用；native local-socket 与 desugaring 配置未改。
- Java 调用共享工具类仍走同名静态入口（Kotlin `object` + `@JvmStatic`）；涉及构造对象的调用继续保留 Java 可见构造器。后续 35/36/37 可直接调用这些 Kotlin API，无需改 SharedPreferences 键、配置键或路径。
- 工单 35 应只迁环境/bootstrap、shell、local socket 与 FableShellEnvironment 相关剩余 Java；不要回头处理已完成的基础设施包。工单 36 处理 terminal-view/extra keys；Report/TextIO Activity 等 app 侧残留留给 38。
