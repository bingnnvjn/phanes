# 37 — app 主壳 Java→Kotlin

**What to build:** app 主壳（Activity / RootView / Service / 会话客户端 / FableTerminalView 与 FableInputTerminalView 接线）迁移为 Kotlin；行为等价。

**Blocked by:** fable-v1/36
Status: 已完成

## 验收清单

- [x] 主壳目标类 100% Kotlin（对应 .java 删除）
- [x] Activity/Service 生命周期与行为等价（现有 JVM 测试 + 构建；真机归工单 40）
- [x] 与其余模块互调编译通过
- [x] APK 构建 + 校验通过

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 1/6）。

2026-08-17 开始实施：沿用工单 36 的 `CoreAdapter` 接线与生命周期基线，先迁 app 主壳、渲染容器、会话客户端/会话包和诊断/配色工具；外部入口与设置页留给工单 38。

2026-08-17 收尾检查点（状态保持“进行中”）：

### 验证数据

- `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac --no-parallel --rerun-tasks` → `BUILD SUCCESSFUL`。
- `./gradlew :app:testDebugUnitTest --no-parallel --rerun-tasks` → `BUILD SUCCESSFUL`。
- `./gradlew :app:assembleDebug --no-parallel --rerun-tasks` → `BUILD SUCCESSFUL`。
- APK `app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`：`aapt2 dump badging` 显示 `com.gph.fable` / versionCode `1022`；arm64 包含 `libfable-render.so`、`libfable-session.so`、`liblocal-socket.so`、`libtermux-bootstrap.so`；`apksigner verify --print-certs` 通过。
- `git diff --cached --check` 通过；迁移后 app 目标目录当前仍有 20 个 `*.java.src`、10 个非本工单 Java 文件、30 个 Kotlin 文件。

### 本轮迁移

- 已真实迁移为 Kotlin：`FontAssets`、`RenderCore`、`SessionHandle`、`SessionEventCallback`、`SessionLogCallback`、`FableDiagnostics`、`FableTerminalPalette`、`KeyboardShortcut`、`FullScreenWorkAround`。
- Activity/Service、渲染容器、会话客户端及其余主壳实现目前采用 `*.java.src` + Gradle `stageLegacyJavaSources` 过渡；Kotlin facade/anchor 已接入同名 Android 入口，行为由原 Java 实现承载。

### 踩过的坑

- 直接保留同名 Java 与 Kotlin 会产生重复类；过渡阶段必须把 Java 移出常规 source tree，并在构建前恢复到 `build/generated/legacy-java`。
- Kotlin daemon 在 Termux ARM64 偶发断连，Gradle fallback 编译仍可通过；并行构建会放大该问题，因此本轮统一使用 `--no-parallel`。
- 构建日志中的 `aapt2 No package ID 7f` 为现有资源工具噪音，不影响 APK 产物与签名校验。

### 双轴复核与结论

- Standards：发现 staged Java 过渡机制、facade middle-man 与 migration anchor；违反 ADR-0009 决策 1/6 及本单“主壳目标类 100% Kotlin”验收，不能按完成验收。
- Spec：跨模块编译、单测、APK 构建/校验通过；100% Kotlin、对应 Java 退场、独立生命周期等价证据仍缺失。
- 对工单 38 的输入：设置/杂项迁移可沿用当前 Kotlin ABI，但不得假设 Activity/Service/terminal/session 实现已完成 Kotlin 化；工单 37 需继续收口剩余 staged Java 后再进入最终验收。

2026-08-17 完成收口：

### 验证数据

- `find app/src -type f -name '*.java.src'` → `0`；`stageLegacyJavaSources`、`generated/legacy-java`、migration anchor、`FableActivityJava` typealias 均清零。
- `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:testDebugUnitTest --no-parallel --no-daemon -x :app:downloadBootstraps` → `BUILD SUCCESSFUL`。
- `./gradlew :app:assembleDebug --no-parallel --no-daemon -x :app:downloadBootstraps` → `BUILD SUCCESSFUL`。
- `javap` ABI smoke：`FableActivity` 生命周期与静态入口、`FableService.LocalBinder.service`、RootView 三构造器、Toolbar/TerminalView/RenderCoreAdapter/ContentProvider/RunCommandService 入口均存在；`rendererSetPalette16` 保持同一 JVM 描述符并恢复可空 JNI 语义。
- arm64 APK `app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`：`aapt2 dump badging` 显示 `com.gph.fable`、versionCode `1022`；包含 `libfable-render.so`、`libfable-session.so`、`liblocal-socket.so`、`libtermux-bootstrap.so`；`apksigner verify --print-certs` 通过。

### 踩过的坑与解法

- clean 后 bootstrap 下载两次遭遇网络 reset/timeout；代码门禁与 APK 构建使用 `-x :app:downloadBootstraps`，本地 NDK/native 产物仍成功生成。后续需要重新拉取 bootstrap 时再单独重试网络任务。
- Java→Kotlin 时 Kotlin 默认生命周期可见性会把原 Java public 方法降为 protected，已显式恢复 `onCreate/onStart/onResume/onDestroy/onSaveInstanceState` 的 public ABI。
- JNI ANSI palette 的 `null` 表示内置默认 16 色，不能替换为空数组；`RenderCore.rendererSetPalette16` 改为 `IntArray?` 并直传 null。

### 结论写回

- app 主壳、会话���户端、渲染容器、Service/Activity 及现有外部入口实现已脱离 staged Java，满足 ADR-0009 Kotlin 壳终态；外部入口仅做行为等价语言迁移，原生替代方案仍按 ADR-0009/工单 38 后续评估。
- 工单 38 可直接复用本单 Kotlin ABI，继续处理设置页、关于、Report/FileReceiver 等剩余 Java；工单 40 负责真机生命周期与权限/外部入口回归。
