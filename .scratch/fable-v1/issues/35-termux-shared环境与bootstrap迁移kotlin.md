# 35 — termux-shared 环境与 bootstrap Java→Kotlin

**What to build:** bootstrap 安装/解包/校验、环境快照组装（AndroidShellEnvironment 等）、shell 命令与 RUN_COMMAND 依赖代码迁移为 Kotlin；环境注入边界不变（Kotlin 计算环境快照传入 Rust 会话层）。

**Blocked by:** fable-v1/34
Status: 进行中

## 验收清单

- [ ] 目标包 100% Kotlin（对应 .java 删除）
- [ ] bootstrap/环境 JVM 可测逻辑测试全绿（路径/版本/权限/校验逻辑）
- [ ] 冷启动 bootstrap 流程构建通过（真机归工单 40）
- [ ] APK 校验（bootstrap assets / 原生库存在）
- [ ] 单测基线不劣化（既有基线规则）

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 1/6；ADR-0008 决策 8 环境注入边界不变）。

2026-08-13 开始实施：先盘点环境/bootstrap、shell 与 RUN_COMMAND 依赖范围，按行为等价迁移。

2026-08-13 进展检查点：

### 已完成

- 环境抽象与变量模型迁移：`UnixShellEnvironment`、`AndroidShellEnvironment`、`IShellEnvironment`、`ShellCommandShellEnvironment`、`ShellEnvironmentVariable`、`ShellEnvironmentUtils`。
- Fable bootstrap 变体/包管理器迁移：`FableBootstrap`，保留 `apt-android-7` / `apt-android-5` 语义与静态入口。
- Fable 环境包装迁移：`FableShellEnvironment`、`FableAppShellEnvironment`、`FableAPIShellEnvironment`、`FableShellCommandShellEnvironment`。
- shell 参数解析与临时目录清理迁移：`FableShellUtils`。
- 新增环境变量校验与 `.env` 稳定排序/转义契约测试。

### 验证数据

- `./gradlew :termux-shared:compileDebugKotlin :termux-shared:compileDebugJavaWithJavac --rerun-tasks` → BUILD SUCCESSFUL。
- `./gradlew :termux-shared:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL；既有测试与新增 `ShellEnvironmentUtilsTest` 全通过。

### 仍待处理

- RUN_COMMAND/AM socket 及其余本工单边界内 shell 依赖的行为等价回归。
- 完整 APK 中 bootstrap assets / native libraries 的最终清单核对。
- Standards + Spec 双轴 code review 与提交。

2026-08-13 继续实施检查点：

### 本轮完成

- shell 小类迁移：`ArgumentTokenizer`、`ShellCommandConstants`、`StreamGobbler`、`ShellUtils`。
- 核心命令模型迁移：`ExecutionCommand`。
- 结果发送迁移：`ResultSender`，保留 PendingIntent 截断、目录输出、临时文件移动及 err 文件最后提交语义。
- local socket 迁移：`LocalClientSocket`、`LocalServerSocket`；JNI native 方法名/参数顺序保持不变。
- 新增 `ArgumentTokenizerTest`，覆盖引号、转义和 stringify 行为。

### 验证数据

- `:termux-shared:compileDebugKotlin :termux-shared:compileDebugJavaWithJavac --rerun-tasks` → `BUILD SUCCESSFUL`。
- `:termux-shared:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`，14 项通过。
- `:fable-core:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`，13 项通过。
- `:app:testDebugUnitTest --rerun-tasks`：编译通过；40 项中 1 项 `FileReceiverActivityTest.testIsSharedTextAnUrl` 在 Robolectric 类加载阶段因 `NoClassDefFoundError` / `ClassReader` 失败，未见本次 shell 迁移调用栈。
- `:app:assembleDebug --rerun-tasks` → `BUILD SUCCESSFUL`；产出 `apt-android-7` 多 ABI APK。

### 当前结论

工单 35 的环境/bootstrap、shell command/result 和 local socket 目标类已完成 Java→Kotlin 迁移，且目标 Java 文件已删除。工单仍保持“进行中”，待做 APK bootstrap/native 清单核对、双轴 code review 和提交；AM socket、AppShell、FableShellSession/FableShellManager 等剩余入口类仍需在本工单边界内继续迁移。

2026-08-13 最终检查点：

### 本轮完成

- 完成 AM socket、`AppShell`、`FableShellSession`、`FableShellManager` Kotlin 迁移；对应目标 Java 文件已删除。
- 新增 `FableBootstrapTest`，覆盖 `apt-android-7` / `apt-android-5` 变体解析、APT 包管理器解析、状态设置与非法变体拒绝。
- 串行验证通过：
  - `./gradlew :termux-shared:testDebugUnitTest :fable-core:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`；termux-shared 基础测试与 bootstrap 回归通过，fable-core 13 项通过。
  - `./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac --rerun-tasks` → `BUILD SUCCESSFUL`。
  - `./gradlew :app:assembleDebug --rerun-tasks` → `BUILD SUCCESSFUL`。
- APK 产物核对（2026-08-13）：
  - `apt-android-7` universal/arm64 APK 含 `libtermux-bootstrap.so`、`libfable-session.so`、`libfable-render.so`、`liblocal-socket.so`。
  - `app/src/main/cpp` 保留四个 ABI bootstrap zip：aarch64、arm、i686、x86_64。
  - `TERMUX_PACKAGE_VARIANT=apt-android-5` 生成 BuildConfig 时为 SDK 21–23、bootstrap release 5.0–6.0；默认构建恢复为 `apt-android-7`、最低 SDK 24。
- `git diff --check` 通过；目标迁移 Java 文件残留扫描为空。

### 踩坑与解法

- 并行运行 app 编译与 termux-shared 测试会竞争 Kotlin 增量缓存，触发 `Storage ... is already registered`；停止并行任务、`./gradlew --stop` 后串行重跑恢复。
- app `testDebugUnitTest` 仍有既知 `FileReceiverActivityTest.testIsSharedTextAnUrl` Robolectric `NoClassDefFoundError` / `ClassReader` 类加载失败；未见本次 shell 迁移调用栈，作为环境/基线问题记录，不据此修改业务代码。
- `apt-android-5` 本轮验证了 Gradle/BuildConfig 语义，尚未在当前轮次完整构建其 APK。

### 双轴复核

- Standards：无文档规范违规；仅记录迁移保留的结构性 smell（`ExecutionCommand` 聚合状态、`ResultSender` 多职责、local socket 管理职责集中、AppShell/FableShellSession 流程相似），不作为本工单阻塞项。
- Spec：迁移范围与 JNI/环境注入边界符合要求；剩余验收缺口为 app Robolectric 基线失败与 apt-android-5 完整 APK/真机验证。

### 当前结论与后续影响

工单 35 的 Java→Kotlin 行为等价迁移、构建和 apt-android-7 产物核验已完成；状态继续保持“进行中”，等待 app 单测基线问题处置及 apt-android-5/真机验证后再改为“待验收”或“已完成”。工单 40 可继续依赖本工单提供的 Kotlin bootstrap/环境快照与 shell 入口，但不得假设 app Robolectric 基线或真机 bootstrap 已验收。

2026-08-13 Robolectric 基线修复：

- 将 app 测试依赖从 Robolectric 4.8.1 升级到 4.16.1，解决旧版 ASM 无法读取 JDK 25 产生的 class major version 65。
- `FileReceiverActivityTest` 使用 `@ConscryptMode(OFF)`，避免当前 Termux ARM64 测试环境加载不存在的 Conscrypt native library。
- 修复 Kotlin `CrashHandler`：`Thread.getDefaultUncaughtExceptionHandler()` 允许为 null，转发时改为安全调用，保持 Java 原行为。
- `./gradlew :app:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`；原 `FileReceiverActivityTest.testIsSharedTextAnUrl` 已通过。
- 该项不改变 Android App 运行时的会话层、CoreAdapter 或渲染状态边界。
