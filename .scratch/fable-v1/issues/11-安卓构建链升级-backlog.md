# 11 — 安卓构建链升级（Gradle / AGP / JDK）— backlog

**What to build:** 把 fable-app 的安卓构建链升级到当前 stable 最新版：**Gradle 7.2 → 9.7.0、AGP 4.2.2 → 9.3.0、JDK 17 → 25（不稳定则退 21）、compileSdk 30 → 36**。跨 8 个大版本需同步迁移 namespace / DSL / 老依赖；**保留** Termux aapt2 override 与 jniLibs 直供 .so 方案（平台限制，不是版本问题）。Rust 部分（cargo 构建）不受影响。

**Blocked by:** None（触发条件已满足：渲染切片 09/10 完成，作为回归样本）。

Status: 已完成

## 验收清单

- [x] 升级前 fable-app 打 git tag / 备份（可回退）：`before-toolchain-upgrade`
- [x] Gradle wrapper 9.7.0（腾讯镜像）+ AGP 9.3.0 + JDK 25（已装 openjdk-25 25.0.4）
- [x] namespace / DSL 迁移完成（project.properties 全局参数 → 模块内配置；老 DSL 写法更新）
- [x] compileSdk 36 + build-tools 36
- [x] **aapt2 override（Termux aapt2）在 AGP 9 下验证有效**（见 Comments 证据）
- [x] 老 androidx 依赖升级后 lint / R8 通过
- [x] 构建成功 + 产物校验（aapt2 badging / apksigner / .so 清单含 libfable-render.so）
- [x] 真机验收：主终端 + Fable Render 探针均正常（2026-08-07 用户确认）
- [x] 结论写回（坑 / 证据 / 对后续影响），更新 Status（真机项待用户验收 → 待验收）

## 记录

- 2026-08-07 用户拍板：现阶段不更新，等基本稳定后追加本工单执行。
- 当前版本：Gradle 7.2（2021）、AGP 4.2.2（2021）、JDK 17（构建脚本 JAVA_HOME 指向 java-17-openjdk）。
- 升级风险评估：AGP 4.2 → 9.x 需 namespace 迁移、Gradle 9、DSL 调整、Java 11 默认源/目标；必须做全量回归（出包 + 签名校验 + 真机）。
- 2026-08-07 触发：渲染切片 09/10 已完成（回归样本就绪）；目标版本已核实（2026-08-07）：Gradle 9.7.0 / AGP 9.3.0 / openjdk-25 可用（Termux）/ compileSdk 36。

## Comments

2026-08-07 建单。

2026-08-07 实施记录（主线程）：

**1. 验证数据/命令**
- 回滚点：`git tag before-toolchain-upgrade`（fable-app HEAD）。
- 版本：Gradle 9.7.0（腾讯镜像 `mirrors.cloud.tencent.com/gradle/gradle-9.7.0-bin.zip`，HTTP 200）、AGP `com.android.tools.build:gradle:9.3.0`、JDK `openjdk-25` 25.0.4（`pkg install openjdk-25`，Termux 源可得）、compileSdk 36、build-tools 36。
- 构建命令（新）：`cd fable-app && export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-25-openjdk && ./gradlew :app:assembleDebug`。
- `:app:assembleDebug` 成功（约 1m30s，113 任务）；`:app:lintDebug` 成功（0 error，41 warning 属存量）；`:app:assembleRelease` 成功（R8 minify 通过，约 4m）。
- 产物校验（debug arm64-v8a，151MB / release universal，144MB）：
  - `aapt2 dump badging`：`package: name='com.gph.fable' versionCode='1022' versionName='0.119.0-beta.3' compileSdkVersion='36'`，minSdk 24、targetSdk 28。
  - `apksigner verify --print-certs`：Signer <签名证书主体>，SHA-256 `<证书指纹>`。
  - `unzip -l | grep '\.so'`：lib/arm64-v8a 下 libfable-render.so、libghostty-spike.so、liblocal-socket.so、libtermux-bootstrap.so、libtermux.so 均在。
- aapt2 override 证据：SDK 自带 aapt2 为 x86_64，直接执行报 `Exec format error`（无法在 aarch64 跑）；Termux `/data/data/com.termux/files/usr/bin/aapt2 version` = `2.20-android-16.0.0_r4`，全部资源任务（compile/link/package）用它完成，badging/dump 正常。构建日志中大量 `No package ID 7f found` 为存量噪音：`build.log`（AGP 4.2.2 时代）已有 954 条，非本次回归。
- 真机：`pm install -r` / `cmd package install --user 0` 均被 <厂商 ROM> <系统分身空间> 拦截（`SecurityException: You either need MANAGE_USERS or CREATE_USERS permission to: query users`），与工单 07 记录一致，无法代装。APK 已复制到 `~/storage/downloads/`：`fable-toolchain-debug-arm64.apk`、`fable-toolchain-release-universal.apk`，待用户安装后验收主终端 + Fable Render 探针。
- APK SHA-256：debug arm64-v8a = `25d6bac76e66f306bf0a5c86cf7f446464734a8f8f35720a4509ef2f50d7321e`；release universal = `f0e7975799d723699c641848bf1afdbd1017f53b02e1438e417e98b744f871bf`。

**2. 踩过的坑与解法**
- AGP 9 移除 `applicationVariants`/`libraryVariants`：产物命名改用 `androidComponents.onVariants` + `variant.outputs` + `FilterConfiguration.FilterType.ABI`；bootstrap 下载依赖改 `tasks.matching`（compile*JavaWithJavac、排除测试任务）。
- AGP 9 不再自动创建 Maven 组件：三个库模块需 `android { publishing { singleVariant("release") } }`，否则 `components.release` 不存在。
- AGP 9 默认禁用 BuildConfig：app 模块加 `buildFeatures { buildConfig = true }`。
- `getDefaultProguardFile('proguard-android.txt')` 在 AGP 9 被禁：改用 `proguard-android-optimize.txt`（R8 默认开启优化）。
- AGP 9 默认 NDK r28c：termux-shared 未显式指定时差点下载 28.2.13676358；四个模块统一显式 `ndkVersion = "22.1.7171670"`（假 NDK 方案不变）。
- R 命名空间化：库资源不再进 app 的 `R`，`R.string.action_yes/no`、`R.raw.bell` 改 `com.gph.fable.shared.R.*`；`Theme_AppCompat_Light_Dialog` 改 `androidx.appcompat.R.style.*`。
- `javax.annotation.Nullable` 随 guava 升级消失：termux-shared 显式加 `com.google.code.findbugs:jsr305:3.0.2`。
- 真机主终端闪退（`NoClassDefFoundError: Failed resolution of: Landroidx/window/WindowManager;`）：`androidx.preference:preference:1.2.1` → `androidx.slidingpanelayout:slidingpanelayout:1.2.0` → 把 `androidx.window:window:1.0.0-alpha09` 顶到 `1.0.0`，而 1.0.0 已移除旧 `androidx.window.WindowManager` 类。解法：`ViewUtils.getDisplaySize()` 迁到新 API `androidx.window.layout.WindowMetricsCalculator`，依赖固定 `androidx.window:window:1.0.0`。真机复测：主终端 + Fable Render 均正常，无新崩溃通知。
- compileSdk 36 下 `WebSettings.setAppCacheEnabled` 已从 SDK 移除：删掉该调用（保留 `setCacheMode(LOAD_NO_CACHE)`）。
- androidx.core 1.19.0 要求 compileSdk 37（AAR metadata `minCompileSdk=37`），本工单决策 compileSdk 36 → 固定 `androidx.core:core:1.18.0`（minCompileSdk=36）。
- lint 新报 `MissingSuperCall`（TermuxActivity.onBackPressed 未调 super）：else 分支改 `super.onBackPressed()`。
- `android:extractNativeLibs` 禁止写在 source manifest：移除，改由 `packaging { jniLibs { useLegacyPackaging = true } }` 控制（保留解压行为）。
- Groovy 空格赋值语法（`namespace "x"`、`compileSdk 36`）Gradle 9 已弃用、Gradle 10 移除：本次新增行已改 `=` 赋值；存量旧写法（splits/signingConfig 等）仍有告警，后续顺手清理。

**3. 结论写回（对后续影响）**
- 构建链正式进入 Gradle 9 / AGP 9 / JDK 25 时代；aapt2 override 与 jniLibs 直供 .so 方案在 AGP 9 下继续有效，红线未触发。
- androidx 版本基线已刷新：annotation 1.10.0、core 1.18.0、drawerlayout 1.2.0、preference 1.2.1、viewpager 1.1.0、material 1.14.0、appcompat 1.7.1、window 1.0.0（ViewUtils 已迁移 WindowMetricsCalculator）、guava 33.6.0-jre、desugar_jdk_libs 2.1.5、jsr305 3.0.2；commons-io 2.5 按代码注释约束保留。
- targetSdk 保持 28 未动（本工单只升 compileSdk；targetSdk 提升属行为变更，另行决策）。
- 后续升级注意：core 1.19+ 需要 compileSdk 37；Gradle 10 会移除 Groovy 空格赋值，建议下一轮把存量 DSL 赋值语法统一改掉；NDK 默认已升 r28c，任何新模块必须显式写 `ndkVersion`。
- 本机安装仍被 <厂商 ROM> 限制，验收流程固定为：用户从 Download 安装 APK 后人工确认。真机项已于 2026-08-07 确认（主终端 + Fable Render 均正常），工单完成。
- 最终交付（2026-08-07）：`fable-toolchain-debug-arm64.apk` sha256 `eef0f468e4b52e9d84ad047216d6601e8199a74e8a1fd788df55efd19cc2d102`；`fable-toolchain-release-universal.apk` sha256 `dac5a17c932b12a2b6e22058c5cf8ade86f53dfc6df83bbb4eb7093de88fd9f4`。
