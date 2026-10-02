# 改名清单（Fable → Phanes）

**用途**：ADR-0013 把代码标识符的改名推给将来，这份清单是那次工作的入口。逐层记
现状、目标、必须同改的关联对象，以及哪些绑死在应用身份上不能单独改。

**核实日期**：2026-10-02（数字用各节给出的命令可重算）。

**本轮已完成**：文档（工单 62–64）＋ App 显示名（工单 64，ADR-0013 决定 3）；仓库名在工单
65 建立私有远端时定为 `phanes`。本节之外的一切代码标识符都还没改。

## 一、冻结点：不能单独改

应用身份 = `applicationId` 及其派生物（ADR-0013 决定 2）。以下各项**改名工作不得触碰**：

| 项 | 现值 | 位置 |
| --- | --- | --- |
| `applicationId` / `namespace` | `com.gph.fable` | `android/app/build.gradle` |
| 数据前缀 `$PREFIX` | `/data/data/com.gph.fable/files/usr` | 由 applicationId 派生；bootstrap 归档与 `fable-repo` 的全部 `.deb` 按它构建（ADR-0005、工单 06） |
| 权限名、provider authority、prefs 文件名 | `com.gph.fable.permission.RUN_COMMAND`、`com.gph.fable.files`、`com.gph.fable_preferences` | `TermuxConstants` 派生常量 |
| 插件包名 | `com.gph.fable.api` / `.boot` / `.window` / `.styling` / `.tasker` / `.widget` | 同上 |
| JNI 符号前缀 | `Java_com_gph_fable_*` | `renderer/src/jni.rs`（44 个）、`session/src/jni_handle.rs`（8 个） |
| 签名身份 | release keystore（仓库外） | 改它 = App 变成新身份、必须重装而非升级 |
| bootstrap 变体名 | `apt-android-7` | 与 bootstrap 归档内容和 `$PREFIX` 绑定 |

JNI 符号前缀是 `Java_` + 包名 + 类名，包名部分绑 applicationId。所以**只要类名改了、
包名没改**，符号必须同步改成 `Java_com_gph_fable_<新类名>_*`：改类名的人要一起改
`renderer/src/jni.rs` 与 `session/src/jni_handle.rs`。包名要改，等于换应用身份，那是
ADR-0013 明确排除的动作。

## 二、App 显示名（本单已改）

2026-10-02 用户确认：保留新名字 Phanes。

| 项 | 改前 | 改后 |
| --- | --- | --- |
| Gradle manifest 占位符 | `manifestPlaceholders.TERMUX_APP_NAME = "Fable"` | `"Phanes"`（`android/app/build.gradle`） |
| 资源实体 | `<!ENTITY TERMUX_APP_NAME "Fable">` | `"Phanes"`（`android/app/src/main/res/values/strings.xml`） |
| 运行时常量 | `TermuxConstants.TERMUX_APP_NAME = "Fable"` | `"Phanes"`（`android/termux-shared/.../TermuxConstants.java`） |
| 用户可见字符串 | 通知渠道名、报错横幅、About 标题里的旧名 | 一并改（`strings.xml` 的 `fable_app_notification_channel_name`、`fable_run_command_notification_channel_name`、`msg_add_fable_debug_info`、`about_fable_header`） |
| 身份测试 | 断言 `TERMUX_APP_NAME == "Fable"` | 断言 `"Phanes"`，并注明它属对外可见层（`FablePackageIdentityTest`） |

连带影响是显示层的，不碰身份：日志 tag、wakelock tag、日志文件名、通知文案变
Phanes；SharedPreferences 文件名、权限名、数据目录都由包名派生，不变。

`strings.xml` 里仍有写死 "Fable" 的字符串（`error_fable_service_*` 等），它们引用的是
**类名** `FableService`，属第四节，跟着类名一起改才不矛盾。

## 三、Rust 库名与 `.so` 文件名

| 现状 | 目标 | 必须同改 |
| --- | --- | --- |
| `renderer/Cargo.toml` `[lib] name = "fable_render"` | `phanes_render` | `renderer/examples/*`、测试与库内 47 处 `fable_render::` 引用；产物名 `libfable_render.so` |
| `session/Cargo.toml` `[lib] name = "fable_session"` | `phanes_session` | `session/` 内 9 处引用 |
| `android/.../jniLibs/arm64-v8a/libfable-render.so`、`libfable-session.so` | `libphanes-render.so`、`libphanes-session.so` | `RenderCore.kt` 的 `System.loadLibrary("fable-render")`、`SessionHandle.kt` 的 `System.loadLibrary("fable-session")`、`scripts/build-session-lib.sh` 的 install 源/目标名 |
| `renderer/build.rs` 的 `-Wl,--undefined=fable_render_tls_pad` | 可留可改 | 同名 C 符号在 `renderer/` 的 TLS 占位里定义；改就两边一起 |

不绑 applicationId，可以单独改。注意 Cargo 产物名会把下划线转成连字符才能给
`System.loadLibrary` 用，改名后仍要保持两者一致。

## 四、`Fable*` 类名与文件名

现状：57 个文件（`.kt` 43、`.java` 14），跨四个模块 `app` 23、`termux-shared` 28、
`core` 5、`terminal-view` 1；文件内还有 83 个文件里的 1071 行提到 `Fable`。

重算：

```bash
find android -name 'Fable*.kt' -o -name 'Fable*.java' | grep -v /build/ | wc -l   # 57
grep -rl Fable android --include=*.kt --include=*.java | grep -v /build/ | wc -l    # 83
```

改名时必须同改的对象：

- 文件名与类名（含 Kotlin `import`、Java 全限定引用、测试类）
- `AndroidManifest.xml` 里的 `.app.FableActivity`、`.app.FableApplication`、
  `.filepicker.FableDocumentsProvider`、`.app.FableService`、`.app.FableOpenReceiver` 等
  `android:name`
- `TermuxConstants` 里以字符串写死的组件名（`TERMUX_ACTIVITY_NAME =
  TERMUX_PACKAGE_NAME + ".app.FableActivity"`）与外部入口（RUN_COMMAND、文件分享）用的
  那些常量：类名一改，字符串常量不改，外部入口就断
- Rust 侧 JNI 导出符号 `Java_com_gph_fable_app_RenderCore_*`（只随类名变前缀后半段，
  包名段冻结）
- 主题名 `Theme.FableApp.*` / `Theme.FableActivity.*`（第八节）

不绑 applicationId，可以单独改；但这是一次"类名 + 全仓引用 + manifest + 字符串常量 +
JNI 符号"的整体动作，只改一部分必然编译失败或运行时找不到组件。

## 五、包名分段

| 段 | 现状 | 能否单独改 |
| --- | --- | --- |
| `com.gph.fable` | 应用包名与 namespace | 否，绑 applicationId（第一节） |
| `com.gph.fable.shared.termux.*` 里的 `termux` 段 | 上游 termux-app 遗留命名 | 是。随第六节的模块改名一起动目录 `android/termux-shared/src/main/java/com/gph/fable/shared/termux/`、全部 `import`、`TermuxConstants` 的包声明 |
| `com.gph.fable.core.*`、`.app.*`、`.view.*` | 已有角色语义 | 可改，但要与类名/模块改名同批 |

## 六、Gradle 模块名

| 现状 | 说明 |
| --- | --- |
| `:app` | 应用模块，角色名 |
| `:core` | 自工单 63 起由 `:fable-core` 改为角色名 |
| `:termux-shared` | 上游名，未改 |
| `:terminal-view` | 上游名，未改 |

改 `:termux-shared` / `:terminal-view` 要同改 `android/settings.gradle`、各
`build.gradle` 里的 `project(":...")`、目录名、Java 包路径与文档。不绑
applicationId，可单独改。

## 七、兼容层：属性文件与用户目录

| 现状 | 位置 | 改名的代价 |
| --- | --- | --- |
| `~/.termux/` 目录名 | `TermuxConstants.TERMUX_DATA_HOME_DIR_PATH` | 改 = 用户既有 dotfiles 全部失联 |
| `termux.properties`、`termux.float.properties` | 同上 | 改 = 用户配置失效 |
| `colors.properties`、`font.ttf`、`boot/`、`tasker/` | 同上 | 同上 |
| 属性键名（`TermuxPreferenceConstants` 常量） | `TermuxPreferenceConstants` | 改 = 用户设置静默失效 |

这一层**不是不能改，是不能只改一半**：要么保留旧名（兼容优先），要么新名 + 读取时
兼容旧名 + 一次性迁移。它与 applicationId 无关，但路径里 `$PREFIX` 那段绑身份，不
能碰。建议单独开一张工单，改名时带迁移与回退方案。

## 八、资源与属性名

| 现状 | 位置 | 必须同改 |
| --- | --- | --- |
| 主题 `Theme.FableApp.*`、`Theme.FableActivity.*` | `res/values/themes.xml`、`AndroidManifest.xml` | 主题定义、manifest 引用、布局与代码里的 `R.style` |
| 字符串键 `fable_*`、`msg_add_fable_debug_info`、`about_fable_header`、`title_activity_fable_settings` | `res/values/strings.xml` | 所有 `R.string.*` 引用 |
| 渠道 ID `termux_notification_channel` 等 | `TermuxConstants` | 改 ID 会重置用户的渠道设置，建议保留 |

键名改名属机械替换，不绑身份；渠道 ID 属兼容层，建议不动。

## 九、构建与产物名

| 现状 | 能否单独改 |
| --- | --- |
| APK 产物名 `fable-app_<variant>_<abi>.apk`（`app/build.gradle` 的 `outputFileName`） | 能改；要同改 `android/build-and-verify.sh` 的路径拼接与文档里的示例文件名 |
| 环境变量 `TERMUX_PACKAGE_VARIANT`、`TERMUX_APK_VERSION_TAG`、`TERMUX_SPLIT_APKS_*`、`JITPACK_NDK_VERSION`、Gradle 属性 `markwonVersion` | 能改；要同改 `build.gradle`、文档、脚本 |
| 签名输入变量 `FABLE_RELEASE_*`、`FABLE_DEBUG_USE_RELEASE_SIGNING` | 能改；要同改 `build.gradle`、`android/docs/signing.md`、本机 `~/.gradle/gradle.properties` |
| 日志文件名 `fable-render-debug.log.txt` | 能改；要同改 `FableDiagnostics.kt`（含文件名常量与分享用 MIME 匹配） |
| `libtermux-bootstrap.so`、`liblocal-socket.so` | 上游名；改了要同改 `Android.mk`/`CMakeLists`、jniLibs 与加载点 |
| bootstrap 变体 `apt-android-7` | 不能单独改（绑归档内容与 `$PREFIX`） |

## 十、已用角色名的项

工单 63 已完成：三个 crate 包名 `renderer` / `session` / `boo`（原 `spike-render`、
`spike-session`、`fable-boo`）、目录 `android/`、`renderer/`、`session/`、`boo/`、
`libghostty/`、应用模块 `core`、发布目录 `docs/release/`。

副作用记录：`boo` 的产物二进制名随包名从 `fable-boo` 变成 `boo`。要在用户环境里保留
旧命令名，加 `[[bin]] name = "fable-boo"` 即可；当前按角色名取 `boo`。

## 改一次的动作顺序（建议）

1. 定批次：类名（第四节）与 `.so`（第三节）分开做，各自可编译可验收
2. 每批先改"名字 + 全仓引用 + manifest/字符串常量 + JNI 符号"，再跑
   `scripts/rust-quality-gate.sh` 与 `cd android && ./gradlew :app:assembleDebug`
3. 兼容层（第七节）与渠道 ID 单独一批，带迁移方案，不混进机械改名
4. 每批改完更新本清单的现状列，并把结论写回对应工单 Comments
