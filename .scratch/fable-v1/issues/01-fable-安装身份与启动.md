# 01 — Fable 安装身份与启动

**What to build:** 把 fork 的包名从 com.termux 改为 com.gph.fable（含 Java 包名、权限名、provider authorities、sharedUserId 等一整套身份），用个人 keystore 签名，固化"构建 + 校验"一条命令；产出 APK 装到手机后，点击图标直接进入终端，能执行基本命令。这是宽重构，按原子重命名处理（不 expand-contract），构建与产物校验是绿灯门槛。

**Blocked by:** None — can start immediately.

Status: 已完成

- [ ] APK 包名为 com.gph.fable（aapt2 badging 校验）
- [ ] APK 由个人 keystore 签名（apksigner verify），与已装 F-Droid Termux 不冲突
- [ ] APK 内含 arm64 原生库（内容清单校验）
- [ ] 构建 + 校验可一条命令复现
- [x] 装到手机成功，图标与名称显示 Fable（寓言）
- [x] 点击图标直接进入终端，echo/pwd 可交互，启动不崩溃（bootstrap 变体一致性）

## Comments

2026-08-05 开工前检查（主线程）：构建环境已就位（JDK 17、SDK platforms 30/34/35/36、原生 aapt2、<上游测试签名材料>）。发现并修复一个问题：假 NDK shim（android-sdk/ndk/21.4.7075529 与 22.1.7171670 的 ndk-build）硬编码了项目旧路径 `CODEX/fable-app`，导致 ndkBuild 任务失败；已改为 `CODEX/Fable/fable-app` 并加日志目录兜底。修复后 `:app:assembleDebug` 构建成功（58s）。基线产物：包名 com.termux、versionCode 1022、debug 签名（CN=APK Signer）、arm64-v8a APK 含 3 个 .so。当前 compileSdk=30/targetSdk=28（工单 05 处理）。fable-app 工作树有先前的未提交修改（gradle.properties、gradle-wrapper.properties 与几个日志文件），保留未动。

2026-08-06 实施完成记录（主线程）：包名 com.termux → com.gph.fable 全量重命名已完成并验证（提交 860a920 + 收尾提交），个人 keystore 签名就位，构建 + 校验一条命令可复现。

- 重命名范围：applicationId、manifest package、`TERMUX_PACKAGE_NAME` 占位符（sharedUserId、RUN_COMMAND 权限与 intent action、documents/files provider authorities）、Java 包名/import、资源实体（`&TERMUX_PACKAGE_NAME;`/`&TERMUX_APP_NAME;`）、JNI 导出符号与 FindClass 字符串（Java_com_gph_fable_*），并用 Termux clang 重编 3 个 arm64 原生库。Maven groupId 同步更新。有意保留：外部依赖 `com.termux:termux-am-library`（AmSocketServer import）、README 上游说明、`gradle.properties` 的 aapt2 覆盖路径（系统路径，非身份）。
- 签名：`keystore/<旧签名材料>`（<签名证书主体>，RSA-4096，有效期至 2053-12-21），debug 与 release 共用；release 构建验证通过，证书 SHA-256 `<证书指纹>`，与 TermuxConstants.APK_RELEASE_TERMUX_DEVS_SIGNING_CERTIFICATE_SHA256_DIGEST 一致。
- 启动一致性：bootstrap 变体 apt-android-7（bootstrap-2025.03.28-r1+apt-android-7，sha256 已核）与 BuildConfig.TERMUX_PACKAGE_VARIANT → TermuxBootstrap.PackageVariant.APT_ANDROID_7 一致；launchable-activity 为 com.gph.fable.app.TermuxActivity，label=Fable，打开即终端。
- 测试：`./gradlew :app:testDebugUnitTest` 通过（FablePackageIdentityTest 5 项身份断言 + TermuxActivityTest + FileReceiverActivityTest，共 7 项）。
- 产物校验（build-and-verify.sh debug，全部通过）：aapt2 badging 包名 com.gph.fable / versionCode 1022 / versionName 0.119.0-beta.3 / label Fable；APK 含 lib/arm64-v8a/libtermux-bootstrap.so、liblocal-socket.so、libtermux.so；apksigner verify <签名证书主体>。
- 代码审查：按 code-review 双轴（Standards/Spec）执行，无阻塞项；提交与规格一致，无范围蔓延。
- 待用户配合（本环境 adb 为 x86_64 二进制无法执行，装不了）：
  1. 安装 APK：`fable-app/app/build/outputs/apk/debug/fable-app_apt-android-7-debug_arm64-v8a.apk`（34MB，arm64）。
  2. 打开 Fable 图标 → 应直接进入终端；执行 `echo ok`、`pwd` 验证输出；`uname -m` 应为 aarch64；`$PREFIX` 应指向 `/data/data/com.gph.fable/files/usr`。
  3. 如安装报 INSTALL_FAILED_UPDATE_INCOMPATIBLE：先确认设备上未装旧 com.gph.fable 签名版本；与已装 F-Droid Termux（com.termux）并存不受影响。
  4. 启动崩溃排查入口：包名/变体一致性（本工单已锁）、工单 05 的 targetSdk 升级。

2026-08-06 keystore 备忘（主线程补记）：签名身份必须长期保管。keystore 位于 `fable-app/keystore/<旧签名材料>`，alias=`fable`，storePassword/keyPassword 见 `fable-app/app/build.gradle` signingConfigs（2026-08-08 打码：密码已从本文件移除）。后续所有版本必须用该 keystore 签名才能覆盖安装更新；若将来把 fable-app 推到公开远端，建议将 keystore 移出仓库并改为本地引用。

2026-08-06 真机验收通过（用户确认，经工单 07 覆盖安装新版 bootstrap 后）：装到手机成功、图标与名称显示 Fable、点击图标直接进入终端、echo/pwd 可交互、无 Bootstrap Error。本工单全部验收项达成。

2026-08-06 真机安装后 Bootstrap Error（主线程诊断）：APK 安装成功，首次启动第二阶段 bootstrap 失败（"Fable was unable to install the bootstrap packages"）。
- 根因：APK 内嵌 bootstrap 归档（官方 apt-android-7 变体，2025-03-28）内部硬编码 `/data/data/com.termux/files/usr` 绝对路径：第二阶段脚本 shebang 与 `export TERMUX_PREFIX`、lock 路径、SYMLINKS.txt 的 keyring/bz* 符号链接目标，以及 bash/dash/dpkg/apt/apt-get 等二进制的编译期前缀。共 632/3490 个文件（18%）含 com.termux 字符串。
- 机制：改名后 Fable 的 $PREFIX 为 `/data/data/com.gph.fable/files/usr`，且新 UID 无法读写旧包数据目录（0700 <应用 UID>）→ 第二阶段脚本以旧路径执行失败 → 安装器删除 prefix 并报错。
- 官方确认（termux-app issue #3973/#1059/#2160）：bootstrap 二进制硬编码 $PREFIX，改包名必须用新前缀重建全部包并重新生成 bootstrap zip。这超出"变体一致性"检查范围；对 v1 有系统性影响（含工单 02"按清单重装包"——官方源包同样硬编码 com.termux 前缀）。
- 决策点（待用户选择）：A. fork termux-packages 设 TERMUX_PREFIX=/data/data/com.gph.fable/files/usr，重建 bootstrap（CI 或手机本地）；B. 修补路线（不可行/极脆弱，不推荐）；C. 重议包名或环境策略。工单 01 真机验收在 bootstrap 重建前无法通过。
