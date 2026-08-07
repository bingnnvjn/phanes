# ADR-0001: v1 界面层采用 XML/经典 View，不引入 Jetpack Compose

- 状态：已确认（2026-08-06，用户拍板）
- 日期：2026-08-06
- 范围：fable-app v1 界面层（工单 04 的输入）；不影响后续版本重新评估

## 背景

工单 03 需要定案 v1 UI 框架：Jetpack Compose（需引入 Kotlin 工具链）vs 继续 XML/经典 View。
交接文档与 spec 对 Compose 只是"倾向"，本决策以代码库事实与构建链实证为准。

代码库现状：

- termux-app fork，**纯 Java**（全仓 0 个 .kt 文件），UI 全部 XML + 经典 View（`TermuxActivity`、
  `TermuxActivityRootView`、DrawerLayout/ViewPager/ListView、androidx.preference 设置页）。
- `TerminalView` 是自绘 View（terminal-view 模块），已在 `activity_termux.xml` 中直接布局。
- 构建链：Gradle 7.2（腾讯镜像）+ AGP 4.2.2 + JDK 17（JAVA_HOME=java-17-openjdk）+
  compileSdk 30 / targetSdk 28 + aarch64 手机本地构建 + 假 NDK shim + Termux 原生 aapt2 覆盖。
  本机 SDK 仅装 platforms 30/34/35/36（无 31/32/33）。
- 已跑通"构建 + 校验 + 真机验收"（工单 01/07），打开即终端可用。

## 决策

**v1 界面层继续使用 XML/经典 View，不引入 Kotlin/Compose。**
TerminalView 继续直接嵌入布局（现状），会话层底盘不动。

## 理由（带证据）

### 1. 与纯 Java 代码库的兼容性：Compose 必须引入 Kotlin，且只能写新 UI

- Compose 的 UI 由 `@Composable` 函数构成，只能在 Kotlin 中声明（官方 Codelab/文档：
  "A Compose app is made up of composable functions - just regular functions marked with @Composable"）。
  Java 无法编写 composable；官方互操作路径（`ComposeView.setContent()`）同样是 Kotlin API。
- 引入 Compose = 全仓引入 Kotlin Gradle 插件 + Kotlin 编译器 + Kotlin 源码集，
  与其余 Java 模块混编。Java↔Kotlin 互操作技术上可行，但 UI 层重写必须用 Kotlin 重写
  `TermuxActivity` 及其大量成熟 View 逻辑（IME 修正、Gboard 遮挡 workaround、extra keys、手势）。

### 2. TerminalView 嵌入方式

- XML：现状即直接布局，零改动、零互操作层。
- Compose：需包一层 `AndroidView`，在 Compose 的 measure/input/lifecycle 与 View 系统之间
  增加互操作面；对 IME/焦点/软键盘高度这类终端敏感行为风险更高，收益为零（TerminalView 仍是 View）。

### 3. 构建链可行性：实证失败（本机实测，2026-08-06）

在 fable-app 临时分支做最小验证（Kotlin 插件 + 单个 composable，未提交、已还原，构建链已恢复验证）：

- **Compose 1.0.5 + Kotlin 1.5.31（compileSdk 30 下唯一可用的 Compose 1.0.x 组合，
  官方兼容表：Compose Compiler 1.0.5 ↔ Kotlin 1.5.31）**：
  `:app:compileDebugKotlin` 失败——Kotlin 1.5.31 编译器在 JDK 17 下抛
  `InaccessibleObjectException: module java.base does not "opens java.util" to unnamed module`。
  尝试 `kotlin.compiler.execution.strategy=in-process` 与 `kotlin.daemon.jvmargs` 加
  `--add-opens` 均无效（Kotlin 1.5.x 的 JDK 17 支持本就不存在，JDK 17 支持自 Kotlin 1.6.0 起）。
- **Compose 1.1.1 + Kotlin 1.6.10（官方兼容表：Compiler 1.1.1 ↔ Kotlin 1.6.10；Kotlin 1.6 支持 JDK 17）**：
  `checkDebugAarMetadata` 硬失败——`androidx.compose.material:material:1.1.1` 的 AAR 元数据
  `minCompileSdk (31) > compileSdkVersion (android-30)`。即 Compose 1.1+ 必须 compileSdk 31+。
- 官方兼容性事实（kotlinlang.org KGP 兼容表 / developer.android.com Compose-Kotlin 兼容表）：
  - 当前 Gradle 7.2 不在任何 KGP ≤1.7.22 的"完全支持"范围内（1.6.20–1.6.21 上限 7.0.2、
    1.7.20 上限 7.1.1）；Kotlin 2.x 要求 Gradle ≥ 7.6.3，当前 7.2 不满足。
  - 现代 Compose（1.12）官方已要求 compileSdk 37 + AGP 9.2（compose-ui 发布说明）。
- 结论：在当前 compileSdk 30 工具链上，**不存在可稳定编译的 Compose 组合**；
  采用 Compose 必须先升级 Gradle + AGP + compileSdk 整链（本机还缺 31–33 platform），
  这会让已跑通且脆弱的 aarch64 构建链（假 NDK、aapt2 覆盖、Robolectric 4.8.1 钉死、bootstrap 哈希钉）
  承担高额回归风险，且远超工单 03"只做决策与文档"的范围。

### 4. 重写范围与风险

- v1 最小 UI 面很小：终端屏 + 扩展键行 + 会话抽屉 + 设置页。
  XML/View 路径直接复用现有可工作实现（TermuxActivity 全套 + Material + Preferences），
  在既有钩子上做 Fable 定制（字号 `KEY_FONTSIZE`、`TermuxThemeUtils`、`extra-keys`、会话服务）。
- Compose 路径等于把已调通的 View 逻辑（含多年修复的 IME/键盘边缘问题）全部用 Kotlin 重写一遍，
  换来的声明式收益在"打开即终端的纯终端 v1"上无差异化价值，且直接威胁红线"打开即终端不被破坏"。

### 5. 长期维护

- Compose 是行业方向，Fable 的 HUD/AI 增强愿景也指向它——但这是**后续版本**的事，
  前提是工具链整链升级完成（工单 05 SDK 升级是其中一步）后再评估。
- XML/View 在本代码库并非死路：官方支持 View 与 Compose 共存增量迁移
  （developer.android.com "Migration strategy"），未来可在升级工具链后按屏幕增量迁移，
  无需 v1 一步到位。

## 被否选项

### Compose（TerminalView 经 AndroidView 嵌入）

- 否因：必须引入 Kotlin 工具链；当前构建链上唯一可配 Compose 的 Kotlin 1.5.31 在 JDK 17 下编译崩溃
  （实测）；Compose 1.1+ 需要 minCompileSdk 31，当前 compileSdk 30 直接拒绝（实测）；
  Compose 2.x 时代需要 Gradle 7.6.3+/AGP 8+，当前 7.2/4.2.2 不满足；重写范围与回归风险高于收益。

## 重开条件（触发重新评估）

- fable-app 工具链升级（Gradle ≥ 7.6.3 / AGP ≥ 8 / compileSdk ≥ 34，含 31–33 platform 补齐）完成并
  验证 aarch64 构建链稳定后，若进入界面现代化（HUD/动画/设计系统）阶段，重新评估 Compose。
- 维持"TerminalView 渲染核心不变 + AndroidView 嵌入"的技术前提（届时仍成立）。

## 参考

- Compose ↔ Kotlin 兼容表（官方）：https://developer.android.com/jetpack/androidx/releases/compose-kotlin
- KGP ↔ Gradle/AGP 兼容表（官方）：https://kotlinlang.org/docs/gradle-configure-project.html
- Compose 迁移策略（官方）：https://developer.android.com/jetpack/compose/interop/adding
- Compose UI 发布说明（1.12 要求 compileSdk 37/AGP 9.2）：https://developer.android.com/jetpack/androidx/releases/compose-ui
- 本机实证记录：工单 03 `## Comments`（2026-08-06 探针）
