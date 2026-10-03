# 05 — Android 15 权限与 targetSdk 升级

**What to build:** 升级 targetSdk/compileSdk 并完成 Android 15 适配：通知权限（POST_NOTIFICATIONS）、前台服务类型、edge-to-edge；确保会话在切后台、锁屏后仍存活，构建与产物校验保持通过。

**Blocked by:** 01

Status: 已完成

- [x] SDK 升级后构建通过、产物校验通过（缝 3）
- [x] 通知与前台服务符合 Android 15 要求（声明/构建/单测 PASS + 真机验收通过）
- [x] edge-to-edge 下终端不被状态栏/导航栏遮挡（真机验收通过，含设置页第二轮修复）
- [x] 会话在切后台/锁屏后存活（真机验收通过）

## Comments

### 2026-08-08 实施（代码完成，待真机验收）

#### 1. 升级前 baseline

- master @ `aefeba6`（工单 04 完成后）；`./gradlew :app:assembleDebug` 通过。
- arm64-v8a debug APK 163,619,856 B，`targetSdkVersion:'28'`，5 个 arm64 .so（含 libfable-render.so），Fable 签名 SHA-256 `<证书指纹>`。
- app 全量单测 28 个，其中 `FileReceiverActivityTest#testIsSharedTextAnUrl` 失败（Robolectric `NoClassDefFoundError: android/webkit/RoboCookieManager`）——既有失败，与本单无关（见坑 3）。

#### 2. 改动清单

- `app/build.gradle`：`buildTargetSdk` 28 → 35（compileSdk 36 / minSdk 24 不变）。
- Manifest：`+POST_NOTIFICATIONS`、`+FOREGROUND_SERVICE_SPECIAL_USE`；TermuxService / RunCommandService 声明 `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（`@string/fgs_special_use_subtype`，`translatable=false`——Manifest 引用的资源不能按语言变化）。
- PermissionUtils：`REQUEST_NOTIFICATION_PERMISSION=1001`；`isNotificationPermissionGranted`（API<33 视为已授予）；`shouldRequestNotificationPermission(granted, askedBefore)`（纯逻辑，可单测）；`requestNotificationPermission`（弹窗一次 + 记录 `askedBefore`，存 `com.gph.fable_preferences`）；拒绝/撤销后不再自动弹窗，只降级。
- TermuxActivity：onStart 请求通知权限；拒绝/撤销时进程内一次性降级 Toast；动态接收器改 `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)`（targetSdk 34+ 要求）；`updateTermuxActivityStyling` 广播补 `setPackage`（显式广播，消 lint `UnsafeImplicitIntentLaunch`）。
- PendingIntent 全部补 `FLAG_IMMUTABLE`：TermuxService ×3（通知点击/退出/唤醒锁）、TermuxCrashUtils ×2、TermuxPluginUtils ×2（crash/插件错误通知，`FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE`）——targetSdk 31+ 不指定可变性会抛 `IllegalArgumentException`。
- 新增单测：`Android15ManifestTest`（4 项 Manifest 声明锁 + 1 项 `fitsSystemWindows` 布局锁）、`NotificationPermissionPolicyTest`（3 项决策）。

#### 3. 验证数据（升级后）

- 单测：新增 8/8 绿；`:terminal-emulator:testDebugUnitTest` BUILD SUCCESSFUL；app 全量 28 个测试仅既有 FileReceiverActivityTest 失败（见坑 3）。
- 构建：`:app:assembleDebug` BUILD SUCCESSFUL（约 1m17s）。
- 产物校验（arm64-v8a debug APK 163,619,856 B）：
  - `aapt2 dump badging`：`targetSdkVersion:'35'`；uses-permission 含 POST_NOTIFICATIONS、FOREGROUND_SERVICE_SPECIAL_USE。
  - `aapt2 dump xmltree --file AndroidManifest.xml`：两个 service 均 `foregroundServiceType=0x40000000`（specialUse）+ PROPERTY_SPECIAL_USE_FGS_SUBTYPE ×2。
  - `unzip -l`：5 个 arm64 .so，含 libfable-render.so。
  - `apksigner verify`：Signer #1 <签名证书主体>（SHA-256 `<证书指纹>`，与 baseline 一致）。
- lint：本单新增错误 0；剩余 4 个错误全部为既有 FableDiagnostics NewApi（MediaStore.Downloads 需 API 29、minSdk 24，工单 04 遗留，见结论）。

#### 4. 权限与前台服务类型决策

- 通知权限：声明 + 运行时请求一次（`askedBefore` 门控，尊重用户选择）；拒绝/撤销 → FGS 照常运行、通知被系统隐藏（官方文档：起 FGS 不要求 POST_NOTIFICATIONS，只是通知不显示），进程内一次性 Toast 降级提示；不重弹、不 crash。
- 前台服务类型：两个服务均 `specialUse`（不用 dataSync）——TermuxService 会话可无限长驻，Android 15 对 dataSync 有 24h 内共 6h 上限（官方 FGS timeout 文档），用 dataSync 会强杀长会话；RunCommandService 是转发器（起 TermuxService 后即停），与主服务保持同一类型、FGS 策略一致。
- edge-to-edge：targetSdk 35 强制 edge-to-edge；本 App 主题本就 `windowTranslucentStatus/Navigation` + 根布局 `fitsSystemWindows="true"` + insets listener，等效边到边布局已生效多年，升级后行为不变（系统栏改透明、内容已按 insets padding），未做整窗 opt-out（按已定决策）。真机现象待验收。

#### 5. 行为变更清单（28 → 35）

- POST_NOTIFICATIONS（API 33+）→ 已实现（声明 + 运行时请求 + 降级）。
- 前台服务类型必填 + `FOREGROUND_SERVICE_*` 权限（API 34+）→ 已实现（specialUse + 子类型属性）。
- PendingIntent 必须显式可变性（API 31+）→ 已实现（FLAG_IMMUTABLE，7 处）。
- 动态接收器必须指定导出标志（API 34+）→ 已实现（RECEIVER_NOT_EXPORTED；SystemEventReceiver 只收系统广播，按官方豁免不传标志）；非导出接收器的发送方改显式广播。
- edge-to-edge 强制（API 35）→ 布局已适配（fitsSystemWindows + insets），待真机验收。
- 前台服务后台启动限制（API 31+）→ 不适用：TermuxService 由前台 Activity `startService` 启动、RunCommandService 用 `startForegroundService`；BOOT_COMPLETED 只重置计数器不起服务。
- dataSync/mediaProcessing 6h 超时（API 35）→ 不适用（未用 dataSync）。
- 精确闹钟默认拒绝（API 34+）→ 不适用（无 setExact / SCHEDULE_EXACT_ALARM，仅声明 SET_ALARM）。
- 隐式 intent 只到 exported 组件（API 34+）→ 已规避（通知 PendingIntent 全 IMMUTABLE + 显式组件；内部广播显式化）。
- 包可见性（API 30+）→ 基本不适用（正常路径只查自身包；插件错误报告查调用包可能返回 null，仅影响报告文本，Fable 不依赖配套 App）。
- Configuration 不再排除系统栏（API 35）、16KB page、elegantTextHeight、TLS 1.0/1.1 限制、音频焦点限制 → 均不适用/系统侧，与本次改动无关。

#### 6. 坑与解法

1. lint `MissingTranslation` / Manifest 资源语言变化：`fgs_special_use_subtype` 先放进 values-zh-rCN 触发 "Resources referenced from the manifest cannot vary by configuration"；解法 = 只放 `values/` + `translatable="false"`。
2. lint `UnsafeImplicitIntentLaunch`：RECEIVER_NOT_EXPORTED 后，ACTION_RELOAD_STYLE 隐式广播被报错；解法 = 发送方 `setPackage`（与 TermuxCrashUtils 既有写法一致）。副作用：外部 Termux:Styling 的 reload 广播不再可达——Fable 不依赖配套 App（ADR 已定），可接受。
3. 既有失败：FileReceiverActivityTest#testIsSharedTextAnUrl 在 JDK 25 / Robolectric 4.8.1 下 `NoClassDefFoundError: android/webkit/RoboCookieManager`；升级前后都失败，与本单无关，未处理（红线：不动无关文件）。
4. 既有 lint：FableDiagnostics NewApi ×4（MediaStore.Downloads 需 API 29、minSdk 24）——工单 04 遗留，本单不动。
5. 上游 termux-app 无参考价值：upstream master 仍 targetSdk 28（无 POST_NOTIFICATIONS、无 foregroundServiceType）；本单以官方文档为准（fgs-types-required / fgs timeout / notification-permission / behavior-changes-14/15，2026-08-08 核实）。

#### 7. 结论写回

- 工具链尾账 "targetSdk 28 评估" = 完成（本单升级 35，构建/产物/单测全绿）。
- ADR-0001 Compose 重开条件部分满足：compileSdk 36 + targetSdk 35 + AGP 9.3 + Gradle 9.7 已就位；仍等"界面现代化"阶段再评。
- 对后续影响：edge-to-edge / 通知 / FGS 三项真机验收项等待用户真机确认（<厂商 ROM> 通知权限交互与原生 Android 15 不同，以 Android 15 为准、<厂商 ROM> 差异记坑）；FableDiagnostics lint 债建议随工单 04 收尾或新 backlog 清理。
- 权限策略定案：通知权限"弹一次、尊重选择、撤销只降级"；前台服务类型定案 specialUse（防 dataSync 6h 杀会话）。

### 2026-08-08 第二轮实施（用户验收反馈修复，代码完成待真机复验）

#### 验收记录（2026-08-08）

用户真机验收通过：通知权限与前台服务行为正常、通知文案汉化确认、设置页状态栏/手势条适配确认、设置子页动画确认、edge-to-edge 不遮挡、切后台/锁屏会话存活。工单 05 关闭。

#### 本轮改动（用户拍板的方案）

1. **全界面 edge-to-edge 适配**（新增 `SystemBarInsets` 共享工具，全部走系统 insets）：
   - 顶部：根布局 padding = 状态栏 inset，不再与状态栏重叠；
   - 底部：手势条 inset + `clipToPadding=false`，背景延伸到手势条后面、不铺色块遮罩，内容可滑入手势条区域，遮挡的只有手势条本身；
   - 覆盖界面：SettingsActivity（含所有设置 fragment，统一 `FablePreferenceFragment` 基类）、ReportActivity、TextIOActivity、HelpActivity（WebView 外包容器，WebView 自身不按 padding 布局）、SpikeActivity / RenderActivity；TermuxActivity 主界面维持原 fitsSystemWindows（用户确认无问题）；FileReceiverActivity 无界面（仅对话框），豁免；
   - 设置/报告/文字编辑页根背景统一 `?android:attr/colorBackground`，手势条区域与界面同色。
2. **设置子页面过渡动画**：SettingsActivity 实现官方 `OnPreferenceStartFragmentCallback`，fragment 事务用系统 `TRANSIT_FRAGMENT_OPEN` + `setReorderingAllowed(true)`（预测性返回），不写自定义动画；Activity 间保持系统默认过渡。
3. **Toast 审计**：确认全部走系统 Toast（<厂商 ROM> 样式，应用名是 <厂商 ROM> 自带不可控）；唯一硬编码英文（SharedProperties 属性文件加载错误）改为资源文案；其余 50 处调用点审计结论 = 已走资源字符串（zh 已在工单 04 翻译）或技术性错误串/会话标题（不可翻译），无 Termux 直译残留。
4. **通知汉化补全**：正文 "N 个会话 / N 个任务 / 已持有唤醒锁"（plurals 资源）；四个通知渠道名全部资源化 + 中文（前台服务 / 命令执行 / 崩溃报告 / 插件命令错误）。

#### 验证数据（第二轮）

- `:app:assembleDebug` BUILD SUCCESSFUL；arm64 APK targetSdk 35、5 个 .so（含 libfable-render.so）、Fable 签名校验 OK、渠道名资源入包。
- 单测：app 31 个（新增 3 条锁测试：设置页 colorBackground、通知 plurals 中英资源），仅既有 FileReceiverActivityTest 失败；terminal-emulator 全绿。
- lint：本轮新增错误 0；仅既有 FableDiagnostics NewApi ×4（工单 04 遗留）。新增 1 条良性 overdraw 警告（根背景与 windowBackground 双绘，接受）。

#### 坑

1. `android.R.anim.fragment_open_enter` 等是隐藏资源无法编译引用 → 退回到纯机制方案（`TRANSIT_FRAGMENT_OPEN` + 预测性返回），符合"不写自定义动画"原则。
2. WebView 不按自身 padding 布局网页内容 → insets 必须加在外层容器。
3. 通知渠道名在系统创建后不可改：老安装升级后渠道仍显示旧名，需卸载重装或清数据才能看到新渠道名（记录，非 bug）。
4. androidx.preference 1.2.1 fallback 打开子页面时事务不带任何过渡（logcat 有提示）→ 必须实现 `OnPreferenceStartFragmentCallback`。

#### 结论写回

- 用户验收反馈的三项（设置页状态栏重叠、底部遮罩、子页无动画）代码层已修，待真机复验；通知汉化待真机确认。
- 设置 fragment 手势条适配统一收口到 `FablePreferenceFragment` 基类，后续新增设置页自动继承。
- <厂商 ROM> 小窗问题与 edge-to-edge 适配有交集，但本体走独立 triage（`.scratch/fable-v1/triage.md`），不在本单。
