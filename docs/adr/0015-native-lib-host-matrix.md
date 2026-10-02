# ADR-0015: 构建链宿主矩阵与单一原生库通路

- 状态：已确认
- 日期：2026-10-02
- 范围：APK 里四个原生库的来源与生成方式；宿主支持矩阵

## 背景

APK 的 `lib/arm64-v8a/` 需要四个原生库，它们的源码都在仓库内：

| 库 | 源码 | 原生成方式 |
| --- | --- | --- |
| `libfable-render.so` | `renderer/` | `cargo build --target aarch64-linux-android --release` |
| `libfable-session.so` | `session/` | 同上 |
| `libtermux-bootstrap.so` | `android/app/src/main/cpp/` | 依赖设备上手工造的假 NDK |
| `liblocal-socket.so` | `android/termux-shared/src/main/cpp/` | 依赖设备上手工造的假 NDK |

假 NDK（`$HOME/android-sdk/ndk/22.1.7171670`，`ndk-build` 是两千字节的 Termux clang
包装脚本）在工单 65 暴露为缺陷：AGP 9 用 `ndk-build ... -n` 建立构建模型，假脚本忽略
`-n`，AGP 解析到的库列表为空（`android_gradle_build.json` 里 `"libraries": {}`），
ndkBuild 产物进不了 APK merge。工作区靠 2026-08-06 留在 `jniLibs/` 的两个旧 `.so`
掩盖了问题，fresh clone 一跑就缺库。

官方 NDK 在 Linux aarch64 上不可用。2026-10-02 拉取
`https://dl.google.com/android/repository/repository2-3.xml`，按 `<host-os>` +
`<host-arch>` 统计包数：linux x64 = 64、linux aarch64 = 0、macosx x64 = 63、
macosx aarch64 = 61、windows x64 = 64。官方不发布 Linux aarch64 的 NDK。

## 决策

1. **一条通路**：`scripts/build-native-libs.sh` 是仓库内唯一的原生库生成入口。一次运行
   生成四个库，外加 local-socket 的 STL 运行库 `libc++_shared.so`，全部放进打包输入目录
   `android/app/src/main/jniLibs/arm64-v8a/`。不再使用 AGP 的
   `externalNativeBuild` / `ndkBuild`，对应的 Gradle 声明与 `Android.mk` /
   `Application.mk` 一并删除。
2. **宿主矩阵**：

   | 宿主 | 工具链 |
   | --- | --- |
   | Linux aarch64（Termux 手机、aarch64 Linux 电脑） | Termux `clang` / `clang++` + `ndk-sysroot` + `libc++`，显式 `--target=aarch64-linux-android{24,21}` |
   | Linux x86_64 / macOS x64 / Windows x64 | 官方 NDK 22.1.7171670 的 `aarch64-linux-android24-clang` / `aarch64-linux-android21-clang++` |
   | macOS arm64 | 同上；NDK 22.1.7171670 没有 darwin-arm64 预编译，脚本回落到 darwin-x86_64 工具链（Rosetta） |

   两条分支同一个脚本、同一组编译参数，只有编译器路径不同；脚本按 `uname` 自动选择，
   可用 `FABLE_NATIVE_CC` / `FABLE_NATIVE_CXX` 覆盖。非 aarch64 宿主用
   `ANDROID_NDK_HOME`（或 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 加 `ndk;<版本>`）指向 NDK。
3. **产物 ABI = `arm64-v8a`**（项目目标设备）。APK splits 只 `include 'arm64-v8a'`；
   `downloadBootstraps` 的两个变体都只取 aarch64 归档，不再下载 arm / i686 / x86_64
   三份用不到的 zip。
4. **构建输入前置自检**：脚本先校验 `bootstrap-aarch64.zip` 的 sha256，缺失或哈希不符
   即失败并给出获取指引（`./gradlew :app:downloadBootstraps`）。
5. **STL 走运行库 `libc++_shared.so`**：Termux 不提供静态 libc++，NDK 分支为保持一致也用
   `c++_shared`。脚本按宿主把对应的 `libc++_shared.so` 一起放进 jniLibs（aarch64 取 Termux
   `libc++` 包；NDK 宿主取 NDK sysroot，属工具链的一部分，和 NDK 本身一样不固定哈希）。
   旧假 NDK 产出的 `liblocal-socket.so` 缺 76 个 `std::__ndk1` 未定义符号且无任何 STL
   依赖记录，加载即失败；本决策修掉这个隐性缺陷。
6. **校验在构建侧**：`android/build-and-verify.sh` 断言 APK 的 `lib/arm64-v8a/` 下四个库
   齐全、`libc++_shared.so` 存在，且没有非 arm64 的 ABI 目录；缺任一即非零退出。

## 后果

- aarch64 宿主不再需要官方 NDK，也不再需要假 NDK；设备上遗留的假 NDK 目录不再是构建
  依赖，可以删除（本 ADR 不替用户删设备配置）。
- x86_64 宿主仍是官方 NDK 22.1.7171670；`README.md`、`docs/build-inputs.md` 与
  `项目总览与交接.md` 都写明版本与矩阵。
- APK 比原来多带一个约 1.4 MB 的 `libc++_shared.so`，换来 local-socket 真正可用。
- `.so` 仍是**构建输入**，不进仓库（ADR-0012），但现在由仓库内脚本可复现；应用身份
  `com.gph.fable` 不变。
- aarch64 宿主用 Termux clang 链接时，产物会带一条指向 `$PREFIX/lib` 的 RUNPATH
  （Termux clang spec 注入）。该 `.so` 不入库，设备路径不会进仓库内的必需构建配置。
- 与旧 `c++_static`（`APP_STL := c++_static`）不同，APK 现在必须带上
  `libc++_shared.so`；只复制四个 `.so` 的旧做法会得到加载失败。

## 备选方案

- **让假 NDK 支持 `-n`**：要给 AGP 伪造完整可解析的 make 输出（编译/链接/安装命令流），
  成本高、易碎，且每个宿主都要维护一份 shim。否决。
- **在 aarch64 上用官方 NDK**：官方没有 Linux aarch64 包，不可行。
- **local-socket 静态链 libc++**：Termux 没有 `libc++_static.a`；为它引入一份 NDK
  sysroot 只为一个静态库，代价大于带一个 1.4 MB 运行库。否决。
