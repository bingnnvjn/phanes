# 38 — app 设置与杂项 Java→Kotlin

**What to build:** app 设置页/关于/Report/FileReceiver 等残留 Java 类迁移为 Kotlin；中文字符串资源保持；设置行为等价。

**Blocked by:** fable-v1/37
Status: 已完成

## 验收清单

- [x] 目标类 100% Kotlin（对应 .java 删除）
- [x] 设置项行为等价：字号/主题/键盘/会话恢复等 JVM 可测逻辑全绿
- [x] 中文界面资源不回归
- [x] APK 构建 + 校验通过（真机归工单 40）

## Comments

2026-08-12 建单（决策窗口 9，ADR-0009 决策 1/6）。

2026-08-18 开始实施：按工单 37 已完成的 Kotlin 壳 ABI 基线，分为“设置/关于”和“FileReceiver/系统外部入口”两组迁移。先以现有公开行为建立 JVM 契约，再逐类删除对应 Java 并恢复 Kotlin 等价实现；中文资源、Manifest/XML 的完整类名与偏好键保持不变。

2026-08-18 实施完成：

### 验证数据

- app 11 个目标 Java（设置/关于、FileReceiver、SystemEventReceiver、DocumentsProvider、UserAction）均已删除并由同 FQCN Kotlin 替换；`find app/src/main ... -name '*.java'` 对本单目标为空。
- 新增设置迁移、主题/字号/键盘/边距 DataStore、XML 反射目标、简中资源键与 FileReceiver 静态 ABI 契约；`./gradlew :app:testDebugUnitTest :app:assembleDebug --no-parallel --no-daemon -x :app:downloadBootstraps`（JDK 25）→ `BUILD SUCCESSFUL`，app JVM 49 项通过。
- APK `app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`：`aapt2 dump badging` = `com.gph.fable` / versionCode `1022`；包含 `libfable-render.so`、`libfable-session.so`、`liblocal-socket.so`、`libtermux-bootstrap.so`；`apksigner verify --print-certs` 通过。
- `git diff --cached --check` 通过；`javap` 确认 Settings/Help/FileReceiver/SystemEventReceiver/DocumentsProvider 保持同类名、无参组件构造和 FileReceiver/SystemEventReceiver 静态入口。

### 踩过的坑与解法

- Java→Kotlin 首轮暴露了 `@JvmField lateinit`、Java getter 映射及平台类型可空性差异；逐项恢复原字段/空指针语义后由编译门禁锁定。
- Kotlin 默认 `final` 会收窄原 Java 组件的可继承性，已将原非 final Android 组件改为 `open class`；Settings 的 Root fragment 保持非 inner 嵌套类。
- DocumentsProvider 的 MIME 后缀大小写需沿用 Java 默认 Locale，已使用 `lowercase(Locale.getDefault())`，不引入无关的行为修正。
- `app/<上游测试签名材料>` 为工作树原有删除，未暂存、未纳入本单。

### 结论写回

- 设置页的主题、字号、键盘、边距与调试偏好键保持不变；会话恢复继续由既有 `RecentSessionStoreTest` 覆盖。中文资源未修改且由迁移契约检查键存在。
- 外部 FileReceiver alias、动态 package receiver、SAF DocumentsProvider 与 About→Report 调用均只做语言迁移；`ReportActivity` 实体继续留在 termux-shared 边界。
- 工单 39 可直接基于全 Kotlin 的 app 设置/外部入口层继续实现浮条与“更多”入口；真机外部分享、文件查看、设置、中文和会话恢复回归归工单 40。
