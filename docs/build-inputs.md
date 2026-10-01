# 构建输入来源表

**构建输入**（见 `CONTEXT.md`）= 构建 APK 所需、但不是本项目源码的外部资产。
它们不进版本库（ADR-0012），本表记录每一样的来源、校验和与落地路径。

> 路径按 ADR-0011 的合并后布局书写（`android/`、`renderer/`、`session/`、`boo/`、
> `libghostty/`）。表中 sha256 为 2026-10-01 在本机实测值，除注明外均为文件本体摘要。

## 一、已自动化（无需手工操作）

### bootstrap 归档

| 项 | 值 |
| --- | --- |
| 文件 | `bootstrap-aarch64.zip` |
| 大小 | 142,296,286 B |
| sha256 | `d9fc5f96691afdeb83ecffc0e9571a7e88cf127d775beefc53d9a0e353e0a601` |
| 来源 | `https://github.com/bingnnvjn/fable-bootstrap/releases/download/bootstrap-2026.08.06-r2%2Bapt-android-7/bootstrap-aarch64.zip` |
| 落地 | `android/app/src/main/cpp/bootstrap-aarch64.zip` |
| 机制 | `android/app/build.gradle` 的 `downloadBootstraps` 任务，构建前自动下载并校验，无需手工 |

注意：只有 aarch64 指向 Fable 自己的 Release（`com.gph.fable` 前缀）。arm / i686 / x86_64
三份仍指向官方 termux-packages Release（`com.termux` 前缀）。本项目的目标设备是 aarch64。

`libtermux-bootstrap.so` **不是**构建输入：它是这个 zip 经 `android/app/src/main/cpp/`
的 ELF 桩在构建时嵌入生成的产物，不要提交，也不要手工替换。

### 彩色 emoji 字体

| 项 | 值 |
| --- | --- |
| 文件 | `AppleColorEmoji.ttf`（剥离产物） |
| 大小 | 30,770,720 B |
| sha256 | `6f6ad8b9751356c5707ab9e2645cddc3521d116a6b387d7b4b1437456d3784a3` |
| 来源 | PoomSmart/EmojiFonts Release `17.0.0-apple` 的 `AppleColorEmoji-160px.ttc`（原始 ttc sha256 `76c37f95960d82a37a2c93c929dce7eb830a641707786a9e6a8ee17d911714dc`，63,888,516 B） |
| 落地 | `android/app/src/main/assets/fonts/AppleColorEmoji.ttf` |
| 机制 | `scripts/fetch-emoji-fonts.sh`：下载原始 ttc → 用 `renderer/examples/strip_apple_emoji.rs` 剥离到 160px 单档 → 校验 sha256 |

许可提示：Apple Color Emoji 不可再分发。它不得进入仓库，无论仓库是私有还是公开（ADR-0012）。
完整溯源见 `docs/fonts/apple-emoji-provenance.md`。

## 二、需要手工获取

### libghostty-vt 静态库与头文件

| 项 | 值 |
| --- | --- |
| 文件 | `libghostty-vt-android-b0947378349e-r3.tar.gz` |
| sha256 | `b38f032f3f5e42cd3686ba6a10ecbb0669382de8df74ddd44be30e362c637d96` |
| 来源 | `https://github.com/arcboxlabs/expo-libghostty/releases/download/storage.libghostty-vt.b0947378349e.r3/libghostty-vt-android-b0947378349e-r3.tar.gz` |
| 版本 | expo-libghostty 0.8.1；ghostty commit `b0947378349eff70f7030dda0e6d022fae1e6fbd`；Zig 0.15.2 / NDK 27.1.12297006 / `-Dsimd=false` |
| 落地 | 解包到 `libghostty/lib/`（含 `arm64-v8a/libghostty-vt.a`、`x86_64/libghostty-vt.a`、`include/ghostty/vt/*.h`） |

解包后的文件摘要（本机实测）：

| 文件 | sha256 |
| --- | --- |
| `lib/arm64-v8a/libghostty-vt.a` | `a469b54a26b072bb25aad13bcbcd86a68474022bab7fa20d9b17027b4d58c89a` |
| `lib/x86_64/libghostty-vt.a` | `4f98a0fefc4259bf52d882a1a43db5bceac5535dc3dfc842e573da8347e0f805` |

链接方式：`renderer/build.rs` 按相对路径 `../libghostty/lib/arm64-v8a` 查找并静态链接
`ghostty-vt`。合并后 `renderer/` 与 `libghostty/` 仍是同级目录，相对路径不变。

升级核心 = 换新 `.a` + 头文件 + 重跑验证（TLS 对齐 / 显示节流 / 回归）。当前 pinned
`b0947378` 不动。

### Nerd Font Symbols

| 项 | 值 |
| --- | --- |
| 文件 | `NerdFontsSymbolsOnly.zip` → `SymbolsNerdFontMono-Regular.ttf` |
| 大小 | 2,507,556 B（解包后的 ttf） |
| sha256 | `f0f624d9b474bea1662cf7e862d44aebe1ae1f6c7f9cb7a0ca5d0e5ac9561c60` |
| 来源 | `https://github.com/ryanoasis/nerd-fonts/releases/download/v3.4.0/NerdFontsSymbolsOnly.zip` |
| 版本 | Nerd Font Mono v3.4.0 |
| 落地 | `libghostty/lib/fonts/SymbolsNerdFontMono-Regular.ttf`（许可见同目录 `NERD-FONTS-LICENSE`） |

## 三、已在仓库内（无需获取）

| 文件 | 位置 | sha256 | 许可 |
| --- | --- | --- | --- |
| `NotoColorEmoji.ttf` | `renderer/assets/` | `0ae57fe58645638523ba35f388d93739d292539a9acb84df5700c81b1e1a28d2` | OFL-1.1 |
| `JetBrainsMono-Regular.ttf` | `renderer/assets/` | `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f` | OFL-1.1 |

## 四、不是构建输入但会挡路的东西

以下两项属于**设备配置**，不进仓库，需要在新设备上自行设置：

- **工具链**：JDK、Android SDK、Gradle、NDK。版本与路径以构建文件为准；`android/local.properties`
  里的 `sdk.dir` 由每台设备自己写（示例路径见 `README.md`）。
- **aapt2 覆盖**：只有 aarch64 宿主需要。SDK 自带的 aapt2 是 x86_64，不能在 aarch64 上运行，
  要指向系统原生 aapt2。仓库内已不再保留这一行（工单 64 移出），改为写进设备自己的
  用户级 Gradle 属性：

  ```properties
  # ~/.gradle/gradle.properties，仅 aarch64 宿主
  android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
  ```

  x86_64 电脑不写这一行，SDK 自带的 aapt2 直接可用。
