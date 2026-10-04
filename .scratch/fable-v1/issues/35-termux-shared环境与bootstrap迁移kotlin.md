# 35 — termux-shared 环境与 bootstrap Java→Kotlin

**What to build:** bootstrap 安装/解包/校验、环境快照组装（AndroidShellEnvironment 等）、shell 命令与 RUN_COMMAND 依赖代码迁移为 Kotlin；环境注入边界不变（Kotlin 计算环境快照传入 Rust 会话层）。

**Blocked by:** fable-v1/34
Status: 已完成

## 验收清单

- [x] 目标包 100% Kotlin（对应 .java 删除）
- [x] bootstrap/环境 JVM 可测逻辑测试全绿（路径/版本/权限/校验逻辑）
- [x] 冷启动 bootstrap 流程构建通过（真机归工单 40）
- [x] APK 校验（bootstrap assets / 原生库存在）
- [x] 单测基线不劣化（既有基线规则）

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

2026-10-04 收尾（终检 + 双轴评审修复）：

本单代码已于 2026-08-14 提交 `8456cc0`（Java→Kotlin 成对替换），工单文件此前只停在中间检查点。本轮补做终检与收尾，并修掉双轴评审查出的行为等价缺口。

**1. 验证数据/命令**

- 双轴评审（并行子代理，Standards + Spec，对照 `8456cc0^` 的 Java 原件逐对比较）共出 5 项迁移偏差与 12 项细微差异，已逐条裁决：
  - `ShellCommandShellEnvironment` 的 `$SHELL_CMD__RUNNER_NAME` 取值错误：迁移写成 `runner.name`（Kotlin 枚举内建名 `APP_SHELL` / `TERMINAL_SESSION`），Java 原件为 `runner.getName()`（契约值 `app-shell` / `terminal-session`）。任何按契约值分支的外部脚本会全部不匹配。**已修**。
  - `$SHELL_CMD__SHELL_ID` 在 id 为 null 时：迁移用 `id?.toString()` 导致该变量不导出；Java `String.valueOf(id)` 导出字面量 `"null"`。**已改回严格等价**。
  - `ArgumentTokenizer` 空白判定：迁移用 Kotlin `Char.isWhitespace()`，它把 NBSP(U+00A0) 等 SpaceChar 也算空白，会把 Java 原件的一个 token 拆成两个。**已改回 `Character.isWhitespace(c)`**。
  - 迁移丢失方法级锁：`LocalServerSocket.start/stop/closeServerSocket/close`、`LocalClientSocket.closeClientSocket(Boolean)`、`FableShellEnvironment.init/writeEnvironmentToFile`（后者写固定 `termux.env.tmp` 再 move）Java 原为 `synchronized`。**已恢复 `@Synchronized`**。
  - `ResultSender`：suffix 为 null 时回写 `""` 的副作用被迁移省掉。**已恢复**。
- 为让环境组装逻辑可 JVM 测试，新增 `ShellCommandShellEnvironment.getEnvironmentForPackageName(packageName, executionCommand)`；原 `getEnvironment(Context, ...)` 保留并委托，Java 调用方无改动。
- 新增测试：`ShellCommandShellEnvironmentTest`（5 项：runner 契约值、package/id/shellName 导出、id 为 null 导出 `"null"`、未设置值跳过、非法 runner 空表）、`ArgumentTokenizerTest.keepsNonBreakingSpaceInsideToken`。两项关键回归均**先红后绿**验证：临时改回 `runner.name` / `c.isWhitespace()` 时对应用例 FAILED，复原后 PASS。
- 全量（2026-10-04，HEAD `775c0fe` + 本轮改动）：`./gradlew :termux-shared:testDebugUnitTest :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`；termux-shared 23/23、core 18/18、app 49/49 全绿（迁移前记录为 17/18/49，净增 6 项）。
- 目标包 Java 残留扫描：`shell/`、`shell/am/`、`shell/command/**`、`net/socket/local/`、`termux/FableBootstrap`、`termux/shell/**` 下 `.java` 为 0。
- APK 校验：`android/app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`（188,966,092 B，sha256 `177c774de275a283590d2a2f443d174b1c947645abfde15cd8fb0b971124abbf`）含 `libtermux-bootstrap.so`、`libfable-session.so`、`libfable-render.so`、`liblocal-socket.so` 与 Apple/Noto emoji assets。取出 APK 内 `libtermux-bootstrap.so`（142,302,128 B）按偏移 1368 截取 142,296,286 B，sha256 = `d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601`，与构建输入 `android/app/src/main/cpp/bootstrap-aarch64.zip` 逐字节一致 → bootstrap 语义与哈希未变。
- `git diff --check` 通过；本轮代码改动 8 个文件（6 个主源 + `ArgumentTokenizerTest` 改，`ShellCommandShellEnvironmentTest` 新增），另加工单文档。
- 本轮 diff 自身的双轴评审（`git diff HEAD`，并行子代理）：Standards 轴无硬违规、无红线触碰；Spec 轴确认 5 处修复均恢复到 `8456cc0^` 原件语义、验收清单 5 条均有依据。两轴共同提出的一点已采纳：新增的 `getEnvironmentForPackageName` 原先放开为 `packageName: String?`，而生产路径 `Context.packageName` 恒非空，已收窄为 `String` 并去掉对应测试的 null 用例（公开可见性保留，理由是 AGP 内置 Kotlin 下 `internal` 对 Java 测试不可见）。

**2. 踩过的坑与解法**

- 工单文件与实际代码状态脱节：本单代码 2026-08-14 已提交，但 Comments 停在中间检查点、Status 停在「进行中」，后续 `36/37/38` 都已收口。判定依据是 `git log`（`8456cc0` 在 HEAD 祖先链上）+ 目标包 `.java` 扫描为空，不据工单文件的 Status 行动。
- 迁移的「静默语义漂移」集中在枚举取值、null 处理与注释掉的锁上：`runner.name` 编译通过、测试全绿、真机也不报错，只有逐对读 `8456cc0^` 原件才能发现。**教训：行为等价迁移的验收必须包含「与原件的逐对比较」，不能只看测试是否绿。**
- `@Synchronized` 在 Kotlin `companion object` 上锁的是伴生对象实例，Java 的 `synchronized static` 锁的是 Class 对象；两个方法同属一个伴生对象，互斥语义一致，但不是同一个 monitor（已在代码注释中标明）。
- 明文 NBSP 写进 Java 测试源码会在编辑器/工具链里被规范化：改用 `\u00A0` 转义。
- Termux 下 `/tmp` 不可写，临时文件必须落 `$TMPDIR`。

**3. 明确保留的刻意偏离（不阻塞，仅供后续评审不再重复提出）**

- `FableAppShellEnvironment` 用 `uppercase()`（无区域依赖）替代 Java 的 `toUpperCase()`（默认区域）；`ResultData` 用 `listOfNotNull` 替代 `Collections.singletonList`；`FableShellSession` transcript 为 null 时不追加字面量 `"null"`；`ShellEnvironmentUtils.convertEnvironmentToDotEnvFile` 用 `sortedWith` 不改写入参（已确认无调用方依赖原地排序）；`LocalClientSocket` 构造器与 `mLocalSocketRunConfig` 可见性放宽、`read(bytes)` 的 null 检查在 Kotlin 类型下恒假（Java 传入 null 仍抛 NPE，异常类型相同）。
- `AmSocketServer` error 分支缺 `return` 导致 `sendResultToClient` 可能调用两次、`LocalSocketErrno` 两个 errno 同值 158 —— 均为 Java 原件既有问题，非本次迁移引入，本单不动。
- `StreamGobbler`：Java 实例方法用 `synchronized (this)`、静态计数器用 `synchronized (StreamGobbler.class)`；Kotlin 改为私有 `stateLock` 对象与伴生对象上的 `@Synchronized`。monitor 身份不同，但锁未对外暴露（全仓无外部 `synchronized(gobbler)` 调用点），组内互斥语义一致，保留不改。

**4. 结论写回（对后续工单/决策的输入）**

- 工单 35 边界内的环境/bootstrap/shell/local socket 迁移已收口并通过终检，工单 `40` 可直接依赖本单的 Kotlin bootstrap/环境快照与 shell 入口做真机验收；**不得**据本单推断 bootstrap 真机冷启动已验收（真机仍归工单 `40`）。
- `termux-shared` 主源仍有 16 个 `.java` 残留，**均不在本单边界**，且无任何工单认领（工单 34 的交接只划到「环境/bootstrap、shell、local socket、FableShellEnvironment 相关」，36/37/38 各自已收口）：
  `termux/plugins/FablePluginUtils`(472)、`termux/TermuxConstants`(1335)、`termux/extrakeys/*`(6 个，1360)、`termux/terminal/*`(2 个，221)、`termux/terminal/io/*`(2 个，164)、`termux/models/UserAction`(18)、`termux/interact/TextInputDialogUtils`(72)、`activities/ReportActivity`(480)、`activities/TextIOActivity`(281)。
  其中 `FablePluginUtils`（RUN_COMMAND 的结果处理/策略校验）与本单措辞最接近，但按 ADR-0009 决策 15「外部入口本次暂保留」的节奏，入口本体的原生化应另开窗口。**建议开一张「termux-shared 残留 Java 收口」工单统一认领**，本单不扩范围。
- 证据缺口如实记录：`apt-android-5` 变体的完整 APK 本轮仍未构建（变体非出货默认，且真机归工单 40），本单不补。
