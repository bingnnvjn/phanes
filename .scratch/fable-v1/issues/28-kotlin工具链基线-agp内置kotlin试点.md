# 28 — Kotlin 工具链基线（AGP 9 内置 Kotlin 试点）

**What to build:** 在 fable-app 验证 AGP 9 内置 Kotlin：不引入 Kotlin Gradle Plugin，选一个小型纯逻辑（如最近会话存储或常量/工具类）以 Kotlin 实现并接线，Java 调用 Kotlin；确认各模块 Kotlin 启用策略；构建/单测/APK 校验全绿且运行时行为不变。

**Blocked by:** None
Status: 已完成

## 验收清单

- [ ] 至少一个 Kotlin 源文件由 AGP 内置 Kotlin 编译并进入 APK（全程无 `org.jetbrains.kotlin.android` 插件）
- [ ] Java→Kotlin 互调有 JVM 单测覆盖（新增测试全绿）
- [ ] 既有测试基线不劣化：terminal-emulator 152/152（或按本单范围调整后等价）；app 仅允许既有 FileReceiverActivityTest 基线失败
- [ ] `:app:assembleDebug` 成功；APK 校验（包名/签名/so 清单）通过
- [ ] 试点改动不改变运行时行为（真机验收归工单 40）

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 3；官方依据：Migrate to built-in Kotlin，AGP 9.0+ 默认启用内置 Kotlin）。

2026-08-12 实施完成（AGP 9 内置 Kotlin 试点：最近会话路径编解码切 Kotlin，Java 生产代码与 JVM 测试互调）。

**1. 验证数据/命令**

- TDD 红→绿：新增 `RecentSessionPathCodecTest` 先编译失败（cannot find symbol）→ 实现 `RecentSessionPathCodec.kt` 后 2/2 PASS；`RecentSessionStoreTest` 8/8 PASS（新增 1 条整链路特殊字符 round-trip）。
- `:terminal-emulator:testDebugUnitTest --rerun-tasks`：152/152 PASS（基线不劣化）。
- `:app:testDebugUnitTest --rerun-tasks`：33 测 32 绿 1 败 = 既有基线 `FileReceiverActivityTest.testIsSharedTextAnUrl`（Robolectric + JDK 25 NoClassDefFoundError，工单 15 已记录，非本次引入）。
- `:app:assembleDebug`：BUILD SUCCESSFUL（JDK 25 / Gradle 9.7.0 / AGP 9.3.0，Termux 原生 aapt2 override）。
- APK 校验（`fable-app_apt-android-7-debug_arm64-v8a.apk`）：包名 `com.gph.fable`、versionCode 1022、minSdk 24；5 个 arm64 .so（libghostty-spike / liblocal-socket / libtermux-bootstrap / libfable-session / libfable-render）；apksigner verify 通过（Fable 证书 SHA-256 `<证书指纹>…`）。
- Kotlin 进包证据：`:app:compileDebugKotlin` 实际执行（产物在 `build/intermediates/built_in_kotlinc/`）；`classes26.dex` 含 `Lcom/gph/fable/app/session/RecentSessionPathCodec;` 与 `RecentSessionPathCodec.kt`。
- 全程无 `org.jetbrains.kotlin.android` / KGP 插件：全部 Gradle 文件 grep 零命中。

**2. 踩过的坑与解法**

- AGP 9.3 的 `android.builtInKotlin` 与 DSL `enableKotlin` 默认均为 true（已反编译 CommonExtensionImpl 核实），四个模块无需额外配置就注册 `compileDebugKotlin` 任务；为防歧义，app 模块显式写 `enableKotlin = true`。
- Kotlin `object` + `@JvmStatic` 可保持 Java 侧 `RecentSessionStore.encode(...)` 式静态调用不变；`@JvmField`/顶层函数方案未必要，试点未引入。
- aapt2 的 `No package ID 7f` 是 Termux 原生 aapt2 存量噪音（项目总览已记录），不影响产物。

**3. 结论写回（对后续工单/决策的影响）**

- **`enableKotlin` 定案**：四个模块保持 AGP 9.3 内置 Kotlin 默认启用（`compileDebugKotlin` 均已注册）；app 模块显式 `enableKotlin = true`，其余模块迁移到 Kotlin 源码时再显式声明。不引 KGP。
- 试点证明 aarch64 本地构建链（假 NDK + Termux 原生 aapt2 override）可稳定编译 Kotlin 并进 APK，ADR-0009 重开条件未触发。
- 对工单 34–38（Java→Kotlin 分模块迁移）：直接放 `.kt` 源文件即可，Java→Kotlin 互调走 `@JvmStatic`/顶层函数；后续迁移可复用本单的互调测试形态与 `built_in_kotlinc` 产物验证方法。
