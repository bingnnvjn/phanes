# 36 — terminal-view Java→Kotlin

**What to build:** 输入视图与配套（手势/缩放/长按选择/选择手柄/扩展键/输入翻译调用）迁移为 Kotlin；仅依赖 fable-core 缝，不依赖旧画法。

**Blocked by:** fable-v1/33
Status: 已完成

## 验收清单

- [x] terminal-view 目标代码 100% Kotlin（对应 .java 删除）
- [x] 手势/选择/扩展键的纯逻辑抽缝后有 JVM 测试覆盖并全绿
- [x] 与 app / termux-shared 互调编译通过
- [x] APK 构建 + 校验通过（真机归工单 40）

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 1/5：可与工单 34/35 并行，注意共享构建文件冲突）。

2026-08-14 实施完成：

- 迁移 `terminal-view` 目标代码：删除 8 个 Java，新增 9 个 Kotlin；`find terminal-view/src/main -name '*.java'` 为 0。
- 保留并补齐输入/手势/选择等价路径：IME `commitText`、组合键/控制字符翻译、扩展键、Back→Escape、mouse tracking 点击/移动/滚轮、alternate buffer DPAD 滚动、scrollback/自动滚动、ActionMode `TYPE_PRIMARY`、选择手柄绘制/可见性、Autofill、cursor blinker。
- 纯逻辑抽缝 `TerminalViewInteractionLogic` 增加滚动余量、滚动目的地、鼠标方向、选择归一化、控制字符翻译；JVM 测试覆盖并全绿。
- 验证命令：
  - `./gradlew :terminal-view:testDebugUnitTest --rerun-tasks --no-parallel`
  - `./gradlew :termux-shared:compileDebugKotlin :termux-shared:compileDebugJavaWithJavac :app:compileDebugKotlin :app:compileDebugJavaWithJavac`
  - `./gradlew :app:testDebugUnitTest --rerun-tasks --no-parallel`
  - `./gradlew :app:assembleDebug --no-parallel`
  - `./gradlew :terminal-view:sourceJar --rerun-tasks`（sources JAR 含 Kotlin 文件）
- 坑与解法：`terminal-view` 原 sourceJar 只收集 `src/main/java`，已补 `src/main/kotlin`；Gradle 并行资源任务偶发不可读目录，改串行重跑通过；APK 日志中的 `aapt2 No package ID 7f` 为现有构建噪音，构建仍成功。
- 对工单 37：后续迁移应沿用 `CoreAdapter` 单一缝；输入/选择行为先抽纯逻辑再写 JVM 回归，避免只验证 ABI/编译而漏掉交互语义。
- `fable-app` 提交：`9f9d028 refactor(view): migrate terminal view to Kotlin`。

2026-08-16 地毯式复审与修复：

- 修复会话切换/Activity detach 的 adapter 所有权与生命周期：旧会话不再解绑 client，新会话先切 session 再绑定 adapter；重置行列、滚动、组合字符；Surface/会话移除时先停止输入再销毁渲染器，并回收残留 SurfaceView。
- 恢复 IME 行为等价性：输入类型、组合文本 finish、LF/C0/Shift 翻译、Unicode surrogate、按长度退格、无 session 时清空编辑缓冲；修复多指缩放首个长按误开选择、手柄坐标四舍五入。
- 恢复 Autofill API 26 以下保护与 `cancel()`，无障碍 content description、键盘诊断日志、字号/字体更新、坐标换算、选择同步与旧公开 ABI。
- 验证命令与结果：
  - `./gradlew :terminal-view:testDebugUnitTest --rerun-tasks --no-parallel`（通过，10 tests）
  - `./gradlew :termux-shared:compileDebugKotlin :termux-shared:compileDebugJavaWithJavac :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:testDebugUnitTest --rerun-tasks --no-parallel`（通过）
  - `./gradlew :app:assembleDebug --no-parallel`（通过）
  - `./gradlew :terminal-view:sourceJar --rerun-tasks`（通过；9 Kotlin/0 Java）
  - arm64 APK `aapt2 dump badging`、原生库清单、`apksigner verify --print-certs`（通过）
- 踩坑：本机 SDK `aapt2` 为 x86_64，校验需使用 Termux 原生 `aapt2`；构建中的 `No package ID 7f` 为既有噪音，不影响产物。
- 结论：工单 36 的迁移、行为等价性与发布 ABI 检查闭环；真机交互验收继续归工单 40。

2026-08-17 复审补丁：

- 修复选择滚动时 viewport-relative overlay 未重新同步、单字符同格选区被误判为空、长按 300ms 防抖期间提前清空 overlay，以及手势结束残留滚动小数的问题。
- 恢复无会话时 `isCursorEnabled()` 的旧语义、基础坐标换算与 adapter 重绑时旧 overlay 清理；新增/调整纯逻辑回归仍为 10 tests。
- 复审结果：未发现新的规范硬违规；`app/<上游测试签名材料>` 为用户既有删除，明确排除本工单提交。
